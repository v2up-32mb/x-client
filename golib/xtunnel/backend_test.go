package xtunnel

import (
	"net"
	"strings"
	"testing"
	"time"

	"xclient/shared/config"
)

func TestBuildConfigFullParams(t *testing.T) {
	params := map[string]string{
		ParamServerAddr:    "wss://tunnel.example.com:8443/path",
		ParamToken:         "secret",
		ParamConnections:   "5",
		ParamClientID:      "android-1",
		ParamRelayNodes:    "relay1.example.com:443, relay2.example.com",
		ParamEnableECH:     "false",
		ParamECHDomain:     "ech.example.com",
		ParamDNSServer:     "https://doh.pub/dns-query",
		ParamInsecure:      "true",
		ParamEnableHotPair: "true",
	}
	cfg, err := buildConfig(params)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.ServerAddr != "wss://tunnel.example.com:8443/path" {
		t.Fatalf("ServerAddr = %q", cfg.ServerAddr)
	}
	if cfg.Token != "secret" || cfg.ClientID != "android-1" {
		t.Fatalf("Token = %q, ClientID = %q", cfg.Token, cfg.ClientID)
	}
	if cfg.Connections != 5 {
		t.Fatalf("Connections = %d, want 5", cfg.Connections)
	}
	if len(cfg.RelayNodes) != 2 || cfg.RelayNodes[0] != "relay1.example.com:443" || cfg.RelayNodes[1] != "relay2.example.com" {
		t.Fatalf("RelayNodes = %#v", cfg.RelayNodes)
	}
	if cfg.EnableECH {
		t.Fatal("EnableECH = true, want false")
	}
	if cfg.ECHDomain != "ech.example.com" || cfg.DNSServer != "https://doh.pub/dns-query" {
		t.Fatalf("ECHDomain = %q, DNSServer = %q", cfg.ECHDomain, cfg.DNSServer)
	}
	if !cfg.InsecureSkipVerify || !cfg.EnableHotPair {
		t.Fatalf("InsecureSkipVerify = %v, EnableHotPair = %v", cfg.InsecureSkipVerify, cfg.EnableHotPair)
	}
}

func TestBuildConfigDefaults(t *testing.T) {
	cfg, err := buildConfig(map[string]string{ParamServerAddr: "wss://tunnel.example.com"})
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.Connections != 3 {
		t.Fatalf("Connections = %d, want default 3", cfg.Connections)
	}
	// x-tunnel 默认值：ECH 开启（懒加载 + 失败回退标准 TLS）
	if !cfg.EnableECH {
		t.Fatal("EnableECH = false, want default true")
	}
	if cfg.ECHDomain != "cloudflare-ech.com" {
		t.Fatalf("ECHDomain = %q, want default", cfg.ECHDomain)
	}
	if cfg.EnableHotPair {
		t.Fatal("EnableHotPair = true, want default false")
	}
	if cfg.InsecureSkipVerify {
		t.Fatal("InsecureSkipVerify = true, want default false")
	}
}

func TestBuildConfigErrors(t *testing.T) {
	cases := []struct {
		name   string
		params map[string]string
		want   string
	}{
		{"missing server", map[string]string{}, "server address is required"},
		{"bad scheme", map[string]string{ParamServerAddr: "tcp://x"}, "wss:// or ws://"},
		{"missing host", map[string]string{ParamServerAddr: "wss://"}, "must include a host"},
		{"empty host query", map[string]string{ParamServerAddr: "wss://?token=x"}, "must include a host"},
		{"bad connections", map[string]string{ParamServerAddr: "wss://x", ParamConnections: "abc"}, `"connections"`},
		{"bad ech bool", map[string]string{ParamServerAddr: "wss://x", ParamEnableECH: "yes"}, `"enable_ech"`},
		{"bad hotpair bool", map[string]string{ParamServerAddr: "wss://x", ParamEnableHotPair: "maybe"}, `"enable_hot_pair"`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := buildConfig(tc.params)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("buildConfig() error = %v, want containing %q", err, tc.want)
			}
		})
	}
}

func TestBackendStartStopReleasesSOCKS5Port(t *testing.T) {
	// 找一个空闲端口作为固定监听地址
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("probe listen failed: %v", err)
	}
	addr := l.Addr().String()
	_ = l.Close()

	b := NewBackend()
	params := map[string]string{
		ParamServerAddr:    "wss://127.0.0.1:1",
		ParamConnections:   "1",
		ParamEnableECH:     "false",
		ParamEnableHotPair: "false",
	}
	if err := b.Start(addr, params, false); err != nil {
		t.Fatalf("Backend.Start() error = %v", err)
	}
	// 重复启动必须报错
	if err := b.Start(addr, params, false); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("second Start() error = %v, want already running", err)
	}
	if err := b.Stop(); err != nil {
		t.Fatalf("Backend.Stop() error = %v", err)
	}
	// 停止后端口必须可重新绑定（监听器关闭验证）
	l2, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatalf("SOCKS5 port not released after Stop(): %v", err)
	}
	_ = l2.Close()
	// 幂等停止
	if err := b.Stop(); err != nil {
		t.Fatalf("second Stop() error = %v", err)
	}
}

func TestLogLevelFromParams(t *testing.T) {
	// 显式 log_level 优先于 verbose
	if got := logLevelFromParams(map[string]string{ParamLogLevel: "warn"}, true); got != config.WARN {
		t.Fatalf("explicit warn = %v, want WARN", got)
	}
	if got := logLevelFromParams(map[string]string{ParamLogLevel: "ERROR"}, false); got != config.ERROR {
		t.Fatalf("explicit ERROR = %v, want ERROR", got)
	}
	// 无显式参数：verbose 兼容
	if got := logLevelFromParams(nil, true); got != config.DEBUG {
		t.Fatalf("verbose = %v, want DEBUG", got)
	}
	if got := logLevelFromParams(nil, false); got != config.INFO {
		t.Fatalf("default = %v, want INFO", got)
	}
	// 未知级别回退 INFO
	if got := logLevelFromParams(map[string]string{ParamLogLevel: "verbose"}, true); got != config.INFO {
		t.Fatalf("unknown level = %v, want INFO fallback", got)
	}
}

func TestBuildConfigHotPairCount(t *testing.T) {
	base := map[string]string{ParamServerAddr: "wss://tunnel.example.com"}

	// 默认 1 对
	cfg, err := buildConfig(base)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.HotPairCount != 1 {
		t.Fatalf("default HotPairCount = %d, want 1", cfg.HotPairCount)
	}

	// 显式数量
	params := map[string]string{ParamServerAddr: "wss://tunnel.example.com", ParamHotPairCount: "4"}
	cfg, err = buildConfig(params)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.HotPairCount != 4 {
		t.Fatalf("HotPairCount = %d, want 4", cfg.HotPairCount)
	}

	// 非法值
	if _, err := buildConfig(map[string]string{ParamServerAddr: "wss://x", ParamHotPairCount: "abc"}); err == nil || !strings.Contains(err.Error(), `"hot_pair_count"`) {
		t.Fatalf("bad hot_pair_count error = %v, want containing %q", err, "hot_pair_count")
	}
	if _, err := buildConfig(map[string]string{ParamServerAddr: "wss://x", ParamHotPairCount: "9"}); err == nil || !strings.Contains(err.Error(), "exceeds max") {
		t.Fatalf("oversized hot_pair_count error = %v, want containing 'exceeds max'", err)
	}
}

func TestBuildConfigAdvancedParams(t *testing.T) {
	base := map[string]string{ParamServerAddr: "wss://tunnel.example.com"}

	// 缺省时使用 8MB 默认
	cfg, err := buildConfig(base)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.BackpressureLimitBytes != DefaultBackpressureLimitBytes {
		t.Fatalf("default BackpressureLimitBytes = %d, want %d", cfg.BackpressureLimitBytes, DefaultBackpressureLimitBytes)
	}

	// 显式字节/毫秒/端口/连接数
	params := map[string]string{
		ParamServerAddr:            "wss://tunnel.example.com",
		ParamBackpressureLimit:     "16777216",
		ParamWriteQueueWaitTimeout: "250",
		ParamReadTimeout:           "20000",
		ParamWriteTimeout:          "8000",
		ParamPingInterval:          "10000",
		ParamReconnectDelay:        "2000",
		ParamConnectTimeout:        "30000",
		ParamDialTimeout:           "5000",
		ParamHandshakeTimeout:      "7000",
		ParamMaxSocks5Connections:  "2048",
		ParamUDPBlockedPorts:       "443,53",
	}
	cfg, err = buildConfig(params)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.BackpressureLimitBytes != 16777216 {
		t.Fatalf("BackpressureLimitBytes = %d, want 16777216", cfg.BackpressureLimitBytes)
	}
	if cfg.WriteQueueWaitTimeout != 250*time.Millisecond {
		t.Fatalf("WriteQueueWaitTimeout = %v, want 250ms", cfg.WriteQueueWaitTimeout)
	}
	if cfg.ReadTimeout != 20*time.Second || cfg.WriteTimeout != 8*time.Second {
		t.Fatalf("timeouts = %v/%v, want 20s/8s", cfg.ReadTimeout, cfg.WriteTimeout)
	}
	if cfg.PingInterval != 10*time.Second || cfg.ReconnectDelay != 2*time.Second {
		t.Fatalf("ping/reconnect = %v/%v, want 10s/2s", cfg.PingInterval, cfg.ReconnectDelay)
	}
	if cfg.ConnectTimeout != 30*time.Second || cfg.DialTimeout != 5*time.Second || cfg.HandshakeTimeout != 7*time.Second {
		t.Fatalf("connect/dial/handshake = %v/%v/%v, want 30s/5s/7s", cfg.ConnectTimeout, cfg.DialTimeout, cfg.HandshakeTimeout)
	}
	if cfg.MaxSOCKS5Connections != 2048 {
		t.Fatalf("MaxSOCKS5Connections = %d, want 2048", cfg.MaxSOCKS5Connections)
	}
	if len(cfg.UDPBlockedPorts) != 2 || cfg.UDPBlockedPorts[0] != 443 || cfg.UDPBlockedPorts[1] != 53 {
		t.Fatalf("UDPBlockedPorts = %v, want [443 53]", cfg.UDPBlockedPorts)
	}

	// 非法值必须报错
	for _, bad := range []map[string]string{
		{ParamServerAddr: "wss://x", ParamBackpressureLimit: "-1"},
		{ParamServerAddr: "wss://x", ParamReadTimeout: "abc"},
		{ParamServerAddr: "wss://x", ParamMaxSocks5Connections: "-5"},
		{ParamServerAddr: "wss://x", ParamUDPBlockedPorts: "443,abc"},
		{ParamServerAddr: "wss://x", ParamUDPBlockedPorts: "70000"},
	} {
		if _, err := buildConfig(bad); err == nil {
			t.Fatalf("buildConfig(%v) expected error", bad)
		}
	}
}
