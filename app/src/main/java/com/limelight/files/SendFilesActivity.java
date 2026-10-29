package com.limelight.files;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.BaseActivity;
import com.limelight.R;
import com.limelight.tailnet.TaildropSendService;
import com.limelight.tailnet.TailnetAccountDialog;
import com.limelight.tailnet.TailnetManager;
import com.limelight.ui.ListRows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Picks the device to send files to with Taildrop: files shared from another app, or picked here. */
public class SendFilesActivity extends BaseActivity {
    private static final int REQUEST_PICK = 7102;
    private static final String STATE_FILES = "files";
    // Devices show up a little after the node connects, so keep looking for a while
    private static final int TARGET_READS = 15;
    private static final long TARGET_RETRY_MS = 2000;

    private final ArrayList<Uri> files = new ArrayList<>();
    private LinearLayout list;
    private TextView emptyView;
    private FileTransferStatus status;
    private List<TailnetManager.FileTarget> targets = Collections.emptyList();
    private boolean askedForNotifications;
    private final FileTransferStatus.Poller poller = new FileTransferStatus.Poller(new FileTransferStatus.Poller.Task() {
        @Override
        public boolean read() {
            TailnetManager tailnet = TailnetManager.get(SendFilesActivity.this);
            status = FileTransferStatus.read(SendFilesActivity.this, tailnet);
            targets = status.problem == null ? tailnet.getFileTargets() : Collections.emptyList();
            return status.isConnecting() || (status.problem == null && targets.isEmpty());
        }

        @Override
        public void show() {
            refresh();
        }
    }, TARGET_READS, TARGET_RETRY_MS);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Same screen as the stream log list
        setContentView(R.layout.activity_stream_logs);
        ((TextView) findViewById(R.id.stream_log_title)).setText(R.string.send_files_title);
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        list = findViewById(R.id.stream_log_list);
        emptyView = findViewById(R.id.stream_log_empty);
        emptyView.setText(R.string.send_files_no_devices);

        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(STATE_FILES);
            if (saved != null) {
                files.addAll(saved);
            }
            return;
        }
        String action = getIntent().getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            files.addAll(sharedFiles(getIntent()));
            if (files.isEmpty()) {
                Toast.makeText(this, R.string.send_files_nothing, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
        else {
            pickFiles();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(STATE_FILES, files);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (files.isEmpty()) {
            // Still picking
            return;
        }
        if (!askedForNotifications) {
            askedForNotifications = true;
            FileTransferStatus.requestNotificationPermission(this);
        }
        TailnetManager.get(this).startIfEnabledAsync();
        poller.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.stop();
    }

    private void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQUEST_PICK);
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.send_files_pick_failed, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            files.addAll(uris(data.getClipData(), data.getData()));
        }
        if (files.isEmpty()) {
            finish();
        }
    }

    private static List<Uri> sharedFiles(Intent intent) {
        List<Uri> shared = uris(intent.getClipData(), null);
        if (!shared.isEmpty()) {
            return shared;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return uris(null, stream);
        }
        ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        return streams != null ? streams : Collections.emptyList();
    }

    // The ClipData is what carries the permission to read each file
    private static List<Uri> uris(ClipData clip, Uri single) {
        List<Uri> uris = new ArrayList<>();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && single != null) {
            uris.add(single);
        }
        return uris;
    }

    private void refresh() {
        list.removeAllViews();
        if (files.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        CharSequence detail;
        if (status == null) {
            detail = getString(R.string.received_files_status_checking);
        }
        else if (status.problem != null) {
            detail = status.problem;
        }
        else {
            detail = getString(R.string.send_files_pick_device);
        }
        list.addView(ListRows.create(this, getString(R.string.send_files_count, files.size()), detail,
                v -> TailnetAccountDialog.show(this)));

        emptyView.setVisibility(status != null && status.problem == null && targets.isEmpty() ? View.VISIBLE : View.GONE);
        for (TailnetManager.FileTarget target : targets) {
            list.addView(ListRows.create(this, target.name, getString(R.string.send_files_device_detail), v -> {
                TaildropSendService.send(this, target, files);
                Toast.makeText(this, R.string.send_files_started, Toast.LENGTH_SHORT).show();
                finish();
            }));
        }
    }
}
