package xtunnel

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func TestHandleSOCKS5RejectsWhenRequiredAuthMethodNotOffered(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		(&clientPool{}).handleSOCKS5(serverConn, &ProxyConfig{Username: "user", Password: "pass"})
	}()

	if _, err := clientConn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("write greeting failed: %v", err)
	}

	resp := readSOCKS5TestBytes(t, clientConn, 2)
	if resp[0] != 0x05 || resp[1] != 0xFF {
		t.Fatalf("expected no acceptable methods reply [5 255], got %v", resp)
	}

	_ = clientConn.Close()
	waitSOCKS5TestDone(t, done)
}

func TestHandleSOCKS5RejectsWhenNoAuthMethodNotOffered(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		(&clientPool{}).handleSOCKS5(serverConn, &ProxyConfig{})
	}()

	if _, err := clientConn.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		t.Fatalf("write greeting failed: %v", err)
	}

	resp := readSOCKS5TestBytes(t, clientConn, 2)
	if resp[0] != 0x05 || resp[1] != 0xFF {
		t.Fatalf("expected no acceptable methods reply [5 255], got %v", resp)
	}

	_ = clientConn.Close()
	waitSOCKS5TestDone(t, done)
}

func TestHandleSOCKS5UserPassAuthRejectsInvalidVersion(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	resultCh := make(chan error, 1)
	go func() {
		resultCh <- (&clientPool{}).handleSOCKS5UserPassAuth(serverConn, &ProxyConfig{Username: "user", Password: "pass"})
	}()

	if _, err := clientConn.Write([]byte{0x02, 0x04}); err != nil {
		t.Fatalf("write auth header failed: %v", err)
	}

	resp := readSOCKS5TestBytes(t, clientConn, 2)
	if resp[0] != 0x01 || resp[1] != 0x01 {
		t.Fatalf("expected auth failure reply [1 1], got %v", resp)
	}

	select {
	case err := <-resultCh:
		if err == nil {
			t.Fatal("expected invalid auth version to fail")
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for auth result")
	}
}

func TestParseSOCKS5UDPPacketRejectsNonZeroReserved(t *testing.T) {
	packet := []byte{0x01, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x1F, 0x90, 'p', 'i', 'n', 'g'}

	if _, _, err := parseSOCKS5UDPPacket(packet); err == nil {
		t.Fatal("expected non-zero RSV packet to be rejected")
	}
}

func TestBuildSOCKS5UDPPacketRejectsInvalidPort(t *testing.T) {
	if _, err := buildSOCKS5UDPPacket("127.0.0.1", -1, []byte("x")); err == nil {
		t.Fatal("expected negative port to be rejected")
	}
	if _, err := buildSOCKS5UDPPacket("127.0.0.1", 65536, []byte("x")); err == nil {
		t.Fatal("expected oversized port to be rejected")
	}
}

func TestUDPAssociationLoopReturnsWhenDoneChannelAlreadySignaled(t *testing.T) {
	listener, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		t.Fatalf("listen udp failed: %v", err)
	}

	assoc := &udpAssociation{
		udpListener: listener,
		done:        make(chan bool, 1),
		pool:        &clientPool{config: DefaultConfig()},
	}
	assoc.done <- true
	_ = listener.Close()

	done := make(chan struct{})
	go func() {
		assoc.loop()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("expected loop to return even if done channel already has a signal")
	}
}

func TestHandleSOCKS5ConnectReturnsFailureReplyWhenNoChannel(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	p := &clientPool{
		config: &Config{ConnectTimeout: 50 * time.Millisecond},
		conns:  make(map[string]*clientConnState),
	}

	done := make(chan struct{})
	go func() {
		defer close(done)
		p.handleSOCKS5Connect(serverConn, &ProxyConfig{}, "example.com:80")
	}()

	_ = readSOCKS5TestBytes(t, clientConn, 10)
	start := time.Now()
	resp := readSOCKS5TestBytes(t, clientConn, 10)
	elapsed := time.Since(start)
	if resp[0] != 0x05 || resp[1] != 0x01 {
		t.Fatalf("expected timeout failure reply [5 1 ...], got %v", resp)
	}
	if elapsed > 500*time.Millisecond {
		t.Fatalf("expected connect timeout to respect config, took %v", elapsed)
	}

	waitSOCKS5TestDone(t, done)
}

func readSOCKS5TestBytes(t *testing.T, conn net.Conn, n int) []byte {
	t.Helper()

	buf := make([]byte, n)
	if err := conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatalf("set read deadline failed: %v", err)
	}
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatalf("read response failed: %v", err)
	}
	return buf
}

func waitSOCKS5TestDone(t *testing.T, done <-chan struct{}) {
	t.Helper()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for SOCKS5 handler to exit")
	}
}

func TestUDPAssociationCloseIsIdempotent(t *testing.T) {
	cfg := DefaultConfig()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	listener, err := net.ListenUDP("udp", nil)
	if err != nil {
		t.Fatalf("ListenUDP() error = %v", err)
	}
	defer listener.Close()

	a := &udpAssociation{
		connID:      "conn-1",
		udpListener: listener,
		pool:        p,
		done:        make(chan bool, 5),
		channelID:   -1,
	}

	a.Close()
	a.Close()
}

func TestUDPAssociationCloseStopsBlockedLoop(t *testing.T) {
	cfg := DefaultConfig()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}

	listener, err := net.ListenUDP("udp", nil)
	if err != nil {
		t.Fatalf("ListenUDP() error = %v", err)
	}

	a := &udpAssociation{
		connID:      "conn-2",
		udpListener: listener,
		pool:        p,
		done:        make(chan bool, 5),
		channelID:   -1,
	}

	done := make(chan struct{})
	go func() {
		a.loop()
		close(done)
	}()

	time.Sleep(50 * time.Millisecond)
	a.Close()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatalf("udpAssociation.loop() did not exit after Close()")
	}
}
