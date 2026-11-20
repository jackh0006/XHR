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
