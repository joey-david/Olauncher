package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import app.olauncher.R
import kotlin.math.roundToInt

/** A monotonic timer shared by the launcher and its accessibility service. */
class FocusSession(private val context: Context) {
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    var minutes: Int
        get() = prefs.getInt("minutes", 25)
        set(value) { prefs.edit { putInt("minutes", value.coerceIn(1, MAX_MINUTES)) } }
    /** Apps a session locks; the next session starts from the same choice. */
    var lockedApps: Set<String>
        get() = prefs.getStringSet("locked", emptySet())!!.toSet()
        set(value) { prefs.edit { putStringSet("locked", value.toSet()) } }
    var closeBlockedApps: Boolean
        get() = prefs.getBoolean("closeBlockedApps", false)
        set(value) { prefs.edit { putBoolean("closeBlockedApps", value) } }
    val duration: Long get() = prefs.getLong("duration", 0)
    val remaining: Long get() {
        // Reboot resets elapsedRealtime. A stale session must never become an indefinite lock.
        if (prefs.getInt("boot", -1) != bootCount) return 0
        return (prefs.getLong("end", 0) - SystemClock.elapsedRealtime()).coerceIn(0, duration)
    }
    val active: Boolean get() = remaining > 0

    fun start() {
        val durationMs = minutes * 60_000L
        prefs.edit {
            putLong("duration", durationMs)
            putLong("end", SystemClock.elapsedRealtime() + durationMs)
            putInt("boot", bootCount)
        }
    }

    companion object {
        const val MAX_MINUTES = 24 * 60
        /** How locked apps look on home and in the drawer. */
        const val LOCKED_ALPHA = 0.3f

        /** Slider position 0..1 to minutes, exponentially from 1 min to 24 h, on round steps. */
        fun minutesAt(position: Float): Int {
            val raw = Math.exp(position * Math.log(MAX_MINUTES.toDouble()))
            val step = when {
                raw < 10 -> 1
                raw < 60 -> 5
                raw < 180 -> 15
                raw < 600 -> 30
                else -> 60
            }
            return ((raw / step).roundToInt() * step).coerceIn(1, MAX_MINUTES)
        }

        fun positionOf(minutes: Int): Float = (Math.log(minutes.toDouble()) / Math.log(MAX_MINUTES.toDouble())).toFloat()

        /** "25 min", "1 h 30 min", "24 h". */
        fun label(minutes: Int): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }

        /** "18:42", or "1:05:09" past an hour. */
        fun clock(millis: Long): String {
            val s = (millis + 999) / 1000
            return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
        }
    }

    fun essentialApps(): Set<String> = buildSet {
        add(context.packageName)
        add("android")
        add("com.android.systemui")
        add("com.android.settings") // Always retain a way to disable the service.
        add("com.android.phone")
        add("com.android.server.telecom")
        add("com.android.emergency")
        add("moe.shizuku.privileged.api")
        add("com.android.permissioncontroller")
        add("com.google.android.permissioncontroller")
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let(::add)
        // Force-stopping clears alarms and message delivery, so keep the clock and SMS apps.
        android.provider.Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        context.packageManager.queryIntentActivities(android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM), 0)
            .forEach { add(it.activityInfo.packageName) }
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.enabledInputMethodList.forEach { add(it.packageName) }
    }

    /** Locked right now. Essentials (calls, Settings, the keyboard...) never are. */
    fun locks(packageName: String, essentials: Set<String>): Boolean =
        active && packageName in lockedApps && packageName !in essentials

    fun allows(packageName: String, essentials: Set<String>): Boolean = !locks(packageName, essentials)

    fun checkLaunch(packageName: String): Boolean {
        if (!locks(packageName, essentialApps())) return true
        context.showToast(R.string.focus_app_blocked)
        return false
    }
}
