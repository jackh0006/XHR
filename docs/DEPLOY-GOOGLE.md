# Deploy XHR on Google only

Phase 1 keeps the whole path inside Google:

```text
client -> Google edge -> Apps Script -> Cloud Run exit -> destination
```

## 1. Create the Apps Script relay

1. Open <https://script.google.com> and create a new project.
2. Replace the code with `gas/Code.gs` from this repository.
3. Open **Project Settings → Script Properties** and add:

| Property | Value |
|---|---|
| `AUTH_KEY` | long random secret for the client |
| `EXIT_URL` | Cloud Run URL from step 2 (ends in `/relay`) |
| `EXIT_HMAC` | long random secret for GAS → exit |
| `MAX_ITEMS` | optional, default 64 |
| `MAX_BYTES` | optional, default 262144 |

4. **Deploy → New deployment → Web app**
   * Execute as: **Me**
   * Who has access: **Anyone**
5. Copy the **Deployment ID** or the full `/exec` URL.

Generate the secrets locally, for example:

```bash
openssl rand -hex 32   # AUTH_KEY
openssl rand -hex 32   # EXIT_HMAC
openssl rand -hex 32   # E2E tunnel key (client + exit only)
```

## 2. Deploy the exit on Google Cloud Run

The exit is a small Go service in `cmd/xhr-exit`.

```bash
gcloud run deploy xhr-exit \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --max-instances 1 \
  --concurrency 80 \
  --timeout 300 \
  --set-env-vars XHR_E2E_KEY=<64-hex>,XHR_GAS_HMAC=<EXIT_HMAC>
```

`XHR_E2E_KEY` is the 32-byte tunnel key. It is shared with the client and is
never given to GAS. `XHR_GAS_HMAC` must equal the `EXIT_HMAC` property in the
script.

Copy the service URL and put `.../relay` into `EXIT_URL`.

Single-instance mode keeps sessions in memory. Do not increase
`--max-instances` until session affinity or shared session storage is added.

## 3. Build the client

```bash
cd cmd/xhr-client
go build -o xhr-client .
```

Create `config.json`:

```json
{
  "gas_url": "https://script.google.com/macros/s/PASTE_DEPLOYMENT_ID/exec",
  "gas_host": "script.google.com",
  "front_domain": "www.google.com",
  "google_ip": "216.239.38.120",
  "auth_key": "<AUTH_KEY>",
  "e2e_key": "<64-hex E2E key>",
  "listen": "127.0.0.1:1080"
}
```

Run:

```bash
./xhr-client -config config.json
```

Point a browser or v2rayNG at `socks5://127.0.0.1:1080`.

## 4. Verify

```bash
curl -x socks5h://127.0.0.1:1080 https://example.com/
```

If that works, the whole chain is alive: client → Google → GAS → Cloud Run →
destination.

## Quotas

* Free GAS: 20,000 UrlFetch calls/day. One long poll covers up to 20 seconds.
* One client session uses approximately one call per 20 seconds when idle.
* Use one deployment per group of users if you need more headroom.
* Cloud Run has a free tier; keep `--max-instances 1` for phase 1.

## Phase 2 — Cloudflare

After the Google path is stable, the same protocol can target a Cloudflare
Worker exit. The client keeps the same frames and keys; only `EXIT_URL` and the
exit implementation change. Cloudflare Workers need Durable Objects to hold TCP
sessions across requests; see `cloudflare/` and the roadmap.
