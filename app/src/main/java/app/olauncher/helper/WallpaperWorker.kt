package app.olauncher.helper

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.olauncher.data.Prefs

/**
 * Daily wallpaper scheduling on the platform JobScheduler. Replaces WorkManager,
 * which pulled Room and SQLite into the APK for this single job.
 */
class WallpaperJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            if (!Prefs(applicationContext).dailyWallpaper) {
                jobFinished(params, false)
                return@Thread
            }
            val wallpaper = GeneratedWallpaper(applicationContext)
            val applied = runCatching { wallpaper.apply() }.getOrDefault(false)
            if (applied) {
                val now = System.currentTimeMillis()
                WallpaperWorker.schedule(applicationContext, (wallpaper.nextChange(now) - now).coerceAtLeast(1000))
            }
            // Not applied (offline, etc.): let JobScheduler retry with backoff.
            jobFinished(params, !applied)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

object WallpaperWorker {
    private const val JOB_ID = 4210

    fun update(context: Context) {
        if (!Prefs(context).dailyWallpaper) return
        schedule(context, 0)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    internal fun schedule(context: Context, delayMs: Long) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, WallpaperJobService::class.java))
            .setMinimumLatency(delayMs)
            .setOverrideDeadline(delayMs + 15 * 60_000L)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        context.getSystemService(JobScheduler::class.java)?.schedule(job)
    }
}

class WallpaperTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { WallpaperWorker.update(context) }
}
