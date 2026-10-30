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

# go.mod and go.sum pin every dependency, gomobile included, so builds don't change by
# themselves. To update, run for example
#   go get tailscale.com@v1.106.0 golang.org/x/mobile@latest && go mod tidy
# here and commit both files.
mobile="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
go install "golang.org/x/mobile/cmd/gomobile@$mobile" "golang.org/x/mobile/cmd/gobind@$mobile"
export PATH="$(go env GOPATH)/bin:$PATH"
# The bridge's own tests, on this machine; they also check Taildrop's file store is still linked
go test ./...
gomobile init

mkdir -p ../app/libs-tailnet
# arm64 only by default to keep the APK small (other ABIs run without the feature);
# TAILNET_TARGET adds others, such as android/amd64 for an emulator. The app's minSdk
# is 21, and 16 KB page alignment keeps the library loadable on newer devices.
gomobile bind -target "${TAILNET_TARGET:-android/arm64}" -androidapi 21 -javapkg com.limelight.gobridge \
    -ldflags "-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
    -o ../app/libs-tailnet/tailnet.aar .
