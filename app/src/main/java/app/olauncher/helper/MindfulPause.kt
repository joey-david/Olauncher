package app.olauncher.helper

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import androidx.core.content.edit
import java.time.LocalDate
import java.time.ZoneId

/**
 * Apps you mark get a short breathing pause, with today's attempts and time spent,
 * before they open. A deliberate speed bump rather than a lock.
 */
class MindfulPause(private val context: Context) {
    private val prefs = context.getSharedPreferences("mindful_pause", Context.MODE_PRIVATE)

    var pausedApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, emptySet())!!.toSet()
        private set(value) = prefs.edit { putStringSet(KEY_APPS, value) }

    fun isPaused(packageName: String) = packageName in pausedApps

    /** Returns the new state. */
    fun toggle(packageName: String): Boolean {
        val paused = !isPaused(packageName)
        pausedApps = if (paused) pausedApps + packageName else pausedApps - packageName
        return paused
    }

    /** Starts the pause screen instead of the app when needed; true if it did. */
    fun intercept(packageName: String, activityClassName: String?, user: UserHandle, shortcutId: String? = null): Boolean {
        if (!isPaused(packageName)) return false
        // Coming back to an app you just chose to open shouldn't ask again.
        if (System.currentTimeMillis() - prefs.getLong(KEY_PASSED + packageName, 0L) < GRACE_MS) return false
        val attempts = recordAttempt(packageName)
        context.startActivity(
            Intent(context, PauseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(PauseActivity.EXTRA_PACKAGE, packageName)
                .putExtra(PauseActivity.EXTRA_ACTIVITY, activityClassName)
                .putExtra(PauseActivity.EXTRA_USER, user)
                .putExtra(PauseActivity.EXTRA_SHORTCUT, shortcutId)
                .putExtra(PauseActivity.EXTRA_ATTEMPTS, attempts),
        )
        return true
    }

    fun markPassed(packageName: String) = prefs.edit { putLong(KEY_PASSED + packageName, System.currentTimeMillis()) }

    /** Foreground time today, or null without usage access. */
    fun usageToday(packageName: String): Long? = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = manager.queryAndAggregateUsageStats(start, System.currentTimeMillis())
        if (stats.isEmpty()) null else stats[packageName]?.totalTimeInForeground ?: 0L
    }.getOrNull()

    private fun recordAttempt(packageName: String): Int {
        val key = KEY_COUNT + LocalDate.now() + ":" + packageName
        val count = prefs.getInt(key, 0) + 1
        prefs.edit {
            // Drop counters from previous days so the file stays tiny.
            prefs.all.keys.filter { it.startsWith(KEY_COUNT) && !it.startsWith(KEY_COUNT + LocalDate.now()) }.forEach(::remove)
            putInt(key, count)
        }
        return count
    }

    private companion object {
        const val KEY_APPS = "apps"
        const val KEY_PASSED = "passed:"
        const val KEY_COUNT = "count:"
        const val GRACE_MS = 3 * 60_000L
    }
}
