// SPDX-License-Identifier: MIT
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/tunnel"
)

// TestTunnelThroughRealExit runs the production client engine against the
// production exit handler, with a tiny Apps Script stand-in in between. This
// covers the full frame flow: client HMAC, GAS re-signing, session creation,
// open_ok, data, and close.
func TestTunnelThroughRealExit(t *testing.T) {
	key := testKey(t)
	const authKey = "client-auth"
	const gasHMAC = "gas-hmac"

	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { _, _ = io.Copy(c, c); _ = c.Close() }()
		}
	}()
	host, portStr, _ := net.SplitHostPort(echo.Addr().String())
	port, _ := strconv.Atoi(portStr)

	e := &exitServer{key: key, gasHMAC: gasHMAC, sessions: map[string]*session{}}
	exit := httptest.NewServer(http.HandlerFunc(e.handleRelay))
	defer exit.Close()

	gas := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req proto.ClientRequest
		if err := json.Unmarshal(body, &req); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		if !proto.VerifyHMAC(authKey, fmt.Sprintf("%d.%s", req.T, req.S), req.M) {
			http.Error(w, "bad client mac", http.StatusForbidden)
			return
		}
		exitBody, _ := json.Marshal(proto.ExitRequest{V: 1, S: req.S, Q: req.Q})
		fwd, _ := http.NewRequestWithContext(
			r.Context(), http.MethodPost, exit.URL+"/relay", bytes.NewReader(exitBody),
		)
		fwd.Header.Set("X-XHR-MAC", proto.HMACHex(gasHMAC, string(exitBody)))
		resp, err := http.DefaultClient.Do(fwd)
		if err != nil {
			http.Error(w, "exit unreachable", http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, resp.Body)
	}))
	defer gas.Close()

	c, err := tunnel.New(tunnel.Config{
		GASURL:  gas.URL + "/exec",
		AuthKey: authKey,
		Key:     key,
		HTTP:    gas.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	conn, err := c.Open(host, uint16(port))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	msg := []byte("end-to-end through the real exit")
	if _, err := conn.Write(msg); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatalf("got %q want %q", got, msg)
	}
	stats := c.Stats()
	if stats.Calls.Load() == 0 || stats.Up.Load() != int64(len(msg)) {
		t.Fatalf("stats calls=%d up=%d", stats.Calls.Load(), stats.Up.Load())
	}
}

func TestTunnelThroughExitOpenError(t *testing.T) {
	key := testKey(t)
	const authKey = "client-auth"
	const gasHMAC = "gas-hmac"
	e := &exitServer{key: key, gasHMAC: gasHMAC, sessions: map[string]*session{}}
	exit := httptest.NewServer(http.HandlerFunc(e.handleRelay))
	defer exit.Close()
	gas := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var req proto.ClientRequest
		if err := json.Unmarshal(body, &req); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		if !proto.VerifyHMAC(authKey, fmt.Sprintf("%d.%s", req.T, req.S), req.M) {
			http.Error(w, "bad client mac", http.StatusForbidden)
			return
		}
		exitBody, _ := json.Marshal(proto.ExitRequest{V: 1, S: req.S, Q: req.Q})
		fwd, _ := http.NewRequestWithContext(
			r.Context(), http.MethodPost, exit.URL+"/relay", bytes.NewReader(exitBody),
		)
		fwd.Header.Set("X-XHR-MAC", proto.HMACHex(gasHMAC, string(exitBody)))
		resp, err := http.DefaultClient.Do(fwd)
		if err != nil {
			http.Error(w, "exit unreachable", http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, resp.Body)
	}))
	defer gas.Close()

	c, err := tunnel.New(tunnel.Config{
		GASURL: gas.URL + "/exec", AuthKey: authKey, Key: key, HTTP: gas.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	if _, err := c.Open("127.0.0.1", 1); err == nil {
		t.Fatal("open to a closed port succeeded")
	}
}
