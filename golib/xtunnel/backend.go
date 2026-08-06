package xtunnel

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"xclient/shared/config"
	"xclient/shared/logger"
	"xclient/shared/routing"
)

// Param keys accepted by Backend.Start（与 Android 侧 X_TUNNEL Profile 字段对齐）。
const (
	maxHotPairCount = 8

	ParamServerAddr    = "server_addr"
	ParamToken         = "token"
	ParamConnections   = "connections"
	ParamClientID      = "client_id"
	ParamRelayNodes    = "relay_nodes"
	ParamEnableECH     = "enable_ech"
	ParamECHDomain     = "ech_domain"
	ParamDNSServer     = "dns_server"
	ParamInsecure      = "insecure"
	ParamEnableHotPair = "enable_hot_pair"
	ParamHotPairCount  = "hot_pair_count"
	ParamLogLevel      = "log_level"

	// 路由绕过（键与 GCM 后端一致，Android 全局设置共用）
	ParamBypassPrivate   = "bypass_private"
	ParamBypassGeoIPCN   = "bypass_geoip_cn"
	ParamBypassGeoSiteCN = "bypass_geosite_cn"
	ParamBypassRules     = "bypass_rules"

	// 高级参数（毫秒/字节整数，0 或缺省使用默认值；不进入分享链接）
	ParamBackpressureLimit     = "backpressure_limit"
	ParamWriteQueueWaitTimeout = "write_queue_wait_timeout"
	ParamDialTimeout           = "dial_timeout"
	ParamHandshakeTimeout      = "handshake_timeout"
	ParamReadTimeout           = "read_timeout"
	ParamWriteTimeout          = "write_timeout"
	ParamPingInterval          = "ping_interval"
	ParamReconnectDelay        = "reconnect_delay"
	ParamConnectTimeout        = "connect_timeout"
	ParamMaxSocks5Connections  = "max_socks5_connections"
	ParamUDPBlockedPorts       = "udp_blocked_ports"
)

// Backend 运行 x-tunnel 协议栈：多通道 WebSocket 隧道 + 通道竞争/Hot Pair/
// UDP associate/HTTP 代理。它满足 xclient.ProxyBackend 接口（结构化实现）。
type Backend struct {
	mu     sync.Mutex
	client *Client
}

// NewBackend 返回一个空闲的 x-tunnel 后端。
func NewBackend() *Backend {
	return &Backend{}
}

// Start 启动 x-tunnel 后端：建立 WebSocket 通道并监听本地 SOCKS5。
// params 的键见 Param* 常量。
func (b *Backend) Start(listenAddr string, params map[string]string, verbose bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client != nil {
		return fmt.Errorf("X-Tunnel proxy is already running")
	}
	if strings.TrimSpace(listenAddr) == "" {
		return fmt.Errorf("SOCKS5 listen address is required")
	}

	cfg, err := buildConfig(params)
	if err != nil {
		return err
	}
	bypassMatcher, err := routing.NewMatcher(cfg.BypassPrivate, cfg.BypassGeoIPCN, cfg.BypassGeoSiteCN, cfg.BypassRules)
	if err != nil {
		return fmt.Errorf("invalid bypass rules: %w", err)
	}

	logger.ClearRuntimeLogs()
	sharedCfg := newSharedConfig(cfg)
	sharedCfg.LogLevel = logLevelFromParams(params, verbose)
	logger.InitGlobalLogger(sharedCfg)
	systemLog := logger.GetLogger("System")
	systemLog.Info("启动 X-Tunnel: Server=%s, 连接数=%d, ECH=%v, RelayNodes=%d, HotPair=%v",
		cfg.ServerAddr, cfg.Connections, cfg.EnableECH, len(cfg.RelayNodes), cfg.EnableHotPair)

	c, err := NewClient(cfg)
	if err != nil {
		logger.Close()
		return err
	}
	c.SetBypassMatcher(bypassMatcher)
	if err := c.Start(); err != nil {
		logger.Close()
		return err
	}
	if err := c.ListenSOCKS5(listenAddr); err != nil {
		_ = c.Shutdown()
		logger.Close()
		return fmt.Errorf("start SOCKS5 server: %w", err)
	}
	b.client = c
	systemLog.Info("X-Tunnel 已就绪")
	return nil
}

// Stop 停止后端并释放所有资源（含 SOCKS5 监听端口）。
func (b *Backend) Stop() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client == nil {
		return nil
	}
	err := b.client.Shutdown()
	logger.Close()
	b.client = nil
	return err
}

// Reconnect 触发重连：强制关闭现有 WebSocket 通道，
// 连接池会立即在新网络上重建（Android 网络切换时不再等待 TCP 死链检测）。
func (b *Backend) Reconnect(reason string) {
	if reason = strings.TrimSpace(reason); reason == "" {
		reason = "Android reconnect requested"
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.client != nil {
		b.client.Reconnect(reason)
	} else {
		sysLog.Info("[客户端] 收到重连请求: %s（后端未运行）", reason)
	}
}

// NotifyNetworkChanged 通知网络变更。
func (b *Backend) NotifyNetworkChanged() {
	b.Reconnect("Android default network changed")
}

func buildConfig(params map[string]string) (*Config, error) {
	c := DefaultConfig()
	c.ServerAddr = strings.TrimSpace(stringParam(params, ParamServerAddr, ""))
	if c.ServerAddr == "" {
		return nil, fmt.Errorf("server address is required")
	}
	if !strings.HasPrefix(c.ServerAddr, "wss://") && !strings.HasPrefix(c.ServerAddr, "ws://") {
		return nil, fmt.Errorf("server address must start with wss:// or ws://")
	}
	// 防御：URL 缺少主机时（如 "wss://"）在启动前给出明确错误，
	// 而不是等到 TLS 握手才报 "either ServerName or InsecureSkipVerify must be specified"。
	if u, err := url.Parse(c.ServerAddr); err != nil || strings.TrimSpace(u.Hostname()) == "" {
		return nil, fmt.Errorf("server address must include a host (e.g. wss://host:443)")
	}
	c.Token = stringParam(params, ParamToken, "")

	if v, err := intParam(params, ParamConnections, 0); err != nil {
		return nil, err
	} else if v > 0 {
		c.Connections = v
	}
	c.ClientID = stringParam(params, ParamClientID, "")

	if v := stringParam(params, ParamRelayNodes, ""); v != "" {
		for _, item := range strings.Split(v, ",") {
			if item = strings.TrimSpace(item); item != "" {
				c.RelayNodes = append(c.RelayNodes, item)
			}
		}
	}

	if v, err := boolParam(params, ParamEnableECH, c.EnableECH); err != nil {
		return nil, err
	} else {
		c.EnableECH = v
	}
	if v := stringParam(params, ParamECHDomain, ""); v != "" {
		c.ECHDomain = v
	}
	if v := stringParam(params, ParamDNSServer, ""); v != "" {
		c.DNSServer = v
	}
	if v, err := boolParam(params, ParamInsecure, false); err != nil {
		return nil, err
	} else {
		c.InsecureSkipVerify = v
	}
	if v, err := boolParam(params, ParamEnableHotPair, false); err != nil {
		return nil, err
	} else {
		c.EnableHotPair = v
	}
	if v, err := intParam(params, ParamHotPairCount, 0); err != nil {
		return nil, err
	} else if v > 0 {
		if v > maxHotPairCount {
			return nil, fmt.Errorf("param %q: hot pair count %d exceeds max %d", ParamHotPairCount, v, maxHotPairCount)
		}
		c.HotPairCount = v
	}

	// 路由绕过：先校验布尔类型，Matcher 在 Start 中构建（错误路径统一报 invalid bypass rules）
	for _, key := range []string{ParamBypassPrivate, ParamBypassGeoIPCN, ParamBypassGeoSiteCN} {
		if _, err := boolParam(params, key, false); err != nil {
			return nil, err
		}
	}
	c.BypassPrivate, _ = boolParam(params, ParamBypassPrivate, false)
	c.BypassGeoIPCN, _ = boolParam(params, ParamBypassGeoIPCN, false)
	c.BypassGeoSiteCN, _ = boolParam(params, ParamBypassGeoSiteCN, false)
	c.BypassRules = stringParam(params, ParamBypassRules, "")

	// 高级参数解析（字节/毫秒整数；负值非法，0 或缺省使用默认值）
	if v, err := intParam(params, ParamBackpressureLimit, 0); err != nil {
		return nil, err
	} else if v < 0 {
		return nil, fmt.Errorf("param %q: must not be negative", ParamBackpressureLimit)
	} else if v > 0 {
		c.BackpressureLimitBytes = v
	}
	if v, err := intParam(params, ParamWriteQueueWaitTimeout, 0); err != nil {
		return nil, err
	} else if v < 0 {
		return nil, fmt.Errorf("param %q: must not be negative", ParamWriteQueueWaitTimeout)
	} else if v > 0 {
		c.WriteQueueWaitTimeout = time.Duration(v) * time.Millisecond
	}
	for _, item := range []struct {
		key string
		dst *time.Duration
	}{
		{ParamDialTimeout, &c.DialTimeout},
		{ParamHandshakeTimeout, &c.HandshakeTimeout},
		{ParamReadTimeout, &c.ReadTimeout},
		{ParamWriteTimeout, &c.WriteTimeout},
		{ParamPingInterval, &c.PingInterval},
		{ParamReconnectDelay, &c.ReconnectDelay},
		{ParamConnectTimeout, &c.ConnectTimeout},
	} {
		if v, err := intParam(params, item.key, 0); err != nil {
			return nil, err
		} else if v < 0 {
			return nil, fmt.Errorf("param %q: must not be negative", item.key)
		} else if v > 0 {
			*item.dst = time.Duration(v) * time.Millisecond
		}
	}
	// max_socks5_connections 单独处理：0 有"无限制"语义，因此仅在显式提供时覆盖默认值
	if v, ok := params[ParamMaxSocks5Connections]; ok && strings.TrimSpace(v) != "" {
		n, err := strconv.Atoi(strings.TrimSpace(v))
		if err != nil {
			return nil, fmt.Errorf("param %q: invalid integer %q", ParamMaxSocks5Connections, v)
		}
		if n < 0 {
			return nil, fmt.Errorf("param %q: must not be negative", ParamMaxSocks5Connections)
		}
		c.MaxSOCKS5Connections = n
	}
	if v := stringParam(params, ParamUDPBlockedPorts, ""); v != "" {
		var ports []int
		for _, item := range strings.Split(v, ",") {
			item = strings.TrimSpace(item)
			if item == "" {
				continue
			}
			port, err := strconv.Atoi(item)
			if err != nil || port < 1 || port > 65535 {
				return nil, fmt.Errorf("param %q: invalid port %q", ParamUDPBlockedPorts, item)
			}
			ports = append(ports, port)
		}
		c.UDPBlockedPorts = ports
	}
	return c, nil
}

// logLevelFromParams 解析代理日志等级：显式 log_level 参数优先，
// verbose 布尔开关保留向后兼容（缺省时 verbose=true → DEBUG，否则 INFO）。
func logLevelFromParams(params map[string]string, verbose bool) config.LogLevel {
	if v := strings.TrimSpace(stringParam(params, ParamLogLevel, "")); v != "" {
		return config.ParseLogLevel(v)
	}
	if verbose {
		return config.DEBUG
	}
	return config.INFO
}

func stringParam(params map[string]string, key, def string) string {
	if v, ok := params[key]; ok {
		return v
	}
	return def
}

func boolParam(params map[string]string, key string, def bool) (bool, error) {
	v, ok := params[key]
	if !ok || strings.TrimSpace(v) == "" {
		return def, nil
	}
	b, err := strconv.ParseBool(strings.TrimSpace(v))
	if err != nil {
		return false, fmt.Errorf("param %q: invalid boolean %q", key, v)
	}
	return b, nil
}

func intParam(params map[string]string, key string, def int) (int, error) {
	v, ok := params[key]
	if !ok || strings.TrimSpace(v) == "" {
		return def, nil
	}
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return 0, fmt.Errorf("param %q: invalid integer %q", key, v)
	}
	return n, nil
}
