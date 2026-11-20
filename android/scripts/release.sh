#!/usr/bin/env bash
# Build and sign the XHR arm64 APK, then copy it to ../dist.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT/android"

VERSION="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts | head -1)"
OUT="$ROOT/dist/XHR-${VERSION}-arm64-v8a.apk"

[[ -f keystore.properties ]] || {
  echo "keystore.properties is missing. Create a release key first." >&2
  exit 1
}

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleRelease --no-daemon

mkdir -p "$ROOT/dist"
cp -f app/build/outputs/apk/release/app-release.apk "$OUT"
sha256sum "$OUT" > "$OUT.sha256"
ls -lh "$OUT" "$OUT.sha256"
