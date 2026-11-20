// SPDX-License-Identifier: MIT
package tunnel

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/jackh0006/XHR/internal/proto"
	"github.com/jackh0006/XHR/internal/wire"
)

const testKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

type fakeRelay struct {
	t      *testing.T
	key    []byte
	mu     sync.Mutex
	calls  int
	opens  int
	closed int
}

func (f *fakeRelay) handler(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	var req proto.ClientRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if !proto.VerifyHMAC("auth", fmt.Sprintf("%d.%s", req.T, req.S), req.M) {
		http.Error(w, "bad mac", http.StatusUnauthorized)
		return
	}
	if req.Q == nil {
		f.t.Errorf("request q was null; Apps Script requires []")
	}
	f.mu.Lock()
	f.calls++
	f.mu.Unlock()

	out := make([]proto.Item, 0)
	for _, it := range req.Q {
		frame, err := wire.Open(f.key, req.S, it.N, it.X)
		if err != nil {
			f.t.Errorf("open frame: %v", err)
			continue
		}
		var reply wire.Frame
		switch frame.Op {
		case "open":
			f.mu.Lock()
			f.opens++
			f.mu.Unlock()
			reply = wire.Frame{Op: "open_ok", Stream: frame.Stream}
		case "data":
			reply = wire.Frame{Op: "data", Stream: frame.Stream, Data: frame.Data}
		case "close":
			f.mu.Lock()
			f.closed++
			f.mu.Unlock()
			reply = wire.Frame{Op: "close", Stream: frame.Stream}
		default:
			continue
		}
		n, x, err := wire.Seal(f.key, req.S, reply, 16)
		if err != nil {
			f.t.Errorf("seal frame: %v", err)
			continue
		}
		out = append(out, proto.Item{N: n, X: x})
	}
	if len(req.Q) == 0 {
		time.Sleep(10 * time.Millisecond)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(proto.Response{R: out})
}

func newTestClient(t *testing.T) (*Client, *fakeRelay, func()) {
	t.Helper()
	key, err := wire.ParseKey(testKeyHex)
	if err != nil {
		t.Fatal(err)
	}
	relay := &fakeRelay{t: t, key: key}
	srv := httptest.NewTLSServer(http.HandlerFunc(relay.handler))
	c, err := New(Config{
		GASURL:  srv.URL,
		AuthKey: "auth",
		Key:     key,
		HTTP:    srv.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return c, relay, func() {
		_ = c.Close()
		srv.Close()
	}
}

func TestClientOpenWriteReadClose(t *testing.T) {
	c, relay, done := newTestClient(t)
	defer done()

	conn, err := c.Open("example.com", 443)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 5)
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "hello" {
		t.Fatalf("got %q", buf)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	relay.mu.Lock()
	calls := relay.calls
	relay.mu.Unlock()
	if calls == 0 {
		t.Fatal("no relay calls")
	}
	stats := c.Stats()
	if stats.Up.Load() != 5 || stats.Down.Load() != 5 {
		t.Fatalf("up=%d down=%d", stats.Up.Load(), stats.Down.Load())
	}
	if stats.Streams.Load() != 1 {
		t.Fatalf("streams=%d", stats.Streams.Load())
	}
}

func TestClientMultiplexesStreams(t *testing.T) {
	c, _, done := newTestClient(t)
	defer done()

	const n = 6
	conns := make([]io.ReadWriteCloser, 0, n)
	for i := 0; i < n; i++ {
		conn, err := c.Open("example.com", 443)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, conn)
	}
	var wg sync.WaitGroup
	for i, conn := range conns {
		wg.Add(1)
		go func(i int, conn io.ReadWriteCloser) {
			defer wg.Done()
			msg := string(rune('a'+i)) + "-payload"
			if _, err := conn.Write([]byte(msg)); err != nil {
				t.Errorf("write: %v", err)
				return
			}
			buf := make([]byte, len(msg))
			if _, err := io.ReadFull(conn, buf); err != nil {
				t.Errorf("read: %v", err)
				return
			}
			if string(buf) != msg {
				t.Errorf("got %q want %q", buf, msg)
			}
			_ = conn.Close()
		}(i, conn)
	}
	wg.Wait()
}

func TestClientRejectsShortKey(t *testing.T) {
	_, err := New(Config{
		GASURL:  "https://example.test/exec",
		AuthKey: "auth",
		Key:     []byte("short"),
	})
	if err == nil {
		t.Fatal("short key accepted")
	}
}

func TestClientFailsOverAcrossDeployments(t *testing.T) {
	key, err := wire.ParseKey(testKeyHex)
	if err != nil {
		t.Fatal(err)
	}
	relay := &fakeRelay{t: t, key: key}
	good := httptest.NewTLSServer(http.HandlerFunc(relay.handler))
	defer good.Close()
	bad := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "down", http.StatusInternalServerError)
	}))
	defer bad.Close()

	c, err := New(Config{
		GASURLs: []string{bad.URL, good.URL},
		AuthKey: "auth",
		Key:     key,
		HTTP:    good.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	conn, err := c.Open("example.com", 443)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write([]byte("failover")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len("failover"))
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "failover" {
		t.Fatalf("got %q", buf)
	}
	_ = conn.Close()
}
