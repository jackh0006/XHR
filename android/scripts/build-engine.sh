#!/usr/bin/env bash
# Build the Go engine and the hev-socks5-tunnel TUN library for Android.
#
# With no arguments, every Android ABI supported by the app is built.  Pass
# one or more ABI names to rebuild only those libraries, for example:
#   ./scripts/build-engine.sh arm64-v8a x86_64
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}"
GO="${GO_BIN:-go}"
HOST_TAG="linux-x86_64"
[[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ]] || HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/include"

[[ -d "$TOOLCHAIN" ]] || { echo "NDK toolchain not found: $TOOLCHAIN" >&2; exit 1; }

if [[ $# -eq 0 ]]; then
    ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
else
    ABIS=("$@")
fi

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        armeabi-v7a) GOARCH=arm; CLANG_TRIPLE=armv7a-linux-androideabi24; GOARM=7 ;;
        arm64-v8a)   GOARCH=arm64; CLANG_TRIPLE=aarch64-linux-android24; GOARM= ;;
        x86)         GOARCH=386; CLANG_TRIPLE=i686-linux-android24; GOARM= ;;
        x86_64)      GOARCH=amd64; CLANG_TRIPLE=x86_64-linux-android24; GOARM= ;;
        *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
    esac
    CLANG="$TOOLCHAIN/bin/$CLANG_TRIPLE-clang"
    OUT="$ROOT/app/src/main/jniLibs/$ABI"
    [[ -x "$CLANG" ]] || { echo "NDK clang not found: $CLANG" >&2; exit 1; }
    mkdir -p "$OUT"

    (
        cd "$ROOT/.."
        CGO_ENABLED=1 GOOS=android GOARCH="$GOARCH" GOARM="$GOARM" CC="$CLANG" \
            CGO_CFLAGS="-I$SYSROOT" \
            "$GO" build -trimpath -buildmode=c-shared \
            -ldflags='-s -w -extldflags=-Wl,-soname,libxhr.so,-z,max-page-size=16384,-z,common-page-size=16384' \
            -o "$OUT/libxhr.so" ./mobile
    )

    "$NDK/ndk-build" \
        NDK_PROJECT_PATH="$ROOT/tun2socks" \
        APP_BUILD_SCRIPT="$ROOT/tun2socks/Android.mk" \
        APP_ABI="$ABI" \
        APP_MODULES=hev-socks5-tunnel \
        APP_PLATFORM=android-24 \
        NDK_OUT="$ROOT/build/hev-obj" \
        NDK_LIBS_OUT="$ROOT/build/hev-libs" \
        -j"${XHR_BUILD_JOBS:-2}"

    install -m 0644 "$ROOT/build/hev-libs/$ABI/libhev-socks5-tunnel.so" "$OUT/libhev-socks5-tunnel.so"
    echo "built $OUT/libxhr.so and $OUT/libhev-socks5-tunnel.so"
done
