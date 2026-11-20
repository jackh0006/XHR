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
