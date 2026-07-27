package dev.healthhub.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.healthhub.core.model.SyncStatus
import java.util.concurrent.TimeUnit

/**
 * Background sync (FR-005).
 *
 * A failed pass is retried rather than dropped, and the cursor has not moved, so the retry
 * re-reads exactly the window that did not make it. The idempotent upload on the server
 * absorbs anything that was already delivered before the failure.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val report = engine.sync()
        return when (report.status) {
            SyncStatus.OK, SyncStatus.PARTIAL -> Result.success()
            // Retry with WorkManager's backoff: the usual causes are a flaky connection or a
            // provider that is briefly unavailable, and both resolve on their own.
            SyncStatus.FAILED, SyncStatus.RUNNING -> Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "healthhub-sync-periodic"
        const val ONE_SHOT_NAME = "healthhub-sync-now"

        /**
         * Schedules the recurring pass.
         *
         * [unmeteredOnly] honours the athlete's network preference (FR-008) — telemetry for a
         * long ride is measured in megabytes, and spending someone's mobile data without
         * asking is not acceptable.
         */
        fun schedule(context: Context, unmeteredOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                // The preference may have changed since the last schedule, so replace rather
                // than keep the existing request.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** A sync the athlete asked for, which ignores the unmetered preference. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT_NAME)
        }
    }
}
