// SPDX-License-Identifier: MIT
// Node test suite for the Cloudflare frame helpers.
import test from "node:test";
import assert from "node:assert/strict";
import {
  importAesKey,
  sealFrame,
  openFrame,
  hmacHex,
  verifyHmac,
  hexToBytes,
} from "./frames.js";

const KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
const SID = "0011223344556677";

test("AES-GCM frame round trip", async () => {
  const key = await importAesKey(KEY);
  const sealed = await sealFrame(key, SID, {
    o: "data",
    st: 1,
    d: Buffer.from("hello").toString("base64"),
  });
  assert.ok(sealed.n.length > 0);
  assert.ok(sealed.x.length > 0);
  const frame = await openFrame(key, SID, sealed.n, sealed.x);
  assert.equal(frame.o, "data");
  assert.equal(frame.st, 1);
  assert.equal(Buffer.from(frame.d, "base64").toString(), "hello");
  assert.ok(frame.r);
});

test("wrong session cannot decrypt", async () => {
  const key = await importAesKey(KEY);
  const sealed = await sealFrame(key, SID, { o: "data", st: 1, d: "" });
  await assert.rejects(() => openFrame(key, "ffffffffffffffff", sealed.n, sealed.x));
});

test("tampered ciphertext fails", async () => {
  const key = await importAesKey(KEY);
  const sealed = await sealFrame(key, SID, { o: "data", st: 1, d: "" });
  let x = sealed.x;
  const first = x[0] === "A" ? "B" : "A";
  x = first + x.slice(1);
  await assert.rejects(() => openFrame(key, SID, sealed.n, x));
});

test("HMAC known vector", async () => {
  const got = await hmacHex("key", "The quick brown fox jumps over the lazy dog");
  assert.equal(got, "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
});

test("HMAC verify", async () => {
  const mac = await hmacHex("secret", "message");
  assert.equal(await verifyHmac("secret", "message", mac), true);
  assert.equal(await verifyHmac("secret", "other", mac), false);
  assert.equal(await verifyHmac("wrong", "message", mac), false);
});

test("key decoding", () => {
  const b = hexToBytes(KEY);
  assert.equal(b.length, 32);
});
