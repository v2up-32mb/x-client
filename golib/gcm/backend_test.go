package gcm

import (
	"strings"
	"testing"

	"xclient/shared/config"
)

func TestBuildConfigFullParams(t *testing.T) {
	params := map[string]string{
		ParamWorkerHost:        "wss://gcm.ics.de5.net/",
		ParamWSConn:            "3",
		ParamRelayIPs:          "saas.sin.fan, relay2.example.com",
		ParamUserID:            "v2up",
		ParamProxyIP:           "128.199.255.242",
		ParamECHDomain:         "cloudflare-ech.com",
		ParamECHDNS:            "https://doh.pub/dns-query",
		ParamEnableECH:         "true",
		ParamDisableIPv6Route:  "false",
		ParamEnableDNSWarmup:   "true",
		ParamBypassPrivate:     "true",
		ParamBypassGeoIPCN:     "true",
		ParamBypassGeoSiteCN:   "true",
		ParamBypassRules:       "192.168.0.0/16\ndomain:example.cn",
		ParamEnableDynamicPool: "true",
		ParamDynamicPoolMax:    "16",
	}

	cfg, err := buildConfig("127.0.0.1:1080", params, true)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.ListenAddress != "127.0.0.1:1080" {
		t.Fatalf("ListenAddress = %q", cfg.ListenAddress)
	}
	if cfg.WorkerHost != "gcm.ics.de5.net" {
		t.Fatalf("WorkerHost = %q", cfg.WorkerHost)
	}
	if len(cfg.RelayIPs) != 2 || cfg.RelayIPs[0] != "saas.sin.fan" || cfg.RelayIPs[1] != "relay2.example.com" {
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
	if cfg.LogLevel.String() != "DEBUG" {
		t.Fatalf("verbose LogLevel = %v", cfg.LogLevel)
	}
	if !cfg.EnableDynamicPool || cfg.MinPoolSize != 3 || cfg.MaxPoolSize != 16 {
		t.Fatalf("dynamic pool config = enabled:%v size:%d..%d", cfg.EnableDynamicPool, cfg.MinPoolSize, cfg.MaxPoolSize)
	}
}

func TestBuildConfigDefaults(t *testing.T) {
	cfg, err := buildConfig("127.0.0.1:1080", map[string]string{ParamWorkerHost: "worker.example"}, false)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.WorkerHost != "worker.example" {
		t.Fatalf("WorkerHost = %q", cfg.WorkerHost)
	}
	if cfg.DoHUrl != "" {
		t.Fatalf("DoHUrl = %q, want main fallback sentinel", cfg.DoHUrl)
	}
	if cfg.EnableECH {
		t.Fatal("EnableECH = true, want false default")
	}
	if cfg.EnableDynamicPool {
		t.Fatal("EnableDynamicPool = true, want false default")
	}
	if cfg.MinPoolSize != defaultPoolSize || cfg.MaxPoolSize != defaultPoolSize {
		t.Fatalf("pool size = %d..%d, want %d", cfg.MinPoolSize, cfg.MaxPoolSize, defaultPoolSize)
	}
}

func TestBuildConfigRequiredFields(t *testing.T) {
	if _, err := buildConfig("", map[string]string{ParamWorkerHost: "worker.example"}, false); err == nil || !strings.Contains(err.Error(), "listen address") {
		t.Fatalf("empty listen address error = %v", err)
	}
	if _, err := buildConfig("127.0.0.1:1080", nil, false); err == nil || !strings.Contains(err.Error(), "Worker address") {
		t.Fatalf("empty worker error = %v", err)
	}
}

func TestBuildConfigInvalidParamValues(t *testing.T) {
	cases := []struct {
		name   string
		params map[string]string
		want   string
	}{
		{"bad bool", map[string]string{ParamWorkerHost: "w", ParamEnableECH: "notabool"}, `"enable_ech"`},
		{"bad int", map[string]string{ParamWorkerHost: "w", ParamWSConn: "abc"}, `"ws_conn"`},
		{"bad dynamic max", map[string]string{ParamWorkerHost: "w", ParamDynamicPoolMax: "x"}, `"dynamic_pool_max"`},
		{"bad bypass bool", map[string]string{ParamWorkerHost: "w", ParamBypassPrivate: "yes"}, `"bypass_private"`},
		{"bad ipv6 flag", map[string]string{ParamWorkerHost: "w", ParamDisableIPv6Route: "maybe"}, `"disable_ipv6_route"`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := buildConfig("127.0.0.1:1080", tc.params, false)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("buildConfig() error = %v, want containing %q", err, tc.want)
			}
		})
	}
}

func TestNormalizePoolSettings(t *testing.T) {
	// 静态池：忽略动态上限
	min, max := normalizePoolSettings(3, false, 16)
	if min != 3 || max != 3 {
		t.Fatalf("static pool = %d..%d", min, max)
	}
	// 动态池默认上限
	min, max = normalizePoolSettings(3, true, 0)
	if min != 3 || max != defaultDynamicPoolMax {
		t.Fatalf("dynamic default = %d..%d", min, max)
	}
	// 超上限截断
	min, max = normalizePoolSettings(3, true, 1000)
	if max != maxDynamicPoolLimit {
		t.Fatalf("oversized limit = %d, want %d", max, maxDynamicPoolLimit)
	}
	// 上限低于初始值时提升
	min, max = normalizePoolSettings(3, true, 1)
	if min != 3 || max != 3 {
		t.Fatalf("undersized limit = %d..%d", min, max)
	}
	// 非正初始值回落默认
	min, max = normalizePoolSettings(0, false, 16)
	if min != defaultPoolSize {
		t.Fatalf("zero initial = %d, want %d", min, defaultPoolSize)
	}
}

func TestNormalizeWorkerHost(t *testing.T) {
	cases := []struct{ in, want string }{
		{"worker.example", "worker.example"},
		{"wss://worker.example/", "worker.example"},
		{"https://worker.example", "worker.example"},
		{"  wss://w.example/path/  ", "w.example/path"},
	}
	for _, tc := range cases {
		if got := normalizeWorkerHost(tc.in); got != tc.want {
			t.Fatalf("normalizeWorkerHost(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestBuildConfigLogLevelPrecedence(t *testing.T) {
	base := map[string]string{ParamWorkerHost: "worker.example"}

	// 显式 log_level 优先于 verbose
	params := map[string]string{ParamWorkerHost: "worker.example", ParamLogLevel: "warn"}
	cfg, err := buildConfig("127.0.0.1:1080", params, true)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.LogLevel != config.WARN {
		t.Fatalf("explicit warn LogLevel = %v, want WARN", cfg.LogLevel)
	}

	// 大写/小写均接受（ParseLogLevel 语义）
	params[ParamLogLevel] = "debug"
	cfg, err = buildConfig("127.0.0.1:1080", params, false)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.LogLevel != config.DEBUG {
		t.Fatalf("explicit debug LogLevel = %v, want DEBUG", cfg.LogLevel)
	}

	// 无显式参数：verbose 兼容
	cfg, err = buildConfig("127.0.0.1:1080", base, true)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.LogLevel != config.DEBUG {
		t.Fatalf("verbose LogLevel = %v, want DEBUG", cfg.LogLevel)
	}
	cfg, err = buildConfig("127.0.0.1:1080", base, false)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.LogLevel != config.INFO {
		t.Fatalf("default LogLevel = %v, want INFO", cfg.LogLevel)
	}

	// 未知级别按 ParseLogLevel 语义回退 INFO
	params[ParamLogLevel] = "verbose"
	cfg, err = buildConfig("127.0.0.1:1080", params, true)
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.LogLevel != config.INFO {
		t.Fatalf("unknown level LogLevel = %v, want INFO fallback", cfg.LogLevel)
	}
}
