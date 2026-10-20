package app.olauncher.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WallpaperWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Prefs(applicationContext).dailyWallpaper) return@withContext Result.success()
        val wallpaper = GeneratedWallpaper(applicationContext)
        if (!wallpaper.apply()) return@withContext Result.retry()
        val now = System.currentTimeMillis()
        // Append so this worker can finish before the next boundary runs.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            Constants.WALLPAPER_WORKER_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            request((wallpaper.nextChange(now) - now).coerceAtLeast(1000)))
        Result.success()
    }

    companion object {
        private fun request(delay: Long = 0) = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()

        fun update(context: Context) {
            if (!Prefs(context).dailyWallpaper) return
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME + "_legacy")
            manager.enqueueUniqueWork(Constants.WALLPAPER_WORKER_NAME, ExistingWorkPolicy.REPLACE, request())
        }
    }
}

class WallpaperTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { WallpaperWorker.update(context) }
}
