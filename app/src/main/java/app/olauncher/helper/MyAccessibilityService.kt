package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.content.ComponentName
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {
    private lateinit var focus: FocusSession
    private lateinit var unlockPause: UnlockPause
    private var essentials = emptySet<String>()
    private var foreground = ""
    private var keyboards = emptySet<String>()
    private var lastBlockTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { focus.noteScreenOff(); unlockPause.screenOff() }
                Intent.ACTION_USER_PRESENT -> { focus.noteUnlock(); unlockPause.unlocked(); guard() }
                Intent.ACTION_SCREEN_ON -> if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { focus.noteUnlock(); unlockPause.unlocked() }
            }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            guard()
            handler.postDelayed(this, 500)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onServiceConnected() {
        focus = FocusSession(this)
        unlockPause = UnlockPause(this)
        essentials = focus.essentialApps()
        keyboards = (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .enabledInputMethodList.map { it.packageName }.toSet()
        Prefs(applicationContext).lockModeOn = true
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked) { focus.noteUnlock(); unlockPause.resume() }
        QuietMode.sync(this)
        handler.post(tick)
        connected = this
        super.onServiceConnected()
    }

    /** The timer also runs without window events, so staying in an app cannot extend a slot. */
    private fun guard() {
        if (!::focus.isInitialized || !getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (focus.active || unlockPause.isVisible) return
        if (foreground.isBlank()) foreground = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (!focus.locks(foreground, essentials) || QuietNotifications.hasCall(foreground)) return
        // Check the live window only before blocking. A keyboard must not hide the app beneath it.
        val live = rootInActiveWindow?.packageName?.toString()
        if (live != null && live !in keyboards && live != "com.android.systemui") foreground = live
        val current = foreground
        if (current.isBlank() || !focus.locks(current, essentials) || QuietNotifications.hasCall(current)) return
        // Refresh for a newly installed keyboard or a new default dialer.
        essentials = focus.essentialApps()
        if (!focus.locks(current, essentials)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockTime < 1000) return
        lastBlockTime = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        showToast(focus.blockedMessage)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::focus.isInitialized) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg !in keyboards && pkg != "com.android.systemui") foreground = pkg
            guard()
            return
        }
        val source = event.source ?: return
        if (source.className == "android.widget.FrameLayout" &&
            source.contentDescription == getString(R.string.lock_layout_description) && Build.VERSION.SDK_INT >= 28)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        if (connected === this) connected = null
        if (::unlockPause.isInitialized) unlockPause.close()
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }
    companion object {
        private var connected: MyAccessibilityService? = null
        val isConnected: Boolean get() = connected != null
        fun communicationArrived() { connected?.unlockPause?.communicationArrived() }
        fun enabled(context: Context): Boolean = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == ComponentName(context, MyAccessibilityService::class.java) } == true
    }
}
