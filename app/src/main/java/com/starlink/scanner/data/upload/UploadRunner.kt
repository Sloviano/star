package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.settings.UploadSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of one upload attempt — shared by the background worker and the manual "Sync now". */
sealed interface SyncResult {
    /** No PENDING/FAILED records to send. */
    data object NothingPending : SyncResult

    /** No Apps Script URL configured yet. */
    data object NoUrl : SyncResult

    /** All pending records accepted by the backend. */
    data class Sent(val count: Int) : SyncResult

    /** Backend/transport failure; records left FAILED for a later retry. [reason] is user-readable. */
    data class Failed(val reason: String) : SyncResult
}

/**
 * Performs one batched upload of every not-yet-sent record to the Google Sheet.
 *
 * Shared by two callers:
 *  - [UploadWorker] — the deferred, `NetworkType.CONNECTED`-constrained background path;
 *  - History's "Sync now" — a *direct* call, so a manual tap isn't held back by WorkManager's
 *    network gating (which treats the dish's no-internet WiFi as "no usable network").
 *
 * Both callers share one instance, so [run] is serialized by [lock]. Without it the two of them can
 * read the same [ScanDao.pending] list and POST it twice — the worker mid-flight while the
 * technician taps Sync now — appending every row to the sheet a second time with no network failure
 * involved. Holding the lock across the whole method means the second caller re-reads `pending()`
 * only after the first has marked its batch SENT, and so correctly finds nothing to do.
 *
 * That closes the concurrent case, not the lost-response one: a batch whose rows were written but
 * whose response never arrived — a read timeout, or the process dying before the records are marked
 * SENT — stays PENDING/FAILED and is retried, which appends those rows a second time. The backend
 * no longer deduplicates (the Upload Key column was removed), so that remains the accepted trade:
 * re-sending can duplicate a row, while not re-sending would lose field work outright. Duplicates
 * are cleaned up in the sheet.
 */
class UploadRunner(
    private val dao: ScanDao,
    private val settings: UploadSettings,
    private val uploader: SheetsUploader,
) {

    /**
     * Serializes [run] across the background worker and the manual "Sync now", which share this
     * instance. Process-local, which is all that is needed: WorkManager runs the worker in the same
     * process as the UI.
     */
    private val lock = Mutex()

    suspend fun run(): SyncResult = lock.withLock {
        val pending = dao.pending()
        if (pending.isEmpty()) return@withLock SyncResult.NothingPending

        val url = settings.sheetsUrl.first().trim()
        if (url.isBlank()) return@withLock SyncResult.NoUrl

        val ids = pending.map { it.id }

        when (val outcome = uploader.postBatch(url, pending.map { it.toUploadDto() })) {
            is UploadOutcome.Success -> {
                dao.markSent(ids)
                settings.setLastUploadTime(System.currentTimeMillis())
                settings.setLastUploadError("")
                SyncResult.Sent(pending.size)
            }
            is UploadOutcome.Failed -> {
                dao.markFailed(ids)
                settings.setLastUploadError(outcome.reason)
                SyncResult.Failed(outcome.reason)
            }
        }
    }
}
