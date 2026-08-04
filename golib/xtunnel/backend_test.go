package xtunnel

import (
	"net"
	"strings"
	"testing"
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
