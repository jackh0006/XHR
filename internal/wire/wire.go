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
