# XHR Protocol v1

XHR tunnels arbitrary TCP through a Google Apps Script deployment to an exit
service. The GAS script is a signed, opaque forwarder. It never sees the
destination, the payload, or the tunnel key.

```text
client (SOCKS5 / TUN)
  -> HTTPS/2 to a Google edge (SNI www.google.com)
  -> script.google.com /exec  (your deployment)
  -> Cloud Run exit (HTTPS, inside Google)
  -> destination TCP
```

## Why an exit is required

Google Apps Script's `UrlFetchApp` can only fetch HTTP/HTTPS URLs. It cannot
open a raw TCP socket. Tunneling arbitrary TCP (Telegram, SSH, games, any app)
therefore needs one HTTP-speaking endpoint that *can* open TCP. That is the
exit. Phase 1 uses **Google Cloud Run**, so the whole path stays inside Google.
Phase 2 adds a **Cloudflare Worker** exit.

## Client request to GAS

```json
{
  "v": 1,
  "t": 1763000000,
  "s": "16-hex-session-id",
  "m": "hex HMAC-SHA256(AUTH_KEY, t + '.' + s)",
  "q": [
    { "n": "base64 12-byte nonce", "x": "base64 AES-256-GCM ciphertext" }
  ]
}
```

* `m` proves the client knows `AUTH_KEY` without sending it.
* `t` must be within 120 seconds of the GAS clock.
* `q` is an opaque frame list. GAS does not parse it.

GAS verifies `m`, then POSTs `{ "v":1, "s":..., "q":[...] }` to `EXIT_URL`
with `X-XHR-MAC: HMAC-SHA256(EXIT_HMAC, body)`. It returns the exit's JSON
unchanged.

## Frame plaintext (inside AES-256-GCM)

```json
{
  "o": "open|open_ok|open_err|data|close|error",
  "st": 1,
  "h": "example.com",
  "p": 443,
  "d": "base64 payload",
  "t": 1763000000,
  "r": "16-hex random",
  "z": "random padding"
}
```

`open` asks the exit to dial `h:p`. The exit answers with `open_ok` on success
or `open_err` with a short reason in `d` on failure. The client does not send
application bytes until `open_ok`, so an app's first write can never be lost
while the exit is still dialing. `data` carries bytes in either direction.
`close` releases the stream.

* Key: 32 bytes, shared only by client and exit.
* Nonce: 12 random bytes per frame, sent outside the ciphertext.
* AAD: `XHR1` + session id. A frame cannot be replayed into another session.
* `r` is a per-frame random value; the exit keeps a bounded replay set.
* `z` pads the plaintext to a random size so packet sizes do not reveal
  request lengths.
* `st` multiplexes many TCP streams inside one session. The GAS script sees
  only the outer session id, never stream ids.

## Response

```json
{ "r": [ { "n": "base64 nonce", "x": "base64 ciphertext" } ] }
```

Downlink frames use the same key, session id and frame format, with
`o = "open_ok"`, `o = "open_err"`, `o = "data"`, or `o = "close"`.

## Long polling

The exit holds an empty poll for up to 15 seconds waiting for downlink data.
This keeps one GAS `UrlFetchApp` call alive instead of polling every few
hundred milliseconds, which matters for the 20,000/day free quota.

A request that carries uplink frames returns in about 20 ms after processing
them. This lets the client pipeline upload batches while a second request
remains parked as the standing downlink poll. Several HTTP handlers may call
`collect` at the same time; each frame is delivered to exactly one response.

## Sessions and streams

* One `s` per client tunnel session. The Android engine normally runs one
  session and creates a second only after 56 concurrent streams.
* Many TCP connections share the session through `st` (1..N). The exit caps a
  session at 64 streams and 128 sessions; the client closes an idle session
  after two minutes.
* The exit enforces maximum sessions, maximum streams, idle timeouts, and a
  bounded downlink queue.
* Phase 1 Cloud Run runs a single instance so sessions stay in memory. For
  scale, use session affinity or move session state to a datastore.

## What each party sees

| Party | Sees |
|---|---|
| ISP / DPI | IP of a Google edge, TCP 443, SNI `www.google.com`, TLS/HTTP2 metadata |
| Google edge + GAS | the deployment, ciphertext frames, timing, volume, exit URL |
| Exit (Cloud Run) | destination host/port and decrypted payload |
| Destination | an IP owned by the exit provider |

The client uses a **Chrome TLS fingerprint** (uTLS) and HTTP/2 so the last mile
looks like a normal Chrome session to Google, not a custom VPN client.

Google can see that *a relay exists* and how much it moves. It cannot read the
destination or content when the exit is not Google-controlled. It can always
see metadata and the script itself; do not store secrets in the script code.

## Limits

* GAS free tier: 20,000 `UrlFetchApp` calls per day, 6-minute execution limit.
* GAS request/response body limits (keep frames small).
* Cloud Run request timeout and instance concurrency.
* Latency is at least one GAS round trip; long polling amortizes it.
* The Cloudflare Worker exit has its own TCP/CPU limits and uses Durable
  Objects for session state. Workers Free currently supports SQLite-backed
  Durable Objects and outbound TCP.
