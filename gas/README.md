# Apps Script relay

`Code.gs` is the **complete** Apps Script relay. Copy the whole file into a new
Apps Script project. Do not trim it.

It does three things:

1. Verifies the client HMAC.
2. Rejects stale requests.
3. Forwards opaque encrypted frames to `EXIT_URL` with a second HMAC.

It never sees the destination, the payload, or the tunnel key.

## Two configuration methods

### Recommended: Script Properties

Leave the `CONFIG` block empty and set:

| Property | Meaning |
|---|---|
| `AUTH_KEY` | Shared with the XHR client |
| `EXIT_URL` | Worker URL ending in `/relay` |
| `EXIT_HMAC` | Shared with the exit |
| `MAX_ITEMS` | `64` |
| `MAX_BYTES` | `262144` |

### Quick test: `CONFIG` block

Fill the block at the top of `Code.gs`:

```javascript
var CONFIG = {
  AUTH_KEY: "your AUTH_KEY",
  EXIT_URL: "https://your-worker.workers.dev/relay",
  EXIT_HMAC: "your GAS value"
};
```

Values in `CONFIG` override Script Properties. Do not commit real values.

The authentication line must always be:

```javascript
var authKey = _prop("AUTH_KEY");
```

Never change it to `_prop("")`.

Full setup: [docs/GUIDE-GOOGLE.md](../docs/GUIDE-GOOGLE.md)

Full copy-paste text: [docs/FULL-TEXT.md](../docs/FULL-TEXT.md)
