#!/usr/bin/env bash
# Build the arm64 Go engine and the hev-socks5-tunnel TUN library.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}"
GO="${GO_BIN:-go}"
HOST_TAG="linux-x86_64"
[[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ]] || HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android24-clang"
SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/include"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"

[[ -x "$CLANG" ]] || { echo "NDK clang not found: $CLANG" >&2; exit 1; }
mkdir -p "$OUT"

(
    cd "$ROOT/.."
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CLANG" \
        CGO_CFLAGS="-I$SYSROOT" \
        "$GO" build -trimpath -buildmode=c-shared \
        -ldflags='-s -w -extldflags=-Wl,-soname,libxhr.so,-z,max-page-size=16384,-z,common-page-size=16384' \
        -o "$OUT/libxhr.so" ./mobile
)

"$NDK/ndk-build" \
    NDK_PROJECT_PATH="$ROOT/tun2socks" \
    APP_BUILD_SCRIPT="$ROOT/tun2socks/Android.mk" \
    APP_ABI=arm64-v8a \
    APP_MODULES=hev-socks5-tunnel \
    APP_PLATFORM=android-24 \
    NDK_OUT="$ROOT/build/hev-obj" \
    NDK_LIBS_OUT="$ROOT/build/hev-libs" \
    -j"${XHR_BUILD_JOBS:-2}"

install -m 0644 "$ROOT/build/hev-libs/arm64-v8a/libhev-socks5-tunnel.so" "$OUT/libhev-socks5-tunnel.so"
echo "built $OUT/libxhr.so and $OUT/libhev-socks5-tunnel.so"
