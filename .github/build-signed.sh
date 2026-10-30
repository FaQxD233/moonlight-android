#!/usr/bin/env bash
# Runs Gradle with the arguments given, signing the APKs with this fork's release key, from the
# RELEASE_KEYSTORE_BASE64 and RELEASE_KEYSTORE_PASSWORD secrets passed in the environment. Without
# them it signs with a throwaway key (enough for checks and for forks), unless REQUIRE_RELEASE_KEY
# is set. Leaves the certificate's SHA-256 fingerprint in $GITHUB_ENV as CERT_SHA256 for check-apk.sh.
set -euo pipefail
alias=moonlight
keystore="$RUNNER_TEMP/signing.keystore"
trap 'rm -f "$keystore"' EXIT

if [ -n "${RELEASE_KEYSTORE_BASE64:-}" ] && [ -n "${RELEASE_KEYSTORE_PASSWORD:-}" ]; then
    printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 -d > "$keystore"
    password=$RELEASE_KEYSTORE_PASSWORD
elif [ -z "${REQUIRE_RELEASE_KEY:-}" ]; then
    echo "::warning::The release key secrets are not set, so these APKs are signed with a throwaway key"
    password=ci-throwaway
    keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias "$alias" -storepass "$password" \
        -keyalg RSA -keysize 2048 -validity 1 -dname "CN=CI throwaway"
else
    echo "::error::Set the RELEASE_KEYSTORE_BASE64 and RELEASE_KEYSTORE_PASSWORD secrets under Settings > Secrets and variables > Actions"
    exit 1
fi

./gradlew --no-daemon -PincludeKishiHaptics=false -PincludeStereo3dAi=false \
    -Pandroid.injected.signing.store.file="$keystore" \
    -Pandroid.injected.signing.store.password="$password" \
    -Pandroid.injected.signing.key.alias="$alias" \
    -Pandroid.injected.signing.key.password="$password" \
    "$@"

# The fingerprint (public) the APKs must carry
cert=$(keytool -J-Duser.language=en -list -v -keystore "$keystore" -storepass "$password" -alias "$alias" \
    | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
test -n "$cert"
echo "CERT_SHA256=$cert" >> "$GITHUB_ENV"
