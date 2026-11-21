# XHR Linux client — Debian package

The Linux client is a loopback-only SOCKS5 proxy. It carries TCP through Apps
Script to your Cloudflare Worker exit. It does not change system proxy settings,
does not enable itself at boot, and does not support UDP/QUIC.

## Install

```bash
sudo apt install ./XHR-1.0.1-linux-amd64.deb
```

The package installs:

| Path | Purpose |
|---|---|
| `/usr/bin/xhr-client` | SOCKS5 client |
| `/usr/lib/systemd/user/xhr-client.service` | optional per-user service |
| `/usr/share/doc/xhr-client/examples/config.json` | example profile |
| `/usr/share/applications/xhr-client.desktop` | desktop launcher |
| `/usr/share/icons/hicolor/1024x1024/apps/xhr.png` | icon |

## Configure

```bash
install -Dm600 /usr/share/doc/xhr-client/examples/config.json \
  ~/.config/xhr-client/config.json
editor ~/.config/xhr-client/config.json
```

Example with two deployments:

```json
{
  "gas_urls": [
    "https://script.google.com/macros/s/DEPLOYMENT_ID_1/exec",
    "https://script.google.com/macros/s/DEPLOYMENT_ID_2/exec"
  ],
  "gas_host": "script.google.com",
  "front_domain": "www.google.com",
  "google_ip": "216.239.38.120",
  "auth_key": "YOUR_AUTH_KEY",
  "e2e_key": "YOUR_64_HEX_E2E_KEY",
  "listen": "127.0.0.1:1080"
}
```

You can put 1–5 deployment URLs in `gas_urls`. They may point to different
Workers and different Cloudflare accounts, but every Worker must use the same
`E2E` key. `auth_key` is the same on every Apps Script deployment.

Set the file private:

```bash
chmod 600 ~/.config/xhr-client/config.json
```

## Run in a terminal

```bash
xhr-client
```

Test in another terminal:

```bash
curl --socks5-hostname 127.0.0.1:1080 https://example.com/
```

## Run as a user service

```bash
systemctl --user enable --now xhr-client
systemctl --user status xhr-client
journalctl --user -u xhr-client -f
```

Stop and disable:

```bash
systemctl --user disable --now xhr-client
```

## Files and logs

* Logs go to the journal when using the user service.
* The SOCKS listener binds `127.0.0.1:1080` only.
* Destinations and keys are never sent to a third party beyond the configured
  relay/exit.
* Do not expose port 1080 to a LAN or the internet.

## Update

```bash
sudo apt install ./XHR-1.0.1-linux-amd64.deb
systemctl --user restart xhr-client
```

The profile in `~/.config/xhr-client/config.json` is preserved.

## Uninstall

```bash
systemctl --user disable --now xhr-client
sudo apt remove xhr-client
rm -rf ~/.config/xhr-client
```

## Limits

* TCP only. UDP and QUIC are not supported.
* Speed is limited by Apps Script.
* The exit provider sees destinations.
* Google sees metadata and the Worker URL.
