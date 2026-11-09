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

/** Covers every app after an unlock. Only a live call or a recent incoming text may cut it short. */
class UnlockPause(private val service: AccessibilityService) {
    private val prefs = service.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(service.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var black: View? = null
    val isVisible: Boolean get() = black != null
    private val finish = Runnable { hide() }

    fun screenOff() {
        hide()
        prefs.edit().putBoolean("unlockArmed", true).putLong("unlockEnd", 0).commit()
    }

    fun resume() {
        if (prefs.contains("unlockArmed") && (prefs.getBoolean("unlockArmed", false) ||
                prefs.getLong("unlockEnd", 0) > SystemClock.elapsedRealtime())) unlocked()
    }

    fun unlocked() {
        if (QuietNotifications.maySkipUnlock(service)) { communicationArrived(); return }
        val now = SystemClock.elapsedRealtime()
        val newUnlock = prefs.getBoolean("unlockArmed", true) || prefs.getInt("unlockBoot", -1) != boot
        if (newUnlock) prefs.edit().putBoolean("unlockArmed", false).putInt("unlockBoot", boot)
            .putLong("unlockEnd", now + WAIT_MS).commit()
        val remaining = prefs.getLong("unlockEnd", 0) - now
        if (remaining <= 0 || isVisible) return
        val view = View(service).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Unlock pause"
            isClickable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, _ -> true }
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
        black?.let { windows.removeView(it) }
        black = null
    }
    companion object { const val WAIT_MS = 10_000L }
}
