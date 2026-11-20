# Apps Script relay

`Code.gs` is the **complete** Apps Script relay. Copy the whole file into a new
Apps Script project. Do not trim it.

It does three things:

1. Verifies the client HMAC.
2. Rejects stale requests.
3. Forwards opaque encrypted frames to `EXIT_URL` with a second HMAC.

It never sees the destination, the payload, or the tunnel key.

Required Script Properties:

| Property | Meaning |
|---|---|
| `AUTH_KEY` | Shared with the XHR client |
| `EXIT_URL` | Cloud Run or Worker URL ending in `/relay` |
| `EXIT_HMAC` | Shared with the exit |

Optional:

| Property | Default |
|---|---|
| `MAX_ITEMS` | `64` |
| `MAX_BYTES` | `262144` |

Full setup: [docs/GUIDE-GOOGLE.md](../docs/GUIDE-GOOGLE.md)

Full copy-paste text: [docs/FULL-TEXT.md](../docs/FULL-TEXT.md)
