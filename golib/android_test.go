package xclient

import (
	"strings"
	"testing"
	"time"
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

func TestParseParamsJSONScalarTypes(t *testing.T) {
	// Android JSONObject.put 会输出无引号的数字/布尔值，必须能被接受
	params, err := parseParamsJSON(`{"connections":3,"enable_ech":true,"token":"t","nil_key":null}`)
	if err != nil {
		t.Fatalf("parseParamsJSON(scalars) error = %v", err)
	}
	if params["connections"] != "3" {
		t.Fatalf("connections = %q, want 3", params["connections"])
	}
	if params["enable_ech"] != "true" {
		t.Fatalf("enable_ech = %q, want true", params["enable_ech"])
	}
	if params["token"] != "t" {
		t.Fatalf("token = %q", params["token"])
	}
	if _, ok := params["nil_key"]; !ok {
		t.Fatal("nil_key missing")
	}

	// 数组/对象等复杂值必须报错
	if _, err := parseParamsJSON(`{"relay_nodes":["a","b"]}`); err == nil || !strings.Contains(err.Error(), "unsupported value type") {
		t.Fatalf("parseParamsJSON(array) error = %v, want unsupported type", err)
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

	backend, err := newBackend(ProtocolXTunnel)
	if err != nil {
		t.Fatalf("newBackend(xtunnel) error = %v", err)
	}
	if backend == nil {
		t.Fatal("newBackend(xtunnel) = nil")
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
	// xtunnel：缺 server_addr 时在启动网络前报错，证明分发到 xtunnel 后端
	if err := StartSocksProxy("127.0.0.1:1080", ProtocolXTunnel, `{"token":"t"}`, false); err == nil || !strings.Contains(err.Error(), "server address is required") {
		t.Fatalf("StartSocksProxy(xtunnel) error = %v", err)
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

func TestSetTimeZone(t *testing.T) {
	oldLocal := time.Local
	t.Cleanup(func() { time.Local = oldLocal })

	// IANA 命名时区
	SetTimeZone("Asia/Shanghai")
	if time.Local.String() != "Asia/Shanghai" {
		t.Fatalf("time.Local = %q, want Asia/Shanghai", time.Local.String())
	}
	if got := time.Now().Location().String(); got != "Asia/Shanghai" {
		t.Fatalf("time.Now().Location() = %q, want Asia/Shanghai", got)
	}

	// Android 固定偏移时区 ID
	SetTimeZone("GMT+08:00")
	if _, offset := time.Now().Zone(); offset != 8*3600 {
		t.Fatalf("GMT+08:00 offset = %d, want %d", offset, 8*3600)
	}
	SetTimeZone("GMT-05:30")
	if _, offset := time.Now().Zone(); offset != -(5*3600 + 30*60) {
		t.Fatalf("GMT-05:30 offset = %d", offset)
	}

	// 未知/空时区不应 panic，也不应清空当前设置
	SetTimeZone("Not/AZone")
	if time.Local.String() != "GMT-05:30" {
		t.Fatalf("unknown tz changed time.Local to %q", time.Local.String())
	}
	SetTimeZone("")
	SetTimeZone("  ")
}
