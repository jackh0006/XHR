// SPDX-License-Identifier: MIT
package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackh0006/XHR/internal/tunnel"
	"github.com/jackh0006/XHR/internal/wire"
	utls "github.com/refraction-networking/utls"
)

type mobileConfig struct {
	GASURL      string   `json:"gas_url"`
	GASURLs     []string `json:"gas_urls"`
	GASHost     string   `json:"gas_host"`
	FrontDomain string   `json:"front_domain"`
	GoogleIP    string   `json:"google_ip"`
	AuthKey     string   `json:"auth_key"`
	E2EKey      string   `json:"e2e_key"`
	ListenPort  int      `json:"listen_port"`
}

type mobileEngine struct {
	cfg      mobileConfig
	client   *tunnel.Client
	listener net.Listener

	mu    sync.Mutex
	conns map[net.Conn]struct{}
}

var engine struct {
	mu sync.Mutex
	e  *mobileEngine
}

var logRing struct {
	mu    sync.Mutex
	lines []string
}

func logf(format string, args ...any) {
	line := time.Now().Format("15:04:05") + " " + fmt.Sprintf(format, args...)
	logRing.mu.Lock()
	logRing.lines = append(logRing.lines, line)
	if len(logRing.lines) > 800 {
		logRing.lines = logRing.lines[len(logRing.lines)-800:]
	}
	logRing.mu.Unlock()
}

//export xhr_start_json
func xhr_start_json(raw *C.char) *C.char {
	engine.mu.Lock()
	defer engine.mu.Unlock()
	if engine.e != nil {
		return C.CString("already running")
	}
	var cfg mobileConfig
	if err := json.Unmarshal([]byte(C.GoString(raw)), &cfg); err != nil {
		return C.CString("invalid configuration")
	}
	if cfg.GASHost == "" {
		cfg.GASHost = "script.google.com"
	}
	if cfg.FrontDomain == "" {
		cfg.FrontDomain = "www.google.com"
	}
	if cfg.GoogleIP == "" {
		cfg.GoogleIP = "216.239.38.120"
	}
	if cfg.ListenPort < 1024 || cfg.ListenPort > 65535 {
		cfg.ListenPort = 1080
	}
	if cfg.AuthKey == "" || cfg.E2EKey == "" {
		return C.CString("missing configuration")
	}
	urls := append([]string{}, cfg.GASURLs...)
	if len(urls) == 0 && cfg.GASURL != "" {
		urls = append(urls, cfg.GASURL)
	}
	if len(urls) == 0 {
		return C.CString("missing deployment URL")
	}
	if len(urls) > 5 {
		return C.CString("use at most 5 deployments")
	}
	for i, u := range urls {
		u = strings.TrimSpace(u)
		if strings.HasPrefix(u, "AKfycb") {
			u = "https://script.google.com/macros/s/" + u + "/exec"
		}
		urls[i] = u
	}
	key, err := wire.ParseKey(cfg.E2EKey)
	if err != nil {
		return C.CString("invalid tunnel key")
	}
	client, err := tunnel.New(tunnel.Config{
		GASURLs:     urls,
		GASHost:     cfg.GASHost,
		FrontDomain: cfg.FrontDomain,
		GoogleIP:    cfg.GoogleIP,
		AuthKey:     cfg.AuthKey,
		Key:         key,
		OnLog:       func(line string) { logf("%s", line) },
	})
	if err != nil {
		return C.CString(err.Error())
	}
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(cfg.ListenPort))
	if err != nil {
		_ = client.Close()
		return C.CString(fmt.Sprintf("port %d is busy", cfg.ListenPort))
	}
	e := &mobileEngine{cfg: cfg, client: client, listener: ln, conns: map[net.Conn]struct{}{}}
	engine.e = e
	go e.serve()
	logf("XHR %s listening on %s", versionString, ln.Addr().String())
	return C.CString("")
}

//export xhr_stop
func xhr_stop() {
	engine.mu.Lock()
	e := engine.e
	engine.e = nil
	engine.mu.Unlock()
	if e != nil {
		e.close()
	}
}

//export xhr_stats_json
func xhr_stats_json() *C.char {
	engine.mu.Lock()
	e := engine.e
	engine.mu.Unlock()
	out := map[string]any{
		"up":      int64(0),
		"down":    int64(0),
		"calls":   int64(0),
		"streams": int64(0),
		"active":  int64(0),
		"running": e != nil,
		"error":   "",
		"version": versionString,
	}
	if e != nil {
		s := e.client.Stats()
		out["up"] = s.Up.Load()
		out["down"] = s.Down.Load()
		out["calls"] = s.Calls.Load()
		out["streams"] = s.Streams.Load()
		out["active"] = s.Active.Load()
		out["error"] = e.client.LastError()
	}
	b, _ := json.Marshal(out)
	return C.CString(string(b))
}

//export xhr_logs_text
func xhr_logs_text() *C.char {
	logRing.mu.Lock()
	defer logRing.mu.Unlock()
	out := strings.Join(logRing.lines, "\n")
	logRing.lines = nil
	return C.CString(out)
}

//export xhr_logs_clear
func xhr_logs_clear() {
	logRing.mu.Lock()
	logRing.lines = nil
	logRing.mu.Unlock()
}

//export xhr_test_sni_json
func xhr_test_sni_json(raw *C.char) *C.char {
	var req struct {
		IP   string `json:"ip"`
		SNI  string `json:"sni"`
		Port int    `json:"port"`
	}
	if err := json.Unmarshal([]byte(C.GoString(raw)), &req); err != nil || req.IP == "" || req.SNI == "" {
		return C.CString(`{"ok":false,"error":"invalid request"}`)
	}
	if req.Port == 0 {
		req.Port = 443
	}
	start := time.Now()
	d := &net.Dialer{Timeout: 7 * time.Second}
	rawConn, err := d.Dial("tcp", net.JoinHostPort(req.IP, strconv.Itoa(req.Port)))
	if err != nil {
		return probeJSON(false, 0, "connect failed", "", "", "")
	}
	defer rawConn.Close()
	u := utls.UClient(rawConn, &utls.Config{
		ServerName: req.SNI,
		NextProtos: []string{"h2", "http/1.1"},
		MinVersion: tls.VersionTLS12,
	}, utls.HelloChrome_Auto)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := u.HandshakeContext(ctx); err != nil {
		return probeJSON(false, 0, "handshake failed", "", "", "")
	}
	ms := time.Since(start).Milliseconds()
	state := u.ConnectionState()
	cn, issuer, alpn := "", "", state.NegotiatedProtocol
	if len(state.PeerCertificates) > 0 {
		cn = state.PeerCertificates[0].Subject.CommonName
		issuer = state.PeerCertificates[0].Issuer.CommonName
	}
	return probeJSON(true, int(ms), "", cn, issuer, alpn)
}

//export xhr_version
func xhr_version() *C.char {
	return C.CString(versionString)
}

const versionString = "1.0.2"

func probeJSON(ok bool, ms int, msg, cn, issuer, alpn string) *C.char {
	b, _ := json.Marshal(map[string]any{
		"ok": ok, "ms": ms, "error": msg,
		"cn": cn, "issuer": issuer, "alpn": alpn,
	})
	return C.CString(string(b))
}

func main() {}

func (e *mobileEngine) serve() {
	for {
		conn, err := e.listener.Accept()
		if err != nil {
			return
		}
		e.mu.Lock()
		e.conns[conn] = struct{}{}
		e.mu.Unlock()
		go func() {
			defer func() {
				e.mu.Lock()
				delete(e.conns, conn)
				e.mu.Unlock()
				_ = conn.Close()
			}()
			e.handleSocks(conn)
		}()
	}
}

func (e *mobileEngine) close() {
	_ = e.listener.Close()
	e.mu.Lock()
	for c := range e.conns {
		_ = c.Close()
	}
	e.conns = map[net.Conn]struct{}{}
	e.mu.Unlock()
	_ = e.client.Close()
	logf("stopped")
}

func (e *mobileEngine) handleSocks(conn net.Conn) {
	if err := socksHandshake(conn); err != nil {
		return
	}
	host, port, err := socksRequest(conn)
	if err != nil {
		return
	}
	remote, err := e.client.Open(host, port)
	if err != nil {
		logf("open %s:%d failed: %v", host, port, err)
		_, _ = conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()
	if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, conn)
		_ = remote.Close()
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, remote)
		_ = conn.Close()
		done <- struct{}{}
	}()
	<-done
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
}

func socksHandshake(conn net.Conn) error {
	h := make([]byte, 2)
	if _, err := io.ReadFull(conn, h); err != nil {
		return err
	}
	if h[0] != 5 {
		return errors.New("socks version")
	}
	methods := make([]byte, int(h[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	for _, m := range methods {
		if m == 0 {
			_, err := conn.Write([]byte{5, 0})
			return err
		}
	}
	_, _ = conn.Write([]byte{5, 0xff})
	return errors.New("socks auth")
}

func socksRequest(conn net.Conn) (string, uint16, error) {
	h := make([]byte, 4)
	if _, err := io.ReadFull(conn, h); err != nil {
		return "", 0, err
	}
	if h[0] != 5 {
		return "", 0, errors.New("socks version")
	}
	if h[1] != 1 {
		_, _ = conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", 0, errors.New("socks command")
	}
	var host string
	switch h[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return "", 0, err
		}
		if l[0] == 0 {
			return "", 0, errors.New("socks address")
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	default:
		return "", 0, errors.New("socks address")
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(conn, p); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(p), nil
}
