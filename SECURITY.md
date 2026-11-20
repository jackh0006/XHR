# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.x | ✅ |
| 0.x | ✅ |

## Reporting a vulnerability

Do **not** open a public issue for a security problem.

Use GitHub’s private reporting: **Security** tab → **Report a vulnerability**.

Include the affected version, deployment type (Cloud Run or Cloudflare),
reproduction steps, and impact. Expect an acknowledgement within **72 hours**
and a fix or written assessment within **14 days**.

## Design

| Property | How |
|---|---|
| End-to-end encryption | AES-256-GCM between client and exit; the key never reaches GAS or the Google edge |
| Client authentication | HMAC-SHA256 over `timestamp.session` with `AUTH_KEY`; the key is not sent |
| GAS → exit authentication | HMAC-SHA256 of the exact body with `EXIT_HMAC` |
| Replay protection | 120-second window and a per-frame random value tracked by the exit |
| Session binding | AES-GCM AAD includes the session id, so frames cannot move between sessions |
| Size hiding | Random plaintext padding |
| DPI resistance | uTLS Chrome fingerprint, HTTP/2, SNI `www.google.com` |
| Secrets at rest | Script Properties (GAS), environment variables (Cloud Run / Worker) |
| Loopback only | The client SOCKS listener binds `127.0.0.1` |
| Android profile at rest | AES-256-GCM key in Android Keystore; backup disabled |
| Android VPN scope | XHR excludes itself from its own TUN; per-app rules are explicit |

## Threat model

**Protected against**

* a passive ISP/DPI observer seeing the destination or content,
* a passive observer distinguishing the client from Chrome by TLS fingerprint
  (best effort, not a guarantee),
* replay of captured frames into another session or after the time window,
* a third party using the GAS deployment without `AUTH_KEY`.

**Not protected against**

* **Google** seeing metadata: that a relay exists, request sizes, timing,
  volume, and the exit URL. Google hosts the GAS script and can inspect it.
* **The exit** seeing destinations and plaintext. The exit must decrypt to
  connect. Cloud Run and Cloudflare are trusted parties in their respective
  phases.
* **Traffic analysis.** Long-lived, evenly sized POSTs to Google can still be
  fingerprinted as a relay.
* A compromised client, compromised exit, or stolen keys.
* UDP. Apps Script has no UDP transport.

XHR is a censorship-resistance tool, not an anonymity network. Do not rely on it
where anonymity is a safety requirement.

## Key management

* `AUTH_KEY`: shared by clients; rotate it in Script Properties and clients.
* `EXIT_HMAC`: shared only by Apps Script and the exit; rotate both together.
* `XHR_E2E_KEY` (tunnel key): shared only by the client and the exit. Never put
  it in Apps Script or a public repository.

A leaked tunnel key exposes all traffic. A leaked `AUTH_KEY` lets others use
your GAS quota. A leaked `EXIT_HMAC` lets others use your exit.

## Logs

The relay does not log destinations or keys. Cloud Run and Cloudflare can log
request metadata at the platform level; disable or limit provider logging if
that matters to you.

The Android app keeps a bounded in-memory log for the user. It includes the
destination host and port of each stream. It is not uploaded. `POST_NOTIFICATIONS`
is used only for the foreground status and live traffic counters.

## Android specifics

* The APK contains arm64 native code and a source-built
  `hev-socks5-tunnel` for `VpnService`.
* XHR's own package is excluded from its TUN. In SOCKS5 mode the user must
  exclude XHR from v2rayNG's per-app VPN to avoid a routing loop.
* The local SOCKS5 server has no authentication, but it binds only
  `127.0.0.1`; another app on the same phone can use it. Do not expose it with
  a port forward.
* UDP ASSOCIATE is refused. QUIC fails by design and apps fall back to TCP.
* If Android Keystore is unavailable on a broken device, the app falls back to
  its private, sandboxed data directory so the profile is not lost. This is
  weaker than Keystore; reinstall or reset the key if that matters.
* The APK is signed with the XHR release key. Verify the SHA-256 published on
  the release page.

## Scope

In scope: this repository’s GAS script, Go exit, Worker exit, preview client,
and protocol.
Out of scope: Google Apps Script, Cloud Run, Cloudflare Workers, and the
networks and sites you visit.
