# XHR Android

The Android app is an arm64-v8a Material 3 client for the XHR relay.

## What is inside

| Path | Purpose |
|---|---|
| `app/src/main/java/com/jackh0006/xhr` | UI, services, JNI bridge, settings |
| `app/src/main/jniLibs/arm64-v8a/libxhr.so` | Go engine + JNI, built by `mobile/` |
| `app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so` | Source-built TUN-to-SOCKS5 shim |
| `tun2socks/` | Pinned hev source used for the shim |
| `scripts/build-engine.sh` | Rebuilds both native libraries with the NDK |
| `scripts/release.sh` | Builds, signs, copies, and hashes the release APK |

The prebuilt libraries are committed so a normal Gradle build does not need the
NDK. Rebuild them after changing `mobile/` or `internal/`:

```bash
ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/26.3.11579264 \
  ./scripts/build-engine.sh
```

## Build

Requirements:

* JDK 17 or newer
* Android SDK platform 34 and build-tools 34.0.0
* `ANDROID_HOME` or `local.properties` pointing at the SDK

```bash
./gradlew :app:assembleDebug
```

Release signing uses `keystore.properties` and a private `.jks`. Both are
ignored by git. `scripts/release.sh` refuses to build a release without them.

## Runtime layout

```text
TUN mode:
  apps → Android TUN → hev-socks5-tunnel → 127.0.0.1:1080
       → Go engine → Google edge → Apps Script → exit

SOCKS5 mode:
  v2rayNG → 127.0.0.1:1080 → Go engine
       → Google edge → Apps Script → exit
```

The Go engine is the same protocol on both paths. TUN is not emulated: it uses
`VpnService`, a real TUN descriptor, and `hev-socks5-tunnel`. The local SOCKS
listener binds loopback only.

## Minimum phone

* Android 7.0 (API 24)
* arm64-v8a CPU
* Huawei Y9 2019 / Kirin 710 is the reference device

## Security

* Profile encrypted with Android Keystore AES-256-GCM.
* No analytics, no accounts, no telemetry.
* Notifications are used only for the foreground service and live counters.
* UDP/QUIC is refused by the SOCKS listener; TCP only.
