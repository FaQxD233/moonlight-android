package com.limelight.tailnet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.ui.AppDialog;
import com.limelight.utils.UpdateChecker;

import java.io.IOException;

/** Settings flow for logging the embedded Tailscale node in or out. */
public final class TailnetAccountDialog {
    private static final long CONNECT_TIMEOUT_MS = 20000;
    // The panic message and the first frames; the full report goes to the clipboard
    private static final int CRASH_PREVIEW_CHARS = 600;

    private TailnetAccountDialog() {
    }

    public static void show(Activity activity) {
        TailnetManager tailnet = TailnetManager.get(activity);
        String title = activity.getString(R.string.tailnet_account_title);
        if (!tailnet.isAvailable()) {
            AppDialog.showMessage(activity, title, activity.getString(R.string.tailnet_unavailable), null, null);
            return;
        }
        String crashReport = tailnet.takeCrashReport();
        if (crashReport != null) {
            showCrashReport(activity, crashReport);
            return;
        }
        if (!tailnet.isEnabled()) {
            AppDialog.showMessage(activity, title, activity.getString(R.string.tailnet_enable_first), null, null);
            return;
        }

        boolean[] cancelled = {false};
        AlertDialog progress = AppDialog.showProgress(activity, title,
                activity.getString(R.string.tailnet_connecting), () -> cancelled[0] = true);
        new Thread(() -> {
            TailnetManager.Status status = tailnet.awaitReady(CONNECT_TIMEOUT_MS);
            activity.runOnUiThread(() -> {
                if (progress != null) {
                    progress.dismiss();
                }
                if (!cancelled[0]) {
                    showStatus(activity, tailnet, status);
                }
            });
        }, "Tailnet status").start();
    }

    private static void showStatus(Activity activity, TailnetManager tailnet, TailnetManager.Status status) {
        if (status.isRunning()) {
            AppDialog.showMessage(activity,
                    activity.getString(R.string.tailnet_connected_title),
                    activity.getString(R.string.tailnet_connected_message,
                            status.name != null ? status.name : "-", TextUtils.join(", ", status.addresses)),
                    activity.getString(R.string.dialog_action_close), null,
                    activity.getString(R.string.tailnet_logout), () -> confirmLogout(activity, tailnet),
                    false);
        }
        else if (status.authUrl != null) {
            AppDialog.showMessage(activity,
                    activity.getString(R.string.tailnet_login_title),
                    activity.getString(R.string.tailnet_login_message),
                    activity.getString(R.string.tailnet_open_browser),
                    () -> UpdateChecker.openUrl(activity, status.authUrl),
                    activity.getString(R.string.tailnet_use_auth_key), () -> askAuthKey(activity, tailnet),
                    false);
        }
        else {
            String message = status.error != null
                    ? status.error
                    : activity.getString(R.string.tailnet_timeout, status.state);
            AppDialog.showMessage(activity, activity.getString(R.string.tailnet_account_title), message, null, null);
        }
    }

    private static void showCrashReport(Activity activity, String report) {
        String title = activity.getString(R.string.tailnet_crash_title);
        if (report.isEmpty()) {
            AppDialog.showMessage(activity, title, activity.getString(R.string.tailnet_crash_no_details), null, null);
            return;
        }
        String preview = report.length() > CRASH_PREVIEW_CHARS
                ? report.substring(0, CRASH_PREVIEW_CHARS) + "…" : report;
        AppDialog.showMessage(activity, title,
                activity.getString(R.string.tailnet_crash_message) + "\n\n" + preview,
                activity.getString(R.string.tailnet_copy), () -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, report));
                    Toast.makeText(activity, R.string.tailnet_copied, Toast.LENGTH_SHORT).show();
                },
                activity.getString(R.string.dialog_action_close), null, false);
    }

    private static void confirmLogout(Activity activity, TailnetManager tailnet) {
        AppDialog.showConfirm(activity,
                activity.getString(R.string.tailnet_logout),
                activity.getString(R.string.tailnet_logout_confirm),
                activity.getString(R.string.tailnet_logout), true,
                () -> new Thread(() -> {
                    String message;
                    try {
                        tailnet.logout();
                        message = activity.getString(R.string.tailnet_logged_out);
                    }
                    catch (IOException e) {
                        message = e.getMessage();
                    }
                    String toast = message;
                    activity.runOnUiThread(() -> Toast.makeText(activity, toast, Toast.LENGTH_LONG).show());
                }, "Tailnet logout").start(),
                null);
    }

    private static void askAuthKey(Activity activity, TailnetManager tailnet) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(R.string.tailnet_auth_key_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.tailnet_use_auth_key)
                .setView(input)
                .setPositiveButton(R.string.dialog_action_confirm, (dialog, which) -> {
                    String authKey = input.getText().toString().trim();
                    if (!authKey.isEmpty()) {
                        loginWithAuthKey(activity, tailnet, authKey);
                    }
                })
                .setNegativeButton(R.string.dialog_action_cancel, null)
                .show();
    }

    private static void loginWithAuthKey(Activity activity, TailnetManager tailnet, String authKey) {
        new Thread(() -> {
            try {
                tailnet.loginWithAuthKey(authKey);
                // Show the progress and the resulting state like a normal visit
                activity.runOnUiThread(() -> show(activity));
            }
            catch (IOException e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "Tailnet auth key").start();
    }
}
