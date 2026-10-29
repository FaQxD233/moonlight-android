package com.limelight.files;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.support.v4.content.FileProvider;
import android.text.format.Formatter;
import android.webkit.MimeTypeMap;

import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Files sent to this device: saved under Downloads/月光, remembered for the file drop screen and announced. */
public final class ReceivedFiles {
    /** Writes the received file into fd, which it must close. */
    public interface Writer {
        void writeTo(int fd) throws Exception;
    }

    public static final class Entry {
        public final String name;
        public final long size;
        public final long time;
        public final String uri;
        public final String mimeType;

        Entry(String name, long size, long time, String uri, String mimeType) {
            this.name = name;
            this.size = size;
            this.time = time;
            this.uri = uri;
            this.mimeType = mimeType;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("name", name).put("size", size).put("time", time)
                    .put("uri", uri).put("mimeType", mimeType);
        }

        static Entry fromJson(JSONObject json) {
            return new Entry(json.optString("name"), json.optLong("size"), json.optLong("time"),
                    json.optString("uri"), json.optString("mimeType", "application/octet-stream"));
        }
    }

    private static final String FOLDER = "月光";
    private static final String PREFS = "received_files";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 100;
    private static final String CHANNEL_ID = "received_files";

    private ReceivedFiles() {
    }

    /** Saves a received file under Downloads/月光 and records it. */
    public static Entry save(Context context, String name, long size, Writer writer) throws Exception {
        // Senders choose the name; never let it pick a directory
        String fileName = new File(name).getName();
        String mimeType = mimeType(fileName);
        Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? saveToDownloads(context, fileName, mimeType, writer)
                : saveToAppDownloads(context, fileName, writer);
        Entry entry = new Entry(fileName, size, System.currentTimeMillis(), uri.toString(), mimeType);
        synchronized (ReceivedFiles.class) {
            List<Entry> entries = list(context);
            entries.add(0, entry);
            store(context, entries.subList(0, Math.min(entries.size(), MAX_ENTRIES)));
        }
        return entry;
    }

    // No storage permission needed: MediaStore lets an app add to Downloads directly
    @TargetApi(Build.VERSION_CODES.Q)
    private static Uri saveToDownloads(Context context, String name, String mimeType, Writer writer) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        // Hidden from other apps until it's complete
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Unable to add " + name + " to Downloads");
        }
        try {
            ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "w");
            if (file == null) {
                throw new IOException("Unable to open " + uri);
            }
            writer.writeTo(file.detachFd());
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }
        catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    // Android 9 and older need a storage permission for the shared Downloads, so use the app's own
    private static Uri saveToAppDownloads(Context context, String name, Writer writer) throws Exception {
        File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads != null ? downloads : new File(context.getFilesDir(), "Download"), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Unable to create " + dir);
        }
        File file = uniqueFile(dir, name);
        try {
            writer.writeTo(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE).detachFd());
        }
        catch (Exception e) {
            file.delete();
            throw e;
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    private static File uniqueFile(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        File file = new File(dir, name);
        for (int i = 1; file.exists(); i++) {
            file = new File(dir, base + " (" + i + ")" + extension);
        }
        return file;
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String type = dot >= 0 ? MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        return type != null ? type : "application/octet-stream";
    }

    /** Newest first. */
    public static synchronized List<Entry> list(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_ENTRIES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                entries.add(Entry.fromJson(array.getJSONObject(i)));
            }
        }
        catch (JSONException e) {
            LimeLog.warning("Unreadable received file list: " + e.getMessage());
        }
        return entries;
    }

    /** Deletes the saved file (if it's still there) and forgets it. */
    public static synchronized void delete(Context context, Entry entry) {
        try {
            context.getContentResolver().delete(Uri.parse(entry.uri), null, null);
        }
        catch (RuntimeException e) {
            LimeLog.warning("Unable to delete " + entry.uri + ": " + e.getMessage());
        }
        List<Entry> entries = list(context);
        // Not removeIf(): this screen also opens on Android versions without it
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().uri.equals(entry.uri)) {
                it.remove();
            }
        }
        store(context, entries);
    }

    private static void store(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(entry.toJson());
            }
        }
        catch (JSONException e) {
            LimeLog.warning("Unable to store the received file list: " + e.getMessage());
            return;
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Intent openIntent(Entry entry) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(entry.uri), entry.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static Intent shareIntent(Entry entry) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(entry.mimeType)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, entry.name);
    }

    /** Posts "File received"; tapping it opens the file. Needs the notification permission on Android 13+. */
    @TargetApi(Build.VERSION_CODES.O)
    public static void notifyReceived(Context context, Entry entry) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.received_files_channel), NotificationManager.IMPORTANCE_DEFAULT));
        int id = entry.uri.hashCode();
        PendingIntent open = PendingIntent.getActivity(context, id, openIntent(entry),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(id, new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.received_files_notification_title))
                .setContentText(entry.name + "  ·  " + Formatter.formatShortFileSize(context, entry.size))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
