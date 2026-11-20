// SPDX-License-Identifier: MIT
package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
)

func testKey(t *testing.T) []byte {
	t.Helper()
	key, err := wire.ParseKey("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func relay(t *testing.T, e *exitServer, sid string, frames []wire.Frame) []wire.Frame {
	t.Helper()
	items := make([]proto.Item, 0, len(frames))
	for _, f := range frames {
		n, x, err := wire.Seal(e.key, sid, f, 32)
		if err != nil {
			t.Fatal(err)
		}
		items = append(items, proto.Item{N: n, X: x})
	}
	body, _ := json.Marshal(proto.ExitRequest{V: 1, S: sid, Q: items})
	req := httptest.NewRequest("POST", "/relay", bytes.NewReader(body))
	req.Header.Set("X-XHR-MAC", proto.HMACHex(e.gasHMAC, string(body)))
	rr := httptest.NewRecorder()
	e.handleRelay(rr, req)
	if rr.Code != 200 {
		t.Fatalf("status %d: %s", rr.Code, rr.Body.String())
	}
	var out proto.Response
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if out.E != "" {
		t.Fatalf("exit error: %s", out.E)
	}
	got := make([]wire.Frame, 0, len(out.R))
	for _, it := range out.R {
		f, err := wire.Open(e.key, sid, it.N, it.X)
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, f)
	}
	return got
}

func waitFor(t *testing.T, e *exitServer, sid string, pred func(wire.Frame) bool) wire.Frame {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		// A no-op frame keeps the test poll in the fast upload path. A real
		// standing poll with an empty queue waits 15 seconds by design.
		got := relay(t, e, sid, []wire.Frame{{
			Op: "noop", Stream: 1, Time: time.Now().Unix(),
		}})
		for _, f := range got {
			if pred(f) {
				return f
			}
		}
	}
	t.Fatal("expected frame did not arrive")
	return wire.Frame{}
}

func TestExitEcho(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { _, _ = io.Copy(c, c); _ = c.Close() }()
		}
	}()
	host, portStr, _ := net.SplitHostPort(ln.Addr().String())
	port, _ := strconv.Atoi(portStr)

	e := &exitServer{key: testKey(t), gasHMAC: "gas-secret", sessions: map[string]*session{}}
	sid, _ := wire.NewSID()
	first := relay(t, e, sid, []wire.Frame{{
		Op: "open", Stream: 1, Host: host, Port: uint16(port), Time: time.Now().Unix(),
	}})
	seenOpen := false
	for _, f := range first {
		if f.Op == "open_ok" && f.Stream == 1 {
			seenOpen = true
		}
	}
	if !seenOpen {
		waitFor(t, e, sid, func(f wire.Frame) bool { return f.Op == "open_ok" && f.Stream == 1 })
	}

	echo := relay(t, e, sid, []wire.Frame{{
		Op: "data", Stream: 1, Data: []byte("hello"), Time: time.Now().Unix(),
	}})
	gotData := ""
	for _, f := range echo {
		if f.Op == "data" && f.Stream == 1 {
			gotData += string(f.Data)
		}
	}
	if gotData == "" {
		f := waitFor(t, e, sid, func(f wire.Frame) bool {
			return f.Op == "data" && f.Stream == 1
		})
		gotData = string(f.Data)
	}
	if gotData != "hello" {
		t.Fatalf("expected echo, got %q", gotData)
	}
}

func TestExitRejectsBadMAC(t *testing.T) {
	e := &exitServer{key: testKey(t), gasHMAC: "gas-secret", sessions: map[string]*session{}}
	body := []byte(`{"v":1,"s":"0011223344556677","q":[]}`)
	req := httptest.NewRequest("POST", "/relay", bytes.NewReader(body))
	req.Header.Set("X-XHR-MAC", "00")
	rr := httptest.NewRecorder()
	e.handleRelay(rr, req)
	if rr.Code != 401 {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}

func TestExitOpenError(t *testing.T) {
	e := &exitServer{key: testKey(t), gasHMAC: "gas-secret", sessions: map[string]*session{}}
	sid, _ := wire.NewSID()
	first := relay(t, e, sid, []wire.Frame{{
		Op: "open", Stream: 1, Host: "127.0.0.1", Port: 1, Time: time.Now().Unix(),
	}})
	for _, f := range first {
		if f.Op == "open_err" && f.Stream == 1 {
			return
		}
	}
	f := waitFor(t, e, sid, func(f wire.Frame) bool {
		return (f.Op == "open_err" || f.Op == "close") && f.Stream == 1
	})
	if f.Op != "open_err" {
		t.Fatalf("expected open_err, got %s", f.Op)
	}
}
