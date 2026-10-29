package com.limelight.tailnet;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.files.ReceivedFilesActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends files with Taildrop from a foreground service, so a transfer carries on, with its progress in
 * a notification, after the user leaves the app.
 */
@TargetApi(Build.VERSION_CODES.O)
public final class TaildropSendService extends Service {
    private static final String ACTION_CANCEL = "com.limelight.tailnet.CANCEL_SEND";
    private static final String EXTRA_TARGET_ID = "target_id";
    private static final String EXTRA_TARGET_NAME = "target_name";
    private static final String CHANNEL_ID = "sending_files";
    private static final int PROGRESS_ID = 0x7a5e0;
    private static final int PROGRESS_MAX = 1000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notifications;
    // Main thread only
    private int jobs;
    // Bumped by each cancel, which stops the jobs queued before it
    private volatile int cancels;

    /** Sends files this app can read to target, in the background. */
    public static void send(Context context, TailnetManager.FileTarget target, List<Uri> files) {
        ClipData clip = ClipData.newRawUri(null, files.get(0));
        for (int i = 1; i < files.size(); i++) {
            clip.addItem(new ClipData.Item(files.get(i)));
        }
        Intent intent = new Intent(context, TaildropSendService.class)
                .putExtra(EXTRA_TARGET_ID, target.id)
                .putExtra(EXTRA_TARGET_NAME, target.name)
                // With the ClipData, this hands the service the permission to read the files
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(clip);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.send_files_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_CANCEL.equals(intent.getAction())) {
            cancels++;
            TailnetManager.get(this).cancelSends();
            if (jobs == 0) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        Job job = new Job(intent, cancels);
        // Has to follow startForegroundService() within seconds, even for nothing to send
        Notification first = progressNotification(job, 0, "", 0, -1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        else {
            startForeground(PROGRESS_ID, first);
        }
        jobs++;
        worker.execute(() -> {
            Notification result = send(job);
            mainHandler.post(() -> finished(PROGRESS_ID + startId, result));
        });
        return START_NOT_STICKY;
    }

    private void finished(int resultId, Notification result) {
        notifications.notify(resultId, result);
        if (--jobs == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Runs on the worker thread
    private Notification send(Job job) {
        ContentResolver resolver = getContentResolver();
        TailnetManager tailnet = TailnetManager.get(this);
        int sent = 0;
        String error = null;
        for (int i = 0; i < job.files.size() && !job.isCanceled(); i++) {
            String name = taildropName(displayName(resolver, job.files.get(i)));
            try (ParcelFileDescriptor file = resolver.openFileDescriptor(job.files.get(i), "r")) {
                if (file == null) {
                    throw new IOException("Unable to open " + job.files.get(i));
                }
                // -1 when the file has no size, as for a stream
                long size = file.getStatSize();
                FileProgress progress = new FileProgress(job, i, name, size);
                progress.sent(0);
                try {
                    tailnet.sendFile(job.targetId, name, file.detachFd(), size, progress);
                }
                finally {
                    progress.finish();
                }
                sent++;
            }
            catch (Exception e) {
                if (!job.isCanceled()) {
                    LimeLog.warning("Taildrop: unable to send " + name + ": " + e.getMessage());
                    error = name + ": " + e.getMessage();
                }
            }
        }
        return resultNotification(job, sent, error);
    }

    private Notification progressNotification(Job job, int index, String name, long sentBytes, long size) {
        PendingIntent cancel = PendingIntent.getService(this, 0,
                new Intent(this, TaildropSendService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE);
        String text = job.files.size() > 1 ? (index + 1) + "/" + job.files.size() + "  " + name : name;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.send_files_progress_title, job.targetName))
                .setContentText(text)
                .setContentIntent(openScreen())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.dialog_action_cancel), cancel).build());
        if (size > 0) {
            builder.setProgress(PROGRESS_MAX, (int) (Math.min(sentBytes, size) * PROGRESS_MAX / size), false);
        }
        else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private Notification resultNotification(Job job, int sent, String error) {
        String title;
        if (job.isCanceled()) {
            title = getString(R.string.send_files_canceled_title);
        }
        else if (error != null) {
            title = getString(R.string.send_files_failed_title, job.targetName);
        }
        else {
            title = getString(R.string.send_files_done_title, job.targetName);
        }
        String text = getString(R.string.send_files_result, sent, job.files.size());
        if (error != null) {
            text += "\n" + error;
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(error == null ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openScreen())
                .setAutoCancel(true)
                .build();
    }

    private PendingIntent openScreen() {
        return PendingIntent.getActivity(this, 0, new Intent(this, ReceivedFilesActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        }
        catch (RuntimeException e) {
            LimeLog.warning("Taildrop: no name for " + uri + ": " + e.getMessage());
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    // Taildrop refuses names with path separators and other characters Windows can't store
    private static String taildropName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return clean.isEmpty() || clean.equals(".") || clean.equals("..") ? "file" : clean;
    }

    // Shows a file's progress until finish(), after which late reports are dropped so the
    // progress notification can't come back once the service has stopped
    private final class FileProgress implements TailnetBridge.SendProgress {
        private final Job job;
        private final int index;
        private final String name;
        private final long size;
        private boolean finished;

        FileProgress(Job job, int index, String name, long size) {
            this.job = job;
            this.index = index;
            this.name = name;
            this.size = size;
        }

        @Override
        public synchronized void sent(long bytes) {
            if (!finished) {
                notifications.notify(PROGRESS_ID, progressNotification(job, index, name, bytes, size));
            }
        }

        synchronized void finish() {
            finished = true;
        }
    }

    private final class Job {
        final String targetId;
        final String targetName;
        final List<Uri> files = new ArrayList<>();
        final int cancelsAtStart;

        Job(Intent intent, int cancelsAtStart) {
            targetId = intent.getStringExtra(EXTRA_TARGET_ID);
            targetName = intent.getStringExtra(EXTRA_TARGET_NAME);
            ClipData clip = intent.getClipData();
            for (int i = 0; clip != null && i < clip.getItemCount(); i++) {
                files.add(clip.getItemAt(i).getUri());
            }
            this.cancelsAtStart = cancelsAtStart;
        }

        boolean isCanceled() {
            return cancels != cancelsAtStart;
        }
    }
}
