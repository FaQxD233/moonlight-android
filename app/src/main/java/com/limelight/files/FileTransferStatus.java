package com.limelight.files;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.limelight.R;
import com.limelight.tailnet.TailnetManager;

/** Whether the embedded node can send and receive files yet, for the file transfer screens. */
final class FileTransferStatus {
    private static final int REQUEST_NOTIFICATIONS = 7101;

    enum State {
        /** Not in this build, or not on this device */
        UNAVAILABLE,
        /** Built-in Tailscale is turned off */
        OFF,
        ERROR,
        LOGIN,
        CONNECTING,
        /** The tailnet doesn't let this device send and receive files */
        NO_SHARING,
        READY
    }

    final State state;
    /** The node's status, or null when built-in Tailscale is unavailable or turned off. */
    final TailnetManager.Status status;
    /** What's wrong, in more words than the state, or null. */
    final CharSequence problem;

    private FileTransferStatus(State state, TailnetManager.Status status, CharSequence problem) {
        this.state = state;
        this.status = status;
        this.problem = problem;
    }

    /** Asks the node, so not on the main thread. */
    static FileTransferStatus read(Context context, TailnetManager tailnet) {
        if (!tailnet.isAvailable()) {
            return new FileTransferStatus(State.UNAVAILABLE, null, context.getString(R.string.tailnet_unavailable));
        }
        if (!tailnet.isEnabled()) {
            return new FileTransferStatus(State.OFF, null, context.getString(R.string.file_tailnet_off_detail));
        }
        TailnetManager.Status status = tailnet.getStatus();
        if (status.error != null) {
            return new FileTransferStatus(State.ERROR, status, status.error);
        }
        if (status.authUrl != null || "NeedsLogin".equals(status.state)) {
            return new FileTransferStatus(State.LOGIN, status, context.getString(R.string.file_tailnet_login_detail));
        }
        if (!status.isRunning()) {
            return new FileTransferStatus(State.CONNECTING, status, null);
        }
        if (!status.fileSharing) {
            return new FileTransferStatus(State.NO_SHARING, status,
                    context.getString(R.string.received_files_status_no_sharing));
        }
        return new FileTransferStatus(State.READY, status, null);
    }

    /** A short word for the state, such as "Connected". */
    int stateLabel() {
        switch (state) {
            case UNAVAILABLE:
                return R.string.file_tailnet_state_unavailable;
            case OFF:
                return R.string.file_tailnet_state_off;
            case ERROR:
                return R.string.file_tailnet_state_error;
            case LOGIN:
                return R.string.file_tailnet_state_login;
            case CONNECTING:
                return R.string.file_tailnet_state_connecting;
            case NO_SHARING:
                return R.string.file_tailnet_state_no_sharing;
            default:
                return R.string.file_tailnet_state_ready;
        }
    }

    /**
     * The name other devices on the tailnet know this one by, the first label of its MagicDNS
     * name (what senders pick), then its Tailscale IPv4 address.
     */
    String describeDevice() {
        if (status == null || status.name == null) {
            return "";
        }
        int dot = status.name.indexOf('.');
        String name = dot > 0 ? status.name.substring(0, dot) : status.name;
        for (String address : status.addresses) {
            if (address.indexOf(':') < 0) {
                return name + " · " + address;
            }
        }
        return name;
    }

    /** "File received" and sending progress notifications need permission on Android 13+. */
    static void requestNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    /** Without notifications LocalSend can't ask before taking files, and sending shows no progress. */
    @TargetApi(Build.VERSION_CODES.O)
    static boolean notificationsEnabled(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        return notifications == null || notifications.areNotificationsEnabled();
    }

    @TargetApi(Build.VERSION_CODES.O)
    static void openNotificationSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
        }
        catch (ActivityNotFoundException e) {
            requestNotificationPermission(activity);
        }
    }

    /**
     * Reads off the main thread whenever a screen shows, then shows the result on the main thread,
     * and reads again every so often while the screen asks to.
     */
    static final class Poller<T> {
        interface Task<T> {
            /** Off the main thread. */
            T read();

            /** On the main thread, with what read() returned; returns whether to read again later. */
            boolean show(T result);
        }

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Task<T> task;
        private final long fastIntervalMs;
        private final int fastReads;
        private final long slowIntervalMs;
        // Changes on every start and stop, so reads still running for an earlier one are dropped
        private int generation;

        /** Reads every intervalMs. */
        Poller(Task<T> task, long intervalMs) {
            this(task, intervalMs, 0, intervalMs);
        }

        /** Reads every fastIntervalMs for the first fastReads reads, then every slowIntervalMs. */
        Poller(Task<T> task, long fastIntervalMs, int fastReads, long slowIntervalMs) {
            this.task = task;
            this.fastIntervalMs = fastIntervalMs;
            this.fastReads = fastReads;
            this.slowIntervalMs = slowIntervalMs;
        }

        void start() {
            stop();
            read(generation, 1);
        }

        void stop() {
            generation++;
            handler.removeCallbacksAndMessages(null);
        }

        private void read(int started, int reads) {
            new Thread(() -> {
                T result = task.read();
                handler.post(() -> {
                    if (started == generation && task.show(result)) {
                        handler.postDelayed(() -> read(started, reads + 1),
                                reads < fastReads ? fastIntervalMs : slowIntervalMs);
                    }
                });
            }, "File transfer status").start();
        }
    }
}
