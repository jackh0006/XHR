# XHR on Android — the very simple guide

This guide is for the **XHR Android app**. It is built for the Huawei Y9 2019 and
other **arm64** Android phones. It does not need root.

The app has two modes:

| Mode | What it does | Use it when |
|---|---|---|
| **TUN** | Sends every TCP app through XHR | You want one switch for the whole phone |
| **SOCKS5** | Opens `127.0.0.1:1080` | You want v2rayNG or another client to use XHR |

It is the same encrypted protocol either way.

---

## Before you start

You need the values from **[GUIDE-GOOGLE.md](GUIDE-GOOGLE.md)**:

* Deployment ID (starts with `AKfycb`) or the full `/exec` URL
* `AUTH_KEY`
* the 64-character tunnel key (`E2E`)
* a Google edge IP (the guide uses `216.239.38.120`)

The Google and Cloudflare exits both work with this app. If you use
Cloudflare, finish **[GUIDE-CLOUDFLARE.md](GUIDE-CLOUDFLARE.md)** first.

> XHR is TCP only. Apps Script cannot carry UDP. QUIC and UDP calls will fall
> back or fail. The app blocks QUIC at the transport level by refusing UDP
> through the SOCKS listener, so browsers usually fall back to TCP.

---

## 1 — Install the APK

1. On the phone, open
   <https://github.com/jackh0006/XHR/releases/latest>.
2. Download `XHR-android-arm64-1.0.2.apk`.
   SHA-256: `549d3bb49f0dcd851709c38da2b61e1afdb12d683e80a89190eb8a3d43432a0f`
3. Open **Files → Downloads**, tap the APK.
4. Android asks to allow installs from this source. Allow it for Files or
   Chrome, then tap **Install**.
5. If Play Protect says the app is unknown, choose **Install anyway**. The APK
   is signed by the XHR release key and the SHA-256 is published with the
   release.

The release includes **arm64-v8a**, **armeabi-v7a**, **x86**, **x86_64**, and
a **universal** APK. Use arm64 for the Huawei Y9 2019 and most modern phones.
Use universal if you are unsure.

---

## 2 — First setup

1. Open **XHR**.
2. The setup page asks for your deployment IDs, AUTH_KEY, tunnel key, and edge IP.
3. Paste the **Deployment ID** (or full `/exec` URL).
4. Paste **AUTH_KEY**.
5. Paste the **64-character tunnel key**.
6. Leave the Google edge as `216.239.38.120` for now.
7. Tap **Save profile**.

Everything is stored with an Android Keystore AES-256-GCM key. There is no
account and no cloud sync.

If you see “Tunnel key must be exactly 64 hex characters”, the key is not the
`E2E` value. It is not the `AUTH_KEY`. It is the 64-character hex key you made
in the Google guide.

---

## 3 — Find the best Google edge

1. Tap **Edge** at the bottom.
2. Leave the SNI as `www.google.com`.
3. Tap **Test all IPs**.
4. Wait. Each row is a real TLS 1.2+/HTTP2 handshake.
5. The fastest green row is shown at the top.
6. Tap **Use `<ip>`**.

This test uses mobile data or Wi-Fi, whichever the phone is on. It does not
consume Apps Script quota. It only measures the path to Google, not the exit
speed.

If every row is red, try:

* Wi-Fi instead of mobile data,
* a different front domain (`www.google.com`, `mail.google.com`,
  `accounts.google.com`),
* a different network. Some carriers block Google edge IPs or the SNI.

---

## 4 — Start TUN mode

1. Tap **Home**.
2. Select **TUN · all apps**.
3. Tap the big power button.
4. Android shows a VPN connection request. Tap **OK**.
5. The notification appears: **XHR is protecting your traffic**.
6. Open a browser and load a page.

What you should see:

* Home says **CONNECTED** and shows live upload/download.
* The notification shows live speed and totals.
* **Today** counts Apps Script requests and data.
* **Live throughput** draws a graph.
* **Test connection** fetches HTTPS through the relay and shows the exit IP.

The first page may be slow. XHR adds one Apps Script round trip. After the
session is warm, browsing and messaging are usable. Video and games are not.

### If TUN will not start

| Message | Fix |
|---|---|
| “VPN permission is required” | Tap the power button again and allow the Android VPN prompt |
| “port 1080 is busy” | Stop the other VPN/proxy app, or change the port in Settings |
| “Tunnel engine stopped” | Open Logs. Check that the Google exit is deployed and `EXIT_HMAC` matches |
| “connection problem: relay HTTP 401” | `AUTH_KEY` or the exit HMAC does not match |
| “expired” | Turn on automatic date and time |
| “no session” | The Cloud Run exit restarted. Reconnect |
| Everything times out | Try a different Google edge IP in Edge |
| Works, then stops after hours | GAS daily quota or Huawei battery saver. See below |

### Huawei Y9 2019 battery settings

Huawei aggressively kills background apps. XHR runs as a foreground VPN, which
is usually enough, but for a stable all-day session:

1. **Settings → Battery → App launch**.
2. Find **XHR**.
3. Turn off **Manage automatically**.
4. Enable **Auto-launch**, **Secondary launch**, and **Run in background**.
5. **Settings → Apps → XHR → Battery** → allow background activity.

This is a Huawei rule, not an XHR bug.

---

## Deployment pool

You can add up to five Apps Script deployments. Each can point to the same
Worker, or to a different Worker under a different Cloudflare account.

- All deployments use the same `AUTH_KEY`.
- All Workers used by this phone use the same `E2E`.
- Each GAS deployment can have its own `EXIT_URL` and `EXIT_HMAC`.

Choose **2 active** deployments for one phone. More active deployments do not
make Apps Script faster; they add failover and can use separate provider
quotas.

## 5 — Per-app routing

1. Tap **Apps**.
2. Choose:
   * **All apps** — everything is tunneled except XHR itself.
   * **Only selected** — only the apps you check are tunneled.
   * **Except selected** — everything except the checked apps is tunneled.
3. Check apps.
4. Reconnect the tunnel so Android applies the new rules.

XHR always excludes itself. That is what prevents a loop: XHR's own HTTPS
connection to Google must not go back into XHR.

In **SOCKS5** mode this screen does not control v2rayNG. v2rayNG has its own
per-app screen; see the next section.

---

## 6 — SOCKS5 mode with v2rayNG

Use this when you want v2rayNG to own the VPN and XHR to be its outbound.

1. In XHR, select **SOCKS5 · v2rayNG**.
2. Tap the power button.
3. Copy the address shown on Home: `127.0.0.1:1080`.
4. Open **v2rayNG**.
5. Tap **+ → Manually input → Socks**.
6. Set:
   * **Address:** `127.0.0.1`
   * **Port:** `1080`
   * **Username / Password:** leave empty
   * **Remarks:** `XHR`
7. Save and select the XHR profile.
8. **Important:** open v2rayNG's **Per-app proxy** settings and **exclude XHR**.
   If v2rayNG captures the XHR app, XHR's own Google connection goes back into
   v2rayNG and loops.
9. Start v2rayNG's VPN.

Traffic now goes:

```text
app → v2rayNG VPN → SOCKS5 127.0.0.1:1080 → XHR engine
    → Google edge → Apps Script → exit → destination
```

XHR still does the end-to-end AES-256-GCM encryption and Chrome TLS
fingerprint. v2rayNG only forwards bytes to the local SOCKS listener.

If v2rayNG has a “domain strategy” setting, use **AsIs** or **UseIP** so names
are sent to XHR instead of being leaked to the local resolver.

### Test it

Home → **Test connection**. It opens a real HTTPS request through the local
SOCKS listener and shows the exit IP. If that works, v2rayNG will work.

---

## 7 — Live logs

Tap **Logs**:

* **Live** means the service is running.
* `CONNECT host:port` is a TCP stream opening.
* `session … started/closed` is an Apps Script session.
* `error:` lines are transport failures.
* **Copy all** copies the visible text.
* **Clear** removes local logs.

Logs contain destination names. They stay on the phone and are never uploaded.
If you post a log publicly, remove private hostnames.

---

## 8 — Daily limits

The free Google Apps Script account allows about **20,000 `UrlFetchApp` calls
per day**. XHR shows the count on Home. It counts every request the engine
makes, including standing long polls.

* Idle TUN session: roughly 5,700 calls/day with one standing poll.
* Active use adds one call per upload batch and per downlink response.
* If you reach the quota, Google returns errors until the daily reset.
* You can lower data use in **Settings → Daily safety limit**. XHR stops the
  tunnel when today's upload + download reaches that size.

The counters are local. They reset at local midnight.

---

## 9 — Security and honest limits

* **End-to-end AES-256-GCM.** The exit and the phone share the key. Google and
  Cloudflare never get it.
* **HMAC-SHA256** authenticates client → GAS and GAS → exit. Replay is rejected.
* **Android Keystore** protects the profile at rest.
* **Loopback only.** The SOCKS listener binds `127.0.0.1`; other devices cannot
  connect to it.
* **No analytics, no accounts, no telemetry.**
* The ISP sees a Google IP, TCP 443, SNI `www.google.com`, and a Chrome TLS
  fingerprint.
* **Google sees metadata**, including that a relay exists, request sizes,
  timing, the deployment, and the exit URL. It cannot see destinations or
  content.
* **The exit sees destinations and plaintext.** It has to, to connect.
* **UDP does not work.** QUIC, calls, and UDP games fall back or fail.
* **It is not anonymity.** It is censorship resistance for TCP.

If you need anonymity, do not use XHR. Use Tor.

---

## 10 — Uninstall or reset

* **Reset profile:** Home → gear → Profile → **Reset profile**. This deletes
  the keys and counters from the phone.
* **Uninstall:** long-press the icon → App info → Uninstall. The VPN profile
  disappears with the app.
* **Disable the exit:** delete the Cloud Run service and the Apps Script
  deployment. That revokes the relay for every client.

---

## Still stuck?

Open an issue with the **Logs** text, the Android version, the phone model,
and the exact error. Remove keys, deployment IDs, and private hostnames first.

Repository: <https://github.com/jackh0006/XHR>
