package com.limelight.files;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.BaseActivity;
import com.limelight.R;
import com.limelight.tailnet.TailnetAccountDialog;
import com.limelight.tailnet.TailnetManager;
import com.limelight.ui.ListRows;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The file drop: how to send files from a computer, and the files received so far. */
public class ReceivedFilesActivity extends BaseActivity {
    private static final int REQUEST_NOTIFICATIONS = 7101;
    // The node may still be connecting when the screen opens; check again a few times
    private static final int STATUS_RETRIES = 5;
    private static final long STATUS_RETRY_MS = 2000;

    private LinearLayout list;
    private TextView emptyView;
    private CharSequence statusText;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Same screen as the stream log list
        setContentView(R.layout.activity_stream_logs);
        ((TextView) findViewById(R.id.stream_log_title)).setText(R.string.received_files_title);
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        list = findViewById(R.id.stream_log_list);
        emptyView = findViewById(R.id.stream_log_empty);
        emptyView.setText(R.string.received_files_empty);
        statusText = getString(R.string.received_files_status_checking);

        // Needed for the "File received" notification on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        TailnetManager tailnet = TailnetManager.get(this);
        tailnet.startIfEnabledAsync();
        updateStatus(tailnet, STATUS_RETRIES);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    // The status needs a call into the node, so it is read off the main thread
    private void updateStatus(TailnetManager tailnet, int retriesLeft) {
        new Thread(() -> {
            TailnetManager.Status status = tailnet.isAvailable() && tailnet.isEnabled() ? tailnet.getStatus() : null;
            boolean connecting = status != null && !status.isRunning() && status.authUrl == null
                    && status.error == null;
            CharSequence text = describe(tailnet, status);
            runOnUiThread(() -> {
                if (!resumed) {
                    return;
                }
                statusText = text;
                refresh();
                if (connecting && retriesLeft > 0) {
                    list.postDelayed(() -> {
                        if (resumed) {
                            updateStatus(tailnet, retriesLeft - 1);
                        }
                    }, STATUS_RETRY_MS);
                }
            });
        }, "File drop status").start();
    }

    private CharSequence describe(TailnetManager tailnet, TailnetManager.Status status) {
        if (!tailnet.isAvailable()) {
            return getString(R.string.tailnet_unavailable);
        }
        if (status == null) {
            return getString(R.string.received_files_status_disabled);
        }
        if (status.error != null) {
            return status.error;
        }
        if (status.authUrl != null || "NeedsLogin".equals(status.state)) {
            return getString(R.string.received_files_status_login);
        }
        if (!status.isRunning()) {
            return getString(R.string.received_files_status_connecting);
        }
        if (!status.fileSharing) {
            return getString(R.string.received_files_status_no_sharing);
        }
        // The device name senders pick is the first label of its MagicDNS name
        String name = status.name != null ? status.name : "";
        int dot = name.indexOf('.');
        return getString(R.string.received_files_status_ready, dot > 0 ? name.substring(0, dot) : name);
    }

    private void refresh() {
        list.removeAllViews();
        list.addView(ListRows.create(this, getString(R.string.received_files_how_to_title), statusText,
                v -> TailnetAccountDialog.show(this)));

        List<ReceivedFiles.Entry> entries = ReceivedFiles.list(this);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (ReceivedFiles.Entry entry : entries) {
            String detail = format.format(new Date(entry.time)) + "  ·  " + Formatter.formatShortFileSize(this, entry.size);
            View row = ListRows.create(this, entry.name, detail, v -> open(entry));
            row.setOnLongClickListener(v -> {
                showActions(entry);
                return true;
            });
            list.addView(row);
        }
    }

    private void open(ReceivedFiles.Entry entry) {
        try {
            startActivity(ReceivedFiles.openIntent(entry));
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.received_files_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ReceivedFiles.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(new String[]{
                        getString(R.string.received_files_open),
                        getString(R.string.received_files_share),
                        getString(R.string.received_files_delete)}, (dialog, which) -> {
                    if (which == 0) {
                        open(entry);
                    }
                    else if (which == 1) {
                        startActivity(ReceivedFiles.shareIntent(entry));
                    }
                    else {
                        confirmDelete(entry);
                    }
                })
                .show();
    }

    private void confirmDelete(ReceivedFiles.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setMessage(R.string.received_files_delete_confirm)
                .setNegativeButton(R.string.dialog_action_cancel, null)
                .setPositiveButton(R.string.received_files_delete, (dialog, which) -> {
                    ReceivedFiles.delete(this, entry);
                    refresh();
                })
                .show();
    }
}
