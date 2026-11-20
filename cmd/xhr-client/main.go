// SPDX-License-Identifier: MIT
// Command xhr-client is the Linux/macOS preview client. The Android app uses
// the same internal/tunnel engine through JNI.
package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"time"

	"github.com/jackh0006/XHR/internal/tunnel"
	"github.com/jackh0006/XHR/internal/wire"
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
	c, err := tunnel.New(tunnel.Config{
		GASURL:      cfg.GASURL,
		GASHost:     cfg.GASHost,
		FrontDomain: cfg.FrontDomain,
		GoogleIP:    cfg.GoogleIP,
		AuthKey:     cfg.AuthKey,
		Key:         key,
		OnLog:       func(line string) { log.Printf("%s", line) },
	})
	if err != nil {
		log.Fatal(err)
	}
	defer c.Close()

	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatal(err)
	}
	defer ln.Close()
	log.Printf("xhr-client SOCKS5 on %s", cfg.Listen)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		go handleSocks(c, conn)
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

func handleSocks(c *tunnel.Client, conn net.Conn) {
	defer conn.Close()
	if err := socksHandshake(conn); err != nil {
		return
	}
	host, port, err := socksRequest(conn)
	if err != nil {
		return
	}
	remote, err := c.Open(host, port)
	if err != nil {
		log.Printf("open %s:%d: %v", host, port, err)
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
