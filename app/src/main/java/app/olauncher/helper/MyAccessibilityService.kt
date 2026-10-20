package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.R
import app.olauncher.data.Prefs

class MyAccessibilityService : AccessibilityService() {
    private lateinit var focus: FocusSession
    private var essentials = emptySet<String>()
    private var lastBlockedPackage: String? = null
    private var lastBlockTime = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        focus = FocusSession(this)
        essentials = focus.essentialApps()
        Prefs(applicationContext).lockModeOn = true
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (!focus.allows(pkg, essentials)) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (pkg != lastBlockedPackage || now - lastBlockTime > 500) {
                    lastBlockedPackage = pkg
                    lastBlockTime = now
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    showToast(R.string.focus_app_blocked)
                }
            }
            return
        }
        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                // Home button for recents feature disabled
                // getString(R.string.recents_layout_description) -> {
                //     performGlobalAction(GLOBAL_ACTION_RECENTS)
                // }
            }
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {

    }
}