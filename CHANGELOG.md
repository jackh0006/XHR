# Changelog

All notable changes to XHR are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-20

Android client release. The Apps Script relay is unchanged. The exit and client
now use an explicit `open_ok`/`open_err` acknowledgment, so connection setup is
deterministic and the first app write is never lost. Redeploy the exit when
upgrading from 0.1.0.

### Added

* Signed **arm64-v8a** Android APK for the Huawei Y9 2019 and modern arm64
  phones (`minSdk 24`, `targetSdk 34`).
* Compose Material 3 UI with a white / Google-colour X mark, live throughput
  graph, daily quota bars, and status-bar speed notification.
* **TUN mode** using `VpnService` + source-built `hev-socks5-tunnel`, with
  `mapdns` remote DNS and XHR excluded from its own tunnel.
* **SOCKS5 mode** on `127.0.0.1:1080` for v2rayNG and other clients.
* Per-app routing: all, only selected, except selected.
* Google edge lab: concurrent TLS/SNI probes with real latency, certificate,
  and ALPN results; one-tap best IP.
* Live local logs with copy and clear.
* Daily request and data counters, optional daily data cap, and automatic stop.
* Android Keystore AES-256-GCM profile storage; no analytics or telemetry.
* Multiplexed client core in `internal/tunnel` with many streams per Apps
  Script session, bounded queues, retry, and idle session cleanup.
* Exits: `open_ok` / `open_err` on Google Cloud Run and Cloudflare Workers.
* Integration tests for multiplexed streams, echoes, framing, and exit errors.

### Changed

* Client → GAS and GAS → exit behaviour remains HMAC-SHA256 and AES-256-GCM.
* Upload requests now return in ~20 ms; a second request stays parked as the
  downlink long poll. This keeps uploads pipelined.
* README, protocol, and a complete Android 0-to-100 guide.

### Known limits

* TCP only; UDP/QUIC is refused by the local SOCKS listener.
* Google sees metadata and the exit URL.
* The exit sees destinations and plaintext.
* Cloud Run phase 1 runs one instance.
* v2rayNG users must exclude the XHR app from v2rayNG's per-app VPN to avoid a
  loop.

## [0.1.0] - 2026-11-20

Initial relay release.

### Added

* Apps Script relay (`gas/Code.gs`) that verifies client HMAC and forwards
  opaque encrypted frames to an exit.
* Google Cloud Run exit (`cmd/xhr-exit`) with AES-256-GCM frames, session and
  stream multiplexing, long polling, replay protection, and bounded queues.
* Cloudflare Worker exit (`cloudflare/worker.js`) with Durable Objects and
  `cloudflare:sockets` TCP connections.
* AES-256-GCM wire format with padding and session-bound AAD.
* HMAC-SHA256 authentication for client → GAS and GAS → exit.
* Linux SOCKS5 preview client (`cmd/xhr-client`) with uTLS Chrome fingerprint
  and HTTP/2 domain fronting.
* Protocol specification and beginner guides for Google-only and
  Google + Cloudflare deployments.
* Go unit tests, exit end-to-end echo test, Node frame tests, and CI.

### Security

* No secrets in request bodies or logs.
* 120-second replay window plus per-frame random values.
* Loopback-only client listener.

### Known limits

* TCP only; UDP (QUIC, calls, games) is not supported by Apps Script.
* Google sees metadata even though it cannot read destinations or payload.
* The exit always sees destinations.
* Cloud Run phase 1 runs a single instance to keep sessions in memory.
* Cloudflare Durable Objects may require a paid Workers plan.

[1.0.0]: https://github.com/jackh0006/XHR/releases/tag/v1.0.0
[0.1.0]: https://github.com/jackh0006/XHR/releases/tag/v0.1.0
