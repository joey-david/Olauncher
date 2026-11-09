package app.olauncher.helper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** DND silences before delivery; this listener keeps an inbox and forwards only chosen alerts. */
class QuietNotifications : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var focus: FocusSession
    private lateinit var inbox: NotificationInbox
    private val calls = mutableMapOf<String, String>()
    private val messages = mutableMapOf<String, Long>()
    private val bypasses = mutableMapOf<String, Pair<String, String>>()
    private var wasActive = false
    private var wasConfigured = false
    private val tick = object : Runnable {
        override fun run() {
            if (focus.active != wasActive || focus.configured != wasConfigured) {
                wasConfigured = focus.configured
                wasActive = focus.active
                QuietMode.sync(this@QuietNotifications)
                if (wasActive) removeMirrors() else captureWaiting()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onListenerConnected() {
        connected = this
        calls.clear()
        messages.clear()
        bypasses.clear()
        focus = FocusSession(this)
        inbox = NotificationInbox(this)
        wasConfigured = focus.configured
        wasActive = focus.active
        QuietMode.sync(this)
        captureWaiting()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun appName(sbn: StatusBarNotification): String = runCatching {
        packageManager.getApplicationInfo(sbn.packageName, 0).loadLabel(packageManager).toString()
    }.getOrDefault(sbn.packageName)

    private fun captureWaiting() {
        activeNotifications.orEmpty().forEach { receive(it, currentRanking, alert = false) }
    }

    private fun receive(sbn: StatusBarNotification, rankingMap: RankingMap, alert: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (isCall(n)) calls[sbn.key] = sbn.packageName else calls.remove(sbn.key)
        val text = n.category == Notification.CATEGORY_MESSAGE ||
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MessagingStyle") == true
        if (!text) messages.remove(sbn.key)
        if (text && n.flags and Notification.FLAG_GROUP_SUMMARY == 0) {
            val age = (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0)
            if (alert || age < TEXT_GRACE_MS) messages[sbn.key] = android.os.SystemClock.elapsedRealtime() +
                if (alert) TEXT_GRACE_MS else TEXT_GRACE_MS - age
        }
        if (isCall(n) || text) MyAccessibilityService.communicationArrived()
        if (!focus.configured || focus.active || isCall(n) || n.category == Notification.CATEGORY_ALARM) return
        val ranking = Ranking()
        rankingMap.getRanking(sbn.key, ranking)
        if (sbn.packageName in focus.notificationApps) {
            // The source stays in Android's tray, hidden by DND. Keep its PendingIntents intact.
            // Only the forwarding copy may alert; native DND exceptions need no copy.
            if (ranking.matchesInterruptionFilter()) return
            forward(sbn, ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT, alert)
        } else {
            inbox.remember(sbn, appName(sbn))
            if (ranking.channel?.canBypassDnd() == true) bypasses[sbn.key] = appName(sbn) to n.channelId
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        receive(sbn, rankingMap, alert = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        calls.remove(sbn.key)
        messages.remove(sbn.key)
        bypasses.remove(sbn.key)
        if (sbn.packageName == packageName && sbn.tag?.startsWith(MIRROR) == true) {
            if (reason == REASON_CLICK) cancelNotification(sbn.tag.removePrefix(MIRROR))
            return
        }
        getSystemService(NotificationManager::class.java).cancel(MIRROR + sbn.key, 0)
        if (reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL)
            inbox.remove(sbn.key)
    }

    private fun forward(sbn: StatusBarNotification, audible: Boolean, alert: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val original = sbn.notification
        if (original.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val channel = if (audible && alert) QuietMode.ALERT_CHANNEL else SILENT_CHANNEL
        if (channel == SILENT_CHANNEL && nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(channel, "Updates", NotificationManager.IMPORTANCE_LOW).apply {
                setBypassDnd(true); setSound(null, null); enableVibration(false)
            })
        }
        val title = original.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = original.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: original.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val builder = Notification.Builder(this, channel)
            .setSmallIcon(original.smallIcon ?: android.graphics.drawable.Icon.createWithResource(this, app.olauncher.R.drawable.ic_lock))
            .setContentTitle("${appName(sbn)} · $title").setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setWhen(sbn.postTime)
            .setContentIntent(original.contentIntent).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(!alert || original.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        original.actions?.forEach { builder.addAction(it) }
        nm.notify(MIRROR + sbn.key, 0, builder.build())
    }

    private fun removeMirrors() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag?.startsWith(MIRROR) == true }.forEach { nm.cancel(it.tag, it.id) }
    }

    override fun onListenerDisconnected() {
        connected = null
        calls.clear()
        handler.removeCallbacks(tick)
        requestRebind(ComponentName(this, QuietNotifications::class.java))
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        private const val MIRROR = "chosen:"
        private const val SILENT_CHANNEL = "chosen_updates"
        private var connected: QuietNotifications? = null
        private const val TEXT_GRACE_MS = 90_000L
        fun maySkipUnlock(context: Context): Boolean {
            val mode = context.getSystemService(android.media.AudioManager::class.java).mode
            return connected?.calls?.isNotEmpty() == true || connected?.messages?.values?.any { it > android.os.SystemClock.elapsedRealtime() } == true ||
                mode == android.media.AudioManager.MODE_RINGTONE || mode == android.media.AudioManager.MODE_IN_CALL ||
                mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        }
        val isConnected: Boolean get() = connected != null
        fun isCall(notification: Notification): Boolean = notification.category == Notification.CATEGORY_CALL &&
            (notification.flags and Notification.FLAG_ONGOING_EVENT != 0 || notification.fullScreenIntent != null ||
                notification.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("CallStyle") == true)
        fun captureForSlot() { connected?.captureWaiting() }
        fun hasCall(pkg: String): Boolean = connected?.calls?.containsValue(pkg) == true
        fun hasAccess(context: Context): Boolean = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == ComponentName(context, QuietNotifications::class.java) } == true
        fun bypassWarnings(): List<Pair<String, String>> = connected?.bypasses?.values?.distinct().orEmpty()

        /** Called from the visible inbox. A stale notification falls back to its app in the same profile. */
        fun open(context: Context, entry: NotificationInbox.Entry): Boolean {
            if (!FocusSession(context).checkLaunch(entry.pkg)) return false
            val live = connected?.activeNotifications?.firstOrNull { it.key == entry.key }
            val opened = runCatching {
                val pending = live?.notification?.contentIntent
                if (pending != null) pending.send()
                else {
                    val launcher = context.getSystemService(LauncherApps::class.java)
                    val user = context.getSystemService(android.os.UserManager::class.java).getUserForSerialNumber(entry.user) ?: return false
                    val component = launcher.getActivityList(entry.pkg, user).firstOrNull()?.componentName ?: return false
                    launcher.startMainActivity(component, user, null, null)
                }
                true
            }.getOrDefault(false)
            if (opened) dismiss(context, entry.key)
            return opened
        }

        fun dismiss(context: Context, key: String) {
            NotificationInbox(context).remove(key)
            connected?.cancelNotification(key)
        }
    }
}
