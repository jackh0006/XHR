// SPDX-License-Identifier: MIT
// XHR Cloudflare Worker exit (phase 2).
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
