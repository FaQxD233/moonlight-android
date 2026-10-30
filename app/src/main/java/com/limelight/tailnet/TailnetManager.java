package com.limelight.tailnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.utils.CacheHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional Tailscale node embedded in the app (tsnet, built from tailnet/). Hosts at Tailscale
 * addresses are reached through relays on a per-host loopback address, so streaming to them needs
 * neither the Tailscale app nor a VPN.
 */
public final class TailnetManager {
    public static final String PREF_ENABLED = "checkbox_enable_tailnet";
    public static final String PREF_CONTROL_URL = "edit_tailnet_control_url";
    public static final String PREF_BACKGROUND = "checkbox_tailnet_background";

    private static final String BRIDGE_CLASS = "com.limelight.tailnet.GoTailnetBridge";
    private static final String STATE_DIR = "tailnet";
    private static final long STATUS_POLL_MS = 500;
    // Set while a start hasn't yet survived START_SURVIVAL_MS; still set at launch means it crashed
    private static final String PREF_START_PENDING = "tailnet_start_pending";
    private static final String PREF_CRASHED = "tailnet_crashed";
    private static final long START_SURVIVAL_MS = 30000;
    private static final String CRASH_LOG = "tailnet-crash.txt";
    private static final int MAX_CRASH_REPORT_CHARS = 64 * 1024;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile TailnetManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final TailnetBridge bridge;
    // SharedPreferences only holds listeners weakly, so keep a reference
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = this::onPreferenceChanged;
    private final Map<String, String> loopbackByHost = new HashMap<>();
    private final Map<String, String> hostByLoopback = new HashMap<>();
    private boolean started;
    private Thread taildropReceiver;

    private TailnetManager(Context appContext) {
        this.appContext = appContext;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        this.bridge = loadBridge();
        if (bridge != null) {
            disableIfLastStartCrashed();
            try {
                bridge.setCrashLog(crashLog().getAbsolutePath());
            }
            catch (Exception e) {
                LimeLog.warning("Embedded Tailscale crash log unavailable: " + e.getMessage());
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    public static TailnetManager get(Context context) {
        TailnetManager manager = instance;
        if (manager == null) {
            synchronized (TailnetManager.class) {
                manager = instance;
                if (manager == null) {
                    manager = new TailnetManager(context.getApplicationContext());
                    instance = manager;
                }
            }
        }
        return manager;
    }

    private static TailnetBridge loadBridge() {
        if (!BuildConfig.HAS_TAILNET) {
            LimeLog.info("Embedded Tailscale not included in this build");
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            LimeLog.info("Embedded Tailscale requires Android 8.0");
            return null;
        }
        try {
            return (TailnetBridge) Class.forName(BRIDGE_CLASS).getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException | LinkageError e) {
            LimeLog.warning("Embedded Tailscale unavailable: " + e);
            return null;
        }
    }

    // A start that never confirmed it survived took the whole app down, and every launch would
    // crash again. Turn the feature off so the app opens; takeCrashReport() explains why.
    private void disableIfLastStartCrashed() {
        if (prefs.getBoolean(PREF_START_PENDING, false)) {
            LimeLog.warning("Embedded Tailscale crashed the app while starting; turning it off");
            prefs.edit().remove(PREF_START_PENDING).putBoolean(PREF_ENABLED, false)
                    .putBoolean(PREF_CRASHED, true).commit();
        }
    }

    /**
     * Go's crash output from the start that crashed the app (empty if none was captured, as
     * with native crashes), or null if it didn't crash. Reading it clears it.
     */
    public synchronized String takeCrashReport() {
        if (!prefs.getBoolean(PREF_CRASHED, false)) {
            return null;
        }
        prefs.edit().remove(PREF_CRASHED).apply();
        File log = crashLog();
        String report = "";
        try {
            if (log.isFile()) {
                report = CacheHelper.readInputStreamToString(new FileInputStream(log)).trim();
            }
            // Truncate instead of deleting: Go keeps appending to its open descriptor
            new FileOutputStream(log, false).close();
        }
        catch (IOException e) {
            LimeLog.warning("Unable to read the embedded Tailscale crash log: " + e.getMessage());
        }
        return report.length() > MAX_CRASH_REPORT_CHARS ? report.substring(0, MAX_CRASH_REPORT_CHARS) : report;
    }

    public boolean isAvailable() {
        return bridge != null;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    /** True if connections to this host go through the embedded node. */
    public boolean shouldRoute(String host) {
        return bridge != null && isEnabled() && isTailnetHost(host);
    }

    // Tailscale's CGNAT range (100.64.0.0/10), its IPv6 prefix (fd7a:115c:a1e0::/48) or a MagicDNS name
    static boolean isTailnetHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".ts.net") || h.endsWith(".ts.net.")) {
            return true;
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.indexOf(':') >= 0) {
            return h.startsWith("fd7a:115c:a1e0:");
        }
        String[] octets = h.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 100 && second >= 64 && second <= 127;
        }
        catch (NumberFormatException e) {
            return false;
        }
    }

    /** Connects in the background so the node is usually up before the first host poll. */
    public void startIfEnabledAsync() {
        if (bridge == null || !isEnabled()) {
            return;
        }
        // Called while the app is in the foreground, which Android requires to start the service
        TailnetBackgroundService.update(appContext, wantsBackground());
        startNodeAsync();
    }

    // Also used by TailnetBackgroundService when the system restarts it without the app
    void startNodeAsync() {
        runAsync("Tailnet start", () -> {
            try {
                ensureStarted();
            }
            catch (IOException e) {
                LimeLog.warning("Embedded Tailscale failed to start: " + e.getMessage());
            }
        });
    }

    private synchronized void ensureStarted() throws IOException {
        if (started) {
            return;
        }
        if (bridge == null) {
            throw new IOException(appContext.getString(R.string.tailnet_unavailable));
        }
        startBridge("");
    }

    private synchronized void startBridge(String authKey) throws IOException {
        // Written synchronously, since a crash would follow before an async write lands
        prefs.edit().putBoolean(PREF_START_PENDING, true).commit();
        try {
            bridge.start(stateDir(), nodeHostname(), prefs.getString(PREF_CONTROL_URL, "").trim(), authKey);
            started = true;
        }
        catch (Exception e) {
            prefs.edit().remove(PREF_START_PENDING).apply();
            throw new IOException(appContext.getString(R.string.tailnet_error, e.getMessage()), e);
        }
        MAIN_HANDLER.postDelayed(() -> prefs.edit().remove(PREF_START_PENDING).apply(), START_SURVIVAL_MS);
        startTaildropReceiver();
    }

    // It ends by itself once stop() or logout() clears started
    private void startTaildropReceiver() {
        if (taildropReceiver == null || !taildropReceiver.isAlive()) {
            taildropReceiver = new Thread(new TaildropReceiver(this, appContext), "Taildrop receiver");
            taildropReceiver.start();
        }
    }

    synchronized boolean isStarted() {
        return started;
    }

    /** Whether to keep the node running while the app is in the background, to receive files. */
    boolean wantsBackground() {
        return bridge != null && isEnabled() && prefs.getBoolean(PREF_BACKGROUND, false);
    }

    String awaitWaitingFiles(int timeoutSeconds) {
        return bridge.awaitWaitingFiles(timeoutSeconds);
    }

    void saveWaitingFile(String name, int fd) throws Exception {
        bridge.saveWaitingFile(name, fd);
    }

    /** The devices files can be sent to right now; asks the node, so not on the main thread. */
    public List<FileTarget> getFileTargets() {
        List<FileTarget> targets = new ArrayList<>();
        if (bridge == null) {
            return targets;
        }
        try {
            JSONArray array = new JSONArray(bridge.fileTargets());
            for (int i = 0; i < array.length(); i++) {
                JSONObject target = array.getJSONObject(i);
                targets.add(new FileTarget(target.getString("id"), target.getString("name")));
            }
        }
        catch (JSONException e) {
            LimeLog.warning("Unreadable Taildrop targets: " + e.getMessage());
        }
        return targets;
    }

    void sendFile(String targetId, String name, int fd, long size, TailnetBridge.SendProgress progress)
            throws Exception {
        bridge.sendFile(targetId, name, fd, size, progress);
    }

    void cancelSends() {
        if (bridge != null) {
            bridge.cancelSends();
        }
    }

    public synchronized void stop() {
        if (started) {
            bridge.stop();
            started = false;
        }
    }

    /** Removes this device from the tailnet and forgets its login. */
    public synchronized void logout() throws IOException {
        if (bridge == null) {
            return;
        }
        started = false;
        try {
            bridge.logout(stateDir());
        }
        catch (Exception e) {
            throw new IOException(appContext.getString(R.string.tailnet_error, e.getMessage()), e);
        }
    }

    /** Replaces the current node with one created from a Tailscale auth key. */
    public synchronized void loginWithAuthKey(String authKey) throws IOException {
        logout();
        startBridge(authKey);
    }

    public Status getStatus() {
        if (bridge == null) {
            return Status.failed(appContext.getString(R.string.tailnet_unavailable));
        }
        Status status = Status.parse(bridge.statusJson());
        // Errors from the node are raw; errors thrown by this class already carry the prefix
        return status.error == null ? status
                : Status.failed(appContext.getString(R.string.tailnet_error, status.error));
    }

    /** Starts the node if needed and waits until it is running or needs the user to log in. */
    public Status awaitReady(long timeoutMs) {
        try {
            ensureStarted();
        }
        catch (IOException e) {
            return Status.failed(e.getMessage());
        }
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        Status status = getStatus();
        while (!status.isRunning() && status.authUrl == null && status.error == null
                && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(STATUS_POLL_MS);
            status = getStatus();
        }
        return status;
    }

    /**
     * Relays every port moonlight-common-c uses for a stream and returns the address to give it,
     * or the host itself when it isn't routed through the tailnet.
     */
    public String prepareStream(String host, int httpPort, int httpsPort, String rtspSessionUrl) throws IOException {
        if (!shouldRoute(host)) {
            return host;
        }
        // Sunshine derives every port from its HTTP port, and GameStream's fixed ports use the
        // same offsets. common-c also probes the HTTPS and HTTP ports before RTSP.
        int rtspPort = urlPort(rtspSessionUrl, httpPort + 21);
        String local = loopbackFor(host);
        forward(host, local,
                joinPorts(httpsPort > 0 ? httpsPort : httpPort - 5, httpPort, rtspPort),
                joinPorts(httpPort + 9, httpPort + 10, httpPort + 11));
        return local;
    }

    /** Relays one more UDP port (the microphone uplink) and returns the address to send to. */
    public String prepareUdp(String host, int port) throws IOException {
        if (!shouldRoute(host)) {
            return host;
        }
        String local = loopbackFor(host);
        forward(host, local, "", String.valueOf(port));
        return local;
    }

    /** For HTTP sockets: the relay to connect to instead of a tailnet host, else the endpoint itself. */
    static SocketAddress route(SocketAddress endpoint) throws IOException {
        TailnetManager manager = instance;
        if (manager == null || !(endpoint instanceof InetSocketAddress)) {
            return endpoint;
        }
        InetSocketAddress target = (InetSocketAddress) endpoint;
        InetAddress address = target.getAddress();
        if (address == null) {
            return endpoint;
        }
        // MagicDNS names were already resolved to their relay address by relayAddressForName()
        String host = manager.hostForLoopback(address.getHostAddress());
        if (host == null) {
            host = address.getHostAddress();
            if (!manager.shouldRoute(host)) {
                return endpoint;
            }
        }
        String local = manager.loopbackFor(host);
        manager.forward(host, local, String.valueOf(target.getPort()), "");
        return new InetSocketAddress(InetAddress.getByName(local), target.getPort());
    }

    /** MagicDNS names only resolve inside the tailnet, so they map straight to their relay address. */
    static InetAddress relayAddressForName(String hostname) throws UnknownHostException {
        TailnetManager manager = instance;
        if (manager == null || !manager.shouldRoute(hostname)) {
            return null;
        }
        byte[] relay = InetAddress.getByName(manager.loopbackFor(hostname)).getAddress();
        return InetAddress.getByAddress(hostname, relay);
    }

    private void forward(String host, String local, String tcpPorts, String udpPorts) throws IOException {
        ensureStarted();
        try {
            bridge.forward(local, host, tcpPorts, udpPorts);
        }
        catch (Exception e) {
            throw new IOException(appContext.getString(R.string.tailnet_error, e.getMessage()), e);
        }
    }

    private synchronized String loopbackFor(String host) {
        String key = host.toLowerCase(Locale.ROOT);
        String local = loopbackByHost.get(key);
        if (local == null) {
            // Each host gets its own loopback address so identical ports never collide;
            // 127.0.0.1 itself is left alone.
            int index = loopbackByHost.size();
            local = "127.0." + (index / 250) + "." + (index % 250 + 2);
            loopbackByHost.put(key, local);
            hostByLoopback.put(local, host);
        }
        return local;
    }

    private synchronized String hostForLoopback(String address) {
        return hostByLoopback.get(address);
    }

    private void onPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (bridge == null) {
            return;
        }
        if (PREF_ENABLED.equals(key)) {
            if (isEnabled()) {
                startIfEnabledAsync();
            }
            else {
                TailnetBackgroundService.update(appContext, false);
                runAsync("Tailnet stop", this::stop);
            }
        }
        else if (PREF_BACKGROUND.equals(key)) {
            TailnetBackgroundService.update(appContext, wantsBackground());
        }
        else if (PREF_CONTROL_URL.equals(key)) {
            // A node belongs to one control server, so switching servers needs a new login
            runAsync("Tailnet logout", () -> {
                try {
                    logout();
                }
                catch (IOException e) {
                    LimeLog.warning("Embedded Tailscale logout failed: " + e.getMessage());
                }
            });
        }
    }

    private File crashLog() {
        return new File(appContext.getFilesDir(), CRASH_LOG);
    }

    private String stateDir() {
        return new File(appContext.getFilesDir(), STATE_DIR).getAbsolutePath();
    }

    private static String nodeHostname() {
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(Locale.ROOT);
        String name = ("moonlight-" + model).replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-").replaceAll("-$", "");
        return name.length() > 63 ? name.substring(0, 63) : name;
    }

    private static int urlPort(String url, int fallback) {
        int port = url == null ? -1 : Uri.parse(url).getPort();
        return port > 0 ? port : fallback;
    }

    private static String joinPorts(int... ports) {
        StringBuilder list = new StringBuilder();
        for (int port : ports) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append(port);
        }
        return list.toString();
    }

    private static void runAsync(String name, Runnable task) {
        new Thread(task, name).start();
    }

    /** A device on the tailnet that files can be sent to. */
    public static final class FileTarget {
        public final String id;
        public final String name;

        FileTarget(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Status {
        public final String state;
        public final String authUrl;
        public final String name;
        public final List<String> addresses;
        public final String error;
        /** Whether the tailnet lets this device receive files with Taildrop. */
        public final boolean fileSharing;

        private Status(String state, String authUrl, String name, List<String> addresses, String error,
                       boolean fileSharing) {
            this.state = state;
            this.authUrl = authUrl;
            this.name = name;
            this.addresses = addresses;
            this.error = error;
            this.fileSharing = fileSharing;
        }

        static Status failed(String error) {
            return new Status("Error", null, null, Collections.emptyList(), error, false);
        }

        static Status parse(String json) {
            try {
                JSONObject status = new JSONObject(json);
                List<String> addresses = new ArrayList<>();
                JSONArray ips = status.optJSONArray("ips");
                if (ips != null) {
                    for (int i = 0; i < ips.length(); i++) {
                        addresses.add(ips.getString(i));
                    }
                }
                return new Status(status.optString("state"), emptyToNull(status.optString("authURL")),
                        emptyToNull(status.optString("name")), addresses, emptyToNull(status.optString("error")),
                        status.optBoolean("fileSharing"));
            }
            catch (JSONException e) {
                return failed(e.getMessage());
            }
        }

        public boolean isRunning() {
            return "Running".equals(state);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }
    }
}
