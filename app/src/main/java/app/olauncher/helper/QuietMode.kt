package app.olauncher.helper

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.Condition
import android.service.notification.ConditionProviderService
import android.service.notification.NotificationListenerService
import android.service.notification.ZenPolicy
import app.olauncher.MainActivity
import app.olauncher.R
import androidx.core.content.edit

/** Owns only this launcher's DND rule. Other DND rules keep their own state. */
object QuietMode {
    const val ALERT_CHANNEL = "chosen_alerts"
    private const val TIMER_CHANNEL = "daily_slot"
    private const val TIMER_ID = 41
    const val END_SLOT = "app.olauncher.END_SLOT"
    const val FINISH_SLOT = "app.olauncher.FINISH_SLOT"
    private fun condition(context: Context) = Uri.parse("condition://${context.packageName}/daily-quiet")
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun alarm(context: Context, action: String = END_SLOT) = PendingIntent.getBroadcast(context, if (action == END_SLOT) 0 else 1,
        Intent(context, DailySlotReceiver::class.java).setAction(action).putExtra("end", FocusSession(context).endElapsed),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun hasPolicyAccess(context: Context) = manager(context).isNotificationPolicyAccessGranted
    fun hasExactAlarms(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun channels(context: Context) {
        val nm = manager(context)
        if (!nm.isNotificationPolicyAccessGranted) return
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(TIMER_CHANNEL, "Slot", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun ruleId(context: Context): String? {
        if (!hasPolicyAccess(context)) return null
        val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
        val nm = manager(context)
        val saved = prefs.getString("quietRule", null)
        if (saved != null && nm.getAutomaticZenRule(saved) != null) return saved
        return nm.automaticZenRules.entries.firstOrNull { it.value.conditionId == condition(context) }?.key
    }

    fun prepare(context: Context): Boolean = runCatching {
        if (!hasPolicyAccess(context)) return false
        channels(context)
        if (ruleId(context) != null) return true
        val owner = ComponentName(context, QuietConditionService::class.java)
        val policy = ZenPolicy.Builder().disallowAllSounds().allowAlarms(true)
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE).allowRepeatCallers(true)
            .allowMedia(true).hideAllVisualEffects().apply {
                if (Build.VERSION.SDK_INT >= 30) allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
                if (Build.VERSION.SDK_INT >= 35) allowPriorityChannels(true)
            }.build()
        val rule = AutomaticZenRule("Focus", owner, null, condition(context), policy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        val id = manager(context).addAutomaticZenRule(rule)
        context.getSharedPreferences("focus", Context.MODE_PRIVATE).edit { putString("quietRule", id) }
        true
    }.getOrDefault(false)

    fun isReady(context: Context): Boolean = runCatching {
        val nm = manager(context)
        val id = ruleId(context) ?: return false
        val focus = FocusSession(context)
        alertsEnabled(context) && nm.getAutomaticZenRule(id)?.isEnabled == true &&
            (!focus.configured || focus.active || nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
    }.getOrDefault(false)

    fun alertsEnabled(context: Context): Boolean {
        val channel = manager(context).getNotificationChannel(ALERT_CHANNEL) ?: return false
        return manager(context).areNotificationsEnabled() && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.canBypassDnd()
    }

    fun restore(context: Context) {
        if (!prepare(context)) return
        val id = ruleId(context) ?: return
        val rule = manager(context).getAutomaticZenRule(id) ?: return
        rule.isEnabled = true
        manager(context).updateAutomaticZenRule(id, rule)
        manager(context).setAutomaticZenRuleState(id, Condition(condition(context), "Restoring quiet time", Condition.STATE_FALSE))
        sync(context)
    }

    fun state(context: Context) = Condition(condition(context), "Quiet",
        if (FocusSession(context).configured && !FocusSession(context).active) Condition.STATE_TRUE else Condition.STATE_FALSE)

    fun sync(context: Context) {
        if (!FocusSession(context).configured || !prepare(context)) return
        runCatching {
            val id = ruleId(context) ?: return
            manager(context).setAutomaticZenRuleState(id, state(context))
        }
        updateTimer(context)
    }

    fun updateTimer(context: Context) {
        val remaining = FocusSession(context).remaining
        val alarms = context.getSystemService(AlarmManager::class.java)
        val nm = manager(context)
        alarms.cancel(alarm(context))
        if (remaining == 0L) { nm.cancel(TIMER_ID); return }
        if (hasExactAlarms(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + remaining, alarm(context))
        if (!nm.areNotificationsEnabled()) return
        val home = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .putExtra("showInbox", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(TIMER_ID, Notification.Builder(context, TIMER_CHANNEL)
            .setSmallIcon(R.drawable.ic_lock).setContentTitle("Slot")
            .setContentIntent(home).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis() + remaining).setUsesChronometer(true).setChronometerCountDown(true)
            .addAction(Notification.Action.Builder(null, "End", alarm(context, FINISH_SLOT)).build()).build())
    }
}

class QuietConditionService : ConditionProviderService() {
    override fun onConnected() { notifyCondition(QuietMode.state(this)) }
    override fun onSubscribe(conditionId: Uri?) { notifyCondition(QuietMode.state(this)) }
    override fun onUnsubscribe(conditionId: Uri?) {}
}

class DailySlotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val focus = FocusSession(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            app.olauncher.data.Prefs(context).dailyWallpaper = false
            WallpaperWorker.cancel(context)
            if (focus.configured) focus.pendingApps()
        }
        val matches = intent.getLongExtra("end", -1) == focus.endElapsed
        if (matches && (intent.action == QuietMode.FINISH_SLOT ||
                (intent.action == QuietMode.END_SLOT && !focus.active))) focus.finish()
        QuietMode.sync(context)
        if (!QuietNotifications.isConnected && QuietNotifications.hasAccess(context))
            NotificationListenerService.requestRebind(ComponentName(context, QuietNotifications::class.java))
    }
}
