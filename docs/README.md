# XHR documentation

Every guide and every deployable source text is a normal, readable file in this
repository. Nothing important is only in chat, only in an APK, or only on a
release page.

## Start here

1. **[Cloudflare-only, from 0 to 100](GUIDE-CLOUDFLARE.md)** — the recommended
   free path. Apps Script + Cloudflare Workers Free, no Google Cloud billing.
2. **[Android, from 0 to 100](GUIDE-ANDROID.md)** — install the APK, set up
   TUN, per-app routing, v2rayNG, logs, and limits.
3. **[Test checklist](TEST-CHECKLIST.md)** — run this on the phone.
4. **[Protocol](PROTOCOL.md)** and **[Security](../SECURITY.md)** — exact
   frames, authentication, and the honest threat model.
5. **[Google Cloud Run guide](GUIDE-GOOGLE.md)** — optional path if you can
   add a billing account/card.

## Complete source texts

- **[FULL-TEXT.md](FULL-TEXT.md)** contains the complete text of every
  deployable/server file in one page:
  - `gas/Code.gs`
  - `cloudflare/worker.js`
  - `cloudflare/frames.js`
  - `cloudflare/wrangler.toml`
  - `cloudflare/package.json`
  - `Dockerfile`
  - `cmd/xhr-exit/main.go`
  - `internal/proto/proto.go`
  - `internal/wire/wire.go`
  - `internal/tunnel/client.go`
  - `mobile/main.go`
  - `mobile/jni.c`

That page is generated from the canonical files. Run
`scripts/build-full-text.sh` after changing one of them. CI checks that it is
current.

## Canonical files

| File | What it is |
|---|---|
| [`gas/Code.gs`](../gas/Code.gs) | The whole Apps Script relay |
| [`cloudflare/worker.js`](../cloudflare/worker.js) | Cloudflare Worker + Durable Object exit |
| [`cloudflare/frames.js`](../cloudflare/frames.js) | Worker AES-GCM and HMAC helpers |
| [`cloudflare/wrangler.toml`](../cloudflare/wrangler.toml) | Wrangler deployment config |
| [`Dockerfile`](../Dockerfile) | Cloud Run container |
| [`cmd/xhr-exit/main.go`](../cmd/xhr-exit/main.go) | Go Cloud Run exit |
| [`internal/proto/proto.go`](../internal/proto/proto.go) | HMAC and request formats |
| [`internal/wire/wire.go`](../internal/wire/wire.go) | AES-256-GCM frame format |
| [`internal/tunnel/client.go`](../internal/tunnel/client.go) | Multiplexed client engine |
| [`mobile/main.go`](../mobile/main.go) | Android JNI engine |
| [`android/`](../android/) | Complete Android app source |
| [`scripts/build-full-text.sh`](../scripts/build-full-text.sh) | Regenerates the complete-text page |

## Quick links

- [Repository](https://github.com/jackh0006/XHR)
- [Latest release](https://github.com/jackh0006/XHR/releases/latest)
- [Contributing](../CONTRIBUTING.md)
- [Changelog](../CHANGELOG.md)
- [License](../LICENSE)

## Why there is a generated page

The Apps Script editor and the Cloudflare dashboard both ask for one complete
file. Copying from a guide can accidentally omit code. `FULL-TEXT.md` gives the
whole file in one place, and the generator keeps it identical to the canonical
source in `gas/` and `cloudflare/`.
