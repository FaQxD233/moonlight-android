package com.limelight;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.preference.PreferenceManager;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import com.limelight.utils.FileUriUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class KeyboardAccessibilityService extends AccessibilityService {

    //不屏蔽的按键列表
    private final static List BLACKLIST_KEYS = Arrays.asList(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER
    );

    private static final String KEY_MAPPING_FILE = "axi_switch_keyboard.json";

    // Scancode -> keycode remapping, reparsed only when the mapping file changes
    private SparseIntArray scanCodeMapping = new SparseIntArray();
    private long mappingFileModified = -1;
    private long mappingFileLength = -1;

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        //如果是手柄类型则忽略
        InputDevice eventDevice = event.getDevice();
        int sources = eventDevice != null ? eventDevice.getSources() : event.getSource();
        if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)) {
            return super.onKeyEvent(event);
        }
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (action == KeyEvent.ACTION_DOWN && PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("checkbox_enable_accessibility_show_log", false)) {
            Toast.makeText(getApplicationContext(),"scancode:"+event.getScanCode()+",code:"+event.getKeyCode(),Toast.LENGTH_SHORT).show();
        }
        //主要解决系统自带快捷键在pc端无法使用问题 home键 scancode=172 code- 3
        if (Game.instance != null && Game.instance.connected && !BLACKLIST_KEYS.contains(keyCode)) {
            KeyEvent mappedEvent = event;
            //fix 小米平板esc键按钮映射错误 KEYCODE_BACK=4
            if (event.getScanCode() == 1) {
                mappedEvent = remapKeyCode(event, KeyEvent.KEYCODE_ESCAPE);
            }
            else {
                SparseIntArray mapping = getScanCodeMapping();
                int index = mapping.indexOfKey(event.getScanCode());
                if (index >= 0) {
                    mappedEvent = remapKeyCode(event, mapping.valueAt(index));
                }
            }

            if (action == KeyEvent.ACTION_DOWN) {
                Game.instance.handleKeyDown(mappedEvent);
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                Game.instance.handleKeyUp(mappedEvent);
                return true;
            }
        }

        return super.onKeyEvent(event);
    }

    private SparseIntArray getScanCodeMapping() {
        File mappingFile = new File(getFilesDir(), KEY_MAPPING_FILE);
        long modified = mappingFile.lastModified();
        long length = mappingFile.length();
        if (modified != mappingFileModified || length != mappingFileLength) {
            mappingFileModified = modified;
            mappingFileLength = length;
            scanCodeMapping = parseScanCodeMapping(mappingFile);
        }
        return scanCodeMapping;
    }

    private SparseIntArray parseScanCodeMapping(File mappingFile) {
        SparseIntArray mapping = new SparseIntArray();
        if (!mappingFile.isFile()) {
            return mapping;
        }
        String authority = getApplicationContext().getPackageName() + ".fileprovider";
        String result = FileUriUtils.openUriForRead(this, FileProvider.getUriForFile(this, authority, mappingFile));
        if (TextUtils.isEmpty(result)) {
            return mapping;
        }
        try {
            JSONArray array = new JSONObject(result).getJSONArray("data");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                int scanCode = item.getInt("scancode");
                // Keep the first entry for a scancode, like the original linear search did
                if (mapping.indexOfKey(scanCode) < 0) {
                    mapping.put(scanCode, item.getInt("code"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return mapping;
    }

    private static KeyEvent remapKeyCode(KeyEvent event, int keyCode) {
        return new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(), keyCode,
                event.getRepeatCount(), event.getMetaState(), event.getDeviceId(),
                event.getScanCode(), event.getFlags(), event.getSource());
    }

    @Override
    public void onServiceConnected() {
        LimeLog.info("Keyboard service is connected");
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[] { getApplicationContext().getPackageName() };
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
//        LimeLog.info("onAccessibilityEvent:"+accessibilityEvent.toString());
    }
    @Override
    public void onInterrupt() {

    }

}
