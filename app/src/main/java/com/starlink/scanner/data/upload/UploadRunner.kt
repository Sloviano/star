package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
 */
class UploadRunner(
    private val dao: ScanDao,
    private val settings: SettingsRepository,
    private val uploader: SheetsUploader,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun run(): SyncResult {
        val pending = dao.pending()
        if (pending.isEmpty()) return SyncResult.NothingPending

        val url = settings.sheetsUrl.first().trim()
        if (url.isBlank()) return SyncResult.NoUrl

        val body = json.encodeToString(
            ListSerializer(ScanUploadDto.serializer()),
            pending.map { it.toUploadDto() },
        )

        return when (val outcome = uploader.post(url, body)) {
            is UploadOutcome.Success -> {
                pending.forEach { dao.markSent(it.id) }
                settings.setLastUploadTime(System.currentTimeMillis())
                settings.setLastUploadError("")
                SyncResult.Sent(pending.size)
            }
            is UploadOutcome.Failed -> {
                pending.forEach { dao.markFailed(it.id) }
                settings.setLastUploadError(outcome.reason)
                SyncResult.Failed(outcome.reason)
            }
        }
    }
}
