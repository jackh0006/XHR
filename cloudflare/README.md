# Cloudflare Worker exit

This folder is the complete Cloudflare exit.

| File | Purpose |
|---|---|
| `worker.js` | Worker fetch handler + `Session` Durable Object |
| `frames.js` | AES-256-GCM frames, HMAC, base64 helpers |
| `wrangler.toml` | Worker, Durable Object binding, migration |
| `package.json` | Node test command |
| `frames.test.mjs` | Six frame/HMAC tests |

Required secrets:

```bash
wrangler secret put XHR_E2E_KEY
wrangler secret put XHR_GAS_HMAC
```

The Worker URL must end in `/relay` in the Apps Script `EXIT_URL` property.

Full setup: [docs/GUIDE-CLOUDFLARE.md](../docs/GUIDE-CLOUDFLARE.md)

Full copy-paste text: [docs/FULL-TEXT.md](../docs/FULL-TEXT.md)

Tests:

```bash
node --test frames.test.mjs
node --check worker.js
```
