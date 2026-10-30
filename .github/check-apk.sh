#!/usr/bin/env bash
# Checks an APK built with build-signed.sh: it must be signed with the key whose fingerprint is
# $CERT_SHA256 and carry the embedded Tailscale library, which Gradle leaves out silently when the
# AAR is missing. Prints the APK's badging line and leaves it in badging.txt.
set -euo pipefail
apk=$1
tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
"$tools/aapt2" dump badging "$apk" | awk 'NR == 1' | tee badging.txt
"$tools/apksigner" verify --print-certs "$apk" | tee certs.txt
grep -qiF "certificate SHA-256 digest: $CERT_SHA256" certs.txt
unzip -l "$apk" | grep -F 'lib/arm64-v8a/libgojni.so' > /dev/null
