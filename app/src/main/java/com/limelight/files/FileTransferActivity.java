package com.limelight.files;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.format.DateUtils;
import android.widget.LinearLayout;

import com.limelight.BaseActivity;
import com.limelight.R;
import com.limelight.tailnet.TailnetAccountDialog;
import com.limelight.tailnet.TailnetManager;
import com.limelight.ui.AppDialog;

import java.util.List;

/**
 * Tailscale and file transfer, from the home screen: sending files, the files received, and the
 * settings of built-in Tailscale, LAN transfer (LocalSend) and receiving in the background. These
 * settings aren't in the settings profiles, which switch streaming settings only.
 */
public class FileTransferActivity extends BaseActivity {
    private static final long STATUS_INTERVAL_MS = 3000;
    // LocalSend shows the alias in a list; longer names get cut off there
    private static final int MAX_ALIAS_LENGTH = 32;

    private LinearLayout content;
    private SharedPreferences prefs;
    private TailnetManager tailnet;
    private LocalSendManager localSend;
    private FileTransferStatus status;
    private String shownStatus;
    // Kept here: SharedPreferences only holds listeners weakly
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (changed, key) -> render();
    private SharedPreferences.OnSharedPreferenceChangeListener receivedListener;
    private final FileTransferStatus.Poller<FileTransferStatus> poller = new FileTransferStatus.Poller<>(
            new FileTransferStatus.Poller.Task<FileTransferStatus>() {
                @Override
                public FileTransferStatus read() {
                    FileTransferStatus read = FileTransferStatus.read(FileTransferActivity.this, tailnet);
                    if (read.status != null && "Stopped".equals(read.status.state)) {
                        // Logging out, or changing the control server, stops the node: start it again
                        // so that it can log in
                        tailnet.startIfEnabledAsync();
                    }
                    return read;
                }

                @Override
                public boolean show(FileTransferStatus result) {
                    status = result;
                    // Only redraw for a change, so a row being pressed isn't replaced under the finger
                    String shown = result.state + "|" + result.problem + "|" + result.describeDevice()
                            + "|" + localSend.isRunning();
                    if (!shown.equals(shownStatus)) {
                        shownStatus = shown;
                        render();
                    }
                    return true;
                }
            }, STATUS_INTERVAL_MS);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        tailnet = TailnetManager.get(this);
        localSend = LocalSendManager.get(this);
        content = FilePage.setUp(this, R.string.file_transfer_title);
        FilePage.addAction(this, R.drawable.ic_help, R.string.file_help_title, v -> FilePage.showHelp(this));
        FileTransferStatus.requestNotificationPermission(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tailnet.startIfEnabledAsync();
        localSend.startIfEnabledAsync();
        prefs.registerOnSharedPreferenceChangeListener(settingsListener);
        receivedListener = ReceivedFiles.listen(this, this::render);
        render();
        poller.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.stop();
        prefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
        ReceivedFiles.stopListening(this, receivedListener);
        receivedListener = null;
    }

    private void render() {
        Object focus = FilePage.focusedKey(content);
        content.removeAllViews();
        if (!FileTransferStatus.notificationsEnabled(this)) {
            FilePage.row(content).icon(R.drawable.ic_ft_warning)
                    .title(getString(R.string.file_notifications_off_title))
                    .detail(getString(R.string.file_notifications_off_detail))
                    .badge(getString(R.string.file_action_enable), R.color.home_warning)
                    .key("notifications")
                    .onClick(v -> FileTransferStatus.openNotificationSettings(this));
        }
        renderFiles();
        renderTailscale();
        renderLocalSend();
        renderBackground();
        FilePage.restoreFocus(content, focus);
    }

    private void renderFiles() {
        FilePage.section(content, getString(R.string.file_section_files), null);
        if (!isOn(tailnet) && !isOn(localSend)) {
            FilePage.note(content, getString(R.string.file_both_off_hint));
        }
        LinearLayout tiles = FilePage.tiles(content);
        FilePage.tile(tiles, R.drawable.ic_ft_image, getString(R.string.file_send_media),
                getString(R.string.file_send_media_detail), "media",
                v -> startActivity(SendFilesActivity.intent(this, SendFilesActivity.SOURCE_MEDIA)));
        FilePage.tile(tiles, R.drawable.ic_ft_file, getString(R.string.file_send_files),
                getString(R.string.file_send_files_detail), "files",
                v -> startActivity(SendFilesActivity.intent(this, SendFilesActivity.SOURCE_FILES)));

        List<ReceivedFiles.Entry> entries = ReceivedFiles.list(this);
        FilePage.Row received = FilePage.row(content).icon(R.drawable.ic_ft_inbox)
                .title(getString(R.string.received_files_title))
                .detail(entries.isEmpty() ? getString(R.string.received_files_empty)
                        : getString(R.string.file_received_latest, entries.get(0).name,
                        DateUtils.getRelativeTimeSpanString(entries.get(0).time, System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS), entries.size()))
                .chevron().key("received")
                .onClick(v -> startActivity(new Intent(this, ReceivedFilesActivity.class)));
        int fresh = ReceivedFiles.countNew(this);
        if (fresh > 0) {
            received.badge(getString(R.string.file_received_new, fresh), R.color.home_warning);
        }
    }

    private void renderTailscale() {
        FilePage.section(content, getString(R.string.tailnet_category), null);
        if (!tailnet.isAvailable()) {
            FilePage.row(content).icon(R.drawable.ic_ft_globe).title(getString(R.string.tailnet_enable_title))
                    .detail(getString(R.string.tailnet_unavailable));
            return;
        }
        boolean enabled = tailnet.isEnabled();
        FilePage.row(content).icon(R.drawable.ic_ft_globe).title(getString(R.string.tailnet_enable_title))
                .detail(getString(R.string.file_tailnet_enable_detail)).toggle(enabled).key("tailnet")
                .onClick(v -> prefs.edit().putBoolean(TailnetManager.PREF_ENABLED, !tailnet.isEnabled()).apply());
        if (tailnet.hasCrashReport()) {
            // The start that crashed turned it off; the report helps find out why
            FilePage.row(content).icon(R.drawable.ic_ft_warning).title(getString(R.string.file_tailnet_crashed))
                    .detail(getString(R.string.file_tailnet_crashed_detail))
                    .badge(getString(R.string.file_action_details), R.color.home_warning).key("crash")
                    .onClick(v -> TailnetAccountDialog.show(this));
        }
        if (!enabled) {
            return;
        }

        FilePage.Row account = FilePage.row(content).icon(R.drawable.ic_ft_account)
                .title(getString(R.string.tailnet_account_title)).chevron().key("account")
                .onClick(v -> TailnetAccountDialog.show(this));
        if (status == null || status.state == FileTransferStatus.State.OFF) {
            // Just turned on: the next read tells
            account.detail(getString(R.string.received_files_status_checking));
        }
        else {
            FileTransferStatus.State state = status.state;
            account.badge(getString(status.stateLabel()),
                    state == FileTransferStatus.State.READY ? R.color.home_connected
                            : state == FileTransferStatus.State.CONNECTING ? R.color.home_secondary_text
                            : R.color.home_warning);
            account.detail(state == FileTransferStatus.State.READY ? status.describeDevice() : status.problem);
        }

        String controlUrl = prefs.getString(TailnetManager.PREF_CONTROL_URL, "").trim();
        FilePage.row(content).icon(R.drawable.ic_ft_server).title(getString(R.string.tailnet_control_url_title))
                .detail(controlUrl.isEmpty() ? getString(R.string.file_control_url_default) : controlUrl)
                .chevron().key("control")
                .onClick(v -> editControlUrl());
    }

    private void renderLocalSend() {
        FilePage.section(content, getString(R.string.localsend_title), null);
        if (!localSend.isAvailable()) {
            FilePage.row(content).icon(R.drawable.ic_ft_lan).title(getString(R.string.file_localsend_enable))
                    .detail(getString(R.string.tailnet_unavailable));
            return;
        }
        boolean enabled = localSend.isEnabled();
        String detail = !enabled ? getString(R.string.localsend_enable_summary)
                : localSend.isRunning() ? getString(R.string.file_localsend_ready, localSend.getAlias())
                : getString(R.string.localsend_status_starting);
        FilePage.row(content).icon(R.drawable.ic_ft_lan).title(getString(R.string.file_localsend_enable))
                .detail(detail).toggle(enabled).key("localsend")
                .onClick(v -> prefs.edit().putBoolean(LocalSendManager.PREF_ENABLED, !localSend.isEnabled()).apply());
        if (!enabled) {
            return;
        }

        FilePage.row(content).icon(R.drawable.ic_ft_edit).title(getString(R.string.localsend_alias_title))
                .detail(localSend.getAlias()).chevron().key("alias")
                .onClick(v -> editAlias());
        boolean autoAccept = prefs.getBoolean(LocalSendManager.PREF_AUTO_ACCEPT, false);
        FilePage.row(content).icon(R.drawable.ic_ft_auto).title(getString(R.string.localsend_auto_accept_title))
                .detail(getString(R.string.localsend_auto_accept_summary)).toggle(autoAccept).key("auto")
                .onClick(v -> prefs.edit().putBoolean(LocalSendManager.PREF_AUTO_ACCEPT, !autoAccept).apply());
    }

    private void renderBackground() {
        FilePage.section(content, getString(R.string.file_section_background), null);
        boolean background = prefs.getBoolean(BackgroundReceiveService.PREF_BACKGROUND, false);
        FilePage.row(content).icon(R.drawable.ic_ft_download).title(getString(R.string.tailnet_background_pref_title))
                .detail(getString(isOn(tailnet) || isOn(localSend) ? R.string.file_background_detail
                        : R.string.file_background_needs_method))
                .toggle(background).key("background")
                .onClick(v -> prefs.edit().putBoolean(BackgroundReceiveService.PREF_BACKGROUND, !background).apply());
    }

    private static boolean isOn(TailnetManager tailnet) {
        return tailnet.isAvailable() && tailnet.isEnabled();
    }

    private static boolean isOn(LocalSendManager localSend) {
        return localSend.isAvailable() && localSend.isEnabled();
    }

    // Only for Headscale; a node belongs to one control server, so changing it logs this device out
    private void editControlUrl() {
        String current = prefs.getString(TailnetManager.PREF_CONTROL_URL, "").trim();
        AppDialog.showInput(this, getString(R.string.tailnet_control_url_title),
                getString(R.string.tailnet_control_url_summary), "https://headscale.example.com", current,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
                    String url = text.trim();
                    if (!url.isEmpty() && !isWebAddress(url)) {
                        return getString(R.string.file_control_url_invalid);
                    }
                    if (!url.equals(current)) {
                        AppDialog.showConfirm(this, getString(R.string.tailnet_control_url_title),
                                getString(R.string.file_control_url_confirm), getString(R.string.dialog_action_confirm),
                                true, () -> prefs.edit().putString(TailnetManager.PREF_CONTROL_URL, url).apply(), null);
                    }
                    return null;
                });
    }

    private static boolean isWebAddress(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null && !uri.getHost().isEmpty();
    }

    private void editAlias() {
        String custom = prefs.getString(LocalSendManager.PREF_ALIAS, "").trim();
        AppDialog.showInput(this, getString(R.string.localsend_alias_title),
                getString(R.string.localsend_alias_summary), localSend.getAlias(), custom,
                InputType.TYPE_CLASS_TEXT, text -> {
                    String alias = text.trim();
                    if (alias.length() > MAX_ALIAS_LENGTH) {
                        return getString(R.string.file_alias_too_long, MAX_ALIAS_LENGTH);
                    }
                    // Empty brings back the default name
                    prefs.edit().putString(LocalSendManager.PREF_ALIAS, alias).apply();
                    return null;
                });
    }
}
