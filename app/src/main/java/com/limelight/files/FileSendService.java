package com.limelight.files;

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
import com.limelight.tailnet.TailnetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends files with Taildrop or LocalSend from a foreground service, so a transfer carries on, with its
 * progress in a notification, after the user leaves the app.
 */
@TargetApi(Build.VERSION_CODES.O)
public final class FileSendService extends Service {
    private static final String ACTION_CANCEL = "com.limelight.files.CANCEL_SEND";
    private static final String EXTRA_TARGET_NAME = "target_name";
    // Taildrop: the device's node id
    private static final String EXTRA_TARGET_ID = "target_id";
    // LocalSend: how to reach the device
    private static final String EXTRA_IP = "ip";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_PROTOCOL = "protocol";
    private static final String EXTRA_FINGERPRINT = "fingerprint";
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

    /** Sends files this app can read to a device with Taildrop, in the background. */
    public static void send(Context context, TailnetManager.FileTarget target, List<Uri> files) {
        start(context, files, new Intent().putExtra(EXTRA_TARGET_NAME, target.name).putExtra(EXTRA_TARGET_ID, target.id));
    }

    /** Sends files this app can read to a LocalSend device, in the background. */
    public static void send(Context context, LocalSendManager.Device device, List<Uri> files) {
        start(context, files, new Intent().putExtra(EXTRA_TARGET_NAME, device.alias).putExtra(EXTRA_IP, device.ip)
                .putExtra(EXTRA_PORT, device.port).putExtra(EXTRA_PROTOCOL, device.protocol)
                .putExtra(EXTRA_FINGERPRINT, device.fingerprint));
    }

    private static void start(Context context, List<Uri> files, Intent intent) {
        ClipData clip = ClipData.newRawUri(null, files.get(0));
        for (int i = 1; i < files.size(); i++) {
            clip.addItem(new ClipData.Item(files.get(i)));
        }
        intent.setClass(context, FileSendService.class)
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
            LocalSendManager.get(this).cancelSend();
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
            Notification result = job.device != null ? sendWithLocalSend(job) : sendWithTaildrop(job);
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

    // These run on the worker thread

    private Notification sendWithTaildrop(Job job) {
        ContentResolver resolver = getContentResolver();
        TailnetManager tailnet = TailnetManager.get(this);
        Progress progress = new Progress(job);
        int sent = 0;
        String error = null;
        for (int i = 0; i < job.files.size() && !job.isCanceled(); i++) {
            int index = i;
            String name = sendableName(displayName(resolver, job.files.get(i)));
            try (ParcelFileDescriptor file = resolver.openFileDescriptor(job.files.get(i), "r")) {
                if (file == null) {
                    throw new IOException("Unable to open " + job.files.get(i));
                }
                // -1 when the file has no size, as for a stream
                long size = file.getStatSize();
                progress.show(index, name, 0, size);
                tailnet.sendFile(job.targetId, name, file.detachFd(), size, bytes -> progress.show(index, name, bytes, size));
                sent++;
            }
            catch (Exception e) {
                if (!job.isCanceled()) {
                    LimeLog.warning("Taildrop: unable to send " + name + ": " + e.getMessage());
                    error = name + ": " + e.getMessage();
                }
            }
        }
        progress.finish();
        return resultNotification(job, sent, error);
    }

    private Notification sendWithLocalSend(Job job) {
        ContentResolver resolver = getContentResolver();
        int count = job.files.size();
        String[] names = new String[count];
        long[] sizes = new long[count];
        long[] sentBytes = new long[count];
        Progress progress = new Progress(job);
        String error = null;
        try {
            // LocalSend asks the other side about every file before sending the first
            JSONArray list = new JSONArray();
            for (int i = 0; i < count; i++) {
                Uri uri = job.files.get(i);
                names[i] = sendableName(displayName(resolver, uri));
                sizes[i] = size(resolver, uri);
                String type = resolver.getType(uri);
                list.put(new JSONObject().put("name", names[i]).put("size", sizes[i])
                        .put("type", type != null ? type : "application/octet-stream"));
            }
            progress.show(0, names[0], 0, sizes[0]);
            LocalSendManager.get(this).send(job.device, list.toString(), new LocalSendBridge.Files() {
                @Override
                public int open(int index) throws Exception {
                    ParcelFileDescriptor file = resolver.openFileDescriptor(job.files.get(index), "r");
                    if (file == null) {
                        throw new IOException("Unable to open " + job.files.get(index));
                    }
                    return file.detachFd();
                }

                @Override
                public void sent(int index, long bytes) {
                    sentBytes[index] = bytes;
                    progress.show(index, names[index], bytes, sizes[index]);
                }
            });
        }
        catch (Exception e) {
            if (!job.isCanceled()) {
                LimeLog.warning("LocalSend: unable to send to " + job.targetName + ": " + e.getMessage());
                error = e.getMessage();
            }
        }
        progress.finish();
        int sent = 0;
        for (int i = 0; i < count; i++) {
            if (error == null || sizes[i] > 0 && sentBytes[i] == sizes[i]) {
                sent++;
            }
        }
        return resultNotification(job, sent, error);
    }

    private static long size(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        catch (RuntimeException e) {
            LimeLog.warning("No size for " + uri + ": " + e.getMessage());
        }
        try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
            long size = file != null ? file.getStatSize() : -1;
            if (size < 0) {
                throw new IOException("Unknown size of " + uri);
            }
            return size;
        }
    }

    private Notification progressNotification(Job job, int index, String name, long sentBytes, long size) {
        PendingIntent cancel = PendingIntent.getService(this, 0,
                new Intent(this, FileSendService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE);
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
            LimeLog.warning("No name for " + uri + ": " + e.getMessage());
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }

    // Taildrop refuses names with path separators and other characters Windows can't store
    private static String sendableName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return clean.isEmpty() || clean.equals(".") || clean.equals("..") ? "file" : clean;
    }

    // Shows a job's progress until finish(), after which late reports are dropped so that the
    // progress notification can't come back once the service has stopped
    private final class Progress {
        private final Job job;
        private boolean finished;

        Progress(Job job) {
            this.job = job;
        }

        synchronized void show(int index, String name, long bytes, long size) {
            if (!finished) {
                notifications.notify(PROGRESS_ID, progressNotification(job, index, name, bytes, size));
            }
        }

        synchronized void finish() {
            finished = true;
        }
    }

    private final class Job {
        final String targetName;
        // One of these: where to send with Taildrop, or with LocalSend
        final String targetId;
        final LocalSendManager.Device device;
        final List<Uri> files = new ArrayList<>();
        final int cancelsAtStart;

        Job(Intent intent, int cancelsAtStart) {
            targetName = intent.getStringExtra(EXTRA_TARGET_NAME);
            targetId = intent.getStringExtra(EXTRA_TARGET_ID);
            String ip = intent.getStringExtra(EXTRA_IP);
            device = ip == null ? null : new LocalSendManager.Device(targetName, "", ip,
                    intent.getIntExtra(EXTRA_PORT, 0), intent.getStringExtra(EXTRA_PROTOCOL),
                    intent.getStringExtra(EXTRA_FINGERPRINT));
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
