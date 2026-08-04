package ech

import (
	"errors"
	"testing"

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
