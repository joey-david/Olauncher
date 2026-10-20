package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import app.olauncher.R

/** A monotonic timer shared by the launcher and its accessibility service. */
class FocusSession(private val context: Context) {
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    var minutes: Int
        get() = prefs.getInt("minutes", 25)
        set(value) { prefs.edit { putInt("minutes", value.coerceIn(1, 240)) } }
    var allowedApps: Set<String>
        get() = prefs.getStringSet("allowed", emptySet())!!.toSet()
        set(value) { prefs.edit { putStringSet("allowed", value.toSet()) } }
    var closeBlockedApps: Boolean
        get() = prefs.getBoolean("closeBlockedApps", false)
        set(value) { prefs.edit { putBoolean("closeBlockedApps", value) } }
    val configured: Boolean get() = prefs.contains("allowed")
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

    fun stop() { prefs.edit { remove("end") } }

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

    fun allows(packageName: String, essentials: Set<String>): Boolean =
        !active || packageName in allowedApps || packageName in essentials

    fun checkLaunch(packageName: String): Boolean {
        if (!active || allows(packageName, essentialApps())) return true
        context.showToast(R.string.focus_app_blocked)
        return false
    }
}
