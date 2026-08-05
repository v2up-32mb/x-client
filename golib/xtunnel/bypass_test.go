package xtunnel

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"xclient/shared/routing"
)

// freePort 找一个空闲 TCP 端口（测试用，关闭后可能被抢占）。
func freePort(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("probe listen failed: %v", err)
	}
	addr := l.Addr().String()
	_ = l.Close()
	return addr
}

// startEchoServer 启动一个 TCP echo 服务，返回监听地址。
func startEchoServer(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen failed: %v", err)
	}
	t.Cleanup(func() { _ = l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()
	return l.Addr().String()
}

func TestShouldBypassRules(t *testing.T) {
	mk := func(private, geoip, geosite bool, rules string) *clientPool {
		m, err := routing.NewMatcher(private, geoip, geosite, rules)
		if err != nil {
			t.Fatalf("NewMatcher() error = %v", err)
		}
		return &clientPool{bypassMatcher: m}
	}

	cases := []struct {
		name   string
		pool   *clientPool
		target string
		want   bool
	}{
		{"nil matcher", &clientPool{}, "1.2.3.4:80", false},
		{"bad target", mk(true, false, false, ""), "192.168.1.10", false},
		{"private lan", mk(true, false, false, ""), "192.168.1.10:8080", true},
		{"private localhost domain", mk(true, false, false, ""), "localhost:1080", true},
		{"private public ip", mk(true, false, false, ""), "8.8.8.8:53", false},
		{"geoip cn", mk(false, true, false, "114.114.114.114"), "114.114.114.114:53", true},
		{"geoip non-cn", mk(false, true, false, ""), "8.8.8.8:53", false},
		{"geosite cn domain", mk(false, false, true, ""), "baidu.com:443", true},
		{"manual suffix", mk(false, false, false, "domain:example.cn"), "www.example.cn:443", true},
		{"manual full domain", mk(false, false, false, "full:example.com"), "sub.example.com:443", false},
		{"manual exact domain", mk(false, false, false, "full:example.com"), "example.com:443", true},
		{"manual ip", mk(false, false, false, "127.0.0.1"), "127.0.0.1:1080", true},
		{"manual cidr", mk(false, false, false, "10.0.0.0/8"), "10.1.2.3:80", true},
		{"manual ipv6", mk(false, false, false, "fc00::/7"), "[fc02::1]:443", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := tc.pool.shouldBypass(tc.target); got != tc.want {
				t.Fatalf("shouldBypass(%q) = %v, want %v", tc.target, got, tc.want)
			}
		})
	}
}

func TestBuildConfigBypassParams(t *testing.T) {
	params := map[string]string{
		ParamServerAddr:      "wss://tunnel.example.com",
		ParamBypassPrivate:   "true",
		ParamBypassGeoIPCN:   "true",
		ParamBypassGeoSiteCN: "true",
		ParamBypassRules:     "192.168.0.0/16\ndomain:example.cn",
	}
	cfg, err := buildConfig(params)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if !cfg.BypassPrivate || !cfg.BypassGeoIPCN || !cfg.BypassGeoSiteCN {
		t.Fatalf("bypass flags = private:%v geoip:%v geosite:%v, want all true",
			cfg.BypassPrivate, cfg.BypassGeoIPCN, cfg.BypassGeoSiteCN)
	}
	if cfg.BypassRules != "192.168.0.0/16\ndomain:example.cn" {
		t.Fatalf("BypassRules = %q", cfg.BypassRules)
	}

	// 布尔参数类型错误必须在启动网络前报错
	if _, err := buildConfig(map[string]string{
		ParamServerAddr:    "wss://x",
		ParamBypassPrivate: "maybe",
	}); err == nil || !strings.Contains(err.Error(), `"bypass_private"`) {
		t.Fatalf("bad bypass bool error = %v, want containing %q", err, "bypass_private")
	}
}

func TestBackendStartInvalidBypassRules(t *testing.T) {
	b := NewBackend()
	err := b.Start("127.0.0.1:0", map[string]string{
		ParamServerAddr:  "wss://127.0.0.1:1",
		ParamBypassRules: "not a valid rule!",
	}, false)
	if err == nil || !strings.Contains(err.Error(), "invalid bypass rules") {
		t.Fatalf("Backend.Start() error = %v, want containing 'invalid bypass rules'", err)
	}
	_ = b.Stop() // 不应 panic
}

// TestBackendSOCKS5BypassDirect 验证命中路由绕过的 SOCKS5 CONNECT 走直连：
// 即使隧道（wss://127.0.0.1:1）不可达也能完成往返。
func TestBackendSOCKS5BypassDirect(t *testing.T) {
	echoAddr := startEchoServer(t)
	socksAddr := freePort(t)

	b := NewBackend()
	params := map[string]string{
		ParamServerAddr:    "wss://127.0.0.1:1",
		ParamConnections:   "1",
		ParamEnableECH:     "false",
		ParamEnableHotPair: "false",
		ParamBypassPrivate: "true", // 127.0.0.1 命中本地/局域网绕过
	}
	if err := b.Start(socksAddr, params, false); err != nil {
		t.Fatalf("Backend.Start() error = %v", err)
	}
	t.Cleanup(func() { _ = b.Stop() })

	conn, err := net.DialTimeout("tcp", socksAddr, 3*time.Second)
	if err != nil {
		t.Fatalf("dial socks5 failed: %v", err)
	}
	defer conn.Close()

	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("write greeting failed: %v", err)
	}
	method := make([]byte, 2)
	if _, err := io.ReadFull(conn, method); err != nil {
		t.Fatalf("read method reply failed: %v", err)
	}
	if method[0] != 0x05 || method[1] != 0x00 {
		t.Fatalf("method reply = %v, want no-auth", method)
	}

	host, portStr, err := net.SplitHostPort(echoAddr)
	if err != nil {
		t.Fatalf("echoAddr %q: %v", echoAddr, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("echo port %q: %v", portStr, err)
	}
	ip := net.ParseIP(host).To4()
	if ip == nil {
		t.Fatalf("echo host %q is not IPv4", host)
	}
	req := []byte{0x05, 0x01, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3], byte(port >> 8), byte(port)}
	if _, err := conn.Write(req); err != nil {
		t.Fatalf("write connect request failed: %v", err)
	}
	resp := make([]byte, 10)
	if _, err := io.ReadFull(conn, resp); err != nil {
		t.Fatalf("read connect reply failed: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		t.Fatalf("connect reply = %v, want success", resp)
	}

	payload := []byte("bypass-echo")
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write payload failed: %v", err)
	}
	got := make([]byte, len(payload))
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echo failed: %v", err)
	}
	if string(got) != string(payload) {
		t.Fatalf("echo = %q, want %q", got, payload)
	}
}

// TestHTTPProxyBypassDirect 验证 HTTP 代理的 CONNECT 与普通请求都走直连绕过。
func TestHTTPProxyBypassDirect(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cfg := DefaultConfig()
	cfg.EnableECH = false
	cfg.EnableHotPair = false
	pool, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("newClientPool() error = %v", err)
	}
	m, err := routing.NewMatcher(true, false, false, "") // 127.0.0.1 命中 private
	if err != nil {
		t.Fatalf("NewMatcher() error = %v", err)
	}
	pool.bypassMatcher = m

	proxyAddr := freePort(t)
	if err := pool.ListenHTTP(proxyAddr); err != nil {
		t.Fatalf("ListenHTTP() error = %v", err)
	}

	// HTTP CONNECT 直连
	echoAddr := startEchoServer(t)
	conn, err := net.DialTimeout("tcp", proxyAddr, 3*time.Second)
	if err != nil {
		t.Fatalf("dial proxy failed: %v", err)
	}
	defer conn.Close()
	fmt.Fprintf(conn, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", echoAddr, echoAddr)
	br := bufio.NewReader(conn)
	statusLine, err := br.ReadString('\n')
	if err != nil {
		t.Fatalf("read connect status failed: %v", err)
	}
	if !strings.Contains(statusLine, "200") {
		t.Fatalf("CONNECT status = %q, want 200", statusLine)
	}
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			t.Fatalf("read connect headers failed: %v", err)
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	payload := []byte("http-connect-echo")
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write via CONNECT failed: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(br, got); err != nil {
		t.Fatalf("read via CONNECT failed: %v", err)
	}
	if string(got) != string(payload) {
		t.Fatalf("CONNECT echo = %q, want %q", got, payload)
	}

	// 普通 HTTP GET 直连
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "hello-%s", r.URL.Path)
	}))
	defer ts.Close()

	proxyURL, err := url.Parse("http://" + proxyAddr)
	if err != nil {
		t.Fatalf("parse proxy url failed: %v", err)
	}
	client := &http.Client{
		Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)},
		Timeout:   5 * time.Second,
	}
	resp, err := client.Get(ts.URL + "/x")
	if err != nil {
		t.Fatalf("GET via proxy failed: %v", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read GET response failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK || string(body) != "hello-/x" {
		t.Fatalf("GET via proxy = %d %q, want 200 hello-/x", resp.StatusCode, body)
	}
}
