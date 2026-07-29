package com.starlink.scanner.data.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.starlink.scanner.di.ServiceLocator

/**
 * Deferred, batched upload of all not-yet-sent records to the Google Sheet (Module 5).
 *
 * Runs under a `NetworkType.CONNECTED` constraint (see [UploadScheduler]), so by the time [doWork]
 * runs there is some internet. The actual work lives in the shared [UploadRunner] (also used by
 * History's direct "Sync now"); here we just map its result onto a WorkManager [Result] — a real
 * failure is retried with backoff, everything else is terminal success.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (ServiceLocator.uploadRunner.run()) {
        is SyncResult.Failed -> Result.retry()
        else -> Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "upload"
    }
}
