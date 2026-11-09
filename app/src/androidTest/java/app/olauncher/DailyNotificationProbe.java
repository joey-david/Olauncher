package app.olauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs in the test APK's own UID, without the target app's Kotlin runtime. */
public class DailyNotificationProbe extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String kind = intent.getStringExtra("kind");
        if ("clear".equals(kind)) {
            manager.cancelAll();
            return;
        }
        boolean call = "call".equals(kind);
        manager.createNotificationChannel(new NotificationChannel("probe", "Test alerts", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(context, 1, new Intent(context, DailyCallProbeActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(7, new Notification.Builder(context, "probe")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(call ? "Incoming test call" : "A held message")
                .setContentText("A real notification from the test app.")
                .setContentIntent(open).setAutoCancel(!call).setOngoing(call)
                .setCategory(call ? Notification.CATEGORY_CALL : Notification.CATEGORY_MESSAGE).build());
    }
}
