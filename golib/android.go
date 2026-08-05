package xclient

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata" // 嵌入 IANA 时区数据库，保证 Android 系统缺少 zoneinfo 时 LoadLocation 可用

	"xclient/gcm"
	"xclient/shared/logger"
	"xclient/shared/routing"
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
// 值允许为字符串、数字或布尔标量（Android 侧 JSONObject.put 会输出
// 无引号的数字/布尔），统一转换为字符串供后端解析。
func parseParamsJSON(paramsJSON string) (map[string]string, error) {
	params := map[string]string{}
	paramsJSON = strings.TrimSpace(paramsJSON)
	if paramsJSON == "" {
		return params, nil
	}
	var raw map[string]interface{}
	if err := json.Unmarshal([]byte(paramsJSON), &raw); err != nil {
		return nil, fmt.Errorf("invalid params JSON: %w", err)
	}
	for key, value := range raw {
		switch v := value.(type) {
		case string:
			params[key] = v
		case float64:
			params[key] = strconv.FormatFloat(v, 'f', -1, 64)
		case bool:
			params[key] = strconv.FormatBool(v)
		case nil:
			params[key] = ""
		default:
			return nil, fmt.Errorf("invalid params JSON: unsupported value type %T for key %q", v, key)
		}
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

// SetTimeZone 将 Go 运行时的本地时区与 Android 系统时区对齐。
//
// gomobile 环境下 Go 的 time.Local 默认为 UTC（Android 不会把系统时区作为
// TZ 环境变量传入），导致运行日志时间戳显示 UTC 而非系统时区。Android 侧在
// 应用/服务启动以及 ACTION_TIMEZONE_CHANGED 时传入
// TimeZone.getDefault().getID()（如 "Asia/Shanghai" 或 "GMT+08:00"）。
func SetTimeZone(tz string) {
	tz = strings.TrimSpace(tz)
	if tz == "" {
		return
	}
	loc, err := time.LoadLocation(tz)
	if err != nil {
		loc = parseFixedZone(tz)
		if loc == nil {
			fmt.Printf("SetTimeZone: unknown timezone %q: %v\n", tz, err)
			return
		}
	}
	time.Local = loc
}

// parseFixedZone 解析 Android 可能返回的固定偏移时区 ID（"GMT+08:00"、
// "GMT-05:30"、"UTC+8" 等）。IANA 名称（如 "Asia/Shanghai"）由 LoadLocation 处理。
func parseFixedZone(tz string) *time.Location {
	rest := tz
	for _, prefix := range []string{"GMT", "UTC"} {
		if strings.HasPrefix(rest, prefix) {
			rest = strings.TrimPrefix(rest, prefix)
			break
		}
	}
	// "GMT" / "UTC" 本身
	if rest == "" {
		return time.UTC
	}
	sign := 1
	switch {
	case strings.HasPrefix(rest, "+"):
		rest = rest[1:]
	case strings.HasPrefix(rest, "-"):
		sign = -1
		rest = rest[1:]
	default:
		return nil
	}
	if rest == "" {
		return nil
	}
	parts := strings.SplitN(rest, ":", 2)
	hours, err := strconv.Atoi(parts[0])
	if err != nil || hours < 0 || hours > 23 {
		return nil
	}
	minutes := 0
	if len(parts) == 2 {
		minutes, err = strconv.Atoi(parts[1])
		if err != nil || minutes < 0 || minutes > 59 {
			return nil
		}
	}
	offset := (hours*60 + minutes) * 60 * sign
	return time.FixedZone(tz, offset)
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
