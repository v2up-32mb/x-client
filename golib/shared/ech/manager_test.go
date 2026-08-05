package ech

import (
	"crypto/tls"
	"errors"
	"sync"
	"testing"
	"time"

	"xclient/shared/config"
	"xclient/shared/dns"
)

func TestEchManagerFallsBackToUDPWhenDoHFails(t *testing.T) {
	cfg := config.DefaultConfig()
	cfg.EnableDoH = true
	cfg.DoHUrl = "http://127.0.0.1:1" // 连接立即失败
	m := NewEchManager(dns.NewDoHClient(cfg), "ech.example.com", 0, 0)
	m.udpFunc = func(domain string) ([]byte, error) {
		if domain != "ech.example.com" {
			t.Fatalf("udpFunc domain = %q", domain)
		}
		return []byte{0xAA, 0xBB}, nil
	}

	tlsCfg, err := m.GetTlsConfig("server.example.com", true)
	if err != nil {
		t.Fatalf("GetTlsConfig() error = %v", err)
	}
	if len(tlsCfg.EncryptedClientHelloConfigList) != 2 ||
		tlsCfg.EncryptedClientHelloConfigList[0] != 0xAA ||
		tlsCfg.EncryptedClientHelloConfigList[1] != 0xBB {
		t.Fatalf("ECH config list = %v, want [AA BB]", tlsCfg.EncryptedClientHelloConfigList)
	}
}

func TestEchManagerPrefersDoHOverUDP(t *testing.T) {
	cfg := config.DefaultConfig()
	cfg.EnableDoH = true
	m := NewEchManager(dns.NewDoHClient(cfg), "ech.example.com", 0, 0)
	m.dohFunc = func(domain string) ([]byte, error) {
		return []byte{0x01}, nil
	}
	m.udpFunc = func(domain string) ([]byte, error) {
		return nil, errors.New("UDP must not be called when DoH succeeds")
	}

	tlsCfg, err := m.GetTlsConfig("server.example.com", true)
	if err != nil {
		t.Fatalf("GetTlsConfig() error = %v", err)
	}
	if len(tlsCfg.EncryptedClientHelloConfigList) != 1 || tlsCfg.EncryptedClientHelloConfigList[0] != 0x01 {
		t.Fatalf("ECH config list = %v, want [01]", tlsCfg.EncryptedClientHelloConfigList)
	}
}

func TestEchManagerFallsBackToStandardTLS(t *testing.T) {
	cfg := config.DefaultConfig()
	cfg.EnableDoH = true
	cfg.DoHUrl = "http://127.0.0.1:1"
	m := NewEchManager(dns.NewDoHClient(cfg), "ech.example.com", 0, 0)
	m.udpFunc = func(domain string) ([]byte, error) {
		return nil, errors.New("udp unavailable")
	}

	tlsCfg, err := m.GetTlsConfig("server.example.com", true)
	if err != nil {
		t.Fatalf("GetTlsConfig() error = %v", err)
	}
	if len(tlsCfg.EncryptedClientHelloConfigList) != 0 {
		t.Fatalf("expected standard TLS fallback, got ECH list %v", tlsCfg.EncryptedClientHelloConfigList)
	}
	if tlsCfg.ServerName != "server.example.com" {
		t.Fatalf("ServerName = %q", tlsCfg.ServerName)
	}
}

func TestEchManagerSingleflightConcurrentFetch(t *testing.T) {
	cfg := config.DefaultConfig()
	cfg.EnableDoH = true
	m := NewEchManager(dns.NewDoHClient(cfg), "ech.example.com", 0, 0)

	var calls int32
	var mu sync.Mutex
	release := make(chan struct{})
	m.dohFunc = func(domain string) ([]byte, error) {
		mu.Lock()
		calls++
		mu.Unlock()
		<-release // 阻塞首个查询，制造并发窗口
		return []byte{0xAA, 0xBB}, nil
	}
	m.udpFunc = func(domain string) ([]byte, error) {
		t.Fatal("UDP fallback must not be called when DoH succeeds")
		return nil, nil
	}

	const workers = 8
	results := make(chan *tls.Config, workers)
	errs := make(chan error, workers)
	for i := 0; i < workers; i++ {
		go func() {
			tlsCfg, err := m.GetTlsConfig("server.example.com", true)
			results <- tlsCfg
			errs <- err
		}()
	}
	// 等所有请求都进入（首个查询在阻塞中）
	time.Sleep(100 * time.Millisecond)
	close(release)

	for i := 0; i < workers; i++ {
		if err := <-errs; err != nil {
			t.Fatalf("GetTlsConfig() error = %v", err)
		}
		tlsCfg := <-results
		if len(tlsCfg.EncryptedClientHelloConfigList) != 2 {
			t.Fatalf("ECH list = %v, want [AA BB]", tlsCfg.EncryptedClientHelloConfigList)
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if calls != 1 {
		t.Fatalf("dohFunc called %d times, want 1 (singleflight)", calls)
	}

	// 查询完成后缓存生效，再次获取不应触发查询
	if _, err := m.GetTlsConfig("server.example.com", true); err != nil {
		t.Fatalf("cached GetTlsConfig() error = %v", err)
	}
	if calls != 1 {
		t.Fatalf("dohFunc called %d times after cache fill, want 1", calls)
	}
}
