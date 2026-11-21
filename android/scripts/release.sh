#!/usr/bin/env bash
# Build and sign XHR APKs for every supported Android ABI, then copy them to
# ../dist.  A universal APK is emitted as well for users who do not know their
# device ABI.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT/android"

VERSION="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts | head -1)"

[[ -f keystore.properties ]] || {
  echo "keystore.properties is missing. Create a release key first." >&2
  exit 1
}

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleRelease --no-daemon

mkdir -p "$ROOT/dist"
shopt -s nullglob
APKS=(app/build/outputs/apk/release/*.apk)
[[ ${#APKS[@]} -gt 0 ]] || { echo "no release APKs were produced" >&2; exit 1; }

for APK in "${APKS[@]}"; do
    NAME="$(basename "$APK")"
    case "$NAME" in
        *armeabi-v7a*) ABI=armeabi-v7a ;;
        *arm64-v8a*) ABI=arm64-v8a ;;
        *x86_64*) ABI=x86_64 ;;
        *x86*) ABI=x86 ;;
        *universal*) ABI=universal ;;
        *) echo "unrecognised APK output: $NAME" >&2; exit 1 ;;
    esac
    OUT="$ROOT/dist/XHR-android-${ABI}-${VERSION}.apk"
    cp -f "$APK" "$OUT"
    sha256sum "$OUT" > "$OUT.sha256"
    ls -lh "$OUT" "$OUT.sha256"
done
