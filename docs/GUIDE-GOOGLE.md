# XHR — the very simple Google-only guide

This guide assumes you have never used a terminal. Follow the steps in order.
Do not skip any step.

You will make two things:

1. **The exit** — a small program on Google Cloud that opens the real internet
   connections.
2. **The relay** — a Google Apps Script that your client talks to.

Then you will test it once. After that, the client app can use it.

> Upgrading from XHR 0.1.0? Deploy the exit again from tag `v1.0.0`. The new
> Android client waits for an `open_ok` acknowledgement that the old binary
> does not send. Apps Script does not need a new deployment.

---

## What you need

* A Google account (Gmail is fine).
* A payment method on Google Cloud. Google gives free usage each month, but it
  asks for a card when you create a billing account. You can set a budget alert.
* About 30 minutes.
* A Linux computer or Google Cloud Shell for the test.

> XHR cannot work with Apps Script alone. Apps Script can only fetch websites;
> it cannot open a raw TCP connection. That is why we make the exit first.

---

## Part 1 — Make the exit (10 minutes)

1. Open <https://shell.cloud.google.com> in a browser. Sign in with your Google
   account.
2. The first time, click **Authorize** and **Continue**.
3. A black terminal opens at the bottom. You will paste a few commands.

### 1.1 Get the XHR code

Paste this and press **Enter**:

```bash
git clone https://github.com/jackh0006/XHR.git
cd XHR
```

### 1.2 Make two secrets

Paste this and press **Enter**:

```bash
echo "E2E=$(openssl rand -hex 32)"
echo "GAS=$(openssl rand -hex 32)"
```

You will see two lines. **Copy them to a safe place.** You will need them again.

Example (yours will be different):

```text
E2E=8f3c...64 characters...
GAS=1a9d...64 characters...
```

* `E2E` is the tunnel key. Only the exit and the client get it.
* `GAS` is the password between Apps Script and the exit.

### 1.3 Pick a Google Cloud project

In the same Cloud Shell terminal, check which project is active:

```bash
gcloud config get-value project
```

If it prints a real project ID, you can continue.

If it prints `(unset)`, create one:

1. Open <https://console.cloud.google.com> in another tab.
2. Create a project named `XHR Relay` (the display name must be 4–30
   characters). Google will assign a project ID like `xhr-relay-123456`.
   Project IDs are 6–30 characters, lowercase, and globally unique.
3. Open **Billing** and link a billing account to that project. Cloud Run
   needs billing enabled, even when the free tier covers the usage.
4. Return to Cloud Shell and set the project. Replace `YOUR_PROJECT_ID` with
   the ID from the console:

```bash
gcloud config set project YOUR_PROJECT_ID
gcloud config get-value project
```

The second command must print your project ID. Do not continue until it does.

> The old line
> `gcloud config set project "$(gcloud config get-value project)"` did not
> create a project. It only re-set an existing one. Use the ID from the
> console instead.

### 1.4 Deploy the exit

First make sure you are inside the XHR folder:

```bash
pwd
```

It must end in `/XHR`. If it does not:

```bash
cd ~/XHR
```

Now replace the two values below with your own from step 1.2, then paste the
whole command and press **Enter**. The values are the 64 characters after
`E2E=` and `GAS=`. Do not add quotes or spaces.

```bash
gcloud run deploy xhr-exit \
  --source . \
  --region us-central1 \
  --allow-unauthenticated \
  --max-instances 1 \
  --concurrency 80 \
  --timeout 300 \
  --set-env-vars XHR_E2E_KEY=PASTE_E2E_HERE,XHR_GAS_HMAC=PASTE_GAS_HERE
```

The `--source .` part means “build the Dockerfile in the current folder”. That
is why `pwd` must show `.../XHR`.

What happens next:

1. If asked to enable APIs, type `y` and press **Enter**.
2. Cloud Shell uploads the folder, builds the container, and deploys it. The
   first build takes a few minutes.
3. It prints a **Service URL** like:

```text
https://xhr-exit-abc123-uc.a.run.app
```

If you do not see the URL, get it again:

```bash
gcloud run services describe xhr-exit \
  --region us-central1 \
  --format='value(status.url)'
```

Test it:

```bash
URL=$(gcloud run services describe xhr-exit \
  --region us-central1 \
  --format='value(status.url)')
echo "$URL/healthz"
curl -i "$URL/healthz"
```

You should see `HTTP/2 200` and the body `ok`.

Your **EXIT_URL** is the service URL with `/relay` at the end:

```bash
echo "$URL/relay"
```

Example:

```text
https://xhr-exit-abc123-uc.a.run.app/relay
```

Copy that `/relay` URL. It goes into the Apps Script `EXIT_URL` property in
Part 2.

If Cloud Run says `Billing account not found`, link a billing account to the
project in the Google Cloud console and run the deploy command again.

---

## Part 2 — Make the Google relay (10 minutes)

1. Open <https://script.google.com> in a new tab.
2. Click **New project**.
3. Delete everything in the editor.
4. Open the file `gas/Code.gs` from the XHR repository and copy all of it.
5. Paste it into the editor.
6. Click the **floppy disk** icon to save. Name it `xhr`.
7. Click **Project Settings** (the gear on the left).
8. Scroll to **Script Properties** and click **Add script property** four times:

| Property | Value |
|---|---|
| `AUTH_KEY` | make up a long random password for clients (example: `openssl rand -hex 32` on your computer) |
| `EXIT_URL` | the `https://.../relay` URL from Part 1 |
| `EXIT_HMAC` | the `GAS=` value from step 1.2 |
| `MAX_ITEMS` | `64` |

9. Go back to **Editor**.
10. Click **Deploy → New deployment**.
11. Click the gear next to “Select type” and choose **Web app**.
12. Set:
    * **Execute as:** Me
    * **Who has access:** Anyone
13. Click **Deploy**. Approve the Google screens.
14. Copy the **Deployment ID** or the full `/exec` URL. It starts with
    `AKfycb...`.

You now have:

* **Deployment ID** — for the client
* **AUTH_KEY** — for the client
* **E2E key** — the `E2E=` value, for the client
* **EXIT_URL** — stored only in Apps Script

---

## Part 3 — Test it once (10 minutes)

You can test from Google Cloud Shell. It already has Go.

1. In Cloud Shell, paste:

```bash
cd ~/XHR/cmd/xhr-client
go build -o xhr-client .
```

2. Make a file called `config.json`:

```bash
nano config.json
```

Paste this, replacing the values with yours:

```json
{
  "gas_url": "https://script.google.com/macros/s/PASTE_DEPLOYMENT_ID/exec",
  "google_ip": "216.239.38.120",
  "auth_key": "PASTE_AUTH_KEY",
  "e2e_key": "PASTE_E2E_KEY",
  "listen": "127.0.0.1:1080"
}
```

Save with `Ctrl+O`, Enter, then `Ctrl+X`.

3. Run it:

```bash
./xhr-client -config config.json
```

4. In a second Cloud Shell tab, test:

```bash
curl -x socks5h://127.0.0.1:1080 https://example.com/
```

If you see HTML from example.com, everything works.

---

## What you have now

| Value | Give it to the client? |
|---|---|
| Deployment ID | Yes |
| AUTH_KEY | Yes |
| E2E key | Yes |
| EXIT_URL | No — it stays inside Apps Script |
| EXIT_HMAC | No — it stays between Apps Script and the exit |

The client will be the Android app and the Linux client. The Android app is the
next phase of this project.

---

## Problems and fixes

| What you see | What it means | Fix |
|---|---|---|
| `Permission denied` in Cloud Shell | not authorized | run `gcloud auth login` and try again |
| `Billing account not found` | Google Cloud has no card | create a billing account and a budget alert |
| `exit 401` in the client | `EXIT_HMAC` does not match | use the same `GAS=` value in Script Properties and Cloud Run |
| `denied` in the client | `AUTH_KEY` is wrong | copy it again, no spaces |
| `expired` | phone clock is wrong | enable automatic time |
| `no session` | the exit restarted | reconnect; Cloud Run keeps one instance for this reason |
| Everything times out | Google edge IP blocked | change `google_ip` in the client to another Google IP |
| Works, then stops after a while | GAS daily quota | wait for the reset or add a second deployment |

---

## Limits in plain words

* It does **not** hide you from Google. Google sees that a relay exists and how
  much data it carries. It cannot read the destination or the content.
* The exit sees the destination. That is unavoidable: it has to connect.
* It is **TCP only**. Apps that insist on UDP (QUIC, some calls) will not work.
* It is **not fast** like a normal VPN. Every packet goes through Google Apps
  Script. Messaging, browsing, SSH, and APIs are fine. Video and games are not.
* The free Google tier has daily limits. Heavy use needs more deployments or a
  paid setup.
