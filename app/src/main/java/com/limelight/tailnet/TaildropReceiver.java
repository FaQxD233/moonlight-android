package com.limelight.tailnet;

import android.content.Context;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.files.ReceivedFiles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Moves the files Taildrop receives into Downloads while the embedded node runs. */
final class TaildropReceiver implements Runnable {
    private static final int WAIT_SECONDS = 60;
    private static final long RETRY_MS = 5000;

    private final TailnetManager tailnet;
    private final Context context;

    TaildropReceiver(TailnetManager tailnet, Context context) {
        this.tailnet = tailnet;
        this.context = context;
    }

    @Override
    public void run() {
        while (tailnet.isStarted()) {
            long waitStart = SystemClock.uptimeMillis();
            JSONArray files = parse(tailnet.awaitWaitingFiles(WAIT_SECONDS));
            if (files.length() == 0) {
                // An immediate empty answer means Taildrop isn't ready yet, e.g. before logging in
                if (SystemClock.uptimeMillis() - waitStart < RETRY_MS) {
                    SystemClock.sleep(RETRY_MS);
                }
                continue;
            }
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file != null) {
                    save(file.optString("name"), file.optLong("size"));
                }
            }
        }
    }

    private void save(String name, long size) {
        try {
            ReceivedFiles.Entry entry = ReceivedFiles.save(context, name, size,
                    fd -> tailnet.saveWaitingFile(name, fd));
            ReceivedFiles.notifyReceived(context, entry);
        }
        catch (Exception e) {
            // The file stays with Taildrop; try again later instead of spinning
            LimeLog.warning("Taildrop: unable to save " + name + ": " + e.getMessage());
            SystemClock.sleep(RETRY_MS);
        }
    }

    private static JSONArray parse(String json) {
        try {
            return new JSONArray(json);
        }
        catch (JSONException e) {
            return new JSONArray();
        }
    }
}
