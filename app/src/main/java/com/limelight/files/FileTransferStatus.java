package com.limelight.files;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limelight.R;
import com.limelight.tailnet.TailnetManager;

/** Whether the embedded node can send and receive files yet, for the file transfer screens. */
final class FileTransferStatus {
    private static final int REQUEST_NOTIFICATIONS = 7101;

    /** The node's status, or null when built-in Tailscale is unavailable or turned off. */
    final TailnetManager.Status status;
    /** Why files can't be sent or received yet, or null when they can. */
    final CharSequence problem;

    private FileTransferStatus(TailnetManager.Status status, CharSequence problem) {
        this.status = status;
        this.problem = problem;
    }

    /** Asks the node, so not on the main thread. */
    static FileTransferStatus read(Context context, TailnetManager tailnet) {
        TailnetManager.Status status = tailnet.isAvailable() && tailnet.isEnabled() ? tailnet.getStatus() : null;
        return new FileTransferStatus(status, problem(context, tailnet, status));
    }

    /** The node is still connecting, so the status is worth reading again shortly. */
    boolean isConnecting() {
        return status != null && !status.isRunning() && status.authUrl == null && status.error == null;
    }

    private static CharSequence problem(Context context, TailnetManager tailnet, TailnetManager.Status status) {
        if (!tailnet.isAvailable()) {
            return context.getString(R.string.tailnet_unavailable);
        }
        if (status == null) {
            return context.getString(R.string.received_files_status_disabled);
        }
        if (status.error != null) {
            return status.error;
        }
        if (status.authUrl != null || "NeedsLogin".equals(status.state)) {
            return context.getString(R.string.received_files_status_login);
        }
        if (!status.isRunning()) {
            return context.getString(R.string.received_files_status_connecting);
        }
        if (!status.fileSharing) {
            return context.getString(R.string.received_files_status_no_sharing);
        }
        return null;
    }

    /** "File received" and sending progress notifications need permission on Android 13+. */
    static void requestNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    /**
     * Reads off the main thread whenever a screen shows, then shows the result on the main thread,
     * and reads again every so often while the read asks to (the node may still be connecting).
     */
    static final class Poller {
        interface Task {
            /** Off the main thread; returns whether to read again later. */
            boolean read();

            /** On the main thread, after each read. */
            void show();
        }

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Task task;
        private final int maxReads;
        private final long intervalMs;
        // Changes on every start and stop, so reads still running for an earlier one are dropped
        private int generation;

        Poller(Task task, int maxReads, long intervalMs) {
            this.task = task;
            this.maxReads = maxReads;
            this.intervalMs = intervalMs;
        }

        void start() {
            stop();
            read(generation, maxReads);
        }

        void stop() {
            generation++;
            handler.removeCallbacksAndMessages(null);
        }

        private void read(int started, int readsLeft) {
            new Thread(() -> {
                boolean again = task.read();
                handler.post(() -> {
                    if (started != generation) {
                        return;
                    }
                    task.show();
                    if (again && readsLeft > 1) {
                        handler.postDelayed(() -> read(started, readsLeft - 1), intervalMs);
                    }
                });
            }, "File transfer status").start();
        }
    }
}
