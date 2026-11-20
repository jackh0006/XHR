/**
 * XHR — Google Apps Script relay (opaque frame forwarder)
 *
 * The client sends authenticated, end-to-end encrypted frames. This script
 * cannot read the destination, the payload, or the tunnel key. It only
 * verifies its own request signature and forwards the opaque frame list to
 * the XHR exit service configured in Script Properties.
 *
 * Required Script Properties:
 *   AUTH_KEY      long random secret shared with the XHR client
 *   EXIT_URL      https URL of the XHR exit service (Cloud Run or other)
 *   EXIT_HMAC     long random secret shared with the exit service
 *
 * Optional:
 *   MAX_ITEMS     default 64
 *   MAX_BYTES     default 262144
 *
 * There are two ways to configure this file:
 *
 * 1. Recommended: leave CONFIG empty and set Script Properties:
 *      AUTH_KEY, EXIT_URL, EXIT_HMAC, MAX_ITEMS, MAX_BYTES
 *
 * 2. For a quick test: fill CONFIG below. Values in CONFIG override
 *    Script Properties. Do not publish a file with real values in CONFIG.
 */

var CONFIG = {
  // AUTH_KEY: "64-character hex secret",
  // EXIT_URL: "https://your-worker.workers.dev/relay",
  // EXIT_HMAC: "64-character hex secret",
  // MAX_ITEMS: 64,
  // MAX_BYTES: 262144
};

var DEFAULTS = { MAX_ITEMS: 64, MAX_BYTES: 262144 };

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return _json({ e: "bad request" });
    }
    var body = e.postData.contents;
    if (body.length > _propInt("MAX_BYTES")) {
      return _json({ e: "too large" });
    }
    var req = JSON.parse(body);
    if (!req || req.v !== 1 || typeof req.t !== "number" ||
        typeof req.s !== "string" || typeof req.m !== "string" ||
        !Array.isArray(req.q)) {
      return _json({ e: "bad request" });
    }

    // Replay window: 120 seconds.
    var now = Math.floor(Date.now() / 1000);
    if (Math.abs(now - req.t) > 120) {
      return _json({ e: "expired" });
    }

    // Authenticate the client without exposing AUTH_KEY in the request.
    var authKey = _prop("AUTH_KEY");
    if (!authKey) return _json({ e: "not configured" });
    var expected = _hmacHex(authKey, req.t + "." + req.s);
    if (!_safeEqual(expected, req.m)) {
      return _decoy();
    }

    if (req.q.length > _propInt("MAX_ITEMS")) {
      return _json({ e: "too many frames" });
    }

    var exitUrl = _prop("EXIT_URL");
    var exitHmac = _prop("EXIT_HMAC");
    if (!exitUrl || !exitHmac) return _json({ e: "not configured" });

    var payload = JSON.stringify({ v: 1, s: req.s, q: req.q });
    var signature = _hmacHex(exitHmac, payload);

    var resp = UrlFetchApp.fetch(exitUrl, {
      method: "post",
      contentType: "application/json",
      payload: payload,
      headers: { "X-XHR-MAC": signature },
      muteHttpExceptions: true,
      followRedirects: false,
      validateHttpsCertificates: true,
      escaping: false
    });

    var code = resp.getResponseCode();
    if (code < 200 || code > 299) {
      return _json({ e: "exit " + code });
    }
    return _json(JSON.parse(resp.getContentText()));
  } catch (err) {
    return _json({ e: "relay error" });
  }
}

function doGet() {
  // A normal-looking endpoint for anything that probes the deployment.
  return _decoy();
}

function _decoy() {
  var html = "<!doctype html><html><head><title>Google</title></head>" +
    "<body><h1>Moved</h1><p>The page you requested was not found.</p></body></html>";
  return ContentService.createTextOutput(html)
    .setMimeType(ContentService.MimeType.HTML);
}

function _json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function _prop(name) {
  var embedded = CONFIG[name];
  if (embedded !== undefined && embedded !== null && embedded !== "") {
    return String(embedded);
  }
  return PropertiesService.getScriptProperties().getProperty(name) || "";
}

function _propInt(name) {
  var v = parseInt(_prop(name), 10);
  return isNaN(v) || v <= 0 ? DEFAULTS[name] : v;
}

function _hmacHex(key, value) {
  var bytes = Utilities.computeHmacSha256Signature(value, key);
  var out = "";
  for (var i = 0; i < bytes.length; i++) {
    var b = (bytes[i] + 256) % 256;
    out += ("0" + b.toString(16)).slice(-2);
  }
  return out;
}

function _safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) {
    return false;
  }
  var diff = 0;
  for (var i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}
