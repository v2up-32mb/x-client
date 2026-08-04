package xtunnel

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	common "xclient/xtunnel/protocol"
	"xclient/xtunnel/relay"
)

func TestReserveQueueBytesRejectsWithoutChangingTotal(t *testing.T) {
	p := &clientPool{globalQueueLimit: 10}
	atomic.StoreInt64(&p.globalQueueBytes, 9)

	if p.reserveQueueBytes(2) {
		t.Fatal("expected reserveQueueBytes to reject over-limit reservation")
	}
	if got := atomic.LoadInt64(&p.globalQueueBytes); got != 9 {
		t.Fatalf("expected queue bytes to stay 9, got %d", got)
	}
}

func TestReleaseQueueBytesClampsAtZero(t *testing.T) {
	p := &clientPool{}
	p.releaseQueueBytes(5)

	if got := atomic.LoadInt64(&p.globalQueueBytes); got != 0 {
		t.Fatalf("expected queue bytes to clamp at 0, got %d", got)
	}
}

func TestBroadcastWriteWithoutActiveConnectionsDoesNotEnqueue(t *testing.T) {
	p := &clientPool{
		writeQueues:      []chan writeJob{make(chan writeJob, 1)},
		wsConns:          []*websocket.Conn{nil},
		globalQueueLimit: 1024,
		nextChannel:      1,
	}

	p.broadcastWrite(websocket.BinaryMessage, []byte("hello"))

	if got := len(p.writeQueues[0]); got != 0 {
		t.Fatalf("expected no queued messages without active connections, got %d", got)
	}
	if got := atomic.LoadInt64(&p.globalQueueBytes); got != 0 {
		t.Fatalf("expected queue bytes to stay 0, got %d", got)
	}
}

func TestWriteWorkerAggregatedJobsReleaseAllQueueBytes(t *testing.T) {
	conn, recvCh, cleanup := newTestWebSocketConn(t)
	defer cleanup()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p := &clientPool{
		ctx:             ctx,
		config:          &Config{PingInterval: time.Hour, WriteTimeout: time.Second, ReadBufferSize: 1024},
		connsWriteMutex: []sync.Mutex{{}},
	}

	queue := make(chan writeJob, 4)
	msg1 := common.EncodeMessage(common.MsgTCPData, "cid", nil, []byte("hello"))
	msg2 := common.EncodeMessage(common.MsgTCPData, "cid", nil, []byte("world"))
	queue <- writeJob{msgType: websocket.BinaryMessage, data: msg1, size: len(msg1)}
	queue <- writeJob{msgType: websocket.BinaryMessage, data: msg2, size: len(msg2)}
	close(queue)
	atomic.StoreInt64(&p.globalQueueBytes, int64(len(msg1)+len(msg2)))

	done := make(chan struct{})
	go func() {
		p.writeWorker(0, conn, queue)
		close(done)
	}()

	select {
	case <-recvCh:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for aggregated websocket message")
	}

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("writeWorker did not exit")
	}

	if got := atomic.LoadInt64(&p.globalQueueBytes); got != 0 {
		t.Fatalf("expected queue bytes to be fully released, got %d", got)
	}
}

func TestUnregisterUDPAssociationReturnsPromptly(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p := &clientPool{
		ctx:    ctx,
		cancel: cancel,
		config: DefaultConfig(),
		conns:  make(map[string]*clientConnState),
	}

	listener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatalf("listen udp failed: %v", err)
	}
	defer listener.Close()

	assoc := &udpAssociation{
		connID:      "udp-conn",
		tcpConn:     serverConn,
		udpListener: listener,
		pool:        p,
		done:        make(chan bool, 1),
	}
	p.conns[assoc.connID] = &clientConnState{
		udpAssoc: assoc,
		reqType:  "SOCKS5 UDP",
	}

	done := make(chan struct{})
	go func() {
		p.Unregister(assoc.connID)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("expected Unregister to return promptly for UDP association")
	}
}

func TestClientStatsCountSentBytesFromWriteWorker(t *testing.T) {
	conn, recvCh, cleanup := newTestWebSocketConn(t)
	defer cleanup()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p := &clientPool{
		ctx:             ctx,
		config:          &Config{PingInterval: time.Hour, WriteTimeout: time.Second, ReadBufferSize: 1024},
		relayManager:    relay.NewRelayNodeManager(),
		connsWriteMutex: []sync.Mutex{{}},
	}

	msg := common.EncodeMessage(common.MsgConnStatus, "cid", []byte{byte(common.StatusOK)}, nil)
	queue := make(chan writeJob, 1)
	queue <- writeJob{msgType: websocket.BinaryMessage, data: msg, size: len(msg)}
	close(queue)

	done := make(chan struct{})
	go func() {
		p.writeWorker(0, conn, queue)
		close(done)
	}()

	select {
	case <-recvCh:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for websocket message to be sent")
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("writeWorker did not exit")
	}

	if got := p.Stats().BytesSent; got != uint64(len(msg)) {
		t.Fatalf("expected bytes sent %d, got %d", len(msg), got)
	}
}

func TestClientStatsCountReceivedBytesFromHandleChannel(t *testing.T) {
	clientConn, serverConn, cleanup := newClientTestWebSocketPair(t)
	defer cleanup()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p := &clientPool{
		ctx:          ctx,
		cancel:       cancel,
		config:       &Config{ReadTimeout: time.Second, WriteTimeout: time.Second},
		relayManager: relay.NewRelayNodeManager(),
		conns:        make(map[string]*clientConnState),
	}

	done := make(chan struct{})
	go func() {
		p.handleChannel(1, clientConn)
		close(done)
	}()

	msg := common.EncodeMessage(common.MsgConnStatus, "cid", []byte{byte(common.StatusOK)}, nil)
	if err := serverConn.WriteMessage(websocket.BinaryMessage, msg); err != nil {
		t.Fatalf("write message failed: %v", err)
	}
	_ = serverConn.Close()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("handleChannel did not exit")
	}

	if got := p.Stats().BytesReceived; got != uint64(len(msg)) {
		t.Fatalf("expected bytes received %d, got %d", len(msg), got)
	}
}

func newTestWebSocketConn(t *testing.T) (*websocket.Conn, <-chan []byte, func()) {
	t.Helper()

	recvCh := make(chan []byte, 1)
	serverDone := make(chan struct{})
	upgrader := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade failed: %v", err)
			close(serverDone)
			return
		}
		defer ws.Close()
		defer close(serverDone)

		_, msg, err := ws.ReadMessage()
		if err == nil {
			recvCh <- msg
		}
	}))

	dialURL := "ws" + strings.TrimPrefix(server.URL, "http")
	conn, _, err := websocket.DefaultDialer.Dial(dialURL, nil)
	if err != nil {
		server.Close()
		t.Fatalf("dial websocket failed: %v", err)
	}

	cleanup := func() {
		_ = conn.Close()
		server.Close()
		select {
		case <-serverDone:
		case <-time.After(time.Second):
		}
	}

	return conn, recvCh, cleanup
}

func newClientTestWebSocketPair(t *testing.T) (*websocket.Conn, *websocket.Conn, func()) {
	t.Helper()

	serverConnCh := make(chan *websocket.Conn, 1)
	release := make(chan struct{})
	serverDone := make(chan struct{})
	upgrader := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade failed: %v", err)
			close(serverDone)
			return
		}
		serverConnCh <- ws
		<-release
		_ = ws.Close()
		close(serverDone)
	}))

	dialURL := "ws" + strings.TrimPrefix(server.URL, "http")
	clientConn, _, err := websocket.DefaultDialer.Dial(dialURL, nil)
	if err != nil {
		server.Close()
		t.Fatalf("dial websocket failed: %v", err)
	}

	var serverConn *websocket.Conn
	select {
	case serverConn = <-serverConnCh:
	case <-time.After(time.Second):
		_ = clientConn.Close()
		server.Close()
		t.Fatal("timed out waiting for server websocket connection")
	}

	cleanup := func() {
		close(release)
		_ = clientConn.Close()
		_ = serverConn.Close()
		server.Close()
		select {
		case <-serverDone:
		case <-time.After(time.Second):
		}
	}

	return clientConn, serverConn, cleanup
}

func TestClientPoolHasChannelNotificationChannels(t *testing.T) {
	cfg := DefaultConfig()
	cfg.EnableHotPair = true
	cfg.Connections = 2

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	if p.chReadyCh == nil {
		t.Fatal("expected chReadyCh to be initialized")
	}
	if p.chInvalidCh == nil {
		t.Fatal("expected chInvalidCh to be initialized")
	}
	if p.pairWarmer == nil {
		t.Fatal("expected pairWarmer to be initialized when EnableHotPair is true")
	}

	// 验证通道有缓冲且容量正确
	if cap(p.chReadyCh) != 64 {
		t.Fatalf("expected chReadyCh cap 64, got %d", cap(p.chReadyCh))
	}
	if cap(p.chInvalidCh) != 64 {
		t.Fatalf("expected chInvalidCh cap 64, got %d", cap(p.chInvalidCh))
	}

	// 验证未启用 HotPair 时 pairWarmer 为 nil
	cfg2 := DefaultConfig()
	cfg2.EnableHotPair = false
	p2, err := newClientPool(cfg2, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}
	if p2.pairWarmer != nil {
		t.Fatal("expected pairWarmer to be nil when EnableHotPair is false")
	}
}

func TestClientPoolStartReturnsWithoutBlockingOnECH(t *testing.T) {
	// 共享 ECH 管理器采用懒加载 + 失败回退标准 TLS（与 GCM 后端一致），
	// Start 不应因 ECH 准备而阻塞。
	cfg := DefaultConfig()
	cfg.ServerAddr = "wss://127.0.0.1:1"
	cfg.EnableECH = true
	cfg.DNSServer = "127.0.0.1:1"
	cfg.ECHDomain = "invalid.example"
	cfg.Connections = 1

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	done := make(chan struct{})
	go func() {
		p.Start(nil)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("Start() blocked on ECH preparation")
	}
}

func TestDialWebSocketIncludesStableClientID(t *testing.T) {
	type requestInfo struct {
		clientID string
		chID     string
	}

	requests := make(chan requestInfo, 2)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests <- requestInfo{
			clientID: r.URL.Query().Get("client_id"),
			chID:     r.URL.Query().Get("ch_id"),
		}
		conn, err := websocket.Upgrade(w, r, nil, 1024, 1024)
		if err != nil {
			t.Errorf("Upgrade() error = %v", err)
			return
		}
		_ = conn.Close()
	}))
	defer server.Close()

	cfg := DefaultConfig()
	cfg.ServerAddr = strings.Replace(server.URL, "https://", "wss://", 1)
	cfg.EnableECH = false
	cfg.InsecureSkipVerify = true
	cfg.ClientID = "test-client-id"

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	conn1, err := p.dialWebSocket(1, "")
	if err != nil {
		t.Fatalf("first dialWebSocket() error = %v", err)
	}
	_ = conn1.Close()

	conn2, err := p.dialWebSocket(2, "")
	if err != nil {
		t.Fatalf("second dialWebSocket() error = %v", err)
	}
	_ = conn2.Close()

	first := <-requests
	second := <-requests

	if first.clientID == "" {
		t.Fatalf("first request missing client_id")
	}
	if second.clientID == "" {
		t.Fatalf("second request missing client_id")
	}
	if first.clientID != second.clientID {
		t.Fatalf("client_id should stay stable across dials: first=%q second=%q", first.clientID, second.clientID)
	}
	if first.chID != "1" {
		t.Fatalf("first ch_id = %q, want 1", first.chID)
	}
	if second.chID != "2" {
		t.Fatalf("second ch_id = %q, want 2", second.chID)
	}
}

func TestDialWebSocketReturnsContextErrorQuicklyWhenCancelledDuringECHRetryWait(t *testing.T) {
	cfg := DefaultConfig()
	cfg.ServerAddr = "wss://example.com:443"
	cfg.EnableECH = true
	cfg.DNSServer = "127.0.0.1:1"
	cfg.ECHDomain = "invalid.example"

	ctx, cancel := context.WithCancel(context.Background())
	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	cancel()
	start := time.Now()
	_, err = p.dialWebSocket(1, "")
	elapsed := time.Since(start)

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("dialWebSocket() error = %v, want context.Canceled", err)
	}
	if elapsed > 300*time.Millisecond {
		t.Fatalf("dialWebSocket() returned too slowly after cancellation: %v", elapsed)
	}
}

func TestDialAndServeStopsPromptlyWhenCancelledDuringReconnectDelay(t *testing.T) {
	connected := make(chan struct{}, 1)
	upgrader := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("Upgrade() error = %v", err)
			return
		}
		select {
		case connected <- struct{}{}:
		default:
		}
		_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""), time.Now().Add(time.Second))
		_ = conn.Close()
	}))
	defer server.Close()

	cfg := DefaultConfig()
	cfg.ServerAddr = strings.Replace(server.URL, "https://", "wss://", 1)
	cfg.EnableECH = false
	cfg.InsecureSkipVerify = true
	cfg.ReconnectDelay = time.Second
	cfg.Connections = 1

	ctx, cancel := context.WithCancel(context.Background())
	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	done := make(chan struct{})
	go func() {
		p.dialAndServe(0, "")
		close(done)
	}()

	select {
	case <-connected:
	case <-time.After(2 * time.Second):
		t.Fatalf("dialAndServe() did not establish initial connection")
	}

	time.Sleep(50 * time.Millisecond)
	start := time.Now()
	cancel()

	select {
	case <-done:
		if elapsed := time.Since(start); elapsed > 300*time.Millisecond {
			t.Fatalf("dialAndServe() returned too slowly after cancellation: %v", elapsed)
		}
	case <-time.After(400 * time.Millisecond):
		t.Fatalf("dialAndServe() did not stop promptly during reconnect delay")
	}
}

func TestDialAndServeKeepsRetryingAfterRetryLimit(t *testing.T) {
	oldBaseDelay := dialAndServeBaseDelay
	oldMaxDelay := dialAndServeMaxDelay
	oldMaxRetries := dialAndServeMaxRetries
	defer func() {
		dialAndServeBaseDelay = oldBaseDelay
		dialAndServeMaxDelay = oldMaxDelay
		dialAndServeMaxRetries = oldMaxRetries
	}()

	dialAndServeBaseDelay = time.Millisecond
	dialAndServeMaxDelay = 2 * time.Millisecond
	dialAndServeMaxRetries = 2

	cfg := DefaultConfig()
	cfg.ServerAddr = "wss://127.0.0.1:1"
	cfg.EnableECH = false
	cfg.DialTimeout = 10 * time.Millisecond
	cfg.Connections = 1

	ctx, cancel := context.WithCancel(context.Background())
	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	done := make(chan struct{})
	go func() {
		p.dialAndServe(0, "")
		close(done)
	}()

	select {
	case <-done:
		t.Fatalf("dialAndServe() returned after hitting retry limit, want it to keep retrying")
	case <-time.After(50 * time.Millisecond):
	}

	cancel()
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("dialAndServe() did not exit after cancellation")
	}
}
