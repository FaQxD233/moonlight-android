package com.limelight.files;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks whether to take the files a LocalSend device offers, with a heads-up notification whose
 * buttons answer. It works the same with the app in the foreground or the background.
 */
@TargetApi(Build.VERSION_CODES.O)
public final class LocalSendPrompt {
    private static final String CHANNEL_ID = "localsend_prompt";
    private static final String EXTRA_ID = "prompt_id";
    private static final String EXTRA_ACCEPT = "accept";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0x10c5000);
    private static final Map<Integer, Answer> PENDING = new ConcurrentHashMap<>();

    private LocalSendPrompt() {
    }

    /** Blocks until the user answers or timeoutMs passes, which counts as declining. */
    static boolean ask(Context context, String title, String text, long timeoutMs) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) {
            LimeLog.warning("LocalSend: declined files, since notifications are off and nothing can ask");
            return false;
        }
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.localsend_prompt_channel), NotificationManager.IMPORTANCE_HIGH));
        int id = NEXT_ID.incrementAndGet();
        Answer answer = new Answer();
        PENDING.put(id, answer);
        try {
            notifications.notify(id, new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setTimeoutAfter(timeoutMs)
                    .addAction(action(context, id, true, R.string.localsend_prompt_accept))
                    .addAction(action(context, id, false, R.string.localsend_prompt_decline))
                    .build());
            return answer.await(timeoutMs);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        finally {
            PENDING.remove(id);
            notifications.cancel(id);
        }
    }

    private static Notification.Action action(Context context, int id, boolean accept, int label) {
        Intent intent = new Intent(context, Buttons.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_ACCEPT, accept);
        // Request codes keep the two buttons' intents apart
        PendingIntent pending = PendingIntent.getBroadcast(context, id * 2 + (accept ? 1 : 0), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(null, context.getString(label), pending).build();
    }

    private static final class Answer {
        private final CountDownLatch answered = new CountDownLatch(1);
        private volatile boolean accepted;

        void set(boolean accept) {
            accepted = accept;
            answered.countDown();
        }

        boolean await(long timeoutMs) throws InterruptedException {
            return answered.await(timeoutMs, TimeUnit.MILLISECONDS) && accepted;
        }
    }

    /** Receives the notification's buttons. */
    public static final class Buttons extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Answer answer = PENDING.get(intent.getIntExtra(EXTRA_ID, 0));
            if (answer != null) {
                answer.set(intent.getBooleanExtra(EXTRA_ACCEPT, false));
            }
        }
    }
}
