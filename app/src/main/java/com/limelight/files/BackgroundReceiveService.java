package com.limelight.files;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.tailnet.TailnetManager;

/**
 * Keeps built-in Tailscale and LocalSend running while the app is in the background, so files keep
 * arriving. It runs while "Receive files in the background" and either of the two are on.
 */
@TargetApi(Build.VERSION_CODES.O)
public final class BackgroundReceiveService extends Service {
    // Named when only Tailscale used it; kept so the setting carries over
    public static final String PREF_BACKGROUND = "checkbox_tailnet_background";

    private static final String ACTION_STOP = "com.limelight.files.STOP_BACKGROUND";
    private static final String CHANNEL_ID = "tailnet_background";
    private static final int NOTIFICATION_ID = 0x7a5e1;

    private static volatile boolean running;

    /** Starts or stops the service to match the settings. Starting only works while the app is in the foreground. */
    public static void update(Context context) {
        Intent intent = new Intent(context, BackgroundReceiveService.class);
        if (!wanted(context)) {
            context.stopService(intent);
        }
        else if (!running) {
            try {
                context.startForegroundService(intent);
            }
            catch (IllegalStateException e) {
                // Android 12+ refuses while the app is in the background; the next app start tries again
                LimeLog.warning("Background receiving not started: " + e.getMessage());
            }
        }
    }

    private static boolean wanted(Context context) {
        TailnetManager tailnet = TailnetManager.get(context);
        LocalSendManager localSend = LocalSendManager.get(context);
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_BACKGROUND, false)
                && (tailnet.isAvailable() && tailnet.isEnabled() || localSend.isAvailable() && localSend.isEnabled());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // TailnetManager also stops this service once the setting is off
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_BACKGROUND, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.tailnet_background_channel), NotificationManager.IMPORTANCE_MIN));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, BackgroundReceiveService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, ReceivedFilesActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.tailnet_background_title))
                .setContentText(getString(R.string.tailnet_background_text))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.tailnet_background_stop), stop).build())
                .build();
        // Before Android 14 the type comes from the manifest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
        else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (!wanted(this)) {
            // Restarted by the system after the settings changed
            stopSelf();
            return START_NOT_STICKY;
        }
        // Also covers the system restarting this service after it killed the app
        TailnetManager.get(this).startIfEnabledAsync();
        LocalSendManager.get(this).startIfEnabledAsync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
