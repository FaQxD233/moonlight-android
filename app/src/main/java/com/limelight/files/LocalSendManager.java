package com.limelight.files;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.format.Formatter;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

/**
 * Exchanges files with LocalSend (localsend.org) on the local network through the embedded Go
 * library: while enabled it can be found by and receive from the LocalSend devices around, asking
 * before taking files unless set to accept them, and it sends files to the devices it finds.
 */
public final class LocalSendManager {
    public static final String PREF_ENABLED = "checkbox_enable_localsend";
    public static final String PREF_ALIAS = "edit_localsend_alias";
    public static final String PREF_AUTO_ACCEPT = "checkbox_localsend_auto_accept";

    private static final String BRIDGE_CLASS = "com.limelight.files.GoLocalSendBridge";
    private static final String STATE_DIR = "localsend";
    // How long a sender may wait for the user to decide
    private static final long ACCEPT_TIMEOUT_MS = 60000;

    private static volatile LocalSendManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final LocalSendBridge bridge;
    // SharedPreferences only holds listeners weakly, so keep a reference
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = this::onPreferenceChanged;
    private WifiManager.MulticastLock multicastLock;
    private boolean started;

    private final LocalSendBridge.Receiver receiver = new LocalSendBridge.Receiver() {
        @Override
        public boolean accept(String offerJson) {
            return prefs.getBoolean(PREF_AUTO_ACCEPT, false) || askToAccept(offerJson);
        }

        @Override
        public void save(String token, String name, String fileType, long size) throws Exception {
            ReceivedFiles.Entry entry = ReceivedFiles.save(appContext, name, size, fd -> bridge.writeUpload(token, fd));
            ReceivedFiles.notifyReceived(appContext, entry);
        }
    };

    private LocalSendManager(Context appContext) {
        this.appContext = appContext;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        this.bridge = loadBridge();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    public static LocalSendManager get(Context context) {
        LocalSendManager manager = instance;
        if (manager == null) {
            synchronized (LocalSendManager.class) {
                manager = instance;
                if (manager == null) {
                    manager = new LocalSendManager(context.getApplicationContext());
                    instance = manager;
                }
            }
        }
        return manager;
    }

    // It ships in the same library as built-in Tailscale, with the same requirements
    private static LocalSendBridge loadBridge() {
        if (!BuildConfig.HAS_TAILNET || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        try {
            return (LocalSendBridge) Class.forName(BRIDGE_CLASS).getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException | LinkageError e) {
            LimeLog.warning("LocalSend unavailable: " + e);
            return null;
        }
    }

    public boolean isAvailable() {
        return bridge != null;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    public synchronized boolean isRunning() {
        return started;
    }

    /** The name other devices show for this one. */
    public String getAlias() {
        String alias = prefs.getString(PREF_ALIAS, "").trim();
        return alias.isEmpty() ? appContext.getString(R.string.localsend_default_alias, Build.MODEL) : alias;
    }

    /** Starts listening in the background; called while the app is in the foreground. */
    public void startIfEnabledAsync() {
        if (bridge == null || !isEnabled()) {
            return;
        }
        BackgroundReceiveService.update(appContext);
        new Thread(this::start, "LocalSend start").start();
    }

    private synchronized void start() {
        if (started || !isEnabled()) {
            return;
        }
        // Without this lock Android drops the multicast that discovery uses
        WifiManager wifi = (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("LocalSend");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        try {
            bridge.start(getAlias(), Build.MODEL, new File(appContext.getFilesDir(), STATE_DIR).getAbsolutePath(), receiver);
            started = true;
        }
        catch (Exception e) {
            LimeLog.warning("LocalSend failed to start: " + e.getMessage());
            releaseMulticastLock();
        }
    }

    public synchronized void stop() {
        if (started) {
            bridge.stop();
            started = false;
        }
        releaseMulticastLock();
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            multicastLock.release();
            multicastLock = null;
        }
    }

    private void onPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (bridge == null) {
            return;
        }
        if (PREF_ENABLED.equals(key) || PREF_ALIAS.equals(key)) {
            // A new name takes a restart too
            new Thread(() -> {
                stop();
                start();
            }, "LocalSend restart").start();
            BackgroundReceiveService.update(appContext);
        }
    }

    private boolean askToAccept(String offerJson) {
        try {
            JSONObject offer = new JSONObject(offerJson);
            JSONArray files = offer.optJSONArray("files");
            int count = files != null ? files.length() : 0;
            long total = 0;
            for (int i = 0; i < count; i++) {
                total += files.getJSONObject(i).optLong("size");
            }
            JSONObject sender = offer.optJSONObject("sender");
            String name = sender != null ? sender.optString("alias") : "";
            return LocalSendPrompt.ask(appContext,
                    appContext.getString(R.string.localsend_prompt_title, name),
                    appContext.getString(R.string.localsend_prompt_text, count, Formatter.formatShortFileSize(appContext, total)),
                    ACCEPT_TIMEOUT_MS);
        }
        catch (JSONException e) {
            LimeLog.warning("Unreadable LocalSend offer: " + e.getMessage());
            return false;
        }
    }

    /** The LocalSend devices heard from lately. */
    public List<Device> getDevices() {
        List<Device> devices = new ArrayList<>();
        if (bridge == null) {
            return devices;
        }
        try {
            JSONArray array = new JSONArray(bridge.devicesJson());
            for (int i = 0; i < array.length(); i++) {
                JSONObject device = array.getJSONObject(i);
                devices.add(new Device(device.getString("alias"), device.optString("deviceModel"),
                        device.getString("ip"), device.getInt("port"), device.optString("protocol", "https"),
                        device.optString("fingerprint")));
            }
        }
        catch (JSONException e) {
            LimeLog.warning("Unreadable LocalSend devices: " + e.getMessage());
        }
        return devices;
    }

    /** Asks the LocalSend devices on the network to make themselves known. */
    public void announce() {
        if (isRunning()) {
            bridge.announce();
        }
    }

    /** Looks for devices on the Wi-Fi network for when multicast doesn't get through; takes a few seconds. */
    public void scan() {
        String prefix = localPrefix();
        if (prefix == null || !isRunning()) {
            return;
        }
        try {
            bridge.scan(prefix);
        }
        catch (Exception e) {
            LimeLog.warning("LocalSend scan failed: " + e.getMessage());
        }
    }

    /** Finds the LocalSend device at a typed-in address; not on the main thread. */
    public void addDevice(String ip) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException(appContext.getString(R.string.localsend_not_running));
        }
        bridge.addDevice(ip, 0);
    }

    void send(Device device, String filesJson, LocalSendBridge.Files files) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException(appContext.getString(R.string.localsend_not_running));
        }
        bridge.send(device.ip, device.port, device.protocol, device.fingerprint, filesJson, files);
    }

    void cancelSend() {
        if (bridge != null) {
            bridge.cancelSend();
        }
    }

    // The IPv4 network of Wi-Fi (or Ethernet), such as 192.168.1.23/24
    private String localPrefix() {
        ConnectivityManager connectivity = appContext.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return null;
        }
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties link = connectivity.getLinkProperties(network);
            if (capabilities == null || link == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                continue;
            }
            for (LinkAddress address : link.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    return address.getAddress().getHostAddress() + "/" + address.getPrefixLength();
                }
            }
        }
        return null;
    }

    /** A LocalSend device on the network. */
    public static final class Device {
        public final String alias;
        public final String deviceModel;
        public final String ip;
        public final int port;
        public final String protocol;
        public final String fingerprint;

        Device(String alias, String deviceModel, String ip, int port, String protocol, String fingerprint) {
            this.alias = alias;
            this.deviceModel = deviceModel;
            this.ip = ip;
            this.port = port;
            this.protocol = protocol;
            this.fingerprint = fingerprint;
        }
    }
}
