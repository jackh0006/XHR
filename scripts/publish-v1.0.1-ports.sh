#!/usr/bin/env bash
# Upload the additional Linux and Android builds to the existing v1.0.1 release.
# The script is safe to rerun: already-present assets are skipped, never replaced.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${XHR_RELEASE_REPO:-jackh0006/XHR}"
TAG="${XHR_RELEASE_TAG:-v1.0.1}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

require_file() {
    [[ -f "$1" ]] || { echo "missing build artifact: $1" >&2; exit 1; }
}

require_file "$ROOT/dist/xhr-client_1.0.1_amd64.deb"
require_file "$ROOT/dist/xhr-client_1.0.1_amd64.deb.sha256"
require_file "$ROOT/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
require_file "$ROOT/android/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk"
require_file "$ROOT/android/app/build/outputs/apk/debug/app-x86-debug.apk"
require_file "$ROOT/android/app/build/outputs/apk/debug/app-x86_64-debug.apk"
require_file "$ROOT/android/app/build/outputs/apk/debug/app-universal-debug.apk"

cp "$ROOT/dist/xhr-client_1.0.1_amd64.deb" "$STAGE/XHR-1.0.1-linux-amd64.deb"
(cd "$STAGE" && sha256sum XHR-1.0.1-linux-amd64.deb > XHR-1.0.1-linux-amd64.deb.sha256)
cp "$ROOT/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk" "$STAGE/XHR-1.0.1-arm64-v8a-debug.apk"
cp "$ROOT/android/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk" "$STAGE/XHR-1.0.1-armeabi-v7a-debug.apk"
cp "$ROOT/android/app/build/outputs/apk/debug/app-x86-debug.apk" "$STAGE/XHR-1.0.1-x86-debug.apk"
cp "$ROOT/android/app/build/outputs/apk/debug/app-x86_64-debug.apk" "$STAGE/XHR-1.0.1-x86_64-debug.apk"
cp "$ROOT/android/app/build/outputs/apk/debug/app-universal-debug.apk" "$STAGE/XHR-1.0.1-universal-debug.apk"
(
    cd "$STAGE"
    sha256sum XHR-1.0.1-linux-amd64.deb XHR-1.0.1-*-debug.apk > SHA256SUMS-ports.txt
)

existing="$(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')"
upload() {
    local file="$1" label="$2" name
    name="$(basename "$file")"
    if grep -Fqx "$name" <<<"$existing"; then
        echo "already uploaded: $name"
        return
    fi
    gh release upload "$TAG" "$file#$label" --repo "$REPO"
}

upload "$STAGE/XHR-1.0.1-linux-amd64.deb" "Linux amd64 Debian package"
upload "$STAGE/XHR-1.0.1-linux-amd64.deb.sha256" "Linux amd64 Debian package checksum"
upload "$STAGE/XHR-1.0.1-arm64-v8a-debug.apk" "Android arm64-v8a debug APK"
upload "$STAGE/XHR-1.0.1-armeabi-v7a-debug.apk" "Android armeabi-v7a debug APK"
upload "$STAGE/XHR-1.0.1-x86-debug.apk" "Android x86 debug APK"
upload "$STAGE/XHR-1.0.1-x86_64-debug.apk" "Android x86_64 debug APK"
upload "$STAGE/XHR-1.0.1-universal-debug.apk" "Android universal debug APK"
upload "$STAGE/SHA256SUMS-ports.txt" "Checksums for Linux and debug Android builds"
