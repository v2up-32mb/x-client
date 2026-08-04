// Package gcm implements the GCM proxy backend: binary stream multiplexing
// over WebSocket (2-byte header), relay scoring/load balancing, ECH, DoH and
// SOCKS5 with bypass routing.
package gcm

import (
	"fmt"
	"log"
	"strconv"
	"strings"
	"sync"

	"xclient/gcm/pool"
	"xclient/gcm/relay"
	"xclient/shared/config"
	"xclient/shared/dns"
	"xclient/shared/ech"
	"xclient/shared/logger"
	"xclient/shared/routing"
	"xclient/shared/socks5"
)

const (
	defaultPoolSize       = 3
	defaultDynamicPoolMax = 16
	maxDynamicPoolLimit   = 64
)

// Param keys accepted by Backend.Start. The set matches the key-value pairs
// assembled by the Android layer (TProxyService).
const (
	ParamWorkerHost        = "worker_host"
	ParamWSConn            = "ws_conn"
	ParamRelayIPs          = "relay_ips"
	ParamUserID            = "user_id"
	ParamProxyIP           = "proxy_ip"
	ParamECHDomain         = "ech_domain"
	ParamECHDNS            = "ech_dns"
	ParamEnableECH         = "enable_ech"
	ParamDisableIPv6Route  = "disable_ipv6_route"
	ParamEnableDNSWarmup   = "enable_dns_warmup"
	ParamBypassPrivate     = "bypass_private"
	ParamBypassGeoIPCN     = "bypass_geoip_cn"
	ParamBypassGeoSiteCN   = "bypass_geosite_cn"
	ParamBypassRules       = "bypass_rules"
	ParamEnableDynamicPool = "enable_dynamic_pool"
	ParamDynamicPoolMax    = "dynamic_pool_max"
)

// Backend runs the GCM protocol stack. It implements the xclient.ProxyBackend
// interface (satisfied structurally, so gcm does not import package xclient).
type Backend struct {
	mu sync.Mutex

	cfg          *config.Config
	relayManager *relay.RelayManager
	dnsCache     *dns.DNSCache
	dohClient    *dns.DoHClient
	echManager   *ech.EchManager
	connPool     *pool.ConnectionPool
	socks5Server *socks5.Server
}

// NewBackend returns an idle GCM backend.
func NewBackend() *Backend {
	return &Backend{}
}

// Start boots the GCM stack: relay manager, ECH/DoH, connection pool, SOCKS5
// server and warmup. listenAddr is the local SOCKS5 address; params carries
// the protocol-specific key-value settings.
func (b *Backend) Start(listenAddr string, params map[string]string, verbose bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socks5Server != nil {
		return fmt.Errorf("GCM proxy is already running")
	}
	logger.ClearRuntimeLogs()

	c, err := buildConfig(listenAddr, params, verbose)
	if err != nil {
		return err
	}
	bypassPrivate, err := boolParam(params, ParamBypassPrivate, false)
	if err != nil {
		return err
	}
	bypassGeoIPCN, err := boolParam(params, ParamBypassGeoIPCN, false)
	if err != nil {
		return err
	}
	bypassGeoSiteCN, err := boolParam(params, ParamBypassGeoSiteCN, false)
	if err != nil {
		return err
	}
	bypassMatcher, err := routing.NewMatcher(bypassPrivate, bypassGeoIPCN, bypassGeoSiteCN, stringParam(params, ParamBypassRules, ""))
	if err != nil {
		return fmt.Errorf("invalid bypass rules: %w", err)
	}

	logger.InitGlobalLogger(c)
	systemLog := logger.GetLogger("System")
	log.SetFlags(log.LstdFlags)
	systemLog.Info("启动 GCM: Worker=%s, 连接数=%d, ECH=%v, DoH=%v", c.WorkerHost, c.MinPoolSize, c.EnableECH, c.EnableDoH)

	d := dns.NewDoHClient(c)
	dc := dns.NewDNSCache(c, d)
	rm := relay.NewRelayManager(c.RelayIPs, c, dc)
	if err := rm.Init(); err != nil {
		dc.Close()
		logger.Close()
		return fmt.Errorf("initialize relay manager: %w", err)
	}

	var em *ech.EchManager
	if c.EnableECH {
		em = ech.NewEchManager(d, c.ECHDomain, c.GetECHCacheTTL(), c.GetECHRefreshInterval())
		if echConfig, err := d.GetECHConfig(c.ECHDomain); err == nil {
			em.CacheConfig(c.ECHDomain, echConfig)
		} else {
			systemLog.Warn("ECH 配置预取失败: %v (将回退到标准 TLS)", err)
		}
	}
	p := pool.NewConnectionPool(c, rm, em)
	if c.EnableDoHProxy {
		d.EnableProxy(pool.NewProxyTransport(p))
	}
	warmupDone := make(chan struct{})
	go func() {
		defer close(warmupDone)
		defer func() {
			if r := recover(); r != nil {
				systemLog.Error("连接池预热 panic: %v", r)
			}
		}()
		if err := p.Warmup(); err != nil {
			systemLog.Warn("连接池预热失败: %v", err)
		}
	}()
	if c.EnableDNSWarmup {
		go func() {
			defer func() {
				if r := recover(); r != nil {
					systemLog.Error("DNS 预热 panic: %v", r)
				}
			}()
			<-warmupDone
			dc.Warmup(c.DNSWarmupDomains)
		}()
	}
	s := socks5.NewServer(c, p, dc)
	s.SetBypassMatcher(bypassMatcher)
	if err := s.Start(); err != nil {
		p.Close()
		rm.Close()
		dc.Close()
		logger.Close()
		return fmt.Errorf("start SOCKS5 server: %w", err)
	}
	if c.EnableECH {
		em.StartAutoRefresh()
	}
	b.cfg, b.dohClient, b.dnsCache, b.relayManager, b.echManager, b.connPool, b.socks5Server = c, d, dc, rm, em, p, s
	systemLog.Info("GCM 已就绪")
	return nil
}

// Stop stops the backend and releases all resources in reverse order.
func (b *Backend) Stop() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socks5Server == nil {
		return nil
	}
	logger.GetLogger("System").Info("正在停止 GCM")
	if b.echManager != nil {
		b.echManager.StopAutoRefresh()
	}
	_ = b.socks5Server.Close()
	b.connPool.Close()
	b.relayManager.Close()
	b.dnsCache.Close()
	logger.Close()
	b.cfg, b.dohClient, b.dnsCache, b.relayManager, b.echManager, b.connPool, b.socks5Server = nil, nil, nil, nil, nil, nil, nil
	return nil
}

// Reconnect replaces all current WebSockets while preserving the VPN/TUN.
func (b *Backend) Reconnect(reason string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.connPool != nil {
		b.connPool.Reconnect(reason)
	}
}

// NotifyNetworkChanged asks the running pool to replace sockets bound to the
// previous physical network. The Android VPN interface is left untouched.
func (b *Backend) NotifyNetworkChanged() {
	b.Reconnect("Android default network changed")
}

func buildConfig(listenAddr string, params map[string]string, verbose bool) (*config.Config, error) {
	c := config.DefaultConfig()
	c.ListenAddress = strings.TrimSpace(listenAddr)
	c.WorkerHost = normalizeWorkerHost(stringParam(params, ParamWorkerHost, ""))
	if c.ListenAddress == "" {
		return nil, fmt.Errorf("SOCKS5 listen address is required")
	}
	if c.WorkerHost == "" {
		return nil, fmt.Errorf("Worker address is required")
	}

	wsConn, err := intParam(params, ParamWSConn, defaultPoolSize)
	if err != nil {
		return nil, err
	}
	enableDynamicPool, err := boolParam(params, ParamEnableDynamicPool, false)
	if err != nil {
		return nil, err
	}
	dynamicPoolMax, err := intParam(params, ParamDynamicPoolMax, defaultDynamicPoolMax)
	if err != nil {
		return nil, err
	}
	initialPoolSize, maxPoolSize := normalizePoolSettings(wsConn, enableDynamicPool, dynamicPoolMax)
	c.MinPoolSize, c.MaxPoolSize = initialPoolSize, maxPoolSize
	c.EnableDynamicPool = enableDynamicPool
	c.DynamicPoolMinSize = initialPoolSize
	c.DynamicPoolMaxSize = maxPoolSize

	if v := stringParam(params, ParamRelayIPs, ""); v != "" {
		for _, item := range strings.Split(v, ",") {
			if item = strings.TrimSpace(item); item != "" {
				c.RelayIPs = append(c.RelayIPs, item)
			}
		}
	}
	c.UserID = stringParam(params, ParamUserID, "")
	c.ProxyIP = stringParam(params, ParamProxyIP, "")
	if v := stringParam(params, ParamECHDomain, ""); v != "" {
		c.ECHDomain = v
	}
	// DoH 服务器：非空时使用用户配置，空值保留主分支内置备用列表语义。
	if v := strings.TrimSpace(stringParam(params, ParamECHDNS, "")); v != "" {
		c.DoHUrl = v
	}
	c.EnableECH, err = boolParam(params, ParamEnableECH, false)
	if err != nil {
		return nil, err
	}
	if _, err := boolParam(params, ParamDisableIPv6Route, false); err != nil {
		return nil, err // 参数保留兼容，路由禁用由 VPN 层处理。
	}
	// bypass 开关在 Start 中用于构建路由匹配器，这里提前校验类型，
	// 保证所有参数错误都能在启动网络前被捕获。
	for _, key := range []string{ParamBypassPrivate, ParamBypassGeoIPCN, ParamBypassGeoSiteCN} {
		if _, err := boolParam(params, key, false); err != nil {
			return nil, err
		}
	}
	if verbose {
		c.LogLevel = config.DEBUG
	} else {
		c.LogLevel = config.INFO
	}
	c.EnableLogFile = false
	// 默认关闭 DNS 预热以避免冷启动冲突；可由 UI 开关启用
	c.EnableDNSWarmup, err = boolParam(params, ParamEnableDNSWarmup, false)
	if err != nil {
		return nil, err
	}
	c.EnableQualityMonitor = true

	return c, nil
}

func normalizeWorkerHost(workerHost string) string {
	return strings.TrimRight(strings.TrimPrefix(strings.TrimPrefix(strings.TrimSpace(workerHost), "wss://"), "https://"), "/")
}

func normalizePoolSettings(wsConn int, enableDynamicPool bool, dynamicPoolMax int) (int, int) {
	initialPoolSize := wsConn
	if initialPoolSize <= 0 {
		initialPoolSize = defaultPoolSize
	}
	if initialPoolSize > maxDynamicPoolLimit {
		initialPoolSize = maxDynamicPoolLimit
	}
	if !enableDynamicPool {
		return initialPoolSize, initialPoolSize
	}

	if dynamicPoolMax <= 0 {
		dynamicPoolMax = defaultDynamicPoolMax
	}
	if dynamicPoolMax > maxDynamicPoolLimit {
		dynamicPoolMax = maxDynamicPoolLimit
	}
	if dynamicPoolMax < initialPoolSize {
		dynamicPoolMax = initialPoolSize
	}
	return initialPoolSize, dynamicPoolMax
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
