package com.limelight.files;

import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.limelight.BaseActivity;
import com.limelight.R;
import com.limelight.ui.AppDialog;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The files received so far, newest first and by day, to open, share or delete. */
public class ReceivedFilesActivity extends BaseActivity {
    private LinearLayout content;
    private SharedPreferences.OnSharedPreferenceChangeListener entriesListener;
    // Entries whose files another app moved or deleted
    private final Set<String> missing = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = FilePage.setUp(this, R.string.received_files_title);
        FilePage.addAction(this, R.drawable.ic_ft_clean, R.string.received_files_clean, v -> cleanUp());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Files arriving while the list is open show up in it, already seen
        entriesListener = ReceivedFiles.listen(this, this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ReceivedFiles.stopListening(this, entriesListener);
        entriesListener = null;
    }

    private void refresh() {
        ReceivedFiles.markViewed(this);
        render();
        findMissing();
    }

    // Opening every file takes a moment, so off the main thread
    private void findMissing() {
        List<ReceivedFiles.Entry> entries = ReceivedFiles.list(this);
        new Thread(() -> {
            Set<String> gone = new HashSet<>();
            for (ReceivedFiles.Entry entry : entries) {
                if (!ReceivedFiles.exists(this, entry)) {
                    gone.add(entry.uri);
                }
            }
            runOnUiThread(() -> {
                if (!gone.equals(missing)) {
                    missing.clear();
                    missing.addAll(gone);
                    render();
                }
            });
        }, "Received files check").start();
    }

    private void render() {
        Object focus = FilePage.focusedKey(content);
        content.removeAllViews();
        FilePage.note(content, getString(R.string.received_files_location, ReceivedFiles.folder(this)));
        List<ReceivedFiles.Entry> entries = ReceivedFiles.list(this);
        if (entries.isEmpty()) {
            FilePage.empty(content, R.drawable.ic_ft_inbox, getString(R.string.received_files_empty),
                    getString(R.string.received_files_help), v -> FilePage.showHelp(this));
            return;
        }

        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        day.add(Calendar.DAY_OF_MONTH, -1);
        long yesterday = day.getTimeInMillis();
        DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);
        DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(this);

        String shownDay = null;
        for (ReceivedFiles.Entry entry : entries) {
            String entryDay = entry.time >= today ? getString(R.string.received_files_today)
                    : entry.time >= yesterday ? getString(R.string.received_files_yesterday)
                    : dateFormat.format(new Date(entry.time));
            if (!entryDay.equals(shownDay)) {
                FilePage.section(content, entryDay, null);
                shownDay = entryDay;
            }

            boolean gone = missing.contains(entry.uri);
            List<String> details = new ArrayList<>();
            if (!entry.source().isEmpty()) {
                details.add(entry.source());
            }
            details.add(Formatter.formatShortFileSize(this, entry.size));
            details.add(timeFormat.format(new Date(entry.time)));
            FilePage.Row row = FilePage.row(content).icon(typeIcon(entry.mimeType)).title(entry.name).middleEllipsis()
                    .detail(gone ? getString(R.string.received_files_missing) : TextUtils.join(" · ", details))
                    .key(entry.uri)
                    .onClick(v -> open(entry))
                    .more(getString(R.string.received_files_more), v -> showActions(entry));
            if (gone) {
                row.dimmed();
            }
            else if (entry.mimeType.startsWith("image/") || entry.mimeType.startsWith("video/")) {
                Glide.with(this).load(Uri.parse(entry.uri)).centerCrop().into(row.picture());
            }
        }
        FilePage.restoreFocus(content, focus);
    }

    private static int typeIcon(String mimeType) {
        if (mimeType.startsWith("image/")) {
            return R.drawable.ic_ft_image;
        }
        if (mimeType.startsWith("video/")) {
            return R.drawable.ic_ft_video;
        }
        if (mimeType.startsWith("audio/")) {
            return R.drawable.ic_ft_audio;
        }
        if ("application/vnd.android.package-archive".equals(mimeType)) {
            return R.drawable.ic_ft_apk;
        }
        return R.drawable.ic_ft_file;
    }

    private void open(ReceivedFiles.Entry entry) {
        if (missing.contains(entry.uri)) {
            Toast.makeText(this, R.string.received_files_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(ReceivedFiles.openIntent(entry));
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.received_files_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ReceivedFiles.Entry entry) {
        AppDialog.showOptions(this, entry.name, new CharSequence[]{
                getString(R.string.received_files_open),
                getString(R.string.received_files_share),
                getString(R.string.received_files_delete)}, which -> {
            if (which == 0) {
                open(entry);
            }
            else if (which == 1) {
                startActivity(ReceivedFiles.shareIntent(entry));
            }
            else if (which == 2) {
                AppDialog.showConfirm(this, entry.name, getString(R.string.received_files_delete_confirm),
                        getString(R.string.received_files_delete), true,
                        () -> ReceivedFiles.delete(this, entry), null);
            }
        });
    }

    // Forgets the files other apps moved or deleted; the list updates through the listener
    private void cleanUp() {
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.received_files_clean_none, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> uris = new ArrayList<>(missing);
        AppDialog.showConfirm(this, getString(R.string.received_files_clean),
                getString(R.string.received_files_clean_confirm, uris.size()), getString(R.string.received_files_clean),
                false, () -> ReceivedFiles.forget(this, uris), null);
    }
}
