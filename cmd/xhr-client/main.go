// SPDX-License-Identifier: MIT
package main

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

type Config struct {
	GASURL      string `json:"gas_url"`
	GASHost     string `json:"gas_host"`
	FrontDomain string `json:"front_domain"`
	GoogleIP    string `json:"google_ip"`
	AuthKey     string `json:"auth_key"`
	E2EKey      string `json:"e2e_key"`
	Listen      string `json:"listen"`
}

type Client struct {
	cfg  Config
	key  []byte
	http *http.Client
}

func main() {
	cfgPath := flag.String("config", "config.json", "path to config.json")
	flag.Parse()

	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		log.Fatal(err)
	}
	key, err := wire.ParseKey(cfg.E2EKey)
	if err != nil {
		log.Fatal("e2e_key must be 64 hex characters")
	}
	c, err := newClient(cfg, key)
	if err != nil {
		log.Fatal(err)
	}
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("xhr-client SOCKS5 on %s", cfg.Listen)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		go c.handleSocks(conn)
	}
}

func loadConfig(path string) (Config, error) {
	var cfg Config
	b, err := os.ReadFile(path)
	if err != nil {
		return cfg, err
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		return cfg, err
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
	if cfg.Listen == "" {
		cfg.Listen = "127.0.0.1:1080"
	}
	if cfg.AuthKey == "" || cfg.E2EKey == "" {
		return cfg, errors.New("auth_key and e2e_key are required")
	}
	if !strings.HasPrefix(cfg.GASURL, "https://") {
		id := strings.TrimSpace(cfg.GASURL)
		if strings.HasPrefix(id, "AKfycb") {
			cfg.GASURL = "https://script.google.com/macros/s/" + id + "/exec"
		} else {
			return cfg, errors.New("gas_url must be an https URL or a deployment ID")
		}
	}
	return cfg, nil
}

func newClient(cfg Config, key []byte) (*Client, error) {
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := &net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}
		raw, err := d.DialContext(ctx, "tcp", net.JoinHostPort(cfg.GoogleIP, "443"))
		if err != nil {
			return nil, err
		}
		u := utls.UClient(raw, &utls.Config{
			ServerName: cfg.FrontDomain,
			NextProtos: []string{"h2"},
			MinVersion: tls.VersionTLS12,
		}, utls.HelloChrome_Auto)
		if err := u.HandshakeContext(ctx); err != nil {
			_ = raw.Close()
			return nil, err
		}
		return u, nil
	}
	tr := &http2.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string, _ *tls.Config) (net.Conn, error) {
			return dial(ctx, network, addr)
		},
		ReadIdleTimeout: 30 * time.Second,
		PingTimeout:     15 * time.Second,
	}
	return &Client{
		cfg:  cfg,
		key:  key,
		http: &http.Client{Transport: tr, Timeout: 35 * time.Second},
	}, nil
}

func (c *Client) roundTrip(sid string, items []proto.Item) ([]wire.Frame, error) {
	t := time.Now().Unix()
	req := proto.ClientRequest{
		V: 1,
		T: t,
		S: sid,
		M: proto.HMACHex(c.cfg.AuthKey, fmt.Sprintf("%d.%s", t, sid)),
		Q: items,
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	httpReq, err := http.NewRequest(http.MethodPost, c.cfg.GASURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Host = c.cfg.GASHost

	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("relay HTTP %d", resp.StatusCode)
	}
	var out proto.Response
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("relay JSON: %w", err)
	}
	if out.E != "" {
		return nil, errors.New(out.E)
	}
	frames := make([]wire.Frame, 0, len(out.R))
	for _, it := range out.R {
		f, err := wire.Open(c.key, sid, it.N, it.X)
		if err != nil {
			continue
		}
		frames = append(frames, f)
	}
	return frames, nil
}

func (c *Client) handleSocks(conn net.Conn) {
	defer conn.Close()
	if err := socksHandshake(conn); err != nil {
		return
	}
	host, port, err := socksRequest(conn)
	if err != nil {
		return
	}
	sid, err := wire.NewSID()
	if err != nil {
		return
	}

	open := wire.Frame{Op: "open", Stream: 1, Host: host, Port: port}
	n, x, err := wire.Seal(c.key, sid, open, 512)
	if err != nil {
		return
	}
	frames, err := c.roundTrip(sid, []proto.Item{{N: n, X: x}})
	if err != nil {
		log.Printf("open %s:%d: %v", host, port, err)
		return
	}
	for _, f := range frames {
		if f.Op == "data" && f.Stream == 1 {
			_, _ = conn.Write(f.Data)
		}
	}

	out := make(chan []byte, 64)
	go func() {
		buf := make([]byte, 900)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				cp := make([]byte, n)
				copy(cp, buf[:n])
				select {
				case out <- cp:
				case <-time.After(10 * time.Second):
					return
				}
			}
			if err != nil {
				close(out)
				return
			}
		}
	}()

	for {
		items := make([]proto.Item, 0, 16)
		closed := false
	drain:
		for len(items) < 16 {
			select {
			case data, ok := <-out:
				if !ok {
					closed = true
					break drain
				}
				f := wire.Frame{Op: "data", Stream: 1, Data: data}
				fn, fx, err := wire.Seal(c.key, sid, f, 256)
				if err == nil {
					items = append(items, proto.Item{N: fn, X: fx})
				}
			default:
				break drain
			}
		}
		if closed {
			f := wire.Frame{Op: "close", Stream: 1}
			fn, fx, _ := wire.Seal(c.key, sid, f, 128)
			_, _ = c.roundTrip(sid, append(items, proto.Item{N: fn, X: fx}))
			return
		}

		frames, err := c.roundTrip(sid, items)
		if err != nil {
			time.Sleep(500 * time.Millisecond)
			continue
		}
		for _, f := range frames {
			switch f.Op {
			case "data":
				if f.Stream == 1 {
					_, _ = conn.Write(f.Data)
				}
			case "close", "error":
				return
			}
		}
		if len(items) > 0 {
			time.Sleep(10 * time.Millisecond)
		}
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
	if h[0] != 5 || h[1] != 1 {
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
	port := binary.BigEndian.Uint16(p)
	_, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	return host, port, err
}
