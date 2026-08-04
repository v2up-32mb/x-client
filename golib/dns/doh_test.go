package dns

import (
	"net"
	"strings"
	"testing"
	"time"

	"gcm/config"
	"gcm/logger"
)

func initTestLogger() {
	dft := config.DefaultConfig()
	dft.LogLevel = config.DEBUG
	logger.InitGlobalLogger(dft)
}

// 验证内置列表顺序与内容
func TestDefaultDoHServers(t *testing.T) {
	want := []string{
		"https://v.recipes/dns-query",
		"https://doh.090227.xyz/CMLiussss",
		"https://doh.pub/dns-query",
	}
	if len(DefaultDoHServers) != 3 {
		t.Fatalf("expect 3 servers, got %d", len(DefaultDoHServers))
	}
	for i := range want {
		if DefaultDoHServers[i] != want[i] {
			t.Errorf("server[%d] = %q, want %q", i, DefaultDoHServers[i], want[i])
		}
	}
}

// 用户手动指定 → 只用用户的
func TestUserSpecifiedDoH(t *testing.T) {
	initTestLogger()
	dft := config.DefaultConfig()
	dft.DoHUrl = "https://my.custom.doh/dns-query"
	c := NewDoHClient(dft)
	if len(c.dohURLs) != 1 {
		t.Fatalf("expect 1 url, got %d: %v", len(c.dohURLs), c.dohURLs)
	}
	if c.dohURLs[0] != "https://my.custom.doh/dns-query" {
		t.Errorf("url = %q", c.dohURLs[0])
	}
}

// 用户不指定 → 内置 3 个
func TestBuiltinDoHList(t *testing.T) {
	initTestLogger()
	dft := config.DefaultConfig()
	dft.DoHUrl = ""
	c := NewDoHClient(dft)
	if len(c.dohURLs) != 3 {
		t.Fatalf("expect 3 urls, got %d: %v", len(c.dohURLs), c.dohURLs)
	}
	for i := range DefaultDoHServers {
		if c.dohURLs[i] != DefaultDoHServers[i] {
			t.Errorf("url[%d] = %q, want %q", i, c.dohURLs[i], DefaultDoHServers[i])
		}
	}
}

// 所有 DoH 失败 → Resolve 返回 "所有 DoH 服务器均失败"
func TestAllDoHFail(t *testing.T) {
	initTestLogger()
	dft := config.DefaultConfig()
	dft.DoHUrl = ""
	c := NewDoHClient(dft)
	// 用不可路由地址，连接会快速失败（无需等满超时）
	c.dohURLs = []string{
		"https://127.0.0.1:1/dns-query",
		"https://127.0.0.1:2/dns-query",
		"https://127.0.0.1:3/dns-query",
	}
	// 缩短超时加速（连接拒绝快速失败，超时作为上限）
	c.client.Timeout = 500 * time.Millisecond
	start := time.Now()
	_, err := c.Resolve("example.com", "A")
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("expect error when all DoH fail, got nil")
	}
	if !strings.Contains(err.Error(), "所有 DoH") {
		t.Errorf("error = %q, expect contains '所有 DoH'", err.Error())
	}
	// 全失败总耗时应小于单台超时的多倍
	if elapsed > 8*time.Second {
		t.Errorf("too slow: %v", elapsed)
	}
}

// 系统 DNS 解析冒烟
func TestSystemDNSFallback(t *testing.T) {
	ips, err := LookupIP("localhost")
	if err != nil {
		t.Fatal(err)
	}
	if len(ips) == 0 {
		t.Fatal("expect at least one IP for localhost")
	}
	if net.ParseIP(ips[0]) == nil {
		t.Errorf("not an IP: %s", ips[0])
	}
}
