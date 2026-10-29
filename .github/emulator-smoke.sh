#!/usr/bin/env bash
# Run by emulator-smoke.yml inside the emulator session: installs the debug build, turns on
# built-in Tailscale, launches the app and prints the Go crash log and logcat excerpts.
# Launches 1 and 2 start a node that has never logged in. Launch 3 starts one with a stored
# login (made up, so control won't accept it) and a file waiting in Taildrop's inbox, which
# the app should move to Downloads/月光.
set -u
pkg=com.limelight.debug
apk=$(ls app/build/outputs/apk/nonRoot/debug/*.apk)

# Copies a file from the runner into the app's private storage
push_private() {
    adb push "$1" /data/local/tmp/smoke-file > /dev/null
    adb shell chmod 644 /data/local/tmp/smoke-file
    adb shell "run-as $pkg mkdir -p $(dirname "$2")"
    adb shell "run-as $pkg cp /data/local/tmp/smoke-file $2"
}

launch() {
    adb logcat -c
    adb shell am start -W -n "$pkg/com.limelight.PcView"
    sleep 45
    echo "=== launch $1: app process after 45 s ==="
    adb shell pidof "$pkg" || echo "not running"
    echo "=== launch $1: Go crash log ==="
    adb shell "run-as $pkg cat files/tailnet-crash.txt" || true
    echo "=== launch $1: tailnet preferences ==="
    adb shell "run-as $pkg cat shared_prefs/${pkg}_preferences.xml" | grep tailnet || true
    adb logcat -d -v threadtime > "logcat-$1.txt"
    echo "=== launch $1: logcat excerpt ==="
    grep -E "GoLog|AndroidRuntime|DEBUG|libc|SIGSYS|seccomp|tailnet|Tailscale|aildrop|tsnet|FATAL|backtrace" "logcat-$1.txt" | tail -n 300
    adb shell am force-stop "$pkg"
}

adb install -r "$apk"
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
    '    <boolean name="checkbox_enable_tailnet" value="true" />' '</map>' > prefs.xml
push_private prefs.xml "shared_prefs/${pkg}_preferences.xml"

launch 1
launch 2

# tailscaled.state holds base64 values; the inbox of user 1 (ci@example.com) is files/<login>-uid-<id>
profile='{"ci":{"ID":"ci","Name":"ci@example.com","Key":"profile-ci","ControlURL":"https://controlplane.tailscale.com","UserProfile":{"ID":1,"LoginName":"ci@example.com","DisplayName":"CI"}}}'
printf '{"_current-profile":"%s","_profiles":"%s"}\n' "$(printf profile-ci | base64 -w0)" \
    "$(printf '%s' "$profile" | base64 -w0)" > tailscaled.state
push_private tailscaled.state files/tailnet/tailscaled.state
inbox=files/tailnet/files/ci-example.com-uid-1
echo "sent by the emulator smoke test" > ci-smoke.txt
push_private ci-smoke.txt "$inbox/ci-smoke.txt"

launch 3
echo "=== launch 3: Taildrop inbox (ci-smoke.txt should be gone) ==="
adb shell "run-as $pkg ls -la $inbox" || true
echo "=== launch 3: Downloads/月光 (ci-smoke.txt should be here) ==="
adb shell content query --uri content://media/external/downloads --projection _id:_display_name:relative_path:_size || true

# Share the received file with "Send to computer", as another app would
id=$(adb shell content query --uri content://media/external/downloads --projection _id \
    --where "_display_name=\'ci-smoke.txt\'" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -n 1)
adb logcat -c
adb shell am start -W -a android.intent.action.SEND -t text/plain --grant-read-uri-permission \
    --eu android.intent.extra.STREAM "content://media/external/downloads/$id" -n "$pkg/com.limelight.files.SendFilesActivity"
sleep 20
echo "=== share screen: app process after 20 s ==="
adb shell pidof "$pkg" || echo "not running"
adb logcat -d -v threadtime > logcat-share.txt
grep -E "AndroidRuntime|FATAL|SendFiles|aildrop" logcat-share.txt | tail -n 50
adb shell am force-stop "$pkg"
