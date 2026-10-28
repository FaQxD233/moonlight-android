package com.limelight.tailnet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
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

    private static final String BRIDGE_CLASS = "com.limelight.tailnet.GoTailnetBridge";
    private static final String STATE_DIR = "tailnet";
    private static final long STATUS_POLL_MS = 500;

    private static volatile TailnetManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final TailnetBridge bridge;
    // SharedPreferences only holds listeners weakly, so keep a reference
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = this::onPreferenceChanged;
    private final Map<String, String> loopbackByHost = new HashMap<>();
    private final Map<String, String> hostByLoopback = new HashMap<>();
    private boolean started;

    private TailnetManager(Context appContext) {
        this.appContext = appContext;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        this.bridge = loadBridge();
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
        try {
            bridge.start(stateDir(), nodeHostname(), prefs.getString(PREF_CONTROL_URL, "").trim(), authKey);
            started = true;
        }
        catch (Exception e) {
            throw new IOException(appContext.getString(R.string.tailnet_error, e.getMessage()), e);
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
                runAsync("Tailnet stop", this::stop);
            }
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

    public static final class Status {
        public final String state;
        public final String authUrl;
        public final String name;
        public final List<String> addresses;
        public final String error;

        private Status(String state, String authUrl, String name, List<String> addresses, String error) {
            this.state = state;
            this.authUrl = authUrl;
            this.name = name;
            this.addresses = addresses;
            this.error = error;
        }

        static Status failed(String error) {
            return new Status("Error", null, null, Collections.emptyList(), error);
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
                        emptyToNull(status.optString("name")), addresses, emptyToNull(status.optString("error")));
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
