package xclient

import (
	"strings"
	"testing"
)

func TestParseParamsJSON(t *testing.T) {
	params, err := parseParamsJSON(`{"worker_host":"w.example","ws_conn":"3","enable_ech":"true"}`)
	if err != nil {
		t.Fatalf("parseParamsJSON() error = %v", err)
	}
	if params["worker_host"] != "w.example" || params["ws_conn"] != "3" || params["enable_ech"] != "true" {
		t.Fatalf("params = %#v", params)
	}

	params, err = parseParamsJSON("")
	if err != nil {
		t.Fatalf("parseParamsJSON(empty) error = %v", err)
	}
	if len(params) != 0 {
		t.Fatalf("empty params = %#v", params)
	}

	if _, err := parseParamsJSON(`{not json`); err == nil || !strings.Contains(err.Error(), "invalid params JSON") {
		t.Fatalf("parseParamsJSON(bad) error = %v", err)
	}
}

func TestNewBackendDispatch(t *testing.T) {
	for _, protocol := range []string{"", "gcm", "GCM", " gcm "} {
		backend, err := newBackend(protocol)
		if err != nil {
			t.Fatalf("newBackend(%q) error = %v", protocol, err)
		}
		if backend == nil {
			t.Fatalf("newBackend(%q) = nil", protocol)
		}
	}

	if _, err := newBackend(ProtocolXTunnel); err == nil || !strings.Contains(err.Error(), "not implemented") {
		t.Fatalf("newBackend(xtunnel) error = %v, want not-implemented", err)
	}
	if _, err := newBackend("bogus"); err == nil || !strings.Contains(err.Error(), `unsupported protocol "bogus"`) {
		t.Fatalf("newBackend(bogus) error = %v", err)
	}
}

func TestStartSocksProxyDispatch(t *testing.T) {
	t.Cleanup(StopSocksProxy)

	// 未知协议：不启动任何后端
	if err := StartSocksProxy("127.0.0.1:1080", "bogus", "", false); err == nil || !strings.Contains(err.Error(), "unsupported protocol") {
		t.Fatalf("StartSocksProxy(bogus) error = %v", err)
	}
	// 非法 params JSON
	if err := StartSocksProxy("127.0.0.1:1080", "gcm", `{bad`, false); err == nil || !strings.Contains(err.Error(), "invalid params JSON") {
		t.Fatalf("StartSocksProxy(bad json) error = %v", err)
	}
	// 空协议默认 GCM：缺 worker 时应在启动网络前报错（证明分发到 GCM 后端）
	if err := StartSocksProxy("127.0.0.1:1080", "", `{}`, false); err == nil || !strings.Contains(err.Error(), "Worker address is required") {
		t.Fatalf("StartSocksProxy(default gcm) error = %v", err)
	}
	// 显式 GCM：非法 bypass 规则在启动网络前报错，证明分发到 GCM 后端
	badRules := `{"worker_host":"w.example","bypass_rules":"not a valid rule!"}`
	if err := StartSocksProxy("127.0.0.1:1080", "gcm", badRules, false); err == nil || !strings.Contains(err.Error(), "invalid bypass rules") {
		t.Fatalf("StartSocksProxy(gcm) error = %v", err)
	}
	// 失败的启动不得留下 active backend，重试不受影响
	if err := StartSocksProxy("127.0.0.1:1080", "gcm", badRules, false); err == nil {
		t.Fatal("StartSocksProxy retry after failure error = nil")
	}
}

func TestStopSocksProxyIdempotent(t *testing.T) {
	StopSocksProxy()
	StopSocksProxy() // 不应 panic
	if activeBackend != nil {
		t.Fatalf("activeBackend = %#v after StopSocksProxy", activeBackend)
	}
}

func TestReconnectWithoutBackend(t *testing.T) {
	Reconnect("test") // 不应 panic
	NotifyNetworkChanged()
	Reconnect("")
}

func TestValidateBypassRules(t *testing.T) {
	if err := ValidateBypassRules("192.168.0.0/16\ndomain:example.cn\nfull:api.example.com"); err != nil {
		t.Fatalf("ValidateBypassRules() error = %v", err)
	}
	if err := ValidateBypassRules("not a valid rule!"); err == nil {
		t.Fatal("ValidateBypassRules() error = nil")
	}
}
