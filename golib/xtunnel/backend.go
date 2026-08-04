package xtunnel

import (
	"fmt"
	"strconv"
	"strings"
	"sync"

	"xclient/config"
	"xclient/logger"
)

// Param keys accepted by Backend.Start（与 Android 侧 X_TUNNEL Profile 字段对齐）。
const (
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

	logger.ClearRuntimeLogs()
	sharedCfg := newSharedConfig(cfg)
	if verbose {
		sharedCfg.LogLevel = config.DEBUG
	} else {
		sharedCfg.LogLevel = config.INFO
	}
	logger.InitGlobalLogger(sharedCfg)
	systemLog := logger.GetLogger("System")
	systemLog.Info("启动 X-Tunnel: Server=%s, 连接数=%d, ECH=%v, RelayNodes=%d, HotPair=%v",
		cfg.ServerAddr, cfg.Connections, cfg.EnableECH, len(cfg.RelayNodes), cfg.EnableHotPair)

	c, err := NewClient(cfg)
	if err != nil {
		logger.Close()
		return err
	}
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

// Reconnect 触发重连。x-tunnel 连接池自带持续重连循环，
// 此处记录请求即可；通道断开后会自动重连。
func (b *Backend) Reconnect(reason string) {
	if reason = strings.TrimSpace(reason); reason == "" {
		reason = "Android reconnect requested"
	}
	sysLog.Info("[客户端] 收到重连请求: %s（通道断开后自动重连）", reason)
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
	return c, nil
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
