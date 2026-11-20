# XHR — Google + Cloudflare guide

This is the second protocol. The client and the Apps Script relay stay the same;
only the **exit** changes from Google Cloud Run to a Cloudflare Worker.

```text
client -> Google edge -> Apps Script -> Cloudflare Worker -> destination
```

Google sees ciphertext going to your Worker. Cloudflare sees the destination.

---

## Before you start

Finish **[GUIDE-GOOGLE.md](GUIDE-GOOGLE.md)** first. You need:

* the **Deployment ID**
* the **AUTH_KEY**
* the **E2E key**
* the **EXIT_HMAC** (`GAS=` value)

You also need:

* a Cloudflare account,
* a Workers plan that includes **Durable Objects** (Cloudflare may ask you to
  enable the small paid Workers plan; Durable Objects are required to keep TCP
  sessions alive between requests),
* about 15 minutes.

---

## Part 1 — Deploy the Cloudflare exit

1. Open <https://shell.cloud.google.com> (or any terminal with Node.js 18+).
2. Install Wrangler, Cloudflare’s command-line tool:

```bash
npm install -g wrangler
```

3. Sign in:

```bash
wrangler login
```

A browser tab opens. Click **Allow**.

4. Get the XHR code and enter the Worker folder:

```bash
git clone https://github.com/jackh0006/XHR.git
cd XHR/cloudflare
```

5. Store the two secrets. Replace the values with yours.

```bash
wrangler secret put XHR_E2E_KEY
```

Paste the **E2E key** and press Enter.

```bash
wrangler secret put XHR_GAS_HMAC
```

Paste the **EXIT_HMAC** (`GAS=` value) and press Enter.

6. Deploy:

```bash
wrangler deploy
```

Wrangler prints a URL like:

```text
https://xhr-exit.YOURNAME.workers.dev
```

Your **EXIT_URL** is that URL plus `/relay`:

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
* Cloudflare Workers have CPU, request duration and TCP limits. Long-lived idle
  tunnels can be evicted; the client reconnects.
* Durable Objects require a paid Workers plan on most accounts.
* UDP does not work. TCP only.
* Speed is still limited by Google Apps Script. Cloudflare does not make Apps
  Script faster.

---

## Problems and fixes

| What you see | Meaning | Fix |
|---|---|---|
| `workers.dev` 404 | wrong path | the URL must end in `/relay` |
| 401 from the Worker | `XHR_GAS_HMAC` mismatch | paste the same `GAS=` value |
| `Durable Objects not enabled` | plan does not include them | enable the Workers plan in the Cloudflare dashboard |
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
