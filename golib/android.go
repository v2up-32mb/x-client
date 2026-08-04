package xclient

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"xclient/gcm"
	"xclient/logger"
	"xclient/routing"
	"xclient/xtunnel"
)

// ProxyBackend 代理后端接口，每种协议实现此接口。
type ProxyBackend interface {
	// Start 启动代理后端。
	// listenAddr: SOCKS5 本地监听地址
	// params: 协议特定参数（key-value 对）
	Start(listenAddr string, params map[string]string, verbose bool) error

	// Stop 停止代理后端。
	Stop() error

	// Reconnect 触发重连。
	Reconnect(reason string)

	// NotifyNetworkChanged 通知网络变更。
	NotifyNetworkChanged()
}

// 协议标识（与 Android 侧 Preferences.Protocol 保持一致）。
const (
	ProtocolGCM     = "gcm"
	ProtocolXTunnel = "xtunnel"
)

var (
	lifecycleMu   sync.Mutex
	activeBackend ProxyBackend
)

// StartSocksProxy 启动指定协议的代理（gomobile AAR 入口）。
// protocol 为空时向后兼容，默认使用 GCM 协议；paramsJSON 为协议参数的
// JSON 对象（{"key": "value", ...}），verbose 控制调试日志级别。
func StartSocksProxy(listenAddr, protocol string, paramsJSON string, verbose bool) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if activeBackend != nil {
		return fmt.Errorf("proxy is already running")
	}

	params, err := parseParamsJSON(paramsJSON)
	if err != nil {
		return err
	}
	backend, err := newBackend(protocol)
	if err != nil {
		return err
	}
	if err := backend.Start(listenAddr, params, verbose); err != nil {
		return err
	}
	activeBackend = backend
	return nil
}

// parseParamsJSON 解析协议参数 JSON 对象。空字符串视为空参数表。
func parseParamsJSON(paramsJSON string) (map[string]string, error) {
	params := map[string]string{}
	paramsJSON = strings.TrimSpace(paramsJSON)
	if paramsJSON == "" {
		return params, nil
	}
	if err := json.Unmarshal([]byte(paramsJSON), &params); err != nil {
		return nil, fmt.Errorf("invalid params JSON: %w", err)
	}
	return params, nil
}

// newBackend 按协议标识返回对应后端实例。protocol 为空时默认 GCM。
func newBackend(protocol string) (ProxyBackend, error) {
	switch strings.ToLower(strings.TrimSpace(protocol)) {
	case "", ProtocolGCM:
		return gcm.NewBackend(), nil
	case ProtocolXTunnel:
		return xtunnel.NewBackend(), nil
	default:
		return nil, fmt.Errorf("unsupported protocol %q", protocol)
	}
}

// ValidateBypassRules validates newline-separated manual routing rules without
// starting the proxy. It is exported for the Android settings screen.
func ValidateBypassRules(rules string) error {
	return routing.ValidateManualRules(rules)
}

// StopSocksProxy 停止当前代理并逆序释放所有资源。
func StopSocksProxy() {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if activeBackend == nil {
		return
	}
	_ = activeBackend.Stop()
	activeBackend = nil
}

// NotifyNetworkChanged asks the running backend to replace sockets bound to
// the previous physical network. The Android VPN interface is left untouched.
func NotifyNetworkChanged() {
	Reconnect("Android default network changed")
}

// Reconnect replaces all current WebSockets while preserving the VPN/TUN.
func Reconnect(reason string) {
	if reason = strings.TrimSpace(reason); reason == "" {
		reason = "Android reconnect requested"
	}
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if activeBackend != nil {
		activeBackend.Reconnect(reason)
	}
}

// AppendRuntimeLog records an Android lifecycle event in the VPN log buffer.
func AppendRuntimeLog(scope, message string) {
	logger.AppendRuntimeLog(scope, message)
}

// GetRuntimeLogs returns logs accumulated since the current VPN start.
func GetRuntimeLogs() string {
	return logger.GetRuntimeLogs()
}
