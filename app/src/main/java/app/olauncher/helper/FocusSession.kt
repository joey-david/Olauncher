package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import app.olauncher.BuildConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Shared daily budget. All writes use the same lock, including writes from the services. */
class FocusSession(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val boot: Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
) {
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private fun date(): LocalDate = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate()
    val hasSavedChoices: Boolean get() = prefs.contains("allowed")
    val configured: Boolean get() = prefs.contains("configuredVersion")
    val needsSetup: Boolean get() = prefs.getInt("configuredVersion", -1) != BuildConfig.VERSION_CODE
    val allowedApps: Set<String> get() = prefs.getStringSet("allowed", emptySet())!!.toSet()
    var morningApps: Set<String>
        get() = prefs.getStringSet("morningApps", emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet("morningApps", value.toSet()).commit() }
    val notificationApps: Set<String> get() = prefs.getStringSet("notifications", emptySet())!!.toSet()

    fun saveChoices(allowed: Set<String>, notifications: Set<String>) = synchronized(lock) {
        if (needsSetup) prefs.edit().putStringSet("allowed", allowed.toSet())
            .putStringSet("notifications", notifications.toSet()).commit()
    }

    /** Choices can change only after an app version update. The budget never resets on update. */
    fun configure(allowed: Set<String>, notifications: Set<String>): Boolean = synchronized(lock) {
        if (!needsSetup) return false
        prefs.edit().putStringSet("allowed", allowed.toSet())
            .putStringSet("notifications", notifications.toSet())
            .putStringSet("knownApps", installedApps())
            .putInt("configuredVersion", BuildConfig.VERSION_CODE).commit()
    }

    private fun installedApps(): Set<String> = context.packageManager.queryIntentActivities(
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.toSet()

    fun pendingApps(): Set<String> = synchronized(lock) {
        val installed = installedApps()
        if (!prefs.contains("knownApps")) {
            prefs.edit().putStringSet("knownApps", installed).commit()
            return emptySet()
        }
        installed - prefs.getStringSet("knownApps", emptySet())!! - essentialApps()
    }

    fun classifyApp(pkg: String, outside: Boolean, morning: Boolean, alerts: Boolean) = synchronized(lock) {
        if (pkg !in pendingApps()) return@synchronized false
        prefs.edit().putStringSet("allowed", if (outside) allowedApps + pkg else allowedApps - pkg)
            .putStringSet("morningApps", if (morning) morningApps + pkg else morningApps - pkg)
            .putStringSet("notifications", if (alerts) notificationApps + pkg else notificationApps - pkg)
            .putStringSet("knownApps", prefs.getStringSet("knownApps", emptySet())!! + pkg).commit()
    }

    fun beginAccessRepair() {
        prefs.edit().putLong("repairUntil", elapsed() + 5 * 60_000L).putInt("repairBoot", boot).commit()
    }
    val repairingAccess: Boolean get() = prefs.getInt("repairBoot", -1) == boot && prefs.getLong("repairUntil", 0) > elapsed()

    private fun refreshDay() {
        val today = date().toString()
        val saved = prefs.getString("day", "")!!
        // Moving the clock or time zone backwards must not refill the budget.
        if (today > saved) prefs.edit().putString("day", today).putInt("used", 0).commit()
    }

    val slotsLeft: Int get() = synchronized(lock) {
        refreshDay()
        (SLOTS - prefs.getInt("used", 0)).coerceIn(0, SLOTS)
    }
    val endElapsed: Long get() = if (prefs.getInt("slotBoot", -1) != boot) 0 else prefs.getLong("slotEnd", 0)
    val remaining: Long get() = (endElapsed - elapsed()).coerceIn(0, SLOT_MS)
    val active: Boolean get() = remaining > 0

    /** First unlock after 04:00, or after four hours with the screen off, starts the morning wait. */
    fun noteUnlock() = synchronized(lock) {
        val local = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault())
        val morningDay = local.toLocalDate().minusDays(if (local.hour < 4) 1 else 0).toString()
        val wakeDay = prefs.getString("wakeDay", "")!!
        val off = prefs.getLong("screenOff", -1)
        val slept = if (prefs.getInt("screenOffBoot", -1) == boot && off >= 0) elapsed() - off else 0
        if (wakeDay.isEmpty() || morningDay > wakeDay || slept >= SLEEP_MS) {
            prefs.edit().putString("wakeDay", morningDay)
                .putLong("wakeEnd", elapsed() + MORNING_MS).putInt("wakeBoot", boot)
                .putInt("morningSteps", 0).remove("screenOff").commit()
        } else if (prefs.getInt("wakeBoot", -1) != boot) {
            // A reboot cannot skip the morning wait or extend a running slot.
            prefs.edit().putLong("wakeEnd", elapsed() + MORNING_MS).putInt("wakeBoot", boot)
                .remove("screenOff").commit()
        } else prefs.edit().remove("screenOff").apply()
    }

    fun noteScreenOff() = synchronized(lock) {
        if (!prefs.contains("screenOff")) prefs.edit().putLong("screenOff", elapsed())
            .putInt("screenOffBoot", boot).commit()
    }

    val morningRemaining: Long get() {
        if (prefs.getInt("wakeBoot", -1) != boot) return MORNING_MS
        return (prefs.getLong("wakeEnd", 0) - elapsed()).coerceIn(0, MORNING_MS)
    }
    val morningSteps: Int get() = prefs.getInt("morningSteps", 0)
    fun toggleMorningStep(index: Int) = synchronized(lock) {
        require(index in 0..2)
        prefs.edit().putInt("morningSteps", morningSteps xor (1 shl index)).commit()
    }

    /** Starts exactly one slot. A caller must also check the Android access grants. */
    fun start(): Boolean = synchronized(lock) {
        if (!configured || active || morningRemaining > 0 || slotsLeft == 0) return false
        prefs.edit().putInt("used", prefs.getInt("used", 0) + 1)
            .putInt("slotBoot", boot).putLong("slotEnd", elapsed() + SLOT_MS).commit()
    }

    /** Ending early spends the whole slot. */
    fun finish() = synchronized(lock) { prefs.edit().putLong("slotEnd", 0).commit() }

    fun essentialApps(): Set<String> = buildSet {
        add(context.packageName)
        add("android")
        add("com.android.systemui")
        runCatching { getDefaultLauncherPackage(context) }.getOrNull()?.let(::add)
        add("com.android.settings")
        // Xiaomi hosts permission warnings outside Android Settings.
        listOf("com.miui.securitycenter", "com.miui.securityadd", "com.miui.permcenter", "com.lbe.security.miui", "com.miui.packageinstaller")
            .filter { pkg -> runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false) }
            .forEach { add(it) }
        add("com.android.phone")
        add("com.android.server.telecom")
        add("com.android.emergency")
        add("com.android.permissioncontroller")
        add("com.google.android.permissioncontroller")
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let(::add)
        context.packageManager.queryIntentServices(android.content.Intent(android.telecom.InCallService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.permission == android.Manifest.permission.BIND_INCALL_SERVICE &&
                it.serviceInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            .forEach { add(it.serviceInfo.packageName) }
        context.packageManager.queryIntentActivities(android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM), 0)
            .forEach { add(it.activityInfo.packageName) }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.forEach { add(it.packageName) }
    }

    fun isSystemPackage(pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** New installs and apps installed later are blocked unless explicitly chosen. */
    fun locks(packageName: String, essentials: Set<String>): Boolean =
        (configured || prefs.contains("allowed")) && packageName.isNotBlank() && !active &&
            packageName !in (if (morningRemaining > 0) morningApps else allowedApps) && packageName !in essentials &&
            !(repairingAccess && isSystemPackage(packageName))

    val blockedMessage: String get() = when {
        !configured -> "Choose your apps first"
        morningRemaining > 0 -> "First slot in ${clock(morningRemaining)}"
        slotsLeft == 0 -> "No slots left"
        else -> "Use a slot"
    }

    fun checkLaunch(packageName: String): Boolean {
        if (!locks(packageName, essentialApps())) return true
        context.showToast(blockedMessage)
        return false
    }

    companion object {
        const val SLOTS = 3
        const val SLOT_MS = 10 * 60_000L
        const val MORNING_MS = 30 * 60_000L
        const val SLEEP_MS = 4 * 60 * 60_000L
        const val LOCKED_ALPHA = 0.35f
        private val lock = Any()

        fun clock(millis: Long): String {
            val seconds = (millis.coerceAtLeast(0) + 999) / 1000
            return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}
