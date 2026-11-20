# XHR Android test checklist

Use this checklist on the phone. A desktop build cannot prove VPN routing,
v2rayNG chaining, or carrier behaviour.

## Before testing

- [ ] `dist/XHR-1.0.1-arm64-v8a.apk.sha256` matches the downloaded APK.
- [ ] The phone is arm64 (Huawei Y9 2019: yes).
- [ ] Google exit is deployed and `/healthz` returns `ok`.
- [ ] GAS has `AUTH_KEY`, `EXIT_URL`, `EXIT_HMAC`.
- [ ] A second Android device can load a normal web page.

## Install and setup

- [ ] APK installs as `com.jackh0006.xhr` version `1.0.1`.
- [ ] Icon is the white/Google-colour X.
- [ ] Setup accepts deployment ID, AUTH_KEY, 64-hex tunnel key, IP.
- [ ] Wrong tunnel key is rejected before saving.
- [ ] Reopen app: values are still there.
- [ ] Airplane mode toggle: app does not crash.

## Edge lab

- [ ] Edge → Test all IPs finishes in under 60 s on Wi-Fi.
- [ ] At least one row is green.
- [ ] Tap Use best IP → Home shows the new IP.
- [ ] Custom SNI `mail.google.com` also returns a result or a useful error.

## TUN mode

- [ ] Home → TUN → power → Android VPN prompt appears.
- [ ] Notification says “XHR is protecting your traffic”.
- [ ] Notification updates upload/download speed every second.
- [ ] Home shows CONNECTED and growing totals.
- [ ] Home → Test connection returns HTTP success and an exit IP.
- [ ] Browser loads `https://example.com`.
- [ ] Browser loads a video site over TCP (may be slow).
- [ ] Home → Logs shows CONNECT lines.
- [ ] Notification Stop action disconnects.
- [ ] Reconnect works without rebooting.

## Per-app routing

- [ ] ALL mode: a second browser is tunneled.
- [ ] ONLY mode with one app: only that app loads through the tunnel.
- [ ] ONLY mode with no apps: start fails with a clear message.
- [ ] EXCEPT mode: selected app bypasses XHR; others still tunnel.
- [ ] XHR itself never loops (logs do not recursively grow).

## SOCKS5 + v2rayNG

- [ ] Home → SOCKS5 → power → Home shows `127.0.0.1:1080`.
- [ ] On the phone: `Test connection` succeeds.
- [ ] v2rayNG adds a Socks server at `127.0.0.1:1080`.
- [ ] v2rayNG per-app **excludes XHR**.
- [ ] v2rayNG connects; a browser loads a page.
- [ ] Stop XHR; v2rayNG fails cleanly (no crash/loop).
- [ ] Restart XHR; v2rayNG reconnects.

## Limits and diagnostics

- [ ] Today request count increases while browsing.
- [ ] Daily data limit stops the tunnel with a clear error.
- [ ] Reset today's counters works.
- [ ] Logs Copy all and Clear work.
- [ ] Settings survives rotation and app restart.
- [ ] `About` shows version 1.0.1 and all 14 donation rows.
- [ ] Copy a donation address and paste it elsewhere.
- [ ] No analytics traffic in a network capture (only the configured relay).
- [ ] Airplane mode → error, then network back → reconnect.

## Huawei Y9 2019 specifically

- [ ] Battery → App launch → XHR set to manual/allow background.
- [ ] Screen off for 30 minutes: notification still updates.
- [ ] Wi-Fi ↔ mobile data switch: tunnel recovers or reports a clear error.
- [ ] Battery saver on: service stays in the foreground.

## Failure report

Include:

- phone model and Android version,
- XHR version,
- exit type (Cloud Run or Cloudflare),
- exact error text,
- Logs text with keys and deployment IDs removed.

Open an issue at <https://github.com/jackh0006/XHR/issues>.
