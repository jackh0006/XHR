// SPDX-License-Identifier: MIT

// Package tunnel is the multiplexed XHR client engine. It keeps one or more
// Apps Script sessions alive, carries many TCP streams over each session, and
// performs end-to-end AES-256-GCM itself. The Apps Script relay only sees
// opaque sealed frames.
package tunnel

import (
	"context"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

const (
	// A session is capped below the exit's 64-stream limit so a new stream can
	// always be opened while an old session is draining.
	maxStreamsPerSession = 56
	sessionIdle          = 2 * time.Minute
	pollWait             = 20 * time.Second
	requestTimeout       = 30 * time.Second
	openTimeout          = 35 * time.Second
	dataChunk            = 8 * 1024
	maxBatchFrames       = 12
	maxBatchBytes        = 96 * 1024
)

// Config describes one XHR client.
type Config struct {
	// GASURL is kept for the single-deployment CLI and tests.
	GASURL string
	// GASURLs is the Android deployment pool. Each URL is tried in turn, so
	// one slow or broken Apps Script deployment does not kill the tunnel.
	GASURLs     []string
	GASHost     string
	FrontDomain string
	GoogleIP    string
	AuthKey     string
	Key         []byte
	// HTTP is optional and exists for tests. When nil the production
	// domain-fronted HTTP/2 client is constructed.
	HTTP *http.Client
	// OnLog receives short human-readable events. It must not block.
	OnLog func(string)
}

// Stats is the live traffic counter set exposed to the Android UI.
type Stats struct {
	Up      atomic.Int64
	Down    atomic.Int64
	Calls   atomic.Int64
	Streams atomic.Int64
	Active  atomic.Int64
}

// Client owns the XHR sessions.
type Client struct {
	cfg   Config
	http  *http.Client
	urls  []string
	next  atomic.Uint32
	stats Stats

	mu       sync.Mutex
	sessions []*Session
	closed   bool

	errMu   sync.Mutex
	lastErr string
}

// New validates cfg and creates a client. It does not open any connections.
func New(cfg Config) (*Client, error) {
	if len(cfg.Key) != 32 {
		return nil, errors.New("tunnel key must be 32 bytes")
	}
	if cfg.AuthKey == "" {
		return nil, errors.New("AUTH_KEY is required")
	}
	urls := make([]string, 0, len(cfg.GASURLs)+1)
	urls = append(urls, cfg.GASURLs...)
	if len(urls) == 0 && cfg.GASURL != "" {
		urls = append(urls, cfg.GASURL)
	}
	seen := make(map[string]struct{}, len(urls))
	clean := urls[:0]
	for _, raw := range urls {
		u := strings.TrimSpace(raw)
		if u == "" {
			continue
		}
		if !strings.HasPrefix(u, "https://") {
			return nil, fmt.Errorf("deployment URL must use https: %s", u)
		}
		if _, ok := seen[u]; ok {
			continue
		}
		seen[u] = struct{}{}
		clean = append(clean, u)
	}
	if len(clean) == 0 {
		return nil, errors.New("at least one deployment URL is required")
	}
	urls = clean
	if cfg.GASHost == "" {
		cfg.GASHost = "script.google.com"
	}
	if cfg.FrontDomain == "" {
		cfg.FrontDomain = "www.google.com"
	}
	if cfg.GoogleIP == "" {
		cfg.GoogleIP = "216.239.38.120"
	}
	c := &Client{cfg: cfg, urls: urls}
	if cfg.HTTP != nil {
		c.http = cfg.HTTP
	} else {
		c.http = newFrontedHTTP(cfg)
	}
	return c, nil
}

func newFrontedHTTP(cfg Config) *http.Client {
	cache := utls.NewLRUClientSessionCache(8)
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := &net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}
		raw, err := d.DialContext(ctx, "tcp", net.JoinHostPort(cfg.GoogleIP, "443"))
		if err != nil {
			return nil, err
		}
		u := utls.UClient(raw, &utls.Config{
			ServerName:         cfg.FrontDomain,
			NextProtos:         []string{"h2"},
			MinVersion:         tls.VersionTLS12,
			ClientSessionCache: cache,
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
	return &http.Client{Transport: tr, Timeout: requestTimeout}
}

// Stats returns the live counters. The returned pointer is stable.
func (c *Client) Stats() *Stats { return &c.stats }

// LastError returns the most recent transport error, if any.
func (c *Client) LastError() string {
	c.errMu.Lock()
	defer c.errMu.Unlock()
	return c.lastErr
}

func (c *Client) noteError(err error) {
	if err == nil {
		return
	}
	c.errMu.Lock()
	c.lastErr = err.Error()
	c.errMu.Unlock()
	c.logf("error: %v", err)
}

func (c *Client) logf(format string, args ...any) {
	if c.cfg.OnLog != nil {
		c.cfg.OnLog(fmt.Sprintf(format, args...))
	}
}

// Open dials host:port through the tunnel. The returned stream implements
// net.Conn and must be closed by the caller.
func (c *Client) Open(host string, port uint16) (net.Conn, error) {
	host = strings.TrimSpace(host)
	if host == "" || port == 0 {
		return nil, errors.New("invalid destination")
	}
	s, err := c.pickSession()
	if err != nil {
		return nil, err
	}
	st, err := s.open(host, port)
	if err != nil {
		return nil, err
	}
	c.stats.Streams.Add(1)
	c.stats.Active.Add(1)
	c.logf("CONNECT %s:%d", host, port)
	return st, nil
}

// Close tears down every session. It is safe to call more than once.
func (c *Client) Close() error {
	c.mu.Lock()
	c.closed = true
	sessions := c.sessions
	c.sessions = nil
	c.mu.Unlock()
	for _, s := range sessions {
		s.close()
	}
	c.http.CloseIdleConnections()
	return nil
}

func (c *Client) pickSession() (*Session, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, errors.New("tunnel is closed")
	}
	for i := len(c.sessions) - 1; i >= 0; i-- {
		s := c.sessions[i]
		if s.isClosed() {
			c.sessions = append(c.sessions[:i], c.sessions[i+1:]...)
			continue
		}
		if s.streamCount() < maxStreamsPerSession {
			return s, nil
		}
	}
	s, err := c.newSessionLocked()
	if err != nil {
		return nil, err
	}
	c.sessions = append(c.sessions, s)
	return s, nil
}

func (c *Client) newSessionLocked() (*Session, error) {
	sid, err := wire.NewSID()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Session{
		c:       c,
		sid:     sid,
		ctx:     ctx,
		cancel:  cancel,
		out:     make(chan wire.Frame, 512),
		streams: make(map[uint32]*Stream),
		last:    time.Now(),
	}
	go s.txLoop()
	go s.rxLoop()
	go s.idleLoop()
	c.logf("session %s started", sid)
	return s, nil
}

func (c *Client) dropSession(s *Session) {
	c.mu.Lock()
	for i, x := range c.sessions {
		if x == s {
			c.sessions = append(c.sessions[:i], c.sessions[i+1:]...)
			break
		}
	}
	c.mu.Unlock()
}

func (c *Client) roundTrip(ctx context.Context, sid string, items []proto.Item) ([]wire.Frame, error) {
	if items == nil {
		// encoding/json writes a nil slice as null. Apps Script requires an
		// array for "q", so an idle downlink poll must send [] not null.
		items = []proto.Item{}
	}
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
	var lastErr error
	for attempt := 0; attempt < len(c.urls); attempt++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		u := c.nextURL()
		frames, err := c.postOnce(ctx, u, sid, body)
		if err == nil {
			return frames, nil
		}
		lastErr = err
		c.noteError(err)
	}
	if lastErr == nil {
		lastErr = errors.New("no relay endpoint")
	}
	return nil, lastErr
}

func (c *Client) nextURL() string {
	if len(c.urls) == 1 {
		return c.urls[0]
	}
	n := c.next.Add(1) - 1
	return c.urls[int(n)%len(c.urls)]
}

func (c *Client) postOnce(ctx context.Context, gasURL, sid string, body []byte) ([]wire.Frame, error) {
	c.stats.Calls.Add(1)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, gasURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("User-Agent", chromeUserAgent)
	httpReq.Header.Set("Accept", "application/json")
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
	if len(raw) > 0 && raw[0] == '<' {
		return nil, errors.New("relay rejected the profile (check AUTH_KEY and deployment URL)")
	}
	var out proto.Response
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("relay response: %w", err)
	}
	if out.E != "" {
		return nil, errors.New(out.E)
	}
	frames := make([]wire.Frame, 0, len(out.R))
	for _, it := range out.R {
		f, err := wire.Open(c.cfg.Key, sid, it.N, it.X)
		if err != nil {
			continue
		}
		frames = append(frames, f)
	}
	return frames, nil
}

const chromeUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

// Session carries many streams over one Apps Script session id.
type Session struct {
	c      *Client
	sid    string
	ctx    context.Context
	cancel context.CancelFunc
	out    chan wire.Frame

	mu      sync.Mutex
	streams map[uint32]*Stream
	nextID  uint32
	last    time.Time
	closed  bool
}

func (s *Session) isClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

func (s *Session) streamCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.streams)
}

func (s *Session) touch() {
	s.mu.Lock()
	s.last = time.Now()
	s.mu.Unlock()
}

func (s *Session) close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	streams := s.streams
	s.streams = map[uint32]*Stream{}
	s.mu.Unlock()
	s.cancel()
	for _, st := range streams {
		st.finish(false)
	}
	s.c.dropSession(s)
	s.c.logf("session %s closed", s.sid)
}

func (s *Session) idleLoop() {
	t := time.NewTicker(20 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-t.C:
			s.mu.Lock()
			idle := len(s.streams) == 0 && time.Since(s.last) > sessionIdle
			s.mu.Unlock()
			if idle {
				s.close()
				return
			}
		}
	}
}

func (s *Session) open(host string, port uint16) (*Stream, error) {
	sid, err := wire.NewSID()
	if err != nil {
		return nil, err
	}
	st := &Stream{
		id:     streamID(sid),
		host:   host,
		port:   port,
		sess:   s,
		opened: make(chan error, 1),
		rx:     make(chan []byte, 512),
		done:   make(chan struct{}),
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, errors.New("session closed")
	}
	for {
		if _, exists := s.streams[st.id]; !exists {
			break
		}
		s.nextID++
		st.id = s.nextID
		if st.id == 0 {
			st.id = 1
		}
	}
	s.streams[st.id] = st
	s.last = time.Now()
	s.mu.Unlock()

	if err := s.send(wire.Frame{Op: "open", Stream: st.id, Host: host, Port: port}, 512); err != nil {
		s.remove(st)
		return nil, err
	}
	select {
	case err := <-st.opened:
		if err != nil {
			s.remove(st)
			return nil, err
		}
		st.counted.Store(true)
		return st, nil
	case <-time.After(openTimeout):
		s.remove(st)
		return nil, errors.New("connection timed out")
	case <-s.ctx.Done():
		s.remove(st)
		return nil, errors.New("session closed")
	case <-st.done:
		select {
		case err := <-st.opened:
			if err != nil {
				return nil, err
			}
		default:
		}
		return nil, errors.New("connection closed")
	}
}

func streamID(sid string) uint32 {
	b, err := hex.DecodeString(sid)
	if err != nil || len(b) < 4 {
		return 1
	}
	id := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
	if id == 0 {
		id = 1
	}
	return id
}

func (s *Session) remove(st *Stream) {
	s.mu.Lock()
	if cur, ok := s.streams[st.id]; ok && cur == st {
		delete(s.streams, st.id)
	}
	s.last = time.Now()
	s.mu.Unlock()
}

func (s *Session) send(f wire.Frame, pad int) error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return errors.New("session closed")
	}
	s.last = time.Now()
	s.mu.Unlock()
	select {
	case s.out <- f:
		return nil
	case <-s.ctx.Done():
		return errors.New("session closed")
	}
}

func (s *Session) txLoop() {
	for {
		var first wire.Frame
		select {
		case <-s.ctx.Done():
			return
		case first = <-s.out:
		}
		batch := []wire.Frame{first}
		bytes := len(first.Data)
		grace := time.NewTimer(4 * time.Millisecond)
	drain:
		for len(batch) < maxBatchFrames && bytes < maxBatchBytes {
			select {
			case f := <-s.out:
				batch = append(batch, f)
				if len(f.Data) > 0 {
					bytes += len(f.Data)
				}
			case <-grace.C:
				break drain
			case <-s.ctx.Done():
				grace.Stop()
				return
			}
		}
		grace.Stop()

		items := make([]proto.Item, 0, len(batch))
		for _, f := range batch {
			pad := 64
			if f.Op != "data" {
				pad = 512
			}
			n, x, err := wire.Seal(s.c.cfg.Key, s.sid, f, pad)
			if err != nil {
				continue
			}
			items = append(items, proto.Item{N: n, X: x})
		}
		if len(items) == 0 {
			continue
		}

		ok := false
		for attempt := 0; attempt < 3; attempt++ {
			if s.ctx.Err() != nil {
				return
			}
			frames, err := s.c.roundTrip(s.ctx, s.sid, items)
			if err == nil {
				s.c.handleFrames(s, frames)
				ok = true
				break
			}
			s.c.noteError(err)
			select {
			case <-time.After(time.Duration(200*(attempt+1)) * time.Millisecond):
			case <-s.ctx.Done():
				return
			}
		}
		if !ok {
			// The session is unhealthy. Closing it forces the next Open to
			// create a fresh Apps Script session instead of silently losing
			// every write.
			s.close()
			return
		}
	}
}

func (s *Session) rxLoop() {
	backoff := 250 * time.Millisecond
	for {
		if s.ctx.Err() != nil {
			return
		}
		frames, err := s.c.roundTrip(s.ctx, s.sid, nil)
		if err != nil {
			if s.ctx.Err() != nil {
				return
			}
			s.c.noteError(err)
			if strings.Contains(err.Error(), "no session") {
				// The exit garbage-collected this session during an outage.
				// Close the mux so apps get a clean reset and the next Open
				// creates a fresh session instead of writing into a void.
				s.close()
				return
			}
			select {
			case <-time.After(backoff):
			case <-s.ctx.Done():
				return
			}
			if backoff < 3*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = 250 * time.Millisecond
		s.c.handleFrames(s, frames)
	}
}

func (c *Client) handleFrames(s *Session, frames []wire.Frame) {
	for _, f := range frames {
		s.mu.Lock()
		st := s.streams[f.Stream]
		s.last = time.Now()
		s.mu.Unlock()
		if st == nil {
			continue
		}
		switch f.Op {
		case "open_ok":
			st.signalOpen(nil)
		case "open_err":
			msg := strings.TrimSpace(string(f.Data))
			if msg == "" {
				msg = "connection failed"
			}
			st.signalOpen(errors.New(msg))
			st.finish(false)
		case "data":
			if len(f.Data) > 0 {
				c.stats.Down.Add(int64(len(f.Data)))
				st.deliver(f.Data)
			}
		case "close":
			st.finish(false)
		case "error":
			st.finish(false)
		}
	}
}

// Stream is one TCP connection carried inside a session.
type Stream struct {
	id     uint32
	host   string
	port   uint16
	sess   *Session
	opened chan error
	rx     chan []byte
	done   chan struct{}

	once    sync.Once
	pending []byte
	closed  atomic.Bool
	counted atomic.Bool
}

func (st *Stream) signalOpen(err error) {
	select {
	case st.opened <- err:
	default:
	}
}

func (st *Stream) deliver(data []byte) {
	cp := make([]byte, len(data))
	copy(cp, data)
	select {
	case st.rx <- cp:
	case <-st.done:
	case <-time.After(5 * time.Second):
		st.finish(false)
	}
}

// Read implements io.Reader.
func (st *Stream) Read(p []byte) (int, error) {
	for len(st.pending) == 0 {
		select {
		case data := <-st.rx:
			st.pending = data
		case <-st.done:
			// Drain everything that arrived before the close. A TCP FIN can
			// arrive in the same poll as the last payload bytes.
			select {
			case data := <-st.rx:
				st.pending = data
			default:
				return 0, io.EOF
			}
		}
	}
	n := copy(p, st.pending)
	st.pending = st.pending[n:]
	return n, nil
}

// Write implements io.Writer.
func (st *Stream) Write(p []byte) (int, error) {
	if st.closed.Load() {
		return 0, errors.New("stream closed")
	}
	total := 0
	for len(p) > 0 {
		n := len(p)
		if n > dataChunk {
			n = dataChunk
		}
		chunk := make([]byte, n)
		copy(chunk, p[:n])
		if err := st.sess.send(wire.Frame{Op: "data", Stream: st.id, Data: chunk}, 64); err != nil {
			return total, err
		}
		st.sess.c.stats.Up.Add(int64(n))
		total += n
		p = p[n:]
	}
	return total, nil
}

// Close implements io.Closer.
func (st *Stream) Close() error {
	st.finish(true)
	return nil
}

// LocalAddr implements net.Conn.
func (st *Stream) LocalAddr() net.Addr { return dummyAddr("xhr-local") }

// RemoteAddr implements net.Conn.
func (st *Stream) RemoteAddr() net.Addr {
	return dummyAddr(net.JoinHostPort(st.host, fmt.Sprintf("%d", st.port)))
}

// SetDeadline implements net.Conn. Deadlines are best-effort; the session
// itself has an idle timeout.
func (st *Stream) SetDeadline(time.Time) error      { return nil }
func (st *Stream) SetReadDeadline(time.Time) error  { return nil }
func (st *Stream) SetWriteDeadline(time.Time) error { return nil }

func (st *Stream) finish(sendClose bool) {
	st.once.Do(func() {
		st.closed.Store(true)
		if sendClose {
			_ = st.sess.send(wire.Frame{Op: "close", Stream: st.id}, 128)
		}
		close(st.done)
		st.sess.remove(st)
		if st.counted.Swap(false) {
			st.sess.c.stats.Active.Add(-1)
		}
	})
}

type dummyAddr string

func (d dummyAddr) Network() string { return "xhr" }
func (d dummyAddr) String() string  { return string(d) }
