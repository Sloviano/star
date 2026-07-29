package com.starlink.scanner.data.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Enqueues the unique [UploadWorker] job. Called on every save, on app start, and from History's
 * "Sync now". The `CONNECTED` constraint holds the job until there is internet; exponential backoff
 * spaces out retries after a failed batch.
 */
object UploadScheduler {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun request() = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    /**
     * Enqueue an upload. [force] uses [ExistingWorkPolicy.REPLACE] so a user-initiated "Sync now"
     * restarts the job immediately even if one is already scheduled/backing off; the default
     * [ExistingWorkPolicy.KEEP] coalesces the routine save/start triggers into a single run.
     */
    fun enqueue(context: Context, force: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UploadWorker.UNIQUE_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(),
        )
    }

    /** True while the upload job is enqueued or running — drives the "Sync now" spinner. */
    fun isRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UploadWorker.UNIQUE_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
}
