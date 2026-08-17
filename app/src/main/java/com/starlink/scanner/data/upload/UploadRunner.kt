package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

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
 * A batch whose rows were written but whose response never arrived — a read timeout, or the process
 * dying before the records are marked SENT — stays PENDING/FAILED and is retried, which appends
 * those rows to the sheet a second time. The backend no longer deduplicates (the Upload Key column
 * was removed), so that is the accepted trade: re-sending can duplicate a row, while not re-sending
 * would lose field work outright. Duplicates are cleaned up in the sheet.
 */
class UploadRunner(
    private val dao: ScanDao,
    private val settings: SettingsRepository,
    private val uploader: SheetsUploader,
) {

    suspend fun run(): SyncResult {
        val pending = dao.pending()
        if (pending.isEmpty()) return SyncResult.NothingPending

        val url = settings.sheetsUrl.first().trim()
        if (url.isBlank()) return SyncResult.NoUrl

        val ids = pending.map { it.id }

        return when (val outcome = uploader.postBatch(url, pending.map { it.toUploadDto() })) {
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
