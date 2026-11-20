# Changelog

All notable changes to XHR are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/).

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

[0.1.0]: https://github.com/jackh0006/XHR/releases/tag/v0.1.0
