#!/usr/bin/env bash
# Run by emulator-smoke.yml inside the emulator session: installs the debug build, turns on
# built-in Tailscale, launches the app twice and prints the Go crash log and logcat excerpts.
set -u
pkg=com.limelight.debug
apk=$(ls app/build/outputs/apk/nonRoot/debug/*.apk)

adb install -r "$apk"
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
    '    <boolean name="checkbox_enable_tailnet" value="true" />' '</map>' > prefs.xml
adb push prefs.xml /data/local/tmp/prefs.xml
adb shell chmod 644 /data/local/tmp/prefs.xml
adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/prefs.xml shared_prefs/${pkg}_preferences.xml'"

for run in 1 2; do
    adb logcat -c
    adb shell am start -W -n "$pkg/com.limelight.PcView"
    sleep 45
    echo "=== launch $run: app process after 45 s ==="
    adb shell pidof "$pkg" || echo "not running"
    echo "=== launch $run: Go crash log ==="
    adb shell "run-as $pkg cat files/tailnet-crash.txt" || true
    echo "=== launch $run: tailnet preferences ==="
    adb shell "run-as $pkg cat shared_prefs/${pkg}_preferences.xml" | grep tailnet || true
    adb logcat -d -v threadtime > "logcat-$run.txt"
    echo "=== launch $run: logcat excerpt ==="
    grep -E "GoLog|AndroidRuntime|DEBUG|libc|SIGSYS|seccomp|tailnet|Tailscale|tsnet|FATAL|backtrace" "logcat-$run.txt" | tail -n 300
    adb shell am force-stop "$pkg"
done
