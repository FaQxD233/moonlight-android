#!/usr/bin/env bash
# Builds app/libs-tailnet/tailnet.aar, the embedded Tailscale (tsnet) bindings behind
# the "Built-in Tailscale" setting. Needs Go (version in go.mod), the Android SDK and
# an NDK. Without the AAR, Gradle leaves the feature out of the APK.
set -euo pipefail

cd "$(dirname "$0")"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n 1)"
    export ANDROID_NDK_HOME
fi

go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
export PATH="$(go env GOPATH)/bin:$PATH"
go mod tidy
gomobile init

mkdir -p ../app/libs-tailnet
# arm64 only to keep the APK small (other ABIs run without the feature). The app's
# minSdk is 21, and 16 KB page alignment keeps the library loadable on newer devices.
gomobile bind -target android/arm64 -androidapi 21 -javapkg com.limelight.gobridge \
    -ldflags "-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
    -o ../app/libs-tailnet/tailnet.aar .
