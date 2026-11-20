// SPDX-License-Identifier: MIT
package main

import (
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
)

const (
	maxBody       = 2 << 20
	maxItems      = 64
	maxSessions   = 128
	maxStreams    = 64
	sessionIdle   = 5 * time.Minute
	pollWait      = 15 * time.Second
	maxFrameData  = 900
	downQueueSize = 256
)

type exitServer struct {
	key      []byte
	gasHMAC  string
	mu       sync.Mutex
	sessions map[string]*session
}

type session struct {
	sid     string
	mu      sync.Mutex
	pollMu  sync.Mutex
	down    chan wire.Frame
	streams map[uint32]*stream
	seen    map[string]int64
	closed  bool
	last    time.Time
}

type stream struct {
	id   uint32
	conn net.Conn
	sess *session
}

func main() {
	keyHex := os.Getenv("XHR_E2E_KEY")
	key, err := wire.ParseKey(keyHex)
	if err != nil {
		log.Fatal("XHR_E2E_KEY must be 64 hex characters")
	}
	gasHMAC := os.Getenv("XHR_GAS_HMAC")
	if gasHMAC == "" {
		log.Fatal("XHR_GAS_HMAC is required")
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	e := &exitServer{key: key, gasHMAC: gasHMAC, sessions: map[string]*session{}}
	go e.cleanupLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("/relay", e.handleRelay)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      pollWait + 15*time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Printf("xhr-exit listening on :%s", port)
	log.Fatal(srv.ListenAndServe())
}

func (e *exitServer) handleRelay(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody+1))
	if err != nil || len(body) > maxBody {
		writeJSON(w, proto.Response{E: "body"})
		return
	}
	if !proto.VerifyHMAC(e.gasHMAC, string(body), r.Header.Get("X-XHR-MAC")) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var req proto.ExitRequest
	if err := json.Unmarshal(body, &req); err != nil || req.V != 1 || len(req.S) < 8 {
		writeJSON(w, proto.Response{E: "bad request"})
		return
	}
	if len(req.Q) > maxItems {
		writeJSON(w, proto.Response{E: "too many frames"})
		return
	}

	s := e.getSession(req.S)
	if s == nil {
		writeJSON(w, proto.Response{E: "no session"})
		return
	}

	for _, it := range req.Q {
		f, err := wire.Open(e.key, req.S, it.N, it.X)
		if err != nil {
			continue
		}
		if !proto.Fresh(f.Time) {
			continue
		}
		if !s.seenFrame(f.Random) {
			continue
		}
		s.dispatch(f)
	}

	frames := s.collect(pollWait)
	resp := proto.Response{R: make([]proto.Item, 0, len(frames))}
	for _, f := range frames {
		n, x, err := wire.Seal(e.key, req.S, f, 512)
		if err != nil {
			continue
		}
		resp.R = append(resp.R, proto.Item{N: n, X: x})
	}
	writeJSON(w, resp)
}

func (e *exitServer) getSession(sid string) *session {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := e.sessions[sid]
	if s == nil {
		if len(e.sessions) >= maxSessions {
			return nil
		}
		s = &session{
			sid:     sid,
			down:    make(chan wire.Frame, downQueueSize),
			streams: map[uint32]*stream{},
			seen:    map[string]int64{},
			last:    time.Now(),
		}
		e.sessions[sid] = s
	}
	s.last = time.Now()
	return s
}

func (e *exitServer) cleanupLoop() {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for range t.C {
		now := time.Now()
		e.mu.Lock()
		for sid, s := range e.sessions {
			if now.Sub(s.last) > sessionIdle {
				s.shutdown()
				delete(e.sessions, sid)
			}
		}
		e.mu.Unlock()
	}
}

func (s *session) seenFrame(r string) bool {
	if r == "" {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.seen[r]; ok {
		return false
	}
	if len(s.seen) > 4096 {
		cutoff := time.Now().Add(-proto.TimeWindow).Unix()
		for k, ts := range s.seen {
			if ts < cutoff {
				delete(s.seen, k)
			}
		}
	}
	s.seen[r] = time.Now().Unix()
	return true
}

func (s *session) dispatch(f wire.Frame) {
	s.last = time.Now()
	switch f.Op {
	case "open":
		s.openStream(f.Stream, f.Host, f.Port)
	case "data":
		s.writeStream(f.Stream, f.Data)
	case "close":
		s.closeStream(f.Stream)
	}
}

func (s *session) openStream(id uint32, host string, port uint16) {
	if id == 0 || host == "" || port == 0 {
		return
	}
	s.mu.Lock()
	if s.closed || len(s.streams) >= maxStreams {
		s.mu.Unlock()
		return
	}
	if _, exists := s.streams[id]; exists {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()

	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(int(port))), 10*time.Second)
	if err != nil {
		s.enqueue(wire.Frame{Op: "error", Stream: id, Data: []byte("connect failed")})
		s.enqueue(wire.Frame{Op: "close", Stream: id})
		return
	}
	st := &stream{id: id, conn: conn, sess: s}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		_ = conn.Close()
		return
	}
	s.streams[id] = st
	s.mu.Unlock()
	go st.readLoop()
}

func (s *session) writeStream(id uint32, data []byte) {
	s.mu.Lock()
	st := s.streams[id]
	s.mu.Unlock()
	if st == nil || len(data) == 0 {
		return
	}
	_ = st.conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
	_, _ = st.conn.Write(data)
}

func (s *session) closeStream(id uint32) {
	s.mu.Lock()
	st := s.streams[id]
	delete(s.streams, id)
	s.mu.Unlock()
	if st != nil {
		_ = st.conn.Close()
	}
}

func (s *session) enqueue(f wire.Frame) {
	select {
	case s.down <- f:
	case <-time.After(5 * time.Second):
		s.shutdown()
	}
}

func (s *session) collect(wait time.Duration) []wire.Frame {
	s.pollMu.Lock()
	defer s.pollMu.Unlock()

	out := make([]wire.Frame, 0, maxItems)
	timer := time.NewTimer(wait)
	defer timer.Stop()

	for {
		select {
		case f := <-s.down:
			out = append(out, f)
			// Drain whatever is already queued and return promptly.
			for len(out) < maxItems {
				select {
				case f := <-s.down:
					out = append(out, f)
				default:
					return out
				}
			}
			return out
		case <-timer.C:
			return out
		}
	}
}

func (s *session) shutdown() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	streams := s.streams
	s.streams = map[uint32]*stream{}
	s.mu.Unlock()
	for _, st := range streams {
		_ = st.conn.Close()
	}
}

func (st *stream) readLoop() {
	buf := make([]byte, 32*1024)
	for {
		n, err := st.conn.Read(buf)
		if n > 0 {
			data := buf[:n]
			for len(data) > 0 {
				chunk := data
				if len(chunk) > maxFrameData {
					chunk = chunk[:maxFrameData]
				}
				cp := make([]byte, len(chunk))
				copy(cp, chunk)
				st.sess.enqueue(wire.Frame{Op: "data", Stream: st.id, Data: cp})
				data = data[len(chunk):]
			}
		}
		if err != nil {
			st.sess.enqueue(wire.Frame{Op: "close", Stream: st.id})
			st.sess.mu.Lock()
			delete(st.sess.streams, st.id)
			st.sess.mu.Unlock()
			_ = st.conn.Close()
			return
		}
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
