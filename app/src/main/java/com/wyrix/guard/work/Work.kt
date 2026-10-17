package com.wyrix.guard.work

import android.content.Context
import androidx.work.*
import com.wyrix.guard.core.Backoff
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.di.entry
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Flushes the Room queue with exponential backoff. Eligible deferred work only: no timing guarantee. */
class SyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ok = try { applicationContext.entry().tracking().syncNow() } catch (_: Exception) { false }
        return if (ok) Result.success() else if (runAttemptCount >= 12) Result.failure() else Result.retry()
    }
}

/** Fallback path when FCM is delayed or unavailable: fetch and validate commands, refresh config and geofences. */
class PollWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = try {
        val ep = applicationContext.entry()
        if (ep.store().deviceId == null) Result.success() else {
            ep.commands().processPending()
            ep.devices().refreshConfig()
            runCatching { ep.geofences().sync() }
            ep.tracking().syncNow()
            Result.success()
        }
    } catch (_: IOException) { Result.retry() }
    catch (e: SupabaseApi.ApiException) { if (e.status == 401 || e.status >= 500 || e.status == 429) Result.retry() else Result.failure() }
}

object Scheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun scheduleSync(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Backoff.delayMs(0), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("wyrix-sync", ExistingWorkPolicy.KEEP, req)
    }

    fun scheduleCommandPoll(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<PollWorker>().setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("wyrix-poll-now", ExistingWorkPolicy.REPLACE, req)
    }

    /** 15 minutes is Android's minimum period; Doze and battery saver can delay it further. */
    fun schedulePolling(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("wyrix-poll", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelAll(ctx: Context) = WorkManager.getInstance(ctx).cancelAllWork()
}
