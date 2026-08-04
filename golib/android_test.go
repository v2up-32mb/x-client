package xclient

import "testing"

func TestBuildConfigMatchesMainParameters(t *testing.T) {
	cfg, err := buildConfig(
		"127.0.0.1:1080",
		"gcm.ics.de5.net",
		3,
		"saas.sin.fan",
		"v2up",
		"128.199.255.242",
		"cloudflare-ech.com",
		"https://doh.pub/dns-query",
		true,
		false,
		true,
		true,
		false,
		16,
	)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}

	if cfg.ListenAddress != "127.0.0.1:1080" {
		t.Fatalf("ListenAddress = %q", cfg.ListenAddress)
	}
	if cfg.WorkerHost != "gcm.ics.de5.net" {
		t.Fatalf("WorkerHost = %q", cfg.WorkerHost)
	}
	if len(cfg.RelayIPs) != 1 || cfg.RelayIPs[0] != "saas.sin.fan" {
		t.Fatalf("RelayIPs = %#v", cfg.RelayIPs)
	}
	if cfg.UserID != "v2up" || cfg.ProxyIP != "128.199.255.242" {
		t.Fatalf("UserID = %q, ProxyIP = %q", cfg.UserID, cfg.ProxyIP)
	}
	if !cfg.EnableECH || cfg.ECHDomain != "cloudflare-ech.com" {
		t.Fatalf("EnableECH = %v, ECHDomain = %q", cfg.EnableECH, cfg.ECHDomain)
	}
	if !cfg.EnableDoH || cfg.DoHUrl != "https://doh.pub/dns-query" {
		t.Fatalf("EnableDoH = %v, DoHUrl = %q", cfg.EnableDoH, cfg.DoHUrl)
	}
	if !cfg.EnableDNSWarmup {
		t.Fatal("EnableDNSWarmup = false")
	}
	if cfg.MinPoolSize != 3 || cfg.MaxPoolSize != 3 || cfg.EnableDynamicPool {
		t.Fatalf("pool size = %d..%d", cfg.MinPoolSize, cfg.MaxPoolSize)
	}
}

func TestBuildConfigPreservesMainDoHFallback(t *testing.T) {
	cfg, err := buildConfig("127.0.0.1:1080", "wss://worker.example/", 0, "", "", "", "", "", false, false, false, false, false, 0)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.WorkerHost != "worker.example" {
		t.Fatalf("WorkerHost = %q", cfg.WorkerHost)
	}
	if cfg.DoHUrl != "" {
		t.Fatalf("DoHUrl = %q, want main fallback sentinel", cfg.DoHUrl)
	}
}

func TestBuildConfigDynamicPoolSettings(t *testing.T) {
	cfg, err := buildConfig("127.0.0.1:1080", "worker.example", 3, "", "", "", "", "", false, false, false, false, true, 16)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if !cfg.EnableDynamicPool || cfg.MinPoolSize != 3 || cfg.MaxPoolSize != 16 {
		t.Fatalf("dynamic pool config = enabled:%v size:%d..%d", cfg.EnableDynamicPool, cfg.MinPoolSize, cfg.MaxPoolSize)
	}

	cfg, err = buildConfig("127.0.0.1:1080", "worker.example", 3, "", "", "", "", "", false, false, false, false, true, 1000)
	if err != nil {
		t.Fatalf("buildConfig() with oversized limit error = %v", err)
	}
	if cfg.MaxPoolSize != maxAndroidDynamicPoolLimit {
		t.Fatalf("oversized dynamic pool max = %d, want %d", cfg.MaxPoolSize, maxAndroidDynamicPoolLimit)
	}

	cfg, err = buildConfig("127.0.0.1:1080", "worker.example", 3, "", "", "", "", "", false, false, false, false, true, 1)
	if err != nil {
		t.Fatalf("buildConfig() with undersized limit error = %v", err)
	}
	if cfg.MaxPoolSize != cfg.MinPoolSize {
		t.Fatalf("dynamic pool max %d is below initial min %d", cfg.MaxPoolSize, cfg.MinPoolSize)
	}
}

func TestValidateBypassRules(t *testing.T) {
	if err := ValidateBypassRules("192.168.0.0/16\ndomain:example.cn\nfull:api.example.com"); err != nil {
		t.Fatalf("ValidateBypassRules() error = %v", err)
	}
	if err := ValidateBypassRules("not a valid rule!"); err == nil {
		t.Fatal("ValidateBypassRules() error = nil")
	}
}
