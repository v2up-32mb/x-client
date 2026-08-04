package xtunnel

import (
	"strings"

	"xclient/shared/config"
	"xclient/shared/dns"
	"xclient/shared/ech"
)

// newSharedEchManager 基于 x-tunnel 配置构建共享 ECH 管理器：
// DoH 优先（多服务器 fallback），失败回退 UDP DNS。
func newSharedEchManager(cfg *Config) *ech.EchManager {
	return ech.NewEchManager(newSharedDoHClient(cfg), cfg.ECHDomain, 0, 0)
}

// newSharedConfig 将 x-tunnel 配置映射为共享配置视图（logger/DoH 使用）。
func newSharedConfig(cfg *Config) *config.Config {
	shared := config.DefaultConfig()
	shared.EnableDoH = true
	if dnsServer := strings.TrimSpace(cfg.DNSServer); dnsServer != "" {
		shared.DoHUrl = dnsServer
	}
	return shared
}

// newSharedDoHClient 基于共享配置视图构建 DoH 客户端。
func newSharedDoHClient(cfg *Config) *dns.DoHClient {
	return dns.NewDoHClient(newSharedConfig(cfg))
}
