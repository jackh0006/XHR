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

func TestExitEcho(t *testing.T) {
	// Local echo server.
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

	key, _ := wire.ParseKey("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	e := &exitServer{key: key, gasHMAC: "gas-secret", sessions: map[string]*session{}}
	sid, _ := wire.NewSID()

	openN, openX, err := wire.Seal(key, sid, wire.Frame{
		Op: "open", Stream: 1, Host: host, Port: uint16(port), Time: time.Now().Unix(),
	}, 32)
	if err != nil {
		t.Fatal(err)
	}
	dataN, dataX, err := wire.Seal(key, sid, wire.Frame{
		Op: "data", Stream: 1, Data: []byte("hello"), Time: time.Now().Unix(),
	}, 32)
	if err != nil {
		t.Fatal(err)
	}

	body, _ := json.Marshal(proto.ExitRequest{
		V: 1, S: sid,
		Q: []proto.Item{{N: openN, X: openX}, {N: dataN, X: dataX}},
	})
	req := httptest.NewRequest("POST", "/relay", bytes.NewReader(body))
	req.Header.Set("X-XHR-MAC", proto.HMACHex("gas-secret", string(body)))
	rr := httptest.NewRecorder()
	e.handleRelay(rr, req)
	if rr.Code != 200 {
		t.Fatalf("status %d: %s", rr.Code, rr.Body.String())
	}
	var out proto.Response
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	var got []byte
	for _, it := range out.R {
		f, err := wire.Open(key, sid, it.N, it.X)
		if err != nil {
			continue
		}
		if f.Op == "data" && f.Stream == 1 {
			got = append(got, f.Data...)
		}
	}
	if string(got) != "hello" {
		t.Fatalf("expected echo, got %q (err=%q)", got, out.E)
	}
}

func TestExitRejectsBadMAC(t *testing.T) {
	key, _ := wire.ParseKey("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	e := &exitServer{key: key, gasHMAC: "gas-secret", sessions: map[string]*session{}}
	body := []byte(`{"v":1,"s":"0011223344556677","q":[]}`)
	req := httptest.NewRequest("POST", "/relay", bytes.NewReader(body))
	req.Header.Set("X-XHR-MAC", "00")
	rr := httptest.NewRecorder()
	e.handleRelay(rr, req)
	if rr.Code != 401 {
		t.Fatalf("expected 401, got %d", rr.Code)
	}
}
