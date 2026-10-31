package com.limelight.files;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.ext.SdkExtensions;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.limelight.BaseActivity;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.tailnet.TailnetAccountDialog;
import com.limelight.tailnet.TailnetManager;
import com.limelight.ui.AppDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks the device to send files to, with Taildrop or LocalSend: files shared from another app
 * (through the exported ShareToComputer alias), or picked here from the gallery or the files.
 * Opened for a host from the home screen, it sends to that host once it finds the device the host
 * runs on.
 */
public class SendFilesActivity extends BaseActivity {
    static final String EXTRA_SOURCE = "source";
    /** What to pick files from first: ask, the gallery or the files */
    static final int SOURCE_ASK = 0;
    static final int SOURCE_MEDIA = 1;
    static final int SOURCE_FILES = 2;
    // The host to send to: its name, all its addresses and the one in use
    private static final String EXTRA_HOST_NAME = "host_name";
    private static final String EXTRA_HOST_ADDRESSES = "host_addresses";
    private static final String EXTRA_HOST_ACTIVE = "host_active";

    private static final int REQUEST_PICK = 7102;
    private static final String STATE_FILES = "files";
    private static final String STATE_PICKING = "picking";
    // Devices turn up within the first seconds, so look often at first
    private static final long FAST_INTERVAL_MS = 2000;
    private static final int FAST_READS = 15;
    private static final long SLOW_INTERVAL_MS = 5000;
    private static final long ANNOUNCE_INTERVAL_MS = 10000;
    // Scanning tries every address of the Wi-Fi network: only when multicast found nobody
    private static final long SCAN_DELAY_MS = 4000;
    private static final long SCAN_INTERVAL_MS = 30000;
    // How long to look for the host before leaving the choice to the user
    private static final long HOST_SEARCH_MS = 15000;

    private final ArrayList<Uri> files = new ArrayList<>();
    private boolean shared;
    // A picker is open; its result comes back even when this screen was recreated meanwhile
    private boolean picking;
    private String filesSummary;
    private LinearLayout content;
    private Found found;
    private String shownState;
    private boolean askedForNotifications;

    private String host;
    private String[] hostAddresses;
    private String hostActive;
    private volatile long hostSearchStart;

    // Read and written by the reads, off the main thread
    private volatile long visitStart;
    private volatile long lastAnnounce;
    private volatile long lastScan;
    private volatile boolean scanNow;
    // LocalSend devices asked during this visit whether they're still there, and those that didn't answer
    private final Set<String> checked = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> unreachable = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final FileTransferStatus.Poller<Found> poller = new FileTransferStatus.Poller<>(
            new FileTransferStatus.Poller.Task<Found>() {
                @Override
                public Found read() {
                    return find();
                }

                @Override
                public boolean show(Found result) {
                    found = result;
                    if (result.host != null) {
                        sendToHost(result.host);
                        return false;
                    }
                    render();
                    return true;
                }
            }, FAST_INTERVAL_MS, FAST_READS, SLOW_INTERVAL_MS);

    /** What one look around found. */
    private static final class Found {
        FileTransferStatus status;
        List<TailnetManager.FileTarget> tailscale = Collections.emptyList();
        boolean lanRunning;
        List<LocalSendManager.Device> lan = Collections.emptyList();
        /** The host's device: a FileTarget or a LocalSend Device */
        Object host;
    }

    /** Picks files from source (SOURCE_*) to send. */
    static Intent intent(Context context, int source) {
        return new Intent(context, SendFilesActivity.class).putExtra(EXTRA_SOURCE, source);
    }

    /** Picks files, then sends them to the device the host runs on. */
    public static void startForHost(Context context, ComputerDetails host) {
        Set<String> addresses = new LinkedHashSet<>();
        for (ComputerDetails.AddressTuple address : new ComputerDetails.AddressTuple[]{host.activeAddress,
                host.localAddress, host.remoteAddress, host.manualAddress, host.ipv6Address}) {
            if (address != null && address.address != null) {
                addresses.add(address.address);
            }
        }
        context.startActivity(new Intent(context, SendFilesActivity.class)
                .putExtra(EXTRA_HOST_NAME, host.name)
                .putExtra(EXTRA_HOST_ADDRESSES, addresses.toArray(new String[0]))
                .putExtra(EXTRA_HOST_ACTIVE, host.activeAddress != null ? host.activeAddress.address : null));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = FilePage.setUp(this, R.string.send_files_title);
        FilePage.addAction(this, R.drawable.ic_home_refresh, R.string.send_files_refresh, v -> lookAgain());
        FilePage.addAction(this, R.drawable.ic_help, R.string.file_help_title, v -> FilePage.showHelp(this));

        Intent intent = getIntent();
        String action = intent.getAction();
        shared = Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
        // Other apps reach this screen through the exported alias. Only this app may name a host,
        // so that no other app can have files sent somewhere without the user choosing it.
        boolean fromThisApp = intent.getComponent() != null
                && SendFilesActivity.class.getName().equals(intent.getComponent().getClassName());
        if (fromThisApp && intent.hasExtra(EXTRA_HOST_ADDRESSES)) {
            host = intent.getStringExtra(EXTRA_HOST_NAME);
            hostAddresses = intent.getStringArrayExtra(EXTRA_HOST_ADDRESSES);
            hostActive = intent.getStringExtra(EXTRA_HOST_ACTIVE);
            if (host == null || hostAddresses == null) {
                host = null;
            }
        }

        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList(STATE_FILES);
            if (saved != null) {
                files.addAll(saved);
            }
            picking = savedInstanceState.getBoolean(STATE_PICKING);
        }
        if (!files.isEmpty()) {
            summarizeFiles();
        }
        else if (shared) {
            files.addAll(sharedFiles(intent));
            if (files.isEmpty()) {
                Toast.makeText(this, R.string.send_files_nothing, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            summarizeFiles();
        }
        else if (!fromThisApp) {
            finish();
        }
        else if (!picking) {
            // Nothing picked yet, and no picker open whose result is still to come
            int source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_ASK);
            if (source == SOURCE_ASK) {
                chooseSource();
            }
            else {
                pick(source);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(STATE_FILES, files);
        outState.putBoolean(STATE_PICKING, picking);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (files.isEmpty()) {
            // Still picking
            return;
        }
        if (!askedForNotifications) {
            askedForNotifications = true;
            FileTransferStatus.requestNotificationPermission(this);
        }
        TailnetManager.get(this).startIfEnabledAsync();
        LocalSendManager.get(this).startIfEnabledAsync();
        if (hostSearchStart == 0) {
            hostSearchStart = SystemClock.elapsedRealtime();
        }
        visitStart = SystemClock.elapsedRealtime();
        lastAnnounce = 0;
        checked.clear();
        shownState = null;
        render();
        poller.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.stop();
    }

    // Asks again right away, for computers that just opened LocalSend or came online
    private void lookAgain() {
        lastAnnounce = 0;
        scanNow = true;
        checked.clear();
        poller.start();
    }

    private void chooseSource() {
        AppDialog.showOptions(this, getString(R.string.send_files_source_title),
                new CharSequence[]{getString(R.string.file_send_media), getString(R.string.file_send_files)},
                which -> {
                    if (which >= 0) {
                        pick(which == 0 ? SOURCE_MEDIA : SOURCE_FILES);
                    }
                    else if (files.isEmpty()) {
                        finish();
                    }
                });
    }

    private void pick(int source) {
        try {
            startActivityForResult(source == SOURCE_MEDIA ? mediaPicker() : filePicker(), REQUEST_PICK);
            picking = true;
        }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.send_files_pick_failed, Toast.LENGTH_SHORT).show();
            if (files.isEmpty()) {
                finish();
            }
        }
    }

    // The system photo picker where there is one (Android 13, or 11 and 12 with its update);
    // elsewhere the gallery apps answer GET_CONTENT for images and videos
    private static Intent mediaPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES)
                    .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        }
        return new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    private static Intent filePicker() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK) {
            return;
        }
        picking = false;
        List<Uri> picked = resultCode == RESULT_OK && data != null
                ? uris(data.getClipData(), data.getData()) : Collections.emptyList();
        if (!picked.isEmpty()) {
            // Picking again replaces the files
            files.clear();
            files.addAll(picked);
            filesSummary = null;
            summarizeFiles();
        }
        else if (files.isEmpty()) {
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

    // The total size and the first names, read off the main thread
    private void summarizeFiles() {
        List<Uri> picked = new ArrayList<>(files);
        new Thread(() -> {
            ContentResolver resolver = getContentResolver();
            List<String> names = new ArrayList<>();
            long total = 0;
            boolean sized = true;
            for (Uri uri : picked) {
                if (names.size() < 3) {
                    names.add(FileSendService.displayName(resolver, uri));
                }
                try {
                    total += FileSendService.size(resolver, uri);
                }
                catch (IOException e) {
                    sized = false;
                }
            }
            String list = TextUtils.join(getString(R.string.send_files_name_separator), names);
            if (picked.size() > names.size()) {
                list = getString(R.string.send_files_more, list);
            }
            String summary = sized ? Formatter.formatShortFileSize(this, total) + " · " + list : list;
            runOnUiThread(() -> {
                if (picked.equals(files)) {
                    filesSummary = summary;
                    render();
                }
            });
        }, "Send files summary").start();
    }

    // Off the main thread
    private Found find() {
        Found result = new Found();
        TailnetManager tailnet = TailnetManager.get(this);
        result.status = FileTransferStatus.read(this, tailnet);
        if (result.status.state == FileTransferStatus.State.READY) {
            result.tailscale = tailnet.getFileTargets();
        }
        LocalSendManager localSend = LocalSendManager.get(this);
        result.lanRunning = localSend.isRunning();
        if (result.lanRunning) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastAnnounce >= ANNOUNCE_INTERVAL_MS) {
                lastAnnounce = now;
                // The devices around answer now rather than when they next announce themselves
                localSend.announce();
            }
            result.lan = localSend.getDevices();
            if (result.lan.isEmpty() && (scanNow
                    || now - visitStart >= SCAN_DELAY_MS && now - lastScan >= SCAN_INTERVAL_MS)) {
                scanNow = false;
                lastScan = now;
                // Multicast often doesn't get through (routers, phones saving power): try each address
                localSend.scan();
                result.lan = localSend.getDevices();
            }
            checkStillThere(localSend, result.lan);
        }
        // Once the search is over the user chooses, so a late find doesn't send under their finger
        if (host != null && isLookingForHost()) {
            result.host = findHost(tailnet, result);
        }
        return result;
    }

    // Each listed device is asked once a visit; the answer shows with the next look
    private void checkStillThere(LocalSendManager localSend, List<LocalSendManager.Device> devices) {
        for (LocalSendManager.Device device : devices) {
            if (checked.add(device.fingerprint)) {
                new Thread(() -> {
                    if (localSend.isReachable(device)) {
                        unreachable.remove(device.fingerprint);
                    }
                    else {
                        unreachable.add(device.fingerprint);
                    }
                }, "LocalSend check").start();
            }
        }
    }

    // The device the host runs on, found by its addresses so that files never go to a wrong one
    private Object findHost(TailnetManager tailnet, Found result) {
        TailnetManager.FileTarget overTailscale = null;
        if (!result.tailscale.isEmpty()) {
            // A Taildrop target only has a name: find the names of the host's Tailscale addresses
            Set<String> names = new HashSet<>();
            List<TailnetManager.Peer> peers = null;
            for (String address : hostAddresses) {
                if (!TailnetManager.isTailnetHost(address)) {
                    continue;
                }
                if (address.toLowerCase(Locale.ROOT).contains(".ts.net")) {
                    names.add(firstLabel(address));
                    continue;
                }
                if (peers == null) {
                    peers = tailnet.getOnlinePeers();
                }
                for (TailnetManager.Peer peer : peers) {
                    if (address.equals(peer.ipv4)) {
                        names.add(firstLabel(peer.name));
                    }
                }
            }
            for (TailnetManager.FileTarget target : result.tailscale) {
                if (names.contains(firstLabel(target.name))) {
                    overTailscale = target;
                    break;
                }
            }
        }
        LocalSendManager.Device overLan = null;
        List<String> addresses = Arrays.asList(hostAddresses);
        for (LocalSendManager.Device device : result.lan) {
            if (addresses.contains(device.ip) && !unreachable.contains(device.fingerprint)) {
                overLan = device;
                break;
            }
        }
        // The way the host is reached right now
        if (hostActive != null && TailnetManager.isTailnetHost(hostActive)) {
            return overTailscale != null ? overTailscale : overLan;
        }
        return overLan != null ? overLan : overTailscale;
    }

    private static String firstLabel(String name) {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        int dot = lower.indexOf('.');
        return dot > 0 ? lower.substring(0, dot) : lower;
    }

    private boolean isLookingForHost() {
        return SystemClock.elapsedRealtime() - hostSearchStart < HOST_SEARCH_MS;
    }

    private void render() {
        if (files.isEmpty()) {
            return;
        }
        // Only redraw for a change, so a row being pressed isn't replaced under the finger
        String state = describeState();
        if (state.equals(shownState)) {
            return;
        }
        shownState = state;

        Object focus = FilePage.focusedKey(content);
        content.removeAllViews();
        FilePage.Row selected = FilePage.row(content).icon(R.drawable.ic_ft_file)
                .title(getString(R.string.send_files_selected, files.size()))
                .detail(filesSummary).key("files");
        if (!shared) {
            selected.badge(getString(R.string.send_files_reselect), R.color.home_accent_bright)
                    .onClick(v -> chooseSource());
        }
        if (host != null) {
            FilePage.row(content).icon(R.drawable.ic_axi_computer)
                    .title(getString(R.string.send_files_to_host, host))
                    .detail(getString(isLookingForHost() ? R.string.send_files_host_searching
                            : R.string.send_files_host_not_found));
        }
        renderTailscale();
        renderLan();
        FilePage.restoreFocus(content, focus);
    }

    // Everything render() shows, to tell whether anything changed
    private String describeState() {
        StringBuilder state = new StringBuilder();
        state.append(files.size()).append('|').append(filesSummary).append('|');
        if (host != null) {
            state.append(isLookingForHost()).append('|');
        }
        state.append(TailnetManager.get(this).isEnabled()).append(LocalSendManager.get(this).isEnabled()).append('|');
        if (found != null) {
            state.append(found.status.state).append(found.status.problem).append(found.lanRunning).append('|');
            for (TailnetManager.FileTarget target : found.tailscale) {
                state.append(target.id).append(target.name).append(',');
            }
            state.append('|');
            for (LocalSendManager.Device device : found.lan) {
                state.append(device.fingerprint).append(device.alias).append(device.ip)
                        .append(unreachable.contains(device.fingerprint)).append(',');
            }
        }
        return state.toString();
    }

    private void renderTailscale() {
        FilePage.section(content, getString(R.string.send_files_tailscale), null);
        FileTransferStatus status = found != null ? found.status : null;
        if (status == null) {
            FilePage.row(content).icon(R.drawable.ic_ft_globe).title(getString(R.string.received_files_status_checking));
            return;
        }
        if (status.state != FileTransferStatus.State.READY) {
            FilePage.Row problem = FilePage.row(content).icon(R.drawable.ic_ft_globe)
                    .title(getString(status.stateLabel())).detail(status.problem).key("tailscale");
            if (status.state == FileTransferStatus.State.OFF) {
                problem.badge(getString(R.string.file_action_enable), R.color.home_accent_bright)
                        .onClick(v -> turnOn(TailnetManager.PREF_ENABLED));
            }
            else if (status.state == FileTransferStatus.State.LOGIN || status.state == FileTransferStatus.State.ERROR) {
                problem.badge(getString(R.string.file_action_login), R.color.home_warning)
                        .onClick(v -> TailnetAccountDialog.show(this));
            }
            return;
        }
        if (found.tailscale.isEmpty()) {
            FilePage.row(content).icon(R.drawable.ic_ft_globe).title(getString(R.string.send_files_no_targets_title))
                    .detail(getString(R.string.send_files_no_targets_detail));
            return;
        }
        for (TailnetManager.FileTarget target : found.tailscale) {
            FilePage.row(content).icon(R.drawable.ic_axi_computer).title(target.name)
                    .detail(getString(R.string.send_files_tailscale)).chevron().key("ts:" + target.id)
                    .onClick(v -> send(target));
        }
    }

    private void renderLan() {
        LocalSendManager localSend = LocalSendManager.get(this);
        boolean searching = localSend.isAvailable() && localSend.isEnabled()
                && (found == null || !found.lanRunning || found.lan.isEmpty());
        FilePage.section(content, getString(R.string.file_section_lan),
                searching ? getString(R.string.send_files_searching) : null);
        if (!localSend.isAvailable()) {
            FilePage.row(content).icon(R.drawable.ic_ft_lan).title(getString(R.string.file_tailnet_state_unavailable))
                    .detail(getString(R.string.tailnet_unavailable));
            return;
        }
        if (!localSend.isEnabled()) {
            FilePage.row(content).icon(R.drawable.ic_ft_lan).title(getString(R.string.file_tailnet_state_off))
                    .detail(getString(R.string.localsend_enable_summary)).key("localsend")
                    .badge(getString(R.string.file_action_enable), R.color.home_accent_bright)
                    .onClick(v -> turnOn(LocalSendManager.PREF_ENABLED));
            return;
        }
        if (found == null || !found.lanRunning) {
            FilePage.row(content).icon(R.drawable.ic_ft_lan).title(getString(R.string.localsend_status_starting));
        }
        else {
            List<LocalSendManager.Device> devices = new ArrayList<>(found.lan);
            // The ones that answer first
            Collections.sort(devices, (a, b) -> Boolean.compare(unreachable.contains(a.fingerprint),
                    unreachable.contains(b.fingerprint)));
            for (LocalSendManager.Device device : devices) {
                FilePage.Row row = FilePage.row(content)
                        .icon("mobile".equals(device.deviceType) ? R.drawable.ic_home_device : R.drawable.ic_axi_computer)
                        .title(device.alias)
                        .detail(device.deviceModel.isEmpty() ? device.ip : device.deviceModel + " · " + device.ip)
                        .chevron().key("ls:" + device.fingerprint)
                        .onClick(v -> send(device));
                if (unreachable.contains(device.fingerprint)) {
                    row.badge(getString(R.string.send_files_unreachable), R.color.home_secondary_text).dimmed();
                }
            }
            if (devices.isEmpty()) {
                FilePage.row(content).icon(R.drawable.ic_ft_lan).title(getString(R.string.send_files_no_lan_title))
                        .detail(getString(R.string.send_files_no_lan_detail));
            }
        }
        FilePage.row(content).icon(R.drawable.ic_home_add).title(getString(R.string.localsend_add_by_ip))
                .detail(getString(R.string.localsend_add_by_ip_detail)).key("add")
                .onClick(v -> askForAddress());
    }

    private void turnOn(String setting) {
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(setting, true).apply();
        lookAgain();
    }

    private void send(TailnetManager.FileTarget target) {
        FileSendService.send(this, target, files);
        sending(target.name);
    }

    private void send(LocalSendManager.Device device) {
        FileSendService.send(this, device, files);
        sending(device.alias);
    }

    private void sendToHost(Object target) {
        if (target instanceof TailnetManager.FileTarget) {
            send((TailnetManager.FileTarget) target);
        }
        else {
            send((LocalSendManager.Device) target);
        }
    }

    private void sending(String name) {
        Toast.makeText(this, getString(R.string.send_files_started_to, name), Toast.LENGTH_SHORT).show();
        finish();
    }

    // For computers LocalSend discovery doesn't find, such as on networks that block multicast
    private void askForAddress() {
        AppDialog.showInput(this, getString(R.string.localsend_add_by_ip), getString(R.string.localsend_add_by_ip_detail),
                "192.168.1.10", null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
                    String address = text.trim();
                    // A host name or an IPv4/IPv6 address, nothing more
                    if (!address.matches("[A-Za-z0-9.:\\-]{1,253}")) {
                        return getString(R.string.localsend_add_failed, address);
                    }
                    new Thread(() -> {
                        try {
                            LocalSendManager.get(this).addDevice(address);
                            runOnUiThread(poller::start);
                        }
                        catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    getString(R.string.localsend_add_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                        }
                    }, "LocalSend add device").start();
                    return null;
                });
    }
}
