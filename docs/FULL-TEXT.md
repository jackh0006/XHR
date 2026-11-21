# XHR — complete source texts

This file is generated from the canonical files in this repository. The
canonical file is always the source path shown above each block. Do not edit
this page by hand; run:

```bash
scripts/build-full-text.sh
```

The guides are ordinary Markdown files:

* [Cloudflare-only, 0 to 100](GUIDE-CLOUDFLARE.md)
* [Android, 0 to 100](GUIDE-ANDROID.md)
* [Google Cloud Run, optional](GUIDE-GOOGLE.md)
* [Protocol](PROTOCOL.md)
* [Security and limits](../SECURITY.md)
* [On-device test checklist](TEST-CHECKLIST.md)

Every deployable text is below, in full.

## `gas/Code.gs`

~~~javascript
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

/*
 * EDIT THESE THREE VALUES.
 *
 * Put the value between the quotes. Do not use // here.
 * If you prefer Script Properties, leave all three empty and set:
 * AUTH_KEY, EXIT_URL, EXIT_HMAC in Project Settings.
 *
 * AUTH_KEY  = the password used by the Android app
 * EXIT_URL  = https://your-worker.workers.dev/relay
 * EXIT_HMAC = the GAS value (NOT the E2E tunnel key)
 */
var CONFIG = {
  AUTH_KEY: "",
  EXIT_URL: "",
  EXIT_HMAC: "",
  MAX_ITEMS: 64,
  MAX_BYTES: 262144
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

~~~

## `cloudflare/worker.js`

~~~javascript
// SPDX-License-Identifier: MIT
// XHR Cloudflare Worker exit.
//
// Worker verifies the GAS HMAC, then routes each session id to a Durable
// Object. The Durable Object holds the TCP sockets and does the encryption.
//
// Required secrets:
//   XHR_E2E_KEY   64 hex characters (client + exit)
//   XHR_GAS_HMAC  same value as EXIT_HMAC in the Apps Script properties

import { connect } from "cloudflare:sockets";
import {
  importAesKey,
  verifyHmac,
  openFrame,
  sealFrame,
  b64encode,
  b64decode,
} from "./frames.js";

const MAX_ITEMS = 64;
const MAX_STREAMS = 64;
const MAX_QUEUE = 512;
const POLL_MS = 12000;
const FRAME_CHUNK = 900;
const TIME_WINDOW = 120;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/healthz" && request.method === "GET") {
      return new Response("ok", {
        status: 200,
        headers: { "Content-Type": "text/plain" },
      });
    }
    if (url.pathname !== "/relay" || request.method !== "POST") {
      return new Response("Not found", { status: 404 });
    }
    const body = await request.text();
    if (body.length > 2 * 1024 * 1024) return json({ e: "body" });
    const mac = request.headers.get("X-XHR-MAC") || "";
    if (!(await verifyHmac(env.XHR_GAS_HMAC, body, mac))) {
      return new Response("Unauthorized", { status: 401 });
    }
    let req;
    try {
      req = JSON.parse(body);
    } catch {
      return json({ e: "bad request" });
    }
    if (!req || req.v !== 1 || typeof req.s !== "string" || !Array.isArray(req.q)) {
      return json({ e: "bad request" });
    }
    const id = env.SESSIONS.idFromName(req.s);
    return env.SESSIONS.get(id).fetch(
      new Request("https://session/relay", {
        method: "POST",
        body,
        headers: { "X-XHR-MAC": mac },
      }),
    );
  },
};

export class Session {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.streams = new Map();
    this.queue = [];
    this.waiters = new Set();
    this.seen = new Map();
    this.keyPromise = importAesKey(env.XHR_E2E_KEY);
    this.closed = false;
  }

  async fetch(request) {
    if (request.method !== "POST") return new Response("Not found", { status: 404 });
    const body = await request.text();
    const mac = request.headers.get("X-XHR-MAC") || "";
    if (!(await verifyHmac(this.env.XHR_GAS_HMAC, body, mac))) {
      return new Response("Unauthorized", { status: 401 });
    }
    let req;
    try {
      req = JSON.parse(body);
    } catch {
      return json({ e: "bad request" });
    }
    if (!req || req.v !== 1 || typeof req.s !== "string" || !Array.isArray(req.q)) {
      return json({ e: "bad request" });
    }
    if (req.q.length > MAX_ITEMS) return json({ e: "too many frames" });
    const key = await this.keyPromise;

    for (const item of req.q) {
      let f;
      try {
        f = await openFrame(key, req.s, item.n, item.x);
      } catch {
        continue;
      }
      if (!this.fresh(f.t)) continue;
      if (!this.acceptRandom(f.r)) continue;
      await this.dispatch(f);
    }

    const frames = await this.collect(req.q.length ? 20 : POLL_MS);
    const out = [];
    for (const f of frames) {
      try {
        out.push(await sealFrame(key, req.s, f));
      } catch {
        // skip malformed outbound frame
      }
    }
    return json({ r: out });
  }

  fresh(t) {
    const now = Math.floor(Date.now() / 1000);
    return typeof t === "number" && Math.abs(now - t) <= TIME_WINDOW;
  }

  acceptRandom(r) {
    if (!r) return false;
    if (this.seen.has(r)) return false;
    if (this.seen.size > 4096) {
      const cutoff = Math.floor(Date.now() / 1000) - TIME_WINDOW;
      for (const [k, ts] of this.seen) if (ts < cutoff) this.seen.delete(k);
    }
    this.seen.set(r, Math.floor(Date.now() / 1000));
    return true;
  }

  async dispatch(f) {
    switch (f.o) {
      case "open":
        await this.openStream(f);
        break;
      case "data":
        await this.writeStream(f);
        break;
      case "close":
        await this.closeStream(f.st);
        break;
    }
  }

  async openStream(f) {
    const id = f.st;
    if (!id || this.streams.has(id) || this.streams.size >= MAX_STREAMS) return;
    if (typeof f.h !== "string" || !f.p) return;
    try {
      const socket = connect({ hostname: f.h, port: Number(f.p) });
      await socket.opened;
      const writer = socket.writable.getWriter();
      const reader = socket.readable.getReader();
      const st = { socket, writer, reader };
      this.streams.set(id, st);
      this.enqueue({ o: "open_ok", st: id });
      this.readLoop(id, reader).catch(() => {});
    } catch {
      this.enqueue({ o: "open_err", st: id, d: b64encode(new TextEncoder().encode("connect failed")) });
    }
  }

  async readLoop(id, reader) {
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        if (!value || value.length === 0) continue;
        for (let off = 0; off < value.length; off += FRAME_CHUNK) {
          const chunk = value.subarray(off, off + FRAME_CHUNK);
          this.enqueue({ o: "data", st: id, d: b64encode(chunk) });
        }
      }
    } catch {
      // fall through to close
    }
    this.enqueue({ o: "close", st: id });
    this.streams.delete(id);
  }

  async writeStream(f) {
    const st = this.streams.get(f.st);
    if (!st || typeof f.d !== "string") return;
    try {
      await st.writer.write(b64decode(f.d));
    } catch {
      await this.closeStream(f.st);
    }
  }

  async closeStream(id) {
    const st = this.streams.get(id);
    if (!st) return;
    this.streams.delete(id);
    try {
      await st.writer.close();
    } catch {}
    try {
      st.socket.close();
    } catch {}
  }

  enqueue(frame) {
    if (this.closed) return;
    if (this.queue.length >= MAX_QUEUE) {
      this.closed = true;
      for (const st of this.streams.values()) {
        try {
          st.socket.close();
        } catch {}
      }
      this.streams.clear();
      return;
    }
    this.queue.push(frame);
    const waiter = this.waiters.values().next().value;
    if (waiter) waiter();
  }

  collect(ms) {
    if (this.queue.length) return Promise.resolve(this.drain());
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        this.waiters.delete(done);
        resolve(this.drain());
      };
      const timer = setTimeout(done, ms);
      this.waiters.add(done);
    });
  }

  drain() {
    const out = this.queue.splice(0, MAX_ITEMS);
    return out;
  }
}

function json(obj) {
  return new Response(JSON.stringify(obj), {
    headers: { "Content-Type": "application/json" },
  });
}

~~~

## `cloudflare/frames.js`

~~~javascript
// SPDX-License-Identifier: MIT
// Shared frame and HMAC helpers for the Cloudflare Worker exit.
// This file is also imported by the Node test suite.

const encoder = new TextEncoder();

export function hexToBytes(hex) {
  if (typeof hex !== "string" || hex.length % 2 !== 0) throw new Error("bad hex");
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export function bytesToHex(bytes) {
  let s = "";
  for (const b of bytes) s += b.toString(16).padStart(2, "0");
  return s;
}

export function b64encode(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function b64decode(s) {
  const raw = atob(s);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

export function randomBytes(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return b;
}

export async function importAesKey(hexKey) {
  return crypto.subtle.importKey("raw", hexToBytes(hexKey), { name: "AES-GCM" }, false, [
    "encrypt",
    "decrypt",
  ]);
}

export async function importHmacKey(secret) {
  return crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

export async function hmacHex(secret, msg) {
  const key = await importHmacKey(secret);
  const sig = await crypto.subtle.sign("HMAC", key, encoder.encode(msg));
  return bytesToHex(new Uint8Array(sig));
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export async function verifyHmac(secret, msg, mac) {
  return safeEqual(await hmacHex(secret, msg), mac);
}

function aad(sid) {
  return encoder.encode("XHR1" + sid);
}

function randomPadding(max) {
  if (!max) return "";
  const n = randomBytes(1)[0];
  const size = Math.floor((n * max) / 255);
  if (!size) return "";
  return b64encode(randomBytes(size));
}

export async function sealFrame(key, sid, frame) {
  const f = { ...frame };
  if (!f.t) f.t = Math.floor(Date.now() / 1000);
  if (!f.r) f.r = bytesToHex(randomBytes(8));
  f.z = randomPadding(512);
  const nonce = randomBytes(12);
  const plain = encoder.encode(JSON.stringify(f));
  const ct = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: aad(sid), tagLength: 128 },
    key,
    plain,
  );
  return { n: b64encode(nonce), x: b64encode(new Uint8Array(ct)) };
}

export async function openFrame(key, sid, n, x) {
  const nonce = b64decode(n);
  const ct = b64decode(x);
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: nonce, additionalData: aad(sid), tagLength: 128 },
    key,
    ct,
  );
  return JSON.parse(new TextDecoder().decode(plain));
}

~~~

## `cloudflare/wrangler.toml`

~~~toml
name = "xhr-exit"
main = "worker.js"
compatibility_date = "2026-11-01"
workers_dev = true

[[durable_objects.bindings]]
name = "SESSIONS"
class_name = "Session"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["Session"]

~~~

## `cloudflare/package.json`

~~~json
{
  "name": "xhr-exit",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "node --test frames.test.mjs"
  }
}

~~~

## `Dockerfile`

~~~dockerfile
# syntax=docker/dockerfile:1
FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /xhr-exit ./cmd/xhr-exit

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /xhr-exit /xhr-exit
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/xhr-exit"]

~~~

## `cmd/xhr-client/main.go`

~~~go
// SPDX-License-Identifier: MIT
// Command xhr-client is the Linux/macOS preview client. The Android app uses
// the same internal/tunnel engine through JNI.
package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"time"

	"github.com/jackh0006/XHR/internal/tunnel"
	"github.com/jackh0006/XHR/internal/wire"
)

type Config struct {
	GASURL      string   `json:"gas_url"`
	GASURLs     []string `json:"gas_urls"`
	GASHost     string   `json:"gas_host"`
	FrontDomain string   `json:"front_domain"`
	GoogleIP    string   `json:"google_ip"`
	AuthKey     string   `json:"auth_key"`
	E2EKey      string   `json:"e2e_key"`
	Listen      string   `json:"listen"`
}

func main() {
	cfgPath := flag.String("config", "config.json", "path to config.json")
	flag.Parse()

	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		log.Fatal(err)
	}
	key, err := wire.ParseKey(cfg.E2EKey)
	if err != nil {
		log.Fatal("e2e_key must be 64 hex characters")
	}
	urls := append([]string{}, cfg.GASURLs...)
	if len(urls) == 0 && cfg.GASURL != "" {
		urls = append(urls, cfg.GASURL)
	}
	c, err := tunnel.New(tunnel.Config{
		GASURLs:     urls,
		GASHost:     cfg.GASHost,
		FrontDomain: cfg.FrontDomain,
		GoogleIP:    cfg.GoogleIP,
		AuthKey:     cfg.AuthKey,
		Key:         key,
		OnLog:       func(line string) { log.Printf("%s", line) },
	})
	if err != nil {
		log.Fatal(err)
	}
	defer c.Close()

	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatal(err)
	}
	defer ln.Close()
	log.Printf("xhr-client SOCKS5 on %s", cfg.Listen)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		go handleSocks(c, conn)
	}
}

func loadConfig(path string) (Config, error) {
	var cfg Config
	b, err := os.ReadFile(path)
	if err != nil {
		return cfg, err
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		return cfg, err
	}
	if cfg.GASHost == "" {
		cfg.GASHost = "script.google.com"
	}
	if cfg.FrontDomain == "" {
		cfg.FrontDomain = "www.google.com"
	}
	if cfg.GoogleIP == "" {
		cfg.GoogleIP = "216.239.38.120"
	}
	if cfg.Listen == "" {
		cfg.Listen = "127.0.0.1:1080"
	}
	if cfg.AuthKey == "" || cfg.E2EKey == "" {
		return cfg, errors.New("auth_key and e2e_key are required")
	}
	urls := append([]string{}, cfg.GASURLs...)
	if len(urls) == 0 && cfg.GASURL != "" {
		urls = append(urls, cfg.GASURL)
	}
	if len(urls) == 0 {
		return cfg, errors.New("gas_url or gas_urls is required")
	}
	if len(urls) > 5 {
		return cfg, errors.New("use at most 5 deployments")
	}
	for i, raw := range urls {
		value := strings.TrimSpace(raw)
		if strings.HasPrefix(value, "AKfycb") {
			value = "https://script.google.com/macros/s/" + value + "/exec"
		}
		if !strings.HasPrefix(value, "https://") {
			return cfg, errors.New("every deployment must be an https URL or deployment ID")
		}
		urls[i] = value
	}
	cfg.GASURLs = urls
	return cfg, nil
}

func handleSocks(c *tunnel.Client, conn net.Conn) {
	defer conn.Close()
	if err := socksHandshake(conn); err != nil {
		return
	}
	host, port, err := socksRequest(conn)
	if err != nil {
		return
	}
	remote, err := c.Open(host, port)
	if err != nil {
		log.Printf("open %s:%d: %v", host, port, err)
		_, _ = conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, conn)
		_ = remote.Close()
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, remote)
		_ = conn.Close()
		done <- struct{}{}
	}()
	<-done
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
}

func socksHandshake(conn net.Conn) error {
	h := make([]byte, 2)
	if _, err := io.ReadFull(conn, h); err != nil {
		return err
	}
	if h[0] != 5 {
		return errors.New("socks version")
	}
	methods := make([]byte, int(h[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	for _, m := range methods {
		if m == 0 {
			_, err := conn.Write([]byte{5, 0})
			return err
		}
	}
	_, _ = conn.Write([]byte{5, 0xff})
	return errors.New("socks auth")
}

func socksRequest(conn net.Conn) (string, uint16, error) {
	h := make([]byte, 4)
	if _, err := io.ReadFull(conn, h); err != nil {
		return "", 0, err
	}
	if h[0] != 5 {
		return "", 0, errors.New("socks version")
	}
	if h[1] != 1 {
		_, _ = conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", 0, errors.New("socks command")
	}
	var host string
	switch h[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return "", 0, err
		}
		if l[0] == 0 {
			return "", 0, errors.New("socks address")
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	default:
		return "", 0, errors.New("socks address")
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(conn, p); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(p), nil
}

~~~

## `cmd/xhr-exit/main.go`

~~~go
// SPDX-License-Identifier: MIT
package main

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
)

const (
	maxBody       = 2 << 20
	maxItems      = 64
	maxSessions   = 128
	maxStreams    = 64
	sessionIdle   = 5 * time.Minute
	pollWait      = 15 * time.Second
	uploadWait    = 20 * time.Millisecond
	coalesceWait  = 5 * time.Millisecond
	maxFrameData  = 900
	downQueueSize = 512
	dialTimeout   = 12 * time.Second
)

type exitServer struct {
	key      []byte
	gasHMAC  string
	mu       sync.Mutex
	sessions map[string]*session
}

type session struct {
	sid string

	mu      sync.Mutex
	down    chan wire.Frame
	streams map[uint32]*stream
	opening map[uint32]struct{}
	seen    map[string]int64
	closed  bool
	last    time.Time
}

type stream struct {
	id   uint32
	conn net.Conn
	sess *session
}

func main() {
	keyHex := os.Getenv("XHR_E2E_KEY")
	key, err := wire.ParseKey(keyHex)
	if err != nil {
		log.Fatal("XHR_E2E_KEY must be 64 hex characters")
	}
	gasHMAC := os.Getenv("XHR_GAS_HMAC")
	if gasHMAC == "" {
		log.Fatal("XHR_GAS_HMAC is required")
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	e := &exitServer{key: key, gasHMAC: gasHMAC, sessions: map[string]*session{}}
	go e.cleanupLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("/relay", e.handleRelay)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      pollWait + 15*time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Printf("xhr-exit listening on :%s", port)
	log.Fatal(srv.ListenAndServe())
}

func (e *exitServer) handleRelay(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody+1))
	if err != nil || len(body) > maxBody {
		writeJSON(w, proto.Response{E: "body"})
		return
	}
	if !proto.VerifyHMAC(e.gasHMAC, string(body), r.Header.Get("X-XHR-MAC")) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var req proto.ExitRequest
	if err := json.Unmarshal(body, &req); err != nil || req.V != 1 || !validSID(req.S) {
		writeJSON(w, proto.Response{E: "bad request"})
		return
	}
	if len(req.Q) > maxItems {
		writeJSON(w, proto.Response{E: "too many frames"})
		return
	}

	s := e.getSession(req.S)
	if s == nil {
		writeJSON(w, proto.Response{E: "no session"})
		return
	}

	for _, it := range req.Q {
		f, err := wire.Open(e.key, req.S, it.N, it.X)
		if err != nil {
			continue
		}
		if !proto.Fresh(f.Time) {
			continue
		}
		if !s.seenFrame(f.Random) {
			continue
		}
		s.dispatch(f)
	}

	// An upload request returns quickly so the client can pipeline the next
	// batch. A request with no uplink is the standing downlink long-poll.
	wait := pollWait
	if len(req.Q) > 0 {
		wait = uploadWait
	}
	frames := s.collect(r.Context(), wait)
	resp := proto.Response{R: make([]proto.Item, 0, len(frames))}
	for _, f := range frames {
		n, x, err := wire.Seal(e.key, req.S, f, 512)
		if err != nil {
			continue
		}
		resp.R = append(resp.R, proto.Item{N: n, X: x})
	}
	writeJSON(w, resp)
}

func validSID(s string) bool {
	if len(s) != 16 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

func (e *exitServer) getSession(sid string) *session {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := e.sessions[sid]
	if s == nil {
		if len(e.sessions) >= maxSessions {
			return nil
		}
		s = &session{
			sid:     sid,
			down:    make(chan wire.Frame, downQueueSize),
			streams: map[uint32]*stream{},
			opening: map[uint32]struct{}{},
			seen:    map[string]int64{},
			last:    time.Now(),
		}
		e.sessions[sid] = s
	}
	s.touch()
	return s
}

func (e *exitServer) cleanupLoop() {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for range t.C {
		now := time.Now()
		e.mu.Lock()
		for sid, s := range e.sessions {
			s.mu.Lock()
			idle := now.Sub(s.last) > sessionIdle
			s.mu.Unlock()
			if idle {
				s.shutdown()
				delete(e.sessions, sid)
			}
		}
		e.mu.Unlock()
	}
}

func (s *session) touch() {
	s.mu.Lock()
	s.last = time.Now()
	s.mu.Unlock()
}

func (s *session) seenFrame(r string) bool {
	if r == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.seen[r]; ok {
		return false
	}
	if len(s.seen) > 4096 {
		cutoff := time.Now().Add(-proto.TimeWindow).Unix()
		for k, ts := range s.seen {
			if ts < cutoff {
				delete(s.seen, k)
			}
		}
	}
	s.seen[r] = time.Now().Unix()
	return true
}

func (s *session) dispatch(f wire.Frame) {
	s.touch()
	switch f.Op {
	case "open":
		s.openStream(f.Stream, f.Host, f.Port)
	case "data":
		s.writeStream(f.Stream, f.Data)
	case "close":
		s.closeStream(f.Stream)
	}
}

func (s *session) openStream(id uint32, host string, port uint16) {
	if id == 0 || host == "" || port == 0 {
		return
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	if len(s.streams)+len(s.opening) >= maxStreams {
		s.mu.Unlock()
		s.enqueue(wire.Frame{Op: "open_err", Stream: id, Data: []byte("stream limit")})
		return
	}
	if _, exists := s.streams[id]; exists {
		s.mu.Unlock()
		return
	}
	if _, exists := s.opening[id]; exists {
		s.mu.Unlock()
		return
	}
	s.opening[id] = struct{}{}
	s.mu.Unlock()

	go func() {
		conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(int(port))), dialTimeout)
		s.mu.Lock()
		delete(s.opening, id)
		if err != nil {
			closed := s.closed
			s.mu.Unlock()
			if !closed {
				s.enqueue(wire.Frame{Op: "open_err", Stream: id, Data: []byte("connect failed")})
			}
			return
		}
		if s.closed {
			s.mu.Unlock()
			_ = conn.Close()
			return
		}
		st := &stream{id: id, conn: conn, sess: s}
		s.streams[id] = st
		s.last = time.Now()
		s.mu.Unlock()

		// The acknowledgement is what releases the client's SOCKS/HTTP
		// handshake, so send it before reading any payload.
		s.enqueue(wire.Frame{Op: "open_ok", Stream: id})
		go st.readLoop()
	}()
}

func (s *session) writeStream(id uint32, data []byte) {
	s.mu.Lock()
	st := s.streams[id]
	s.mu.Unlock()
	if st == nil || len(data) == 0 {
		return
	}
	_ = st.conn.SetWriteDeadline(time.Now().Add(20 * time.Second))
	if _, err := st.conn.Write(data); err != nil {
		s.closeStream(id)
	}
}

func (s *session) closeStream(id uint32) {
	s.mu.Lock()
	delete(s.opening, id)
	st := s.streams[id]
	delete(s.streams, id)
	s.mu.Unlock()
	if st != nil {
		_ = st.conn.Close()
	}
}

func (s *session) enqueue(f wire.Frame) {
	s.mu.Lock()
	closed := s.closed
	s.mu.Unlock()
	if closed {
		return
	}
	select {
	case s.down <- f:
	case <-time.After(5 * time.Second):
		s.shutdown()
	}
}

// collect is safe to call from several HTTP handlers. Each frame is delivered
// to exactly one response. A short grace window batches adjacent frames.
func (s *session) collect(ctx context.Context, wait time.Duration) []wire.Frame {
	out := make([]wire.Frame, 0, maxItems)
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case f := <-s.down:
		out = append(out, f)
	case <-timer.C:
		return out
	case <-ctx.Done():
		return out
	}
	grace := time.NewTimer(coalesceWait)
	defer grace.Stop()
	for len(out) < maxItems {
		select {
		case f := <-s.down:
			out = append(out, f)
		case <-grace.C:
			return out
		case <-ctx.Done():
			return out
		}
	}
	return out
}

func (s *session) shutdown() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	streams := s.streams
	s.streams = map[uint32]*stream{}
	s.opening = map[uint32]struct{}{}
	s.mu.Unlock()
	for _, st := range streams {
		_ = st.conn.Close()
	}
}

func (st *stream) readLoop() {
	buf := make([]byte, 32*1024)
	for {
		n, err := st.conn.Read(buf)
		if n > 0 {
			data := buf[:n]
			for len(data) > 0 {
				chunk := data
				if len(chunk) > maxFrameData {
					chunk = chunk[:maxFrameData]
				}
				cp := make([]byte, len(chunk))
				copy(cp, chunk)
				st.sess.enqueue(wire.Frame{Op: "data", Stream: st.id, Data: cp})
				data = data[len(chunk):]
			}
		}
		if err != nil {
			st.sess.mu.Lock()
			removed := st.sess.streams[st.id] == st
			if removed {
				delete(st.sess.streams, st.id)
			}
			st.sess.mu.Unlock()
			if removed {
				st.sess.enqueue(wire.Frame{Op: "close", Stream: st.id})
			}
			_ = st.conn.Close()
			return
		}
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

~~~

## `internal/proto/proto.go`

~~~go
// SPDX-License-Identifier: MIT
package proto

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"time"
)

type Item struct {
	N string `json:"n"`
	X string `json:"x"`
}

type ClientRequest struct {
	V int    `json:"v"`
	T int64  `json:"t"`
	S string `json:"s"`
	M string `json:"m"`
	Q []Item `json:"q"`
}

type ExitRequest struct {
	V int    `json:"v"`
	S string `json:"s"`
	Q []Item `json:"q"`
}

type Response struct {
	R []Item `json:"r"`
	E string `json:"e,omitempty"`
}

const TimeWindow = 120 * time.Second

// HMACHex returns the lowercase hex HMAC-SHA256 of msg.
func HMACHex(secret, msg string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(msg))
	return hex.EncodeToString(m.Sum(nil))
}

// VerifyHMAC compares in constant time.
func VerifyHMAC(secret, msg, mac string) bool {
	want := HMACHex(secret, msg)
	got, err := hex.DecodeString(mac)
	if err != nil {
		return false
	}
	w, err := hex.DecodeString(want)
	if err != nil {
		return false
	}
	return hmac.Equal(got, w)
}

func Fresh(t int64) bool {
	now := time.Now().Unix()
	d := now - t
	if d < 0 {
		d = -d
	}
	return d <= int64(TimeWindow.Seconds())
}

~~~

## `internal/wire/wire.go`

~~~go
// SPDX-License-Identifier: MIT
package wire

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
)

// Frame is the plaintext carried inside one AES-256-GCM envelope.
type Frame struct {
	Op     string `json:"o"`
	Stream uint32 `json:"st,omitempty"`
	Host   string `json:"h,omitempty"`
	Port   uint16 `json:"p,omitempty"`
	Data   []byte `json:"d,omitempty"`
	Time   int64  `json:"t"`
	Random string `json:"r"`
	Pad    string `json:"z,omitempty"`
}

var (
	ErrKeySize = errors.New("xhr: key must be 32 bytes")
	ErrNonce   = errors.New("xhr: bad nonce")
	ErrDecrypt = errors.New("xhr: authentication failed")
	ErrFrame   = errors.New("xhr: malformed frame")
	ErrClock   = errors.New("xhr: frame outside time window")
	ErrReplay  = errors.New("xhr: replayed frame")
)

// ParseKey decodes a 64-character hex key.
func ParseKey(s string) ([]byte, error) {
	k, err := hex.DecodeString(s)
	if err != nil || len(k) != 32 {
		return nil, ErrKeySize
	}
	return k, nil
}

// NewSID returns a 16-character hex session id.
func NewSID() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func randomPadding(max int) (string, error) {
	if max <= 0 {
		return "", nil
	}
	n := make([]byte, 1)
	if _, err := rand.Read(n); err != nil {
		return "", err
	}
	size := int(n[0]) * max / 255
	if size == 0 {
		return "", nil
	}
	b := make([]byte, size)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(b), nil
}

func aad(sid string) []byte { return []byte("XHR1" + sid) }

func gcm(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// Seal encrypts one frame. The returned nonce and ciphertext are base64.
func Seal(key []byte, sid string, f Frame, maxPad int) (string, string, error) {
	if len(key) != 32 {
		return "", "", ErrKeySize
	}
	if f.Time == 0 {
		f.Time = time.Now().Unix()
	}
	if f.Random == "" {
		r, err := randomHex(8)
		if err != nil {
			return "", "", err
		}
		f.Random = r
	}
	pad, err := randomPadding(maxPad)
	if err != nil {
		return "", "", err
	}
	f.Pad = pad
	plain, err := json.Marshal(f)
	if err != nil {
		return "", "", err
	}
	aead, err := gcm(key)
	if err != nil {
		return "", "", err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", "", err
	}
	ct := aead.Seal(nil, nonce, plain, aad(sid))
	return base64.StdEncoding.EncodeToString(nonce),
		base64.StdEncoding.EncodeToString(ct), nil
}

// Open decrypts one frame. It does not enforce replay or time; the exit does.
func Open(key []byte, sid, nonceB64, ctB64 string) (Frame, error) {
	var f Frame
	if len(key) != 32 {
		return f, ErrKeySize
	}
	nonce, err := base64.StdEncoding.DecodeString(nonceB64)
	if err != nil || len(nonce) != 12 {
		return f, ErrNonce
	}
	ct, err := base64.StdEncoding.DecodeString(ctB64)
	if err != nil || len(ct) < 16 {
		return f, ErrDecrypt
	}
	aead, err := gcm(key)
	if err != nil {
		return f, err
	}
	plain, err := aead.Open(nil, nonce, ct, aad(sid))
	if err != nil {
		return f, ErrDecrypt
	}
	if err := json.Unmarshal(plain, &f); err != nil {
		return f, ErrFrame
	}
	return f, nil
}

~~~

## `internal/tunnel/client.go`

~~~go
// SPDX-License-Identifier: MIT

// Package tunnel is the multiplexed XHR client engine. It keeps one or more
// Apps Script sessions alive, carries many TCP streams over each session, and
// performs end-to-end AES-256-GCM itself. The Apps Script relay only sees
// opaque sealed frames.
package tunnel

import (
	"context"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

const (
	// A session is capped below the exit's 64-stream limit so a new stream can
	// always be opened while an old session is draining.
	maxStreamsPerSession = 56
	sessionIdle          = 2 * time.Minute
	pollWait             = 20 * time.Second
	requestTimeout       = 30 * time.Second
	openTimeout          = 35 * time.Second
	dataChunk            = 8 * 1024
	maxBatchFrames       = 12
	maxBatchBytes        = 96 * 1024
)

// Config describes one XHR client.
type Config struct {
	// GASURL is kept for the single-deployment CLI and tests.
	GASURL string
	// GASURLs is the Android deployment pool. Each URL is tried in turn, so
	// one slow or broken Apps Script deployment does not kill the tunnel.
	GASURLs     []string
	GASHost     string
	FrontDomain string
	GoogleIP    string
	AuthKey     string
	Key         []byte
	// HTTP is optional and exists for tests. When nil the production
	// domain-fronted HTTP/2 client is constructed.
	HTTP *http.Client
	// OnLog receives short human-readable events. It must not block.
	OnLog func(string)
}

// Stats is the live traffic counter set exposed to the Android UI.
type Stats struct {
	Up      atomic.Int64
	Down    atomic.Int64
	Calls   atomic.Int64
	Streams atomic.Int64
	Active  atomic.Int64
}

// Client owns the XHR sessions.
type Client struct {
	cfg   Config
	http  *http.Client
	urls  []string
	next  atomic.Uint32
	stats Stats

	mu       sync.Mutex
	sessions []*Session
	closed   bool

	errMu   sync.Mutex
	lastErr string
}

// New validates cfg and creates a client. It does not open any connections.
func New(cfg Config) (*Client, error) {
	if len(cfg.Key) != 32 {
		return nil, errors.New("tunnel key must be 32 bytes")
	}
	if cfg.AuthKey == "" {
		return nil, errors.New("AUTH_KEY is required")
	}
	urls := make([]string, 0, len(cfg.GASURLs)+1)
	urls = append(urls, cfg.GASURLs...)
	if len(urls) == 0 && cfg.GASURL != "" {
		urls = append(urls, cfg.GASURL)
	}
	seen := make(map[string]struct{}, len(urls))
	clean := urls[:0]
	for _, raw := range urls {
		u := strings.TrimSpace(raw)
		if u == "" {
			continue
		}
		if !strings.HasPrefix(u, "https://") {
			return nil, fmt.Errorf("deployment URL must use https: %s", u)
		}
		if _, ok := seen[u]; ok {
			continue
		}
		seen[u] = struct{}{}
		clean = append(clean, u)
	}
	if len(clean) == 0 {
		return nil, errors.New("at least one deployment URL is required")
	}
	urls = clean
	if cfg.GASHost == "" {
		cfg.GASHost = "script.google.com"
	}
	if cfg.FrontDomain == "" {
		cfg.FrontDomain = "www.google.com"
	}
	if cfg.GoogleIP == "" {
		cfg.GoogleIP = "216.239.38.120"
	}
	c := &Client{cfg: cfg, urls: urls}
	if cfg.HTTP != nil {
		c.http = cfg.HTTP
	} else {
		c.http = newFrontedHTTP(cfg)
	}
	return c, nil
}

func newFrontedHTTP(cfg Config) *http.Client {
	cache := utls.NewLRUClientSessionCache(8)
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := &net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}
		raw, err := d.DialContext(ctx, "tcp", net.JoinHostPort(cfg.GoogleIP, "443"))
		if err != nil {
			return nil, err
		}
		u := utls.UClient(raw, &utls.Config{
			ServerName:         cfg.FrontDomain,
			NextProtos:         []string{"h2"},
			MinVersion:         tls.VersionTLS12,
			ClientSessionCache: cache,
		}, utls.HelloChrome_Auto)
		if err := u.HandshakeContext(ctx); err != nil {
			_ = raw.Close()
			return nil, err
		}
		return u, nil
	}
	tr := &http2.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string, _ *tls.Config) (net.Conn, error) {
			return dial(ctx, network, addr)
		},
		ReadIdleTimeout: 30 * time.Second,
		PingTimeout:     15 * time.Second,
	}
	return &http.Client{Transport: tr, Timeout: requestTimeout}
}

// Stats returns the live counters. The returned pointer is stable.
func (c *Client) Stats() *Stats { return &c.stats }

// LastError returns the most recent transport error, if any.
func (c *Client) LastError() string {
	c.errMu.Lock()
	defer c.errMu.Unlock()
	return c.lastErr
}

func (c *Client) noteError(err error) {
	if err == nil {
		return
	}
	c.errMu.Lock()
	c.lastErr = err.Error()
	c.errMu.Unlock()
	c.logf("error: %v", err)
}

func (c *Client) logf(format string, args ...any) {
	if c.cfg.OnLog != nil {
		c.cfg.OnLog(fmt.Sprintf(format, args...))
	}
}

// Open dials host:port through the tunnel. The returned stream implements
// net.Conn and must be closed by the caller.
func (c *Client) Open(host string, port uint16) (net.Conn, error) {
	host = strings.TrimSpace(host)
	if host == "" || port == 0 {
		return nil, errors.New("invalid destination")
	}
	s, err := c.pickSession()
	if err != nil {
		return nil, err
	}
	st, err := s.open(host, port)
	if err != nil {
		return nil, err
	}
	c.stats.Streams.Add(1)
	c.stats.Active.Add(1)
	c.logf("CONNECT %s:%d", host, port)
	return st, nil
}

// Close tears down every session. It is safe to call more than once.
func (c *Client) Close() error {
	c.mu.Lock()
	c.closed = true
	sessions := c.sessions
	c.sessions = nil
	c.mu.Unlock()
	for _, s := range sessions {
		s.close()
	}
	c.http.CloseIdleConnections()
	return nil
}

func (c *Client) pickSession() (*Session, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, errors.New("tunnel is closed")
	}
	for i := len(c.sessions) - 1; i >= 0; i-- {
		s := c.sessions[i]
		if s.isClosed() {
			c.sessions = append(c.sessions[:i], c.sessions[i+1:]...)
			continue
		}
		if s.streamCount() < maxStreamsPerSession {
			return s, nil
		}
	}
	s, err := c.newSessionLocked()
	if err != nil {
		return nil, err
	}
	c.sessions = append(c.sessions, s)
	return s, nil
}

func (c *Client) newSessionLocked() (*Session, error) {
	sid, err := wire.NewSID()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Session{
		c:       c,
		sid:     sid,
		ctx:     ctx,
		cancel:  cancel,
		out:     make(chan wire.Frame, 512),
		streams: make(map[uint32]*Stream),
		last:    time.Now(),
	}
	go s.txLoop()
	go s.rxLoop()
	go s.idleLoop()
	c.logf("session %s started", sid)
	return s, nil
}

func (c *Client) dropSession(s *Session) {
	c.mu.Lock()
	for i, x := range c.sessions {
		if x == s {
			c.sessions = append(c.sessions[:i], c.sessions[i+1:]...)
			break
		}
	}
	c.mu.Unlock()
}

func (c *Client) roundTrip(ctx context.Context, sid string, items []proto.Item) ([]wire.Frame, error) {
	if items == nil {
		// encoding/json writes a nil slice as null. Apps Script requires an
		// array for "q", so an idle downlink poll must send [] not null.
		items = []proto.Item{}
	}
	t := time.Now().Unix()
	req := proto.ClientRequest{
		V: 1,
		T: t,
		S: sid,
		M: proto.HMACHex(c.cfg.AuthKey, fmt.Sprintf("%d.%s", t, sid)),
		Q: items,
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for attempt := 0; attempt < len(c.urls); attempt++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		u := c.nextURL()
		frames, err := c.postOnce(ctx, u, sid, body)
		if err == nil {
			return frames, nil
		}
		lastErr = err
		c.noteError(err)
	}
	if lastErr == nil {
		lastErr = errors.New("no relay endpoint")
	}
	return nil, lastErr
}

func (c *Client) nextURL() string {
	if len(c.urls) == 1 {
		return c.urls[0]
	}
	n := c.next.Add(1) - 1
	return c.urls[int(n)%len(c.urls)]
}

func (c *Client) postOnce(ctx context.Context, gasURL, sid string, body []byte) ([]wire.Frame, error) {
	c.stats.Calls.Add(1)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, gasURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("User-Agent", chromeUserAgent)
	httpReq.Header.Set("Accept", "application/json")
	httpReq.Host = c.cfg.GASHost
	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("relay HTTP %d", resp.StatusCode)
	}
	if len(raw) > 0 && raw[0] == '<' {
		return nil, errors.New("relay rejected the profile (check AUTH_KEY and deployment URL)")
	}
	var out proto.Response
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("relay response: %w", err)
	}
	if out.E != "" {
		return nil, errors.New(out.E)
	}
	frames := make([]wire.Frame, 0, len(out.R))
	for _, it := range out.R {
		f, err := wire.Open(c.cfg.Key, sid, it.N, it.X)
		if err != nil {
			continue
		}
		frames = append(frames, f)
	}
	return frames, nil
}

const chromeUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

// Session carries many streams over one Apps Script session id.
type Session struct {
	c      *Client
	sid    string
	ctx    context.Context
	cancel context.CancelFunc
	out    chan wire.Frame

	mu      sync.Mutex
	streams map[uint32]*Stream
	nextID  uint32
	last    time.Time
	closed  bool
}

func (s *Session) isClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

func (s *Session) streamCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.streams)
}

func (s *Session) touch() {
	s.mu.Lock()
	s.last = time.Now()
	s.mu.Unlock()
}

func (s *Session) close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	streams := s.streams
	s.streams = map[uint32]*Stream{}
	s.mu.Unlock()
	s.cancel()
	for _, st := range streams {
		st.finish(false)
	}
	s.c.dropSession(s)
	s.c.logf("session %s closed", s.sid)
}

func (s *Session) idleLoop() {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-t.C:
			s.mu.Lock()
			idle := len(s.streams) == 0 && time.Since(s.last) > sessionIdle
			s.mu.Unlock()
			if idle {
				s.close()
				return
			}
		}
	}
}

func (s *Session) open(host string, port uint16) (*Stream, error) {
	sid, err := wire.NewSID()
	if err != nil {
		return nil, err
	}
	st := &Stream{
		id:     streamID(sid),
		host:   host,
		port:   port,
		sess:   s,
		opened: make(chan error, 1),
		rx:     make(chan []byte, 512),
		done:   make(chan struct{}),
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, errors.New("session closed")
	}
	for {
		if _, exists := s.streams[st.id]; !exists {
			break
		}
		s.nextID++
		st.id = s.nextID
		if st.id == 0 {
			st.id = 1
		}
	}
	s.streams[st.id] = st
	s.last = time.Now()
	s.mu.Unlock()

	if err := s.send(wire.Frame{Op: "open", Stream: st.id, Host: host, Port: port}, 512); err != nil {
		s.remove(st)
		return nil, err
	}
	select {
	case err := <-st.opened:
		if err != nil {
			s.remove(st)
			return nil, err
		}
		st.counted.Store(true)
		return st, nil
	case <-time.After(openTimeout):
		s.remove(st)
		return nil, errors.New("connection timed out")
	case <-s.ctx.Done():
		s.remove(st)
		return nil, errors.New("session closed")
	case <-st.done:
		select {
		case err := <-st.opened:
			if err != nil {
				return nil, err
			}
		default:
		}
		return nil, errors.New("connection closed")
	}
}

func streamID(sid string) uint32 {
	b, err := hex.DecodeString(sid)
	if err != nil || len(b) < 4 {
		return 1
	}
	id := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
	if id == 0 {
		id = 1
	}
	return id
}

func (s *Session) remove(st *Stream) {
	s.mu.Lock()
	if cur, ok := s.streams[st.id]; ok && cur == st {
		delete(s.streams, st.id)
	}
	s.last = time.Now()
	s.mu.Unlock()
}

func (s *Session) send(f wire.Frame, pad int) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errors.New("session closed")
	}
	s.last = time.Now()
	s.mu.Unlock()
	select {
	case s.out <- f:
		return nil
	case <-s.ctx.Done():
		return errors.New("session closed")
	}
}

func (s *Session) txLoop() {
	for {
		var first wire.Frame
		select {
		case <-s.ctx.Done():
			return
		case first = <-s.out:
		}
		batch := []wire.Frame{first}
		bytes := len(first.Data)
		grace := time.NewTimer(4 * time.Millisecond)
	drain:
		for len(batch) < maxBatchFrames && bytes < maxBatchBytes {
			select {
			case f := <-s.out:
				batch = append(batch, f)
				if len(f.Data) > 0 {
					bytes += len(f.Data)
				}
			case <-grace.C:
				break drain
			case <-s.ctx.Done():
				grace.Stop()
				return
			}
		}
		grace.Stop()

		items := make([]proto.Item, 0, len(batch))
		for _, f := range batch {
			pad := 64
			if f.Op != "data" {
				pad = 512
			}
			n, x, err := wire.Seal(s.c.cfg.Key, s.sid, f, pad)
			if err != nil {
				continue
			}
			items = append(items, proto.Item{N: n, X: x})
		}
		if len(items) == 0 {
			continue
		}

		ok := false
		for attempt := 0; attempt < 3; attempt++ {
			if s.ctx.Err() != nil {
				return
			}
			frames, err := s.c.roundTrip(s.ctx, s.sid, items)
			if err == nil {
				s.c.handleFrames(s, frames)
				ok = true
				break
			}
			s.c.noteError(err)
			select {
			case <-time.After(time.Duration(200*(attempt+1)) * time.Millisecond):
			case <-s.ctx.Done():
				return
			}
		}
		if !ok {
			// The session is unhealthy. Closing it forces the next Open to
			// create a fresh Apps Script session instead of silently losing
			// every write.
			s.close()
			return
		}
	}
}

func (s *Session) rxLoop() {
	backoff := 250 * time.Millisecond
	for {
		if s.ctx.Err() != nil {
			return
		}
		frames, err := s.c.roundTrip(s.ctx, s.sid, nil)
		if err != nil {
			if s.ctx.Err() != nil {
				return
			}
			s.c.noteError(err)
			if strings.Contains(err.Error(), "no session") {
				// The exit garbage-collected this session during an outage.
				// Close the mux so apps get a clean reset and the next Open
				// creates a fresh session instead of writing into a void.
				s.close()
				return
			}
			select {
			case <-time.After(backoff):
			case <-s.ctx.Done():
				return
			}
			if backoff < 3*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = 250 * time.Millisecond
		s.c.handleFrames(s, frames)
	}
}

func (c *Client) handleFrames(s *Session, frames []wire.Frame) {
	for _, f := range frames {
		s.mu.Lock()
		st := s.streams[f.Stream]
		s.last = time.Now()
		s.mu.Unlock()
		if st == nil {
			continue
		}
		switch f.Op {
		case "open_ok":
			st.signalOpen(nil)
		case "open_err":
			msg := strings.TrimSpace(string(f.Data))
			if msg == "" {
				msg = "connection failed"
			}
			st.signalOpen(errors.New(msg))
			st.finish(false)
		case "data":
			if len(f.Data) > 0 {
				c.stats.Down.Add(int64(len(f.Data)))
				st.deliver(f.Data)
			}
		case "close":
			st.finish(false)
		case "error":
			st.finish(false)
		}
	}
}

// Stream is one TCP connection carried inside a session.
type Stream struct {
	id     uint32
	host   string
	port   uint16
	sess   *Session
	opened chan error
	rx     chan []byte
	done   chan struct{}

	once    sync.Once
	pending []byte
	closed  atomic.Bool
	counted atomic.Bool
}

func (st *Stream) signalOpen(err error) {
	select {
	case st.opened <- err:
	default:
	}
}

func (st *Stream) deliver(data []byte) {
	cp := make([]byte, len(data))
	copy(cp, data)
	select {
	case st.rx <- cp:
	case <-st.done:
	case <-time.After(5 * time.Second):
		st.finish(false)
	}
}

// Read implements io.Reader.
func (st *Stream) Read(p []byte) (int, error) {
	for len(st.pending) == 0 {
		select {
		case data := <-st.rx:
			st.pending = data
		case <-st.done:
			// Drain everything that arrived before the close. A TCP FIN can
			// arrive in the same poll as the last payload bytes.
			select {
			case data := <-st.rx:
				st.pending = data
			default:
				return 0, io.EOF
			}
		}
	}
	n := copy(p, st.pending)
	st.pending = st.pending[n:]
	return n, nil
}

// Write implements io.Writer.
func (st *Stream) Write(p []byte) (int, error) {
	if st.closed.Load() {
		return 0, errors.New("stream closed")
	}
	total := 0
	for len(p) > 0 {
		n := len(p)
		if n > dataChunk {
			n = dataChunk
		}
		chunk := make([]byte, n)
		copy(chunk, p[:n])
		if err := st.sess.send(wire.Frame{Op: "data", Stream: st.id, Data: chunk}, 64); err != nil {
			return total, err
		}
		st.sess.c.stats.Up.Add(int64(n))
		total += n
		p = p[n:]
	}
	return total, nil
}

// Close implements io.Closer.
func (st *Stream) Close() error {
	st.finish(true)
	return nil
}

// LocalAddr implements net.Conn.
func (st *Stream) LocalAddr() net.Addr { return dummyAddr("xhr-local") }

// RemoteAddr implements net.Conn.
func (st *Stream) RemoteAddr() net.Addr {
	return dummyAddr(net.JoinHostPort(st.host, fmt.Sprintf("%d", st.port)))
}

// SetDeadline implements net.Conn. Deadlines are best-effort; the session
// itself has an idle timeout.
func (st *Stream) SetDeadline(time.Time) error      { return nil }
func (st *Stream) SetReadDeadline(time.Time) error  { return nil }
func (st *Stream) SetWriteDeadline(time.Time) error { return nil }

func (st *Stream) finish(sendClose bool) {
	st.once.Do(func() {
		st.closed.Store(true)
		if sendClose {
			_ = st.sess.send(wire.Frame{Op: "close", Stream: st.id}, 128)
		}
		close(st.done)
		st.sess.remove(st)
		if st.counted.Swap(false) {
			st.sess.c.stats.Active.Add(-1)
		}
	})
}

type dummyAddr string

func (d dummyAddr) Network() string { return "xhr" }
func (d dummyAddr) String() string  { return string(d) }

~~~

## `mobile/main.go`

~~~go
// SPDX-License-Identifier: MIT
package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackh0006/XHR/internal/tunnel"
	"github.com/jackh0006/XHR/internal/wire"
	utls "github.com/refraction-networking/utls"
)

type mobileConfig struct {
	GASURL      string   `json:"gas_url"`
	GASURLs     []string `json:"gas_urls"`
	GASHost     string   `json:"gas_host"`
	FrontDomain string   `json:"front_domain"`
	GoogleIP    string   `json:"google_ip"`
	AuthKey     string   `json:"auth_key"`
	E2EKey      string   `json:"e2e_key"`
	ListenPort  int      `json:"listen_port"`
}

type mobileEngine struct {
	cfg      mobileConfig
	client   *tunnel.Client
	listener net.Listener

	mu    sync.Mutex
	conns map[net.Conn]struct{}
}

var engine struct {
	mu sync.Mutex
	e  *mobileEngine
}

var logRing struct {
	mu    sync.Mutex
	lines []string
}

func logf(format string, args ...any) {
	line := time.Now().Format("15:04:05") + " " + fmt.Sprintf(format, args...)
	logRing.mu.Lock()
	logRing.lines = append(logRing.lines, line)
	if len(logRing.lines) > 800 {
		logRing.lines = logRing.lines[len(logRing.lines)-800:]
	}
	logRing.mu.Unlock()
}

//export xhr_start_json
func xhr_start_json(raw *C.char) *C.char {
	engine.mu.Lock()
	defer engine.mu.Unlock()
	if engine.e != nil {
		return C.CString("already running")
	}
	var cfg mobileConfig
	if err := json.Unmarshal([]byte(C.GoString(raw)), &cfg); err != nil {
		return C.CString("invalid configuration")
	}
	if cfg.GASHost == "" {
		cfg.GASHost = "script.google.com"
	}
	if cfg.FrontDomain == "" {
		cfg.FrontDomain = "www.google.com"
	}
	if cfg.GoogleIP == "" {
		cfg.GoogleIP = "216.239.38.120"
	}
	if cfg.ListenPort < 1024 || cfg.ListenPort > 65535 {
		cfg.ListenPort = 1080
	}
	if cfg.AuthKey == "" || cfg.E2EKey == "" {
		return C.CString("missing configuration")
	}
	urls := append([]string{}, cfg.GASURLs...)
	if len(urls) == 0 && cfg.GASURL != "" {
		urls = append(urls, cfg.GASURL)
	}
	if len(urls) == 0 {
		return C.CString("missing deployment URL")
	}
	if len(urls) > 5 {
		return C.CString("use at most 5 deployments")
	}
	for i, u := range urls {
		u = strings.TrimSpace(u)
		if strings.HasPrefix(u, "AKfycb") {
			u = "https://script.google.com/macros/s/" + u + "/exec"
		}
		urls[i] = u
	}
	key, err := wire.ParseKey(cfg.E2EKey)
	if err != nil {
		return C.CString("invalid tunnel key")
	}
	client, err := tunnel.New(tunnel.Config{
		GASURLs:     urls,
		GASHost:     cfg.GASHost,
		FrontDomain: cfg.FrontDomain,
		GoogleIP:    cfg.GoogleIP,
		AuthKey:     cfg.AuthKey,
		Key:         key,
		OnLog:       func(line string) { logf("%s", line) },
	})
	if err != nil {
		return C.CString(err.Error())
	}
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(cfg.ListenPort))
	if err != nil {
		_ = client.Close()
		return C.CString(fmt.Sprintf("port %d is busy", cfg.ListenPort))
	}
	e := &mobileEngine{cfg: cfg, client: client, listener: ln, conns: map[net.Conn]struct{}{}}
	engine.e = e
	go e.serve()
	logf("XHR %s listening on %s", versionString, ln.Addr().String())
	return C.CString("")
}

//export xhr_stop
func xhr_stop() {
	engine.mu.Lock()
	e := engine.e
	engine.e = nil
	engine.mu.Unlock()
	if e != nil {
		e.close()
	}
}

//export xhr_stats_json
func xhr_stats_json() *C.char {
	engine.mu.Lock()
	e := engine.e
	engine.mu.Unlock()
	out := map[string]any{
		"up":      int64(0),
		"down":    int64(0),
		"calls":   int64(0),
		"streams": int64(0),
		"active":  int64(0),
		"running": e != nil,
		"error":   "",
		"version": versionString,
	}
	if e != nil {
		s := e.client.Stats()
		out["up"] = s.Up.Load()
		out["down"] = s.Down.Load()
		out["calls"] = s.Calls.Load()
		out["streams"] = s.Streams.Load()
		out["active"] = s.Active.Load()
		out["error"] = e.client.LastError()
	}
	b, _ := json.Marshal(out)
	return C.CString(string(b))
}

//export xhr_logs_text
func xhr_logs_text() *C.char {
	logRing.mu.Lock()
	defer logRing.mu.Unlock()
	out := strings.Join(logRing.lines, "\n")
	logRing.lines = nil
	return C.CString(out)
}

//export xhr_logs_clear
func xhr_logs_clear() {
	logRing.mu.Lock()
	logRing.lines = nil
	logRing.mu.Unlock()
}

//export xhr_test_sni_json
func xhr_test_sni_json(raw *C.char) *C.char {
	var req struct {
		IP   string `json:"ip"`
		SNI  string `json:"sni"`
		Port int    `json:"port"`
	}
	if err := json.Unmarshal([]byte(C.GoString(raw)), &req); err != nil || req.IP == "" || req.SNI == "" {
		return C.CString(`{"ok":false,"error":"invalid request"}`)
	}
	if req.Port == 0 {
		req.Port = 443
	}
	start := time.Now()
	d := &net.Dialer{Timeout: 7 * time.Second}
	rawConn, err := d.Dial("tcp", net.JoinHostPort(req.IP, strconv.Itoa(req.Port)))
	if err != nil {
		return probeJSON(false, 0, "connect failed", "", "", "")
	}
	defer rawConn.Close()
	u := utls.UClient(rawConn, &utls.Config{
		ServerName: req.SNI,
		NextProtos: []string{"h2", "http/1.1"},
		MinVersion: tls.VersionTLS12,
	}, utls.HelloChrome_Auto)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := u.HandshakeContext(ctx); err != nil {
		return probeJSON(false, 0, "handshake failed", "", "", "")
	}
	ms := time.Since(start).Milliseconds()
	state := u.ConnectionState()
	cn, issuer, alpn := "", "", state.NegotiatedProtocol
	if len(state.PeerCertificates) > 0 {
		cn = state.PeerCertificates[0].Subject.CommonName
		issuer = state.PeerCertificates[0].Issuer.CommonName
	}
	return probeJSON(true, int(ms), "", cn, issuer, alpn)
}

//export xhr_version
func xhr_version() *C.char {
	return C.CString(versionString)
}

const versionString = "1.0.1"

func probeJSON(ok bool, ms int, msg, cn, issuer, alpn string) *C.char {
	b, _ := json.Marshal(map[string]any{
		"ok": ok, "ms": ms, "error": msg,
		"cn": cn, "issuer": issuer, "alpn": alpn,
	})
	return C.CString(string(b))
}

func main() {}

func (e *mobileEngine) serve() {
	for {
		conn, err := e.listener.Accept()
		if err != nil {
			return
		}
		e.mu.Lock()
		e.conns[conn] = struct{}{}
		e.mu.Unlock()
		go func() {
			defer func() {
				e.mu.Lock()
				delete(e.conns, conn)
				e.mu.Unlock()
				_ = conn.Close()
			}()
			e.handleSocks(conn)
		}()
	}
}

func (e *mobileEngine) close() {
	_ = e.listener.Close()
	e.mu.Lock()
	for c := range e.conns {
		_ = c.Close()
	}
	e.conns = map[net.Conn]struct{}{}
	e.mu.Unlock()
	_ = e.client.Close()
	logf("stopped")
}

func (e *mobileEngine) handleSocks(conn net.Conn) {
	if err := socksHandshake(conn); err != nil {
		return
	}
	host, port, err := socksRequest(conn)
	if err != nil {
		return
	}
	remote, err := e.client.Open(host, port)
	if err != nil {
		logf("open %s:%d failed: %v", host, port, err)
		_, _ = conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, conn)
		_ = remote.Close()
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, remote)
		_ = conn.Close()
		done <- struct{}{}
	}()
	<-done
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
}

func socksHandshake(conn net.Conn) error {
	h := make([]byte, 2)
	if _, err := io.ReadFull(conn, h); err != nil {
		return err
	}
	if h[0] != 5 {
		return errors.New("socks version")
	}
	methods := make([]byte, int(h[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	for _, m := range methods {
		if m == 0 {
			_, err := conn.Write([]byte{5, 0})
			return err
		}
	}
	_, _ = conn.Write([]byte{5, 0xff})
	return errors.New("socks auth")
}

func socksRequest(conn net.Conn) (string, uint16, error) {
	h := make([]byte, 4)
	if _, err := io.ReadFull(conn, h); err != nil {
		return "", 0, err
	}
	if h[0] != 5 {
		return "", 0, errors.New("socks version")
	}
	if h[1] != 1 {
		_, _ = conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", 0, errors.New("socks command")
	}
	var host string
	switch h[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return "", 0, err
		}
		if l[0] == 0 {
			return "", 0, errors.New("socks address")
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	default:
		return "", 0, errors.New("socks address")
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(conn, p); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(p), nil
}

~~~

## `mobile/jni.c`

~~~c
/*
 * JNI entry points for the XHR Android client.
 * The package is com.jackh0006.xhr and the Kotlin object is Native.
 */
#ifdef __ANDROID__

#include <jni.h>
#include <stdlib.h>

extern char *xhr_start_json(char *raw);
extern void xhr_stop(void);
extern char *xhr_stats_json(void);
extern char *xhr_logs_text(void);
extern void xhr_logs_clear(void);
extern char *xhr_test_sni_json(char *raw);
extern char *xhr_version(void);

static jstring take(JNIEnv *env, char *s)
{
    jstring out;
    if (s == NULL) return NULL;
    out = (*env)->NewStringUTF(env, s);
    free(s);
    return out;
}

static jstring call_json(JNIEnv *env, jstring input, char *(*fn)(char *))
{
    const char *raw;
    char *result;
    jstring out;

    if (input == NULL) return NULL;
    raw = (*env)->GetStringUTFChars(env, input, NULL);
    if (raw == NULL) return NULL;
    result = fn((char *) raw);
    (*env)->ReleaseStringUTFChars(env, input, raw);
    out = take(env, result);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_start(JNIEnv *env, jobject self, jstring config)
{
    (void) self;
    return call_json(env, config, xhr_start_json);
}

JNIEXPORT void JNICALL
Java_com_jackh0006_xhr_Native_stop(JNIEnv *env, jobject self)
{
    (void) env;
    (void) self;
    xhr_stop();
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_stats(JNIEnv *env, jobject self)
{
    (void) self;
    return take(env, xhr_stats_json());
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_logs(JNIEnv *env, jobject self)
{
    (void) self;
    return take(env, xhr_logs_text());
}

JNIEXPORT void JNICALL
Java_com_jackh0006_xhr_Native_clearLogs(JNIEnv *env, jobject self)
{
    (void) env;
    (void) self;
    xhr_logs_clear();
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_testSni(JNIEnv *env, jobject self, jstring input)
{
    (void) self;
    return call_json(env, input, xhr_test_sni_json);
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_version(JNIEnv *env, jobject self)
{
    (void) self;
    return take(env, xhr_version());
}

#endif

~~~
