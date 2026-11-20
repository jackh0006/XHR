# XHR

### Encrypted TCP through Google Apps Script. Two exits: Google, then Cloudflare.

XHR is a new relay project. It is **not** mhrv and **not** XRV. It tunnels
arbitrary **TCP** from a client to a destination through an Apps Script
deployment and an exit service, with end-to-end AES-256-GCM.

```text
client (SOCKS5 / TUN)
  -> HTTPS/2 to a Google edge (SNI www.google.com, Chrome TLS fingerprint)
  -> Google Apps Script /exec  (opaque encrypted frames)
  -> exit service (Google Cloud Run, then Cloudflare Worker)
  -> destination TCP
```

| Phase | Exit | Status |
|---|---|---|
| **1** | Google Cloud Run | Relay, exit, protocol, and tests in this repository |
| **2** | Cloudflare Worker + Durable Objects | Worker exit included; deploy guide included |
| **3** | Android app (TUN + SOCKS5) | Next, after the relay is approved |

The client in `cmd/xhr-client` is a Linux preview for testing the relay.

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
| Exit (Cloud Run) | destination and payload | client identity |
| Cloudflare (phase 2) | destination and payload | client identity |

This is **not** anonymity. Google always sees metadata and the script code. The
exit always sees the destination. Traffic analysis is still possible.

## What works over XHR

* All **TCP** applications: browsers, APIs, Telegram (TCP fallback), SSH,
  messaging, app stores.
* **SOCKS5** mode for v2rayNG and other apps.
* **TUN** mode in the upcoming Android app.

## What cannot work

* **UDP** (QUIC, Telegram calls, games). Apps Script has no UDP transport. The
  client will block QUIC so apps fall back to TCP.
* High bandwidth or gaming latency. Every packet crosses Apps Script.
* Hiding from Google and the exit provider at the same time. Whoever exits must
  decrypt.

## Quick start

* **[Google-only guide, from 0 to 100](docs/GUIDE-GOOGLE.md)**
* **[Google + Cloudflare guide](docs/GUIDE-CLOUDFLARE.md)**
* [Protocol specification](docs/PROTOCOL.md)
* [Security and limits](SECURITY.md)

## Repository layout

```text
gas/Code.gs             Apps Script relay (opaque forwarder)
cmd/xhr-exit/           Google Cloud Run exit (Go)
cmd/xhr-client/         Linux SOCKS5 preview client (Go)
internal/wire/          AES-256-GCM frames
internal/proto/         HMAC and request formats
cloudflare/             Cloudflare Worker exit + Durable Object
docs/                   Protocol and step-by-step guides
```

## Tests

```bash
go test ./...
go vet ./...
go build ./...
cd cloudflare && node --test frames.test.mjs
```

CI runs all of these on every push.

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
