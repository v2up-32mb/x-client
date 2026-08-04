package gcm

import (
	"fmt"
	"log"
	"strings"
	"sync"

	"gcm/config"
	"gcm/dns"
	"gcm/ech"
	"gcm/logger"
	"gcm/pool"
	"gcm/relay"
	"gcm/routing"
	"gcm/socks5"
)

var (
	lifecycleMu  sync.Mutex
	cfg          *config.Config
	relayManager *relay.RelayManager
	dnsCache     *dns.DNSCache
	dohClient    *dns.DoHClient
	echManager   *ech.EchManager
	connPool     *pool.ConnectionPool
	socks5Server *socks5.Server
)

const (
	defaultAndroidPoolSize       = 3
	defaultAndroidDynamicPoolMax = 16
	maxAndroidDynamicPoolLimit   = 64
)

// StartSocksProxy 启动 GCM 代理（gomobile AAR 入口）
func StartSocksProxy(listenAddr, workerHost string, wsConn int, relayIPs, userID, proxyIP, echDomain, dohURL string, enableECH, disableIPv6Route, enableDNSWarmup, bypassPrivate, bypassGeoIPCN, bypassGeoSiteCN bool, bypassRules string, verbose, enableDynamicPool bool, dynamicPoolMax int) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if socks5Server != nil {
		return fmt.Errorf("GCM proxy is already running")
	}
	logger.ClearRuntimeLogs()

	c, err := buildConfig(listenAddr, workerHost, wsConn, relayIPs, userID, proxyIP, echDomain, dohURL, enableECH, disableIPv6Route, enableDNSWarmup, verbose, enableDynamicPool, dynamicPoolMax)
	if err != nil {
		return err
	}
	bypassMatcher, err := routing.NewMatcher(bypassPrivate, bypassGeoIPCN, bypassGeoSiteCN, bypassRules)
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
	cfg, dohClient, dnsCache, relayManager, echManager, connPool, socks5Server = c, d, dc, rm, em, p, s
	systemLog.Info("GCM 已就绪")
	return nil
}

// ValidateBypassRules validates newline-separated manual routing rules without
// starting the proxy. It is exported for the Android settings screen.
func ValidateBypassRules(rules string) error {
	return routing.ValidateManualRules(rules)
}

func buildConfig(listenAddr, workerHost string, wsConn int, relayIPs, userID, proxyIP, echDomain, dohURL string, enableECH, disableIPv6Route, enableDNSWarmup, verbose, enableDynamicPool bool, dynamicPoolMax int) (*config.Config, error) {
	_ = disableIPv6Route
	c := config.DefaultConfig()
	c.ListenAddress = strings.TrimSpace(listenAddr)
	c.WorkerHost = strings.TrimRight(strings.TrimPrefix(strings.TrimPrefix(strings.TrimSpace(workerHost), "wss://"), "https://"), "/")
	if c.ListenAddress == "" {
		return nil, fmt.Errorf("SOCKS5 listen address is required")
	}
	if c.WorkerHost == "" {
		return nil, fmt.Errorf("Worker address is required")
	}
	initialPoolSize, maxPoolSize := normalizeAndroidPoolSettings(wsConn, enableDynamicPool, dynamicPoolMax)
	c.MinPoolSize, c.MaxPoolSize = initialPoolSize, maxPoolSize
	c.EnableDynamicPool = enableDynamicPool
	c.DynamicPoolMinSize = initialPoolSize
	c.DynamicPoolMaxSize = maxPoolSize
	if relayIPs != "" {
		for _, item := range strings.Split(relayIPs, ",") {
			if item = strings.TrimSpace(item); item != "" {
				c.RelayIPs = append(c.RelayIPs, item)
			}
		}
	}
	c.UserID = userID
	c.ProxyIP = proxyIP
	if echDomain != "" {
		c.ECHDomain = echDomain
	}
	// DoH 服务器：非空时使用用户配置，空值保留主分支内置备用列表语义。
	if dohURL = strings.TrimSpace(dohURL); dohURL != "" {
		c.DoHUrl = dohURL
	}
	c.EnableECH = enableECH
	if verbose {
		c.LogLevel = config.DEBUG
	} else {
		c.LogLevel = config.INFO
	}
	c.EnableLogFile = false
	// 默认关闭 DNS 预热以避免冷启动冲突；可由 UI 开关启用
	c.EnableDNSWarmup = enableDNSWarmup
	c.EnableQualityMonitor = true

	return c, nil
}

func normalizeAndroidPoolSettings(wsConn int, enableDynamicPool bool, dynamicPoolMax int) (int, int) {
	initialPoolSize := wsConn
	if initialPoolSize <= 0 {
		initialPoolSize = defaultAndroidPoolSize
	}
	if initialPoolSize > maxAndroidDynamicPoolLimit {
		initialPoolSize = maxAndroidDynamicPoolLimit
	}
	if !enableDynamicPool {
		return initialPoolSize, initialPoolSize
	}

	if dynamicPoolMax <= 0 {
		dynamicPoolMax = defaultAndroidDynamicPoolMax
	}
	if dynamicPoolMax > maxAndroidDynamicPoolLimit {
		dynamicPoolMax = maxAndroidDynamicPoolLimit
	}
	if dynamicPoolMax < initialPoolSize {
		dynamicPoolMax = initialPoolSize
	}
	return initialPoolSize, dynamicPoolMax
}

// StopSocksProxy 停止代理并逆序释放所有资源。
func StopSocksProxy() {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if socks5Server == nil {
		return
	}
	logger.GetLogger("System").Info("正在停止 GCM")
	if echManager != nil {
		echManager.StopAutoRefresh()
	}
	_ = socks5Server.Close()
	connPool.Close()
	relayManager.Close()
	dnsCache.Close()
	logger.Close()
	cfg, dohClient, dnsCache, relayManager, echManager, connPool, socks5Server = nil, nil, nil, nil, nil, nil, nil
}

// NotifyNetworkChanged asks the running pool to replace sockets bound to the
// previous physical network. The Android VPN interface is left untouched.
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
	if connPool != nil {
		connPool.Reconnect(reason)
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
