package xtunnel

import (
	"bufio"
	"encoding/base64"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestHandleHTTPProxyConnRejectsMissingProxyAuth(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	p := &clientPool{config: DefaultConfig(), conns: make(map[string]*clientConnState)}
	done := make(chan struct{})
	go func() {
		defer close(done)
		p.handleHTTPProxyConn(serverConn, &ProxyConfig{Username: "user", Password: "pass"})
	}()

	_, err := clientConn.Write([]byte("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"))
	if err != nil {
		t.Fatalf("write request failed: %v", err)
	}

	resp, err := http.ReadResponse(bufio.NewReader(clientConn), nil)
	if err != nil {
		t.Fatalf("read response failed: %v", err)
	}
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("expected 407 response, got %d", resp.StatusCode)
	}
	if got := resp.Header.Get("Proxy-Authenticate"); !strings.Contains(got, "Basic") {
		t.Fatalf("expected Proxy-Authenticate Basic header, got %q", got)
	}

	waitHTTPProxyTestDone(t, done)
}

func TestHandleHTTPProxyConnReturnsBadGatewayWhenNoChannel(t *testing.T) {
	serverConn, clientConn := net.Pipe()
	defer clientConn.Close()

	p := &clientPool{
		config: &Config{ConnectTimeout: 50 * time.Millisecond},
		conns:  make(map[string]*clientConnState),
	}
	encodedAuth := base64.StdEncoding.EncodeToString([]byte("user:pass"))
	done := make(chan struct{})
	go func() {
		defer close(done)
		p.handleHTTPProxyConn(serverConn, &ProxyConfig{Username: "user", Password: "pass"})
	}()

	request := "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic " + encodedAuth + "\r\n\r\n"
	if _, err := clientConn.Write([]byte(request)); err != nil {
		t.Fatalf("write request failed: %v", err)
	}

	start := time.Now()
	resp, err := http.ReadResponse(bufio.NewReader(clientConn), nil)
	if err != nil {
		t.Fatalf("read response failed: %v", err)
	}
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("expected 502 response, got %d", resp.StatusCode)
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Fatalf("expected connect timeout to respect config, took %v", elapsed)
	}

	waitHTTPProxyTestDone(t, done)
}

func TestBuildHTTPRequestForUpstreamRemovesProxyHeaders(t *testing.T) {
	req, err := http.ReadRequest(bufio.NewReader(strings.NewReader(
		"GET http://example.com/path?q=1 HTTP/1.1\r\n" +
			"Host: example.com\r\n" +
			"Proxy-Connection: keep-alive\r\n" +
			"Proxy-Authorization: Basic dGVzdA==\r\n\r\n")))
	if err != nil {
		t.Fatalf("read request failed: %v", err)
	}

	data, err := buildHTTPRequestForUpstream(req)
	if err != nil {
		t.Fatalf("build upstream request failed: %v", err)
	}
	text := string(data)
	if strings.Contains(text, "http://example.com/path") {
		t.Fatalf("expected origin-form request line, got %q", text)
	}
	if strings.Contains(strings.ToLower(text), "proxy-connection") || strings.Contains(strings.ToLower(text), "proxy-authorization") {
		t.Fatalf("expected proxy-only headers to be removed, got %q", text)
	}
	if !strings.HasPrefix(text, "GET /path?q=1 HTTP/1.1\r\n") {
		t.Fatalf("expected origin-form request line, got %q", text)
	}
}

func TestBuildHTTPRequestForUpstreamDoesNotConsumeBody(t *testing.T) {
	req, err := http.ReadRequest(bufio.NewReader(strings.NewReader(
		"POST http://example.com/upload HTTP/1.1\r\n" +
			"Host: example.com\r\n" +
			"Content-Length: 5\r\n\r\nhello")))
	if err != nil {
		t.Fatalf("read request failed: %v", err)
	}

	data, err := buildHTTPRequestForUpstream(req)
	if err != nil {
		t.Fatalf("build upstream request failed: %v", err)
	}
	if strings.Contains(string(data), "hello") {
		t.Fatalf("expected first upstream packet to contain headers only, got %q", string(data))
	}
}

func TestReadBufferedProxyBytesReturnsUnreadBody(t *testing.T) {
	reader := bufio.NewReader(strings.NewReader(
		"POST http://example.com/upload HTTP/1.1\r\n" +
			"Host: example.com\r\n" +
			"Content-Length: 5\r\n\r\nhello"))
	if _, err := http.ReadRequest(reader); err != nil {
		t.Fatalf("read request failed: %v", err)
	}

	data, err := readBufferedProxyBytes(reader)
	if err != nil {
		t.Fatalf("read buffered proxy bytes failed: %v", err)
	}
	if string(data) != "hello" {
		t.Fatalf("expected unread body bytes hello, got %q", string(data))
	}
}

func waitHTTPProxyTestDone(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for HTTP proxy handler to exit")
	}
}
