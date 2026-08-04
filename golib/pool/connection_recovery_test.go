package pool

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"gcm/config"
	"gcm/logger"
	"github.com/gorilla/websocket"
)

func TestRetireConnectionRemovesDeadActiveConnection(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 1, 256*1024, 32*1024, 1024*1024, 5*time.Second)
	streamID, ok := sm.tryAllocateStream("example.com:443")
	if !ok {
		t.Fatal("stream allocation failed")
	}

	p := &ConnectionPool{
		pool:              make([]*ConnItem, 0),
		managerByConn:     map[*ConnItem]*StreamManager{conn: sm},
		targetToConn:      map[string]*ConnItem{"example.com:443": conn},
		pendingHeartbeats: map[string]time.Time{},
	}
	conn.markActive()
	atomic.StoreInt32(&p.activeConnections, 1)
	sm.RegisterHandler(streamID, &StreamHandler{
		OnClose: func() {
			// Mirrors the SOCKS close callback. The pool must already have
			// detached the manager before this callback runs.
			p.UnregisterStreamHandler(conn, streamID)
			p.ReleaseConnection(conn)
		},
	})

	done := make(chan struct{})
	go func() {
		p.RetireConnection(conn, "test")
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("connection close did not finish")
	}

	if got := atomic.LoadInt32(&p.activeConnections); got != 0 {
		t.Fatalf("active connection count = %d, want 0", got)
	}
	if len(p.pool) != 0 {
		t.Fatalf("dead connection was returned to idle pool")
	}
	if _, ok := p.managerByConn[conn]; ok {
		t.Fatalf("dead connection manager was retained")
	}
	if _, ok := p.targetToConn["example.com:443"]; ok {
		t.Fatalf("dead connection affinity was retained")
	}
	if sm.GetStreamCount() != 0 {
		t.Fatalf("stream manager retained streams after connection close")
	}
}

func TestReleaseConnectionDoesNotReuseClosedSocket(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x04, 0x05, 0x06},
		Traffic:      &TrafficCounter{},
	}
	conn.closed.Store(true)
	conn.markActive()
	sm := NewStreamManager(conn, 1, 256*1024, 32*1024, 1024*1024, 5*time.Second)
	p := &ConnectionPool{
		pool:          make([]*ConnItem, 0),
		managerByConn: map[*ConnItem]*StreamManager{conn: sm},
	}
	atomic.StoreInt32(&p.activeConnections, 1)

	p.ReleaseConnection(conn)

	if len(p.pool) != 0 {
		t.Fatalf("closed connection was returned to idle pool")
	}
	if got := atomic.LoadInt32(&p.activeConnections); got != 0 {
		t.Fatalf("active connection count = %d, want 0", got)
	}
}

func TestReconnectMarksEveryConnectionClosed(t *testing.T) {
	connA := &ConnItem{ConnectionID: []byte{0x07, 0x08, 0x09}, Traffic: &TrafficCounter{}}
	connB := &ConnItem{ConnectionID: []byte{0x0a, 0x0b, 0x0c}, Traffic: &TrafficCounter{}}
	p := &ConnectionPool{
		log: logger.GetLogger("PoolTest"),
		managerByConn: map[*ConnItem]*StreamManager{
			connA: nil,
			connB: nil,
		},
	}

	p.Reconnect("test network transition")

	if !connA.closed.Load() || !connB.closed.Load() {
		t.Fatal("network recovery did not mark every connection closed")
	}
	if len(p.managerByConn) != 0 {
		t.Fatalf("network recovery retained %d connection managers", len(p.managerByConn))
	}
}

func TestWriteMessageMarksSocketClosedAfterWriteFailure(t *testing.T) {
	ws := newTestWebSocket(t)
	if err := ws.Close(); err != nil {
		t.Fatalf("close test websocket: %v", err)
	}

	conn := &ConnItem{
		WS:           ws,
		ConnectionID: []byte{0x0d, 0x0e, 0x0f},
		Traffic:      &TrafficCounter{},
		writeTimeout: 20 * time.Millisecond,
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, []byte("test")); err == nil {
		t.Fatal("WriteMessage succeeded on a closed websocket")
	}
	if !conn.closed.Load() {
		t.Fatal("failed websocket write did not mark the connection closed")
	}
}

func TestHeartbeatDoesNotHoldPoolLockDuringWrite(t *testing.T) {
	conn := &ConnItem{
		WS:           newTestWebSocket(t),
		ConnectionID: []byte{0x10, 0x11, 0x12},
		Traffic:      &TrafficCounter{},
	}
	conn.writeMu.Lock()

	p := &ConnectionPool{
		cfg:               config.DefaultConfig(),
		log:               logger.GetLogger("PoolTest"),
		managerByConn:     map[*ConnItem]*StreamManager{conn: nil},
		pendingHeartbeats: map[string]time.Time{},
	}

	done := make(chan struct{})
	go func() {
		p.sendHeartbeat()
		close(done)
	}()

	poolLockAvailable := make(chan struct{})
	go func() {
		p.mu.Lock()
		p.mu.Unlock()
		close(poolLockAvailable)
	}()

	select {
	case <-poolLockAvailable:
	case <-time.After(time.Second):
		conn.writeMu.Unlock()
		t.Fatal("heartbeat write held the pool lock")
	}

	conn.writeMu.Unlock()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("heartbeat did not finish after the write lock was released")
	}
}

func TestApplicationDataAcknowledgesPendingHeartbeat(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x13, 0x14, 0x15},
		Traffic:      &TrafficCounter{},
	}
	connID := formatConnID(conn.ConnectionID)
	p := &ConnectionPool{
		log: logger.GetLogger("PoolTest"),
		pendingHeartbeats: map[string]time.Time{
			connID: time.Now().Add(-20 * time.Millisecond),
		},
	}

	p.acknowledgeHeartbeat(conn, connID, "data")

	if len(p.pendingHeartbeats) != 0 {
		t.Fatal("application data did not clear the pending heartbeat")
	}
	if got := time.Duration(conn.RTT.Load()); got <= 0 {
		t.Fatalf("RTT = %v, want positive sample", got)
	}
}

func newTestWebSocket(t *testing.T) *websocket.Conn {
	t.Helper()
	stopServer := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		<-stopServer
		_ = conn.Close()
	}))
	t.Cleanup(func() {
		close(stopServer)
		server.Close()
	})

	wsURL := "ws" + strings.TrimPrefix(server.URL, "http")
	ws, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial test websocket: %v", err)
	}
	return ws
}
