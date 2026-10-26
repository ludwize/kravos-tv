package net.kravos.tv.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.kravos.tv.App
import net.kravos.tv.data.Health
import java.util.concurrent.TimeUnit

/** Twice a day: fresh channel list, fresh guide, and a dead-stream sweep over everything (resumes where it stopped). */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        if (app.catalog.isStale()) app.catalog.refresh()
        app.refreshEpg(false)
        app.health.checkAll(app.catalog.all.value, maxAgeMs = 20 * Health.HOUR)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
