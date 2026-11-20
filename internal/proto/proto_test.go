// SPDX-License-Identifier: MIT
package proto

import "testing"

func TestHMACKnownVector(t *testing.T) {
	// RFC 4231-style vector: HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
	const want = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
	if got := HMACHex("key", "The quick brown fox jumps over the lazy dog"); got != want {
		t.Fatalf("HMAC mismatch: %s", got)
	}
}

func TestVerifyHMAC(t *testing.T) {
	mac := HMACHex("secret", "message")
	if !VerifyHMAC("secret", "message", mac) {
		t.Fatal("valid HMAC rejected")
	}
	if VerifyHMAC("secret", "message2", mac) {
		t.Fatal("HMAC accepted for different message")
	}
	if VerifyHMAC("wrong", "message", mac) {
		t.Fatal("HMAC accepted for wrong key")
	}
	if VerifyHMAC("secret", "message", "zz") {
		t.Fatal("bad hex accepted")
	}
}
