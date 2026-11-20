// SPDX-License-Identifier: MIT
// Executes gas/Code.gs inside a VM with mocked Apps Script globals and
// verifies the authentication, forwarding and error paths.
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import crypto from "node:crypto";

const source = fs.readFileSync(new URL("./Code.gs", import.meta.url), "utf8");

function hmacHex(secret, msg) {
  return crypto.createHmac("sha256", secret).update(msg).digest("hex");
}

function makeContext(properties) {
  let lastFetch = null;
  const context = {
    Date,
    Math,
    JSON,
    isNaN,
    parseInt,
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (name) => properties[name] ?? "",
      }),
    },
    Utilities: {
      computeHmacSha256Signature: (value, key) =>
        Array.from(crypto.createHmac("sha256", key).update(value).digest()).map((b) =>
          b > 127 ? b - 256 : b,
        ),
    },
    UrlFetchApp: {
      fetch: (url, opts) => {
        lastFetch = { url, opts };
        return {
          getResponseCode: () => 200,
          getContentText: () => '{"r":[],"ok":true}',
        };
      },
    },
    ContentService: {
      MimeType: { JSON: "application/json", HTML: "text/html" },
      createTextOutput: (text) => ({
        text,
        mime: null,
        setMimeType(m) {
          this.mime = m;
          return this;
        },
        getContentText() {
          return this.text;
        },
      }),
    },
    console,
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  return { context, fetch: () => lastFetch };
}

const PROPS = {
  AUTH_KEY: "client-secret",
  EXIT_URL: "https://exit.test/relay",
  EXIT_HMAC: "exit-secret",
  MAX_ITEMS: "64",
};

test("valid request is signed and forwarded", () => {
  const { context, fetch } = makeContext(PROPS);
  const t = Math.floor(Date.now() / 1000);
  const sid = "0011223344556677";
  const body = JSON.stringify({
    v: 1,
    t,
    s: sid,
    m: hmacHex(PROPS.AUTH_KEY, `${t}.${sid}`),
    q: [],
  });
  const res = context.doPost({ postData: { contents: body } });
  assert.equal(res.getContentText(), '{"r":[],"ok":true}');
  const out = fetch();
  assert.equal(out.url, PROPS.EXIT_URL);
  assert.equal(out.opts.method, "post");
  assert.equal(out.opts.headers["X-XHR-MAC"], hmacHex(PROPS.EXIT_HMAC, out.opts.payload));
});

test("wrong client HMAC gets the decoy", () => {
  const { context, fetch } = makeContext(PROPS);
  const t = Math.floor(Date.now() / 1000);
  const body = JSON.stringify({ v: 1, t, s: "0011223344556677", m: "00", q: [] });
  const res = context.doPost({ postData: { contents: body } });
  assert.match(res.getContentText(), /<!doctype html>/i);
  assert.equal(fetch(), null);
});

test("old timestamp is rejected", () => {
  const { context } = makeContext(PROPS);
  const t = Math.floor(Date.now() / 1000) - 1000;
  const sid = "0011223344556677";
  const body = JSON.stringify({
    v: 1,
    t,
    s: sid,
    m: hmacHex(PROPS.AUTH_KEY, `${t}.${sid}`),
    q: [],
  });
  const res = context.doPost({ postData: { contents: body } });
  assert.equal(res.getContentText(), '{"e":"expired"}');
});

test("misspelled version is rejected", () => {
  const { context } = makeContext(PROPS);
  const body = JSON.stringify({ v: 2, t: 1, s: "x", m: "00", q: [] });
  const res = context.doPost({ postData: { contents: body } });
  assert.match(res.getContentText(), /bad request/);
});
