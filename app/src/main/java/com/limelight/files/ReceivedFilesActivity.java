package com.limelight.files;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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

/** File transfer: how to send files from a computer, sending files to one, and the files received so far. */
public class ReceivedFilesActivity extends BaseActivity {
    // The node may still be connecting when the screen opens; check again a few times
    private static final int STATUS_READS = 6;
    private static final long STATUS_RETRY_MS = 2000;

    private LinearLayout list;
    private TextView emptyView;
    private CharSequence statusText;
    private FileTransferStatus status;
    private final FileTransferStatus.Poller poller = new FileTransferStatus.Poller(new FileTransferStatus.Poller.Task() {
        @Override
        public boolean read() {
            status = FileTransferStatus.read(ReceivedFilesActivity.this, TailnetManager.get(ReceivedFilesActivity.this));
            return status.isConnecting();
        }

        @Override
        public void show() {
            statusText = describe(status);
            refresh();
        }
    }, STATUS_READS, STATUS_RETRY_MS);

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
        FileTransferStatus.requestNotificationPermission(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        TailnetManager.get(this).startIfEnabledAsync();
        poller.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.stop();
    }

    private CharSequence describe(FileTransferStatus status) {
        if (status.problem != null) {
            return status.problem;
        }
        // The device name senders pick is the first label of its MagicDNS name
        String name = status.status.name != null ? status.status.name : "";
        int dot = name.indexOf('.');
        return getString(R.string.received_files_status_ready, dot > 0 ? name.substring(0, dot) : name);
    }

    private void refresh() {
        list.removeAllViews();
        list.addView(ListRows.create(this, getString(R.string.received_files_how_to_title), statusText,
                v -> TailnetAccountDialog.show(this)));
        list.addView(ListRows.create(this, getString(R.string.send_files_action), getString(R.string.send_files_action_summary),
                v -> startActivity(new Intent(this, SendFilesActivity.class))));

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
