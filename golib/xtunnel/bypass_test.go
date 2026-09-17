package xtunnel

import (
	"context"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/v2up-32mb/xshared/routing"
	xtlib "github.com/v2up-32mb/xtunnel"
)

func freePort(t *testing.T) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	defer l.Close()
	return l.Addr().String()
}

func startEchoServer(t *testing.T) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen failed: %v", err)
	}
	t.Cleanup(func() { _ = l.Close() })
	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				_, _ = io.Copy(c, c)
				_ = c.Close()
			}(conn)
		}
	}()
	return l.Addr().String()
}

// TestShouldBypassRules 验证共享路由匹配器的绕过规则（原 clientPool.shouldBypass
// 语义等价：Match(originalHost, resolvedHost)，同主机场景二者一致）。
func TestShouldBypassRules(t *testing.T) {
	mk := func(private, geoip, geosite bool, rules string) *routing.Matcher {
		m, err := routing.NewMatcher(private, geoip, geosite, rules)
		if err != nil {
			t.Fatalf("NewMatcher() error = %v", err)
		}
		return m
	}

	cases := []struct {
		name    string
		matcher *routing.Matcher
		target  string
		want    bool
	}{
		// xshared Matcher 约定收不带端口的 host（端口由 SOCKS5 服务器解析后单独传递）
		{"private lan", mk(true, false, false, ""), "192.168.1.10", true},
		{"private localhost domain", mk(true, false, false, ""), "localhost", true},
		{"private public ip", mk(true, false, false, ""), "8.8.8.8", false},
		{"geoip cn", mk(false, true, false, "114.114.114.114"), "114.114.114.114", true},
		{"geoip non-cn", mk(false, true, false, ""), "8.8.8.8", false},
		{"geosite cn domain", mk(false, false, true, ""), "baidu.com", true},
		{"manual suffix", mk(false, false, false, "domain:example.cn"), "www.example.cn", true},
		{"manual full domain", mk(false, false, false, "full:example.com"), "sub.example.com", false},
		{"manual exact domain", mk(false, false, false, "full:example.com"), "example.com", true},
		{"manual ip", mk(false, false, false, "127.0.0.1"), "127.0.0.1", true},
		{"manual cidr", mk(false, false, false, "10.0.0.0/8"), "10.1.2.3", true},
		{"manual ipv6", mk(false, false, false, "fc00::/7"), "fc02::1", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if tc.matcher == nil {
				return
			}
			if got := tc.matcher.Match(tc.target, tc.target); got != tc.want {
				t.Fatalf("Match(%q) = %v, want %v", tc.target, got, tc.want)
			}
		})
	}
}

// TestBuildConfigBypassParams 验证 bypass 参数的类型校验（值不再进入协议库 Config，
// Matcher 在 Start 中构建）。
func TestBuildConfigBypassParams(t *testing.T) {
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
	_ = xtlib.DefaultConfig // 引用库包（编译期依赖检查）
	_ = context.Background
}
