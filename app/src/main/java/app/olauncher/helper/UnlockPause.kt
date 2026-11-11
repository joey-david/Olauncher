package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import app.olauncher.data.DeadlineStore

/** Covers every app after an unlock. Only a live call or a recent incoming text may cut it short. */
class UnlockPause(private val service: AccessibilityService) {
    private val prefs = service.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(service.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var black: View? = null
    val isVisible: Boolean get() = black != null
    private val finish = Runnable { hide() }
    private var countdown: TextView? = null
    private val update = object : Runnable {
        override fun run() {
            val deadline = DeadlineStore(service).next()
            countdown?.text = if (deadline == null) "No deadline" else {
                val seconds = ((deadline.at - System.currentTimeMillis()).coerceAtLeast(0) / 1000)
                "${deadline.title}\n${seconds / 86400}d  ${seconds / 3600 % 24}h  ${seconds / 60 % 60}m  ${seconds % 60}s"
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun screenOff() {
        hide()
        prefs.edit().putBoolean("unlockArmed", true).putLong("unlockEnd", 0).commit()
    }

    fun resume() {
        if (prefs.contains("unlockArmed") && (prefs.getBoolean("unlockArmed", false) ||
                prefs.getLong("unlockEnd", 0) > SystemClock.elapsedRealtime())) unlocked()
    }

    fun unlocked() {
        val focus = FocusSession(service)
        val pkg = service.rootInActiveWindow?.packageName?.toString()
        if (focus.repairingAccess && pkg != null && pkg != service.packageName && focus.isSystemPackage(pkg)) {
            prefs.edit().putBoolean("unlockArmed", false).putInt("unlockBoot", boot).putLong("unlockEnd", 0).commit()
            hide()
            return
        }
        if (QuietNotifications.maySkipUnlock(service)) { communicationArrived(); return }
        val now = SystemClock.elapsedRealtime()
        val newUnlock = prefs.getBoolean("unlockArmed", true) || prefs.getInt("unlockBoot", -1) != boot
        if (newUnlock) prefs.edit().putBoolean("unlockArmed", false).putInt("unlockBoot", boot)
            .putLong("unlockEnd", now + WAIT_MS).commit()
        val remaining = prefs.getLong("unlockEnd", 0) - now
        if (remaining <= 0 || isVisible) return
        fun label(value: String, size: Float) = TextView(service).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(40, 24, 40, 24)
        }
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 80, 24, 80)
            addView(label(DailyPhoneUsage(service).summary(), 18f))
            countdown = label("", 26f)
            addView(countdown)
        }
        val view = ScrollView(service).apply {
            addView(panel)
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            contentDescription = "Unlock pause"
            isClickable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, _ -> true }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "Unlock pause"
        }
        windows.addView(view, params)
        black = view
        view.requestFocus()
        // After the wait, the nearest deadline is the first thing on screen.
        if (newUnlock) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        handler.post(update)
        handler.postDelayed(finish, remaining)
    }

    fun communicationArrived() {
        if (!QuietNotifications.maySkipUnlock(service)) return
        prefs.edit().putBoolean("unlockArmed", false).putInt("unlockBoot", boot).putLong("unlockEnd", 0).commit()
        hide()
    }

    fun close() = hide()
    private fun hide() {
        handler.removeCallbacks(finish)
        handler.removeCallbacks(update)
        countdown = null
        black?.let { windows.removeView(it) }
        black = null
    }
    companion object { const val WAIT_MS = 10_000L }
}
