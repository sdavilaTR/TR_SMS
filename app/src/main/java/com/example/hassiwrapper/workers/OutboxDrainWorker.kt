package com.example.hassiwrapper.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.hassiwrapper.ServiceLocator

/**
 * Background safety net for the SMS outbox — an expedited one-shot job that drains only the
 * CRUD outbox (no master data download, no legacy upload routes) so an op left PENDING when
 * the foreground [MainActivity][com.example.hassiwrapper.MainActivity] loop stops (crash,
 * OEM background-kill, app update install) still has a chance to land before the next time the
 * app is opened. Not a replacement for the foreground sync loop — see plan Tier 4.
 */
class OutboxDrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "OutboxDrainWorker"
        private const val UNIQUE_WORK_NAME = "outbox_drain"

        /** Enqueue the expedited one-shot drain; call from onPause/onStop. onPause fires far more
         *  often than "app actually backgrounded" (scanner activity round-trips, dialogs, screen
         *  off) — KEEP so a drain already queued or running is left alone instead of being
         *  cancelled and restarted by the next onPause, which would starve it indefinitely on a
         *  scan-heavy kiosk. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val result = ServiceLocator.syncService.drainOutboxOnly()
            if (result == null) {
                Log.i(TAG, "drain skipped (busy / no service / auth failed)")
            } else {
                Log.i(TAG, "drain done: ${result.done} done, ${result.failed} failed, transient=${result.transient}")
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "drain failed: ${e.message}")
            Result.failure()
        }
    }
}
