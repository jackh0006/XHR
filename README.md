<img align="right" src="docs/assets/xhr-icon.png" width="96" alt="XHR icon">

# XHR

### Encrypted TCP through Google Apps Script. Cloudflare first; Google Cloud Run optional.

XHR is a new relay project. It is **not** mhrv and **not** XRV. It tunnels
arbitrary **TCP** from a client to a destination through an Apps Script
deployment and an exit service, with end-to-end AES-256-GCM.

> **Recommended free path:** Apps Script + **Cloudflare Workers Free**. It needs
> no Google Cloud billing account and no card. Follow the
> **[complete Cloudflare guide](docs/GUIDE-CLOUDFLARE.md)**.

```text
client (SOCKS5 / TUN)
  -> HTTPS/2 to a Google edge (SNI www.google.com, Chrome TLS fingerprint)
  -> Google Apps Script /exec  (opaque encrypted frames)
  -> exit service (Cloudflare Worker, or Google Cloud Run)
  -> destination TCP
```

| Phase | Exit / client | Status |
|---|---|---|
| **1** | **Cloudflare Worker + Durable Objects** | Recommended free path; complete guide |
| **2** | Google Cloud Run | Optional; needs a billing account/card |
| **3** | Android app (TUN + SOCKS5) | **XHR 1.0.1 APK**, armeabi-v7a / arm64-v8a / x86 / x86_64, Huawei Y9 2019 reference device |

The Linux preview client in `cmd/xhr-client` is still useful for testing the
relay from a computer.

## Why an exit is required

Google Apps Script's `UrlFetchApp` can only fetch HTTP/HTTPS URLs. It cannot open
a raw TCP socket. Tunneling arbitrary TCP (Telegram, SSH, games, any app)
therefore needs one HTTP endpoint that *can* open TCP: the exit.

XHR’s GAS script only verifies its own request signature and forwards **opaque
ciphertext**. It cannot see the destination, the payload, or the tunnel key.

## Security design

* **AES-256-GCM end-to-end** between client and exit. The key never reaches
  Google or Cloudflare.
* **HMAC-SHA256** for client → GAS and GAS → exit authentication. Secrets stay
  in Script Properties / environment variables, never in request bodies.
* **Replay protection**: 120-second window plus a per-frame random value.
* **Session binding** in the AES-GCM AAD, so a frame cannot be replayed into a
  different session.
* **Padding and jitter** to blur frame sizes and timing.
* **uTLS Chrome fingerprint + HTTP/2** on the client, so the last mile looks
  like Chrome to a passive observer.
* Listeners bind loopback only. No telemetry. MIT licensed.

### What can and cannot be hidden

| Observer | Sees | Does not see |
|---|---|---|
| ISP / DPI | a Google IP, TCP 443, SNI `www.google.com` | destination, payload |
| Google (GAS) | ciphertext frames, timing, volume, exit URL | destination, payload, keys |
| Exit (Cloudflare or Cloud Run) | destination and payload | client identity |

This is **not** anonymity. Google always sees metadata and the script code. The
exit always sees the destination. Traffic analysis is still possible.

## What works over XHR

* All **TCP** applications: browsers, APIs, Telegram (TCP fallback), SSH,
  messaging, app stores.
* **SOCKS5** mode for v2rayNG and other apps.
* **TUN** mode in the Android app.

## What cannot work

* **UDP** (QUIC, Telegram calls, games). Apps Script has no UDP transport. The
  client will block QUIC so apps fall back to TCP.
* High bandwidth or gaming latency. Every packet crosses Apps Script.
* Hiding from Google and the exit provider at the same time. Whoever exits must
  decrypt.

## Download the Android app

**[XHR 1.0.1 release](https://github.com/jackh0006/XHR/releases/latest)**

Release APK SHA-256: `2d30085be2704327290c0736a7d944496e24bd7dea2631b9cd44681ed818f891`

The same release also includes:

| Asset | Use |
|---|---|
| `XHR-1.0.1-linux-amd64.deb` | Debian amd64 client |
| `XHR-1.0.1-universal-debug.apk` | Debug APK for any supported Android ABI |
| `XHR-1.0.1-arm64-v8a-debug.apk` | Debug APK for arm64 phones |
| `XHR-1.0.1-armeabi-v7a-debug.apk` | Debug APK for 32-bit ARM |
| `XHR-1.0.1-x86-debug.apk` | Debug APK for x86 |
| `XHR-1.0.1-x86_64-debug.apk` | Debug APK for x86_64 |

Debug APKs use the Android debug key. They cannot be installed over the
release-signed APK; uninstall the release app first. They are for testing.

> **Upgrading?** Redeploy the exit from tag `v1.0.1`. The 1.0.x client waits
> for the `open_ok` acknowledgement; the old 0.1.0 exit binary will time out.

* One switch for all TCP apps (**TUN**), or a local **SOCKS5** listener for
  v2rayNG.
* **1–5 Apps Script deployments** per profile, with active-count selection,
  round-robin, and failover.
* **Multiple Google and Cloudflare accounts** can be combined into one
  deployment pool.
* Real upload/download totals and live speed in the status-bar notification.
* Per-app routing (all / only / except).
* Google edge lab with real TLS/SNI latency results.
* Live local logs, daily request and data counters, optional daily data cap.
* About and 14 donation assets.
* Android Keystore-encrypted profile, no analytics, no telemetry.
* `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, `minSdk 24`, built and signed with the Huawei Y9 2019 as the reference device.

## Deployment pools and multiple accounts

One XHR profile can use **1–5 Apps Script deployments**. They can all point to
the same Worker, or each can point to a different Worker under a different
Cloudflare account.

| Thing | Rule |
|---|---|
| `E2E` tunnel key | **Same on every Worker** used by one profile |
| Android `AUTH_KEY` | **Same on every Apps Script** deployment |
| Worker URL | May be different |
| Cloudflare account/email | May be different |
| Worker `XHR_GAS_HMAC` | Can be different for each Worker |
| Apps Script `EXIT_HMAC` | Must match its own Worker |
| Apps Script `EXIT_URL` | Must point to its own Worker `/relay` |
| Deployment ID | Different for each Apps Script project |

The app stores only the Apps Script Deployment IDs. Each Apps Script holds its
own `EXIT_URL`. A typical five-way pool:

```text
Google account A -> GAS A -> Cloudflare A -> Worker A
Google account B -> GAS B -> Cloudflare B -> Worker B
Google account C -> GAS C -> Cloudflare C -> Worker C
Google account D -> GAS D -> Cloudflare D -> Worker D
Google account E -> GAS E -> Cloudflare E -> Worker E
```

All five Workers must use the same `XHR_E2E_KEY`; otherwise the app cannot
decrypt through that exit. Different Google accounts have separate Apps Script
quotas. Different Cloudflare accounts have separate Worker/Durable Object
quotas. Accounts under the same provider share that provider's quota.

## Linux client

Download the Debian package for amd64 from the latest release:

```bash
sudo apt install ./XHR-1.0.1-linux-amd64.deb
```

Create the per-user profile:

```bash
install -Dm600 /usr/share/doc/xhr-client/examples/config.json \
  ~/.config/xhr-client/config.json
editor ~/.config/xhr-client/config.json
```

Start it:

```bash
xhr-client
```

Then use `127.0.0.1:1080` as a SOCKS5 proxy.

Full Linux guide: [docs/GUIDE-LINUX.md](docs/GUIDE-LINUX.md)

## Quick start

* **[Cloudflare-only guide, from 0 to 100](docs/GUIDE-CLOUDFLARE.md)** — free path, no Google Cloud billing
* **[Android guide](docs/GUIDE-ANDROID.md)** — install, TUN, SOCKS5, v2rayNG, per-app
* **[On-device test checklist](docs/TEST-CHECKLIST.md)**
* **[Documentation index](docs/README.md)** — every guide and source path
* **[Google Cloud Run guide](docs/GUIDE-GOOGLE.md)** — optional paid-card path
* **[Complete source texts](docs/FULL-TEXT.md)** — `Code.gs`, Worker, exit, engine in full
* [Protocol specification](docs/PROTOCOL.md)
* [Security and limits](SECURITY.md)

## Repository layout

```text
gas/Code.gs             Apps Script relay (opaque forwarder)
cmd/xhr-exit/           Optional Google Cloud Run exit (Go)
cmd/xhr-client/         Linux SOCKS5 preview client (Go)
internal/tunnel/        Multiplexed Android/Linux client core
internal/wire/          AES-256-GCM frames
internal/proto/         HMAC and request formats
cloudflare/             Cloudflare Worker exit + Durable Object
android/                Android app: Compose UI, TUN, SOCKS5, JNI engine
mobile/                 Go c-shared JNI entry points
branding/               White/Google-colour X logo and launcher icons
docs/                   Documentation index and step-by-step guides
scripts/                Source-text generator
gas/Code.gs             The complete Apps Script relay text
docs/FULL-TEXT.md       All deployable texts in one generated page
```

## Tests

```bash
go test ./...
go test -race ./...
go vet ./...
go build ./...

cd cloudflare && node --test frames.test.mjs
cd .. && node --test gas/gas.test.mjs

# Android (needs JDK 17+ and Android SDK 34)
cd android && ./gradlew :app:assembleRelease
```

GitHub Actions runs the Go tests, Apps Script VM tests, Cloudflare frame tests,
and an Android `assembleDebug` build on every push.

## Android design notes

* **TUN is real TUN.** `VpnService` creates the interface; the source-built
  `hev-socks5-tunnel` turns packets into SOCKS5; the Go engine carries them
  through the relay. XHR excludes its own package from the VPN.
* **Proxy is a real SOCKS5 server.** The Go engine listens on `127.0.0.1:1080`
  (loopback only) and performs the same XHR protocol. v2rayNG can use it as a
  Socks outbound.
* **DNS stays off the local resolver in TUN mode.** `mapdns` answers with
  mapped IPs and sends the hostname to the exit over SOCKS.
* **UDP is refused, not faked.** The SOCKS listener rejects UDP ASSOCIATE, so
  QUIC fails and browsers fall back to TCP. Apps Script has no UDP transport.
* **Traffic is counted at the engine.** The notification, Home screen, and
  daily limit all use the same byte counters.

## Donate

XHR is free and MIT-licensed. Donations pay for test infrastructure and
maintenance.

| Asset | Network | Address |
|-------|---------|---------|
| Bitcoin | BTC | `bc1q8t0fn2yrsy4lh3m0pz34uj27t8vxjeavkjym83` |
| Dogecoin | DOGE | `D6ZdMQ7mHGGmuH9prpZ2zjpnG5Q3WVRDtC` |
| Ethereum | ETH | `0xdad428900a4359be8f76b3062df34211582e09eb` |
| USDT | ERC-20 | `0xdad428900a4359be8f76b3062df34211582e09eb` |
| BNB | BEP-20 | `0xdad428900a4359be8f76b3062df34211582e09eb` |
| USDT | BEP-20 | `0xdad428900a4359be8f76b3062df34211582e09eb` |
| TRON | TRX | `TMpb6RNTuGNM1eTakm9kjds1mRTPYYJesf` |
| USDT | TRC-20 | `TMpb6RNTuGNM1eTakm9kjds1mRTPYYJesf` |
| Solana | SOL | `BDCCrRez1yD1RpkAtiqKKDk3BfxPD8P7nkL26jCYrzgL` |
| USDT | SPL | `BDCCrRez1yD1RpkAtiqKKDk3BfxPD8P7nkL26jCYrzgL` |
| USDC | SPL | `BDCCrRez1yD1RpkAtiqKKDk3BfxPD8P7nkL26jCYrzgL` |
| XRP | XRP Ledger | `rNUAhaATFLvosdu9m9M95bupRBtZ8eqpj9` |
| Toncoin | TON | `UQCu6-3yGyQ5dzvcCxr2gobuvx5ddbS9EC690qtey92P5_wX` |
| Litecoin | LTC | `ltc1q2gs89cfy3mumr7gu9w0zl9rllf80q67m5rmma8` |

Only send the matching asset on the matching network.

## Credits

Made by **jackh0006** · [jackh109867@gmail.com](mailto:jackh109867@gmail.com).

Uses the MIT-licensed `utls` and `golang.org/x/net` libraries. XHR is an
independent project; it is not affiliated with Google, Cloudflare, or the mhrv
or XRV projects.

MIT © 2026 jackh0006 — [LICENSE](LICENSE).
