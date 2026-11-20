// SPDX-License-Identifier: MIT
package wire

import (
	"strings"
	"testing"
)

func testKey() []byte {
	k, _ := ParseKey("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	return k
}

func TestSealOpenRoundTrip(t *testing.T) {
	key := testKey()
	sid := "0011223344556677"
	f := Frame{Op: "data", Stream: 3, Data: []byte("hello world"), Time: 1763000000}
	n, x, err := Seal(key, sid, f, 64)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Open(key, sid, n, x)
	if err != nil {
		t.Fatal(err)
	}
	if got.Op != f.Op || got.Stream != f.Stream || string(got.Data) != string(f.Data) {
		t.Fatalf("round trip mismatch: %+v", got)
	}
	if got.Random == "" {
		t.Fatal("random field missing")
	}
}

func TestWrongKeyFails(t *testing.T) {
	key := testKey()
	other := append([]byte{}, key...)
	other[0] ^= 0xff
	n, x, err := Seal(key, "sid", Frame{Op: "data", Data: []byte("x")}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Open(other, "sid", n, x); err == nil {
		t.Fatal("wrong key accepted")
	}
}

func TestTamperFails(t *testing.T) {
	key := testKey()
	n, x, err := Seal(key, "sid", Frame{Op: "data", Data: []byte("secret")}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if x == "" {
		t.Fatal("empty ciphertext")
	}
	// Flip one base64 character.
	b := []byte(x)
	if b[0] == 'A' {
		b[0] = 'B'
	} else {
		b[0] = 'A'
	}
	if _, err := Open(key, "sid", n, string(b)); err == nil {
		t.Fatal("tampered ciphertext accepted")
	}
}

func TestSessionBinding(t *testing.T) {
	key := testKey()
	n, x, err := Seal(key, "sid1", Frame{Op: "data", Data: []byte("x")}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Open(key, "sid2", n, x); err == nil {
		t.Fatal("frame accepted under a different session id")
	}
}

func TestParseKeyRejectsShort(t *testing.T) {
	if _, err := ParseKey(strings.Repeat("00", 31)); err == nil {
		t.Fatal("31-byte key accepted")
	}
	if _, err := ParseKey("zz"); err == nil {
		t.Fatal("non-hex key accepted")
	}
}
