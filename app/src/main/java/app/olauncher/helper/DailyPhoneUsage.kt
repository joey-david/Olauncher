package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.time.LocalDate

/** Samples only unlocked, interactive time; never fills gaps after sleep or process death. */
class DailyPhoneUsage(
    private val context: Context,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val remind: () -> Unit = { context.showToast("Don't waste your one in a krillion time.") },
) {
    private val prefs = context.getSharedPreferences("phone_usage", Context.MODE_PRIVATE)
    private var previous = elapsed()
    private var outside = 0L
    fun sample(unlocked: Boolean, pkg: String, inSlot: Boolean) {
        val now = elapsed()
        val delta = (now - previous).coerceIn(0, 2000)
        previous = now
        if (!unlocked) { outside = 0; return }
        val day = LocalDate.now().toString()
        val sameDay = prefs.getString("day", "") == day
        val apps = if (sameDay) JSONObject(prefs.getString("apps", "{}")!!) else JSONObject()
        if (pkg.isNotBlank()) apps.put(pkg, apps.optLong(pkg) + delta)
        prefs.edit().putString("day", day).putLong("total", (if (sameDay) prefs.getLong("total", 0) else 0) + delta)
            .putString("apps", apps.toString()).apply()
        outside = if (inSlot) 0 else outside + delta
        if (outside >= 120_000) {
            outside = 0
            remind()
        }
    }
    fun summary(): String {
        if (prefs.getString("day", "") != LocalDate.now().toString()) return "Unlocked  0m"
        val apps = JSONObject(prefs.getString("apps", "{}")!!)
        fun duration(ms: Long): String = if (ms >= 3_600_000) "${ms / 3_600_000}h ${ms / 60_000 % 60}m" else "${ms / 60_000}m ${ms / 1000 % 60}s"
        return buildString {
            append("Unlocked  ").append(duration(prefs.getLong("total", 0)))
            apps.keys().asSequence().map { it to apps.getLong(it) }.sortedByDescending { it.second }.forEach { (pkg, time) ->
                val label = runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
                append("\n").append(label).append("  ").append(duration(time))
            }
        }
    }
}
