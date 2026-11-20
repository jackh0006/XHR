# XHR — complete Cloudflare-only guide

This is the complete, self-contained path. You will use:

* **Google Apps Script** as the relay.
* **Cloudflare Workers Free** as the exit.
* The **XHR Android APK** as the client.

You will **not** use Cloud Run, `gcloud run`, a Google Cloud billing account, or
a credit card for this path.

```text
Android app / v2rayNG
        |
        |  HTTPS/2, Chrome TLS fingerprint, SNI www.google.com
        v
Google edge  ->  your Apps Script /exec
        |
        |  HTTPS, opaque encrypted frames + X-XHR-MAC
        v
Cloudflare Worker  ->  Durable Object  ->  destination TCP
```

Google sees ciphertext, timing, volume, and the Worker URL.
Cloudflare sees the destination and plaintext. The phone and the exit share the
end-to-end AES-256-GCM key. Apps Script never sees that key.

---

## 1. What you need

* A Google account (Gmail is fine). Workspace accounts may block “Anyone”
  Web App access; if so, use a personal Gmail.
* A Cloudflare account. The **Workers Free** plan is enough. Do not add a card
  for this guide.
* A terminal. Google Cloud Shell is free and already has Node.js:
  <https://shell.cloud.google.com>
* An arm64 Android phone, Android 7.0 or newer. Huawei Y9 2019 is the reference
  device.
* About 30 minutes.

You do **not** need:

* a Google Cloud project,
* Cloud Run,
* a billing account,
* a domain name,
* a public IP,
* a computer that stays on.

---

## 2. The three secrets

You need three independent secrets.

| Name | Used by | Goes in Apps Script? |
|---|---|---|
| `E2E` | Android client + Cloudflare exit | **No** |
| `GAS` | Apps Script + Cloudflare exit | Yes, as `EXIT_HMAC` |
| `AUTH_KEY` | Android client + Apps Script | Yes |

Generate them in Cloud Shell:

```bash
echo "E2E=$(openssl rand -hex 32)"
echo "GAS=$(openssl rand -hex 32)"
echo "AUTH_KEY=$(openssl rand -hex 32)"
```

You will see three 64-character hex lines. Save them in a password manager or a
private note. Do not post them in chat, screenshots, or GitHub issues.

* `E2E` is the tunnel key. It protects the destination and payload from Google.
* `GAS` is the password between Apps Script and the Worker.
* `AUTH_KEY` is the password between the Android app and Apps Script.

Never put `E2E` in Apps Script. Apps Script must stay blind.

---

## 3. Get the XHR repository

In Cloud Shell:

```bash
git clone https://github.com/jackh0006/XHR.git
cd XHR
pwd
```

`pwd` must end in `/XHR`.

If you already cloned it:

```bash
cd ~/XHR
git pull
```

The repository is public, so this clone needs no login.

---

## 4. Create the Cloudflare account

1. Open <https://dash.cloudflare.com/sign-up>.
2. Create a free account with your email.
3. Verify the email.
4. Do **not** add a paid plan or a card for this guide.
5. In the dashboard, open **Workers & Pages**.

If Cloudflare asks for a card before allowing Workers, stop. The free path is
not available on that account; use a different account or a different exit.

---

## 5. Deploy the Worker exit

### 5.1 Enter the Worker folder

```bash
cd ~/XHR/cloudflare
ls
```

You must see:

```text
worker.js
frames.js
frames.test.mjs
wrangler.toml
package.json
```

### 5.2 Install Wrangler

```bash
npm install -g wrangler
wrangler --version
```

If global install is blocked:

```bash
npx wrangler --version
```

In that case, use `npx wrangler` instead of `wrangler` in every command below.

### 5.3 Sign in to Cloudflare

```bash
wrangler login
```

A browser tab opens, or Wrangler prints a URL. Approve the login.

Verify:

```bash
wrangler whoami
```

You should see your Cloudflare account email and account ID.

### 5.4 Deploy once

```bash
wrangler deploy
```

If asked to create a `workers.dev` subdomain, accept it.

Wrangler prints a URL like:

```text
https://xhr-exit.YOURNAME.workers.dev
```

Save it. This is your Worker base URL.

If the deploy fails with `Durable Objects not enabled`, check that the account
is on the Workers Free plan. As of Cloudflare's current docs, Workers Free
includes SQLite-backed Durable Objects and outbound TCP sockets. If Cloudflare
insists on a paid plan for your account, this free path is not available there.

### 5.5 Add the two Worker secrets

The Worker must exist first. It does now.

```bash
wrangler secret put XHR_E2E_KEY
```

When asked for the value, paste your **E2E** value and press Enter.

```bash
wrangler secret put XHR_GAS_HMAC
```

Paste your **GAS** value.

Verify the names, not the values:

```bash
wrangler secret list
```

You should see both names.

### 5.6 Test the Worker health endpoint

```bash
WORKER="https://xhr-exit.YOURNAME.workers.dev"
curl -i "$WORKER/healthz"
```

Expected:

```text
HTTP/2 200
ok
```

If you get `404`, the URL is wrong.

### 5.7 Test the relay route without auth

```bash
curl -i -X POST "$WORKER/relay"
```

Expected:

```text
HTTP/2 401
Unauthorized
```

This is correct: no HMAC was sent.

A `500` usually means a secret is missing. Run `wrangler tail` in one terminal
and repeat the request in another.

### 5.8 Your EXIT_URL

```text
https://xhr-exit.YOURNAME.workers.dev/relay
```

It must end in `/relay`. Save it.

---

## 6. Create the Apps Script relay

### 6.1 New project

1. Open <https://script.google.com>.
2. Click **New project**.
3. Delete the default `function myFunction()`.
4. Rename the project to `XHR`.

### 6.2 Paste the complete relay

Open:

```text
https://raw.githubusercontent.com/jackh0006/XHR/main/gas/Code.gs
```

Copy **all** of it. Paste it into the Apps Script editor. Click **Save**.

The same complete text is also in the repository:

* `gas/Code.gs`
* `docs/FULL-TEXT.md`

### 6.3 Where to put the values — choose one method

The code supports both methods. **Use only one.**

#### Method A — Script Properties (recommended)

Leave the `CONFIG` block empty and set these in
**Project Settings → Script Properties**:

| Property | Value |
|---|---|
| `AUTH_KEY` | your `AUTH_KEY` value |
| `EXIT_URL` | `https://xhr-exit.YOURNAME.workers.dev/relay` |
| `EXIT_HMAC` | your `GAS` value |
| `MAX_ITEMS` | `64` |
| `MAX_BYTES` | `262144` |

This is the safest method because the values are not in the code.

#### Method B — `CONFIG` block (quick test)

At the top of `Code.gs`, fill the `CONFIG` block:

```javascript
var CONFIG = {
  AUTH_KEY: "your AUTH_KEY",
  EXIT_URL: "https://xhr-exit.YOURNAME.workers.dev/relay",
  EXIT_HMAC: "your GAS value",
  MAX_ITEMS: 64,
  MAX_BYTES: 262144
};
```

Do not commit a file with real values. If you use Method B, you do not need
Script Properties.

#### The one line you must never break

The relay must contain:

```javascript
var authKey = _prop("AUTH_KEY");
```

If you see:

```javascript
var authKey = _prop("");
```

it will never authenticate. Change it back to `_prop("AUTH_KEY")`.

`E2E` is not used here. It goes only in the Android app.

### 6.4 Set Script Properties

1. Click **Project Settings** (the gear on the left).
2. Scroll to **Script Properties**.
3. Add these:

| Property | Value |
|---|---|
| `AUTH_KEY` | your `AUTH_KEY` value |
| `EXIT_URL` | `https://xhr-exit.YOURNAME.workers.dev/relay` |
| `EXIT_HMAC` | your `GAS` value |
| `MAX_ITEMS` | `64` |
| `MAX_BYTES` | `262144` |

`MAX_BYTES` is optional, but setting it keeps requests inside the Apps Script
body limit.

Do **not** add `E2E`. Apps Script must never have it.

### 6.5 Deploy the Web App

1. Click **Deploy → New deployment**.
2. Click the gear next to **Select type**.
3. Choose **Web app**.
4. Set:
   * **Description:** `XHR relay`
   * **Execute as:** `Me`
   * **Who has access:** `Anyone`
5. Click **Deploy**.
6. Approve the Google authorization screens.
7. Copy the **Deployment ID** or the full `/exec` URL.

The URL looks like:

```text
https://script.google.com/macros/s/AKfycbxxxxxxxxxxxxxxxxxxxxxxxxxxxxx/exec
```

The Deployment ID is the `AKfycb...` part between `/s/` and `/exec`.

### 6.6 Quick check

Open the `/exec` URL in a browser. It returns a harmless “Moved / not found”
page from `doGet`. That is expected; the real relay is `doPost`.

### 6.7 If you change Code.gs later

Script Properties changes apply immediately. Code changes do not. After editing
the code:

1. **Deploy → Manage deployments**.
2. Edit the existing Web App deployment.
3. Choose **New version**.
4. Deploy again.

The Deployment ID stays the same.

### 6.8 Multiple deployment IDs (1 to 5)

XHR supports up to five Apps Script deployments in one profile.

You can:

* create several deployments from the same Apps Script project
  (**Deploy → New deployment** each time), or
* create separate Apps Script projects and paste the same code/properties into
  each.

Every deployment must use:

* the same `AUTH_KEY`,
* the same `EXIT_URL`,
* the same `EXIT_HMAC`.

In the Android setup screen, put one Deployment ID or `/exec` URL per line.
Then choose **Active deployments** from 1 to 5.

How many should you use?

| Active | Best for |
|---|---|
| **1** | Lowest Apps Script quota use |
| **2** | Recommended balance: one downlink poll plus upload/failover |
| **3-5** | More failover or separate Google accounts |

Important: deployments in the **same Google account share the 20,000
UrlFetchApp calls/day**. Adding five IDs does not multiply that quota. Separate
Google accounts do.

The Android engine round-robins requests across the active deployments and
retries the same encrypted frames on another deployment if one fails.

---

## 7. Install the Android app

1. Open
   <https://github.com/jackh0006/XHR/releases/latest>.
2. Download `XHR-1.0.1-arm64-v8a.apk`.
3. SHA-256:
   `2d30085be2704327290c0736a7d944496e24bd7dea2631b9cd44681ed818f891`
4. Open **Files → Downloads** and tap the APK.
5. Allow installs from this source.
6. If Play Protect warns, choose **Install anyway**.

The APK is signed by the XHR release key, arm64-v8a, `minSdk 24`,
`targetSdk 34`.

---

## 8. Configure the Android app

Open XHR. On the setup screen enter:

| Field | Value |
|---|---|
| Deployment ID or URL | Up to 5 IDs/URLs, one per line |
| Active deployments | 1–5 (`2` recommended) |
| `AUTH_KEY` | your `AUTH_KEY` value |
| Tunnel key | your `E2E` value, exactly 64 hex characters |
| Google edge | `216.239.38.120` |
| Front domain | `www.google.com` (advanced) |
| Local port | `1080` (advanced) |

Tap **Save profile**.

The profile is stored encrypted with Android Keystore. There is no account and
no cloud sync.

---

## 9. Find the best Google edge

1. Tap **Edge**.
2. Leave SNI as `www.google.com`.
3. Tap **Test all IPs**.
4. Wait for the TLS/HTTP2 results.
5. Tap **Use `<ip>`** on the fastest green row.

This only measures the network path to Google. It does not consume Apps Script
quota.

If all rows fail:

* try Wi-Fi,
* try another front domain: `mail.google.com` or `accounts.google.com`,
* try another network,
* some carriers block Google edge IPs.

After changing the IP, reconnect the tunnel.

---

## 10. Start TUN mode

1. Tap **Home**.
2. Select **TUN · all apps**.
3. Tap the power button.
4. Android asks for VPN permission. Tap **OK**.
5. The notification appears: **XHR is protecting your traffic**.
6. Open a browser and load a page.

Expected:

* Home shows **CONNECTED**.
* Upload/download totals increase.
* The notification shows live speed.
* **Test connection** succeeds and shows an exit IP.
* Logs show `CONNECT host:port`.

TUN mode uses a real Android `VpnService` interface and the source-built
`hev-socks5-tunnel` library. XHR excludes its own package so its Google
connection cannot loop through the tunnel.

### Huawei Y9 2019 battery settings

Huawei kills background apps aggressively:

1. **Settings → Battery → App launch**.
2. Find **XHR**.
3. Turn off **Manage automatically**.
4. Enable **Auto-launch**, **Secondary launch**, and **Run in background**.
5. **Settings → Apps → XHR → Battery** → allow background activity.

---

## 11. Per-app routing

Tap **Apps**:

* **All apps** — every app is tunneled except XHR.
* **Only selected** — only the checked apps are tunneled.
* **Except selected** — every app except the checked apps is tunneled.

Reconnect after changing the list.

XHR itself is always excluded.

---

## 12. SOCKS5 mode with v2rayNG

1. In XHR, select **SOCKS5 · v2rayNG**.
2. Tap the power button.
3. Home shows `127.0.0.1:1080`.
4. Open **v2rayNG**.
5. Tap **+ → Manually input → Socks**.
6. Set:
   * Address: `127.0.0.1`
   * Port: `1080`
   * Username/password: empty
   * Remarks: `XHR`
7. Save and select the profile.
8. In v2rayNG’s **Per-app proxy**, **exclude XHR**. If v2rayNG captures XHR,
   XHR’s own Google connection loops back into v2rayNG.
9. Start v2rayNG.

Traffic:

```text
app -> v2rayNG VPN -> 127.0.0.1:1080 -> XHR engine
    -> Google edge -> Apps Script -> Cloudflare Worker -> destination
```

The local SOCKS listener binds only `127.0.0.1`. It has no password, so do not
expose it with a port forward.

---

## 13. Prove the whole chain

### Android

Home → **Test connection**. It performs an HTTPS request through the local
SOCKS listener and shows the exit IP.

### Optional Linux test

Cloud Shell has Go. Create a profile:

```bash
cd ~/XHR/cmd/xhr-client
go build -o xhr-client .
nano config.json
```

Paste:

```json
{
  "gas_url": "https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec",
  "google_ip": "216.239.38.120",
  "auth_key": "YOUR_AUTH_KEY",
  "e2e_key": "YOUR_E2E",
  "listen": "127.0.0.1:1080"
}
```

Save with `Ctrl+O`, Enter, then `Ctrl+X`.

Run:

```bash
./xhr-client -config config.json
```

In a second terminal:

```bash
curl --socks5-hostname 127.0.0.1:1080 -i https://example.com/
```

If you see HTML, the full chain works.

---

## 14. Speed and stability settings

These are the settings that matter most:

| Setting | Best choice |
|---|---|
| Edge IP | Fastest green result in **Edge** |
| Mode | **TUN** for all apps, unless you specifically need v2rayNG |
| MTU | `1500`; use `1400` only if a network fragments |
| Android Private DNS | **Off** |
| Chrome Secure DNS | **Off**, or use system DNS |
| Battery | Allow XHR background activity |
| Transport | TCP; do not expect UDP/QUIC |

### Disable Private DNS

In TUN mode, XHR answers DNS through `mapdns` on `198.18.0.2:53`. Android
Private DNS tries DoT on port `853`, which XHR cannot use. It also causes log
lines like:

```text
open 198.18.0.2:853 failed
open 1.1.1.1:853 failed
```

On Android:

1. **Settings → Network & internet → Private DNS**.
2. Choose **Off** or **Automatic**.
3. Reconnect XHR.

In Chrome:

1. **Settings → Privacy and security → Use secure DNS**.
2. Turn it **off** or select **Use current service provider**.

Cloudflare Workers also block outbound TCP to Cloudflare IP ranges, so
`1.1.1.1:853`, `1.1.1.1:443`, and Cloudflare-hosted DNS names will fail from
the Worker. This is expected. Use the XHR mapdns path instead.

### Keep the tunnel stable

* Stay on TUN unless v2rayNG is required.
* Do not run two VPNs at once.
* Exclude XHR itself from v2rayNG if you use proxy mode.
* Allow XHR in Huawei battery settings.
* If a network blocks the Google edge, choose another IP in Edge.

## 15. Live logs and daily limits

Tap **Logs**:

* `CONNECT host:port` — a TCP stream opened.
* `session ... started/closed` — an Apps Script session.
* `error:` — a transport problem.
* **Copy all** and **Clear** are local only.

Home shows:

* upload/download totals and live rates,
* today’s Apps Script request count,
* optional data limit,
* active stream count.

The free Apps Script account allows about **20,000 `UrlFetchApp` calls/day**.
An idle session uses roughly 5,700 calls/day because the exit long-polls.
Cloudflare Workers Free allows **100,000 requests/day** and SQLite Durable
Objects. Apps Script is the tighter quota.

Set **Settings → Daily safety limit** to stop the tunnel at a chosen size.

---

## 16. Troubleshooting

| Symptom | Meaning | Fix |
|---|---|---|
| `wrangler: command not found` | npm global bin missing | use `npx wrangler ...` |
| `wrangler login` cannot open browser | Cloud Shell | open the printed URL manually |
| `wrangler deploy` says Durable Objects not enabled | account/plan problem | use Workers Free with SQLite DO, or a different Cloudflare account |
| Worker `/healthz` returns 404 | wrong URL | copy the exact `workers.dev` URL |
| Worker `/healthz` returns 500 | missing secret or code error | `wrangler secret list`, `wrangler tail` |
| POST `/relay` returns 401 | no HMAC sent | expected; not an error |
| POST `/relay` returns 500 | secret/config error | check both secrets |
| Apps Script returns decoy HTML for `/exec` | normal GET | relay is `doPost`; use the app test |
| App says `relay HTTP 401` | `AUTH_KEY`/`EXIT_HMAC` mismatch | copy again, no spaces |
| App says `expired` | phone clock wrong | enable automatic time |
| `no session` | exit evicted/restarted | reconnect |
| `Durable Objects not enabled` | plan issue | see above |
| Works then stops | Apps Script daily quota | wait for reset or add another deployment |
| TUN won’t start | VPN permission | tap power again, allow Android VPN prompt |
| Port 1080 busy | another proxy/VPN | stop it or change port in Settings |
| v2rayNG loops | XHR not excluded | exclude XHR in v2rayNG per-app |
| Cloudflare CPU error | free 10 ms CPU | reduce load or use Workers Paid |
| Session dies after long idle | Worker/DO eviction | reconnect; the client reconnects automatically |

Useful commands:

```bash
cd ~/XHR/cloudflare
wrangler tail
wrangler deployments list
wrangler secret list
```

---

## 17. Security and honest limits

* End-to-end **AES-256-GCM**. Google and Cloudflare never get the `E2E` key.
* **HMAC-SHA256** on client → GAS and GAS → Worker, with a 120-second window.
* Session-bound AES-GCM AAD; replayed frames are rejected.
* uTLS Chrome fingerprint, HTTP/2, SNI `www.google.com` on the last mile.
* Android profile encrypted with Android Keystore.
* Local SOCKS listener binds loopback only.
* The ISP sees a Google IP, TCP 443, SNI `www.google.com`.
* Google sees metadata: the deployment, timing, sizes, Worker URL.
* Cloudflare sees destinations and plaintext.
* **TCP only.** Apps Script has no UDP; QUIC fails and apps fall back to TCP.
* Speed is limited by Apps Script, not Cloudflare.
* This is censorship resistance, not anonymity.
* Cloudflare Workers Free has CPU, request, Durable Object, and open-connection
  limits. If Cloudflare changes the free plan, the Worker may need Workers Paid.

---

## 18. Update, rotate, or remove

### Rotate secrets

* `E2E`: change `XHR_E2E_KEY` in the Worker, then update every client. Restart
  the Worker deployment. Old clients stop working.
* `GAS`: change `XHR_GAS_HMAC` in the Worker and `EXIT_HMAC` in Apps Script.
* `AUTH_KEY`: change it in Apps Script and every client. Old clients stop.

### Update the Worker

```bash
cd ~/XHR
git pull
cd cloudflare
wrangler deploy
```

Secrets stay in place.

### Remove the Worker

```bash
cd ~/XHR/cloudflare
wrangler delete
```

### Remove the Apps Script relay

Delete the Apps Script project, or delete the Web App deployment. That revokes
the client’s relay.

### Reset the Android profile

Home → gear → **Reset profile**. This deletes keys and counters from the phone.

---

## 19. File map

| Path | What it is |
|---|---|
| `gas/Code.gs` | Complete Apps Script relay |
| `cloudflare/worker.js` | Complete Worker + Durable Object exit |
| `cloudflare/frames.js` | Worker AES-GCM/HMAC helpers |
| `cloudflare/wrangler.toml` | Worker/DO configuration |
| `docs/FULL-TEXT.md` | All deployable source texts in one page |
| `docs/GUIDE-CLOUDFLARE.md` | This guide |
| `docs/GUIDE-ANDROID.md` | Android app details |
| `docs/TEST-CHECKLIST.md` | On-device checks |
| `cmd/xhr-client` | Linux SOCKS5 preview client |
| `android/` | Complete Android app source |

---

## 20. Free-plan reality check

This path is free **if** Cloudflare Workers Free on your account includes:

* SQLite-backed Durable Objects,
* outbound TCP sockets,
* enough CPU for AES-GCM frames,
* 100,000 Worker requests/day and the Durable Object free limits.

Cloudflare’s current documentation says Workers Free includes SQLite-backed
Durable Objects and TCP sockets. Plans can change. If your account is asked for
a card or a paid plan, the free Cloudflare exit is not available there.

Apps Script is free within its daily quotas. There is no Google Cloud billing
in this path.
