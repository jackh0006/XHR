# XHR — Google + Cloudflare guide

This is the second protocol. The client and the Apps Script relay stay the same;
only the **exit** changes from Google Cloud Run to a Cloudflare Worker.

> Upgrading from XHR 0.1.0? Deploy the Worker again from tag `v1.0.0` so it
> sends `open_ok`. The Apps Script relay does not change.

```text
client -> Google edge -> Apps Script -> Cloudflare Worker -> destination
```

Google sees ciphertext going to your Worker. Cloudflare sees the destination.

---

## Before you start

**This path does not need Cloud Run, Google Cloud billing, or a credit card.**
Apps Script is free. The Worker exit runs on the Cloudflare **Workers Free**
plan, which currently includes SQLite-backed Durable Objects and outbound TCP
sockets.

You need:

* a Google account for Apps Script,
* a Cloudflare account,
* Node.js 18+ (Cloud Shell already has it),
* about 15 minutes.

You need two secrets. Generate them now if you have not:

```bash
echo "E2E=$(openssl rand -hex 32)"
echo "GAS=$(openssl rand -hex 32)"
```

Save them privately. `E2E` is for the client and exit. `GAS` is for Apps Script
and the exit.

You also need an Apps Script project. If you do not have one, follow **Part 2**
of [GUIDE-GOOGLE.md](GUIDE-GOOGLE.md), but **skip Part 1** (do not deploy
Cloud Run). Use the Worker `/relay` URL as `EXIT_URL` when you set the
properties. `EXIT_HMAC` is the `GAS=` value. `AUTH_KEY` is a separate password
for the XHR client.

---

## Part 1 — Deploy the Cloudflare exit

1. Open <https://shell.cloud.google.com> (or any terminal with Node.js 18+).
2. Enter the Worker folder in the XHR repository:

```bash
git clone https://github.com/jackh0006/XHR.git
cd XHR/cloudflare
```

If you already cloned XHR, just:

```bash
cd ~/XHR/cloudflare
```

3. Install Wrangler, Cloudflare’s command-line tool:

```bash
npm install -g wrangler
```

If `npm install -g` is blocked, use `npx wrangler` in every command instead.

4. Sign in:

```bash
wrangler login
```

A browser tab or a code/URL appears. Approve the login.

5. Deploy the Worker. It must exist before secrets can be attached:

```bash
wrangler deploy
```

Wrangler prints a URL like:

```text
https://xhr-exit.YOURNAME.workers.dev
```

6. Store the two secrets. At the `Enter a secret value:` prompt, paste the
value and press Enter. Nothing is echoed.

```bash
wrangler secret put XHR_E2E_KEY
wrangler secret put XHR_GAS_HMAC
```

Use:
* `XHR_E2E_KEY` = your `E2E=` value
* `XHR_GAS_HMAC` = your `GAS=` value

7. Prove the Worker is live. This is expected to return **401**, because no HMAC
was sent:

```bash
WORKER="https://xhr-exit.YOURNAME.workers.dev"
curl -i -X POST "$WORKER/relay"
```

A `401 Unauthorized` means the Worker and its route exist. A `404` means the URL
or `/relay` path is wrong. A `500` usually means a secret is missing.

8. Your **EXIT_URL** is the Worker URL plus `/relay`:

```text
https://xhr-exit.YOURNAME.workers.dev/relay
```

---

## Part 2 — Point Apps Script at Cloudflare

1. Open your XHR Apps Script project at <https://script.google.com>.
2. Open **Project Settings → Script Properties**.
3. Change **EXIT_URL** to the Worker URL ending in `/relay`.
4. Keep `AUTH_KEY` and `EXIT_HMAC` exactly the same.
5. Save. You do not need a new deployment; the Deployment ID stays the same.

That is the whole change. The client uses the same Deployment ID, AUTH_KEY and
E2E key.

---

## Part 3 — Test

Use the same test as the Google guide:

```bash
cd ~/XHR/cmd/xhr-client
go build -o xhr-client .
./xhr-client -config config.json
```

In another tab:

```bash
curl -x socks5h://127.0.0.1:1080 https://example.com/
```

If it returns HTML, the Cloudflare exit works.

---

## How to tell which exit is active

| You changed `EXIT_URL` to | Traffic exits through |
|---|---|
| `https://xhr-exit-....run.app/relay` | Google Cloud Run |
| `https://xhr-exit....workers.dev/relay` | Cloudflare |

You can switch back and forth by editing that one property. The client does not
change.

---

## Limits and honest warnings

* **Cloudflare sees the destination.** It is the exit. End-to-end encryption
  protects the payload from Google, not from the exit.
* **Google still sees metadata**: your deployment, request sizes, timing, and
  the Worker URL.
* Cloudflare Workers Free has CPU, request, Durable Object, and TCP limits.
  Long-lived idle tunnels can be evicted; the client reconnects.
* Workers Free currently includes SQLite-backed Durable Objects and TCP
  sockets. If Cloudflare changes that or asks for a paid plan, the exit needs
  the Workers Paid plan.
* Free plan CPU is low. If `wrangler tail` shows CPU-limit errors under load,
  reduce traffic or move to Workers Paid.
* UDP does not work. TCP only.
* Speed is still limited by Google Apps Script. Cloudflare does not make Apps
  Script faster.

---

## Problems and fixes

| What you see | Meaning | Fix |
|---|---|---|
| `workers.dev` 404 | wrong path | the URL must end in `/relay` |
| 401 from the Worker | `XHR_GAS_HMAC` mismatch | paste the same `GAS=` value |
| `Durable Objects not enabled` | account/plan does not include them | check the Cloudflare Workers plan; free accounts use SQLite-backed Durable Objects |
| Session dies after a few minutes | Worker/DO eviction | reconnect; do not idle for very long |
| `connect failed` | destination refused or blocked by Cloudflare | check the destination and Worker logs with `wrangler tail` |
| Works on Google exit, not Cloudflare | secret mismatch | compare `EXIT_HMAC` in Script Properties and the Worker secret |

---

## Which one should I use?

| Situation | Use |
|---|---|
| You want everything inside Google | Google Cloud Run exit |
| You already have Cloudflare Workers | Cloudflare exit |
| Cloud Run billing is a problem | Cloudflare exit |
| You want the destination hidden from Google | either exit; Google only sees ciphertext |
| You want the simplest setup | Google Cloud Run (one `gcloud` command) |
