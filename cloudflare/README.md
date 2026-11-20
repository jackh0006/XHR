# Cloudflare Worker exit

This folder is the complete Cloudflare exit.

| File | Purpose |
|---|---|
| `worker.js` | Worker fetch handler + `Session` Durable Object |
| `frames.js` | AES-256-GCM frames, HMAC, base64 helpers |
| `wrangler.toml` | Worker, Durable Object binding, migration |
| `package.json` | Node test command |
| `frames.test.mjs` | Six frame/HMAC tests |

## Cloudflare authentication

Do not use `wrangler login` in Cloud Shell. Its callback is
`http://localhost:8976/oauth/callback`, which Cloud Shell cannot receive. The
browser will say `localhost refused to connect`.

Create an API token instead:

1. Open <https://dash.cloudflare.com/profile/api-tokens>.
2. **Create Token → Edit Cloudflare Workers → Use template**.
3. Select your account and create the token.
4. Copy it once.

Then:

```bash
cd ~/XHR/cloudflare
read -r -s -p "Cloudflare API token: " CLOUDFLARE_API_TOKEN
echo
read -r -p "Cloudflare Account ID: " CLOUDFLARE_ACCOUNT_ID
export CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID
wrangler whoami
```

The Account ID is on **Workers & Pages**, or in the dashboard URL. If `wrangler`
is not installed, use `npx wrangler`.

## Required secrets

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
