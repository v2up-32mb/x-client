package pool

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"gcm/config"
	"gcm/logger"
	"gcm/protocol"
	"gcm/relay"
	"github.com/gorilla/websocket"
)

// EchManagerInterface ECH 管理器接口
type EchManagerInterface interface {
	GetTlsConfig(domain string, useEch bool) (*tls.Config, error)
	Refresh(domain string) error
}

const defaultWebSocketWriteTimeout = 5 * time.Second

// ConnItem 连接项
type ConnItem struct {
	WS           *websocket.Conn
	ConnectionID []byte // 3 bytes WS ID
	RelayAddr    string // 中转节点地址
	CreatedAt    time.Time
	RTT          atomic.Int64        // 存储纳秒值
	Streams      int                 // 当前活跃流数
	Traffic      *TrafficCounter     // 流量计数器
	mu           sync.Mutex          // 保护 Streams 和 targets
	writeMu      sync.Mutex          // 保护 WS 写操作
	writeTimeout time.Duration       // 单次 WebSocket 写入超时
	targets      map[string]struct{} // 该连接服务的前往目标地址集合 (用于多路复用亲和性)
	active       atomic.Bool         // 是否已从空闲池取出并计入 activeConnections
	closed       atomic.Bool         // WebSocket 是否已失效，失效连接不可再次入池

	// 质量监控字段
	QualityScore      int64             // 质量评分 (0-100)，原子操作
	BaselineRTT       time.Duration     // 基线 RTT（创建时的 RTT）
	RTTHistory        [10]time.Duration // RTT 历史（环形缓冲区）
	RTTIndex          int               // RTT 历史索引
	HeartbeatFailures int64             // 心跳失败次数（原子操作）
	RequestFailures   int64             // 请求失败次数（原子操作）
	RequestSuccesses  int64             // 请求成功次数（原子操作）
	LastQualityCheck  time.Time         // 上次质量检查时间
	IsDegraded        bool              // 是否已劣化
	DegradedSince     time.Time         // 劣化开始时间
	qualityMu         sync.Mutex        // 保护质量监控字段
}

func (c *ConnItem) markClosed() {
	c.closed.Store(true)
	if c.WS != nil {
		_ = c.WS.Close()
	}
}

// WriteMessage 线程安全的 WebSocket 写入方法
func (c *ConnItem) WriteMessage(messageType int, data []byte) error {
	if c == nil || c.WS == nil {
		if c != nil {
			c.markClosed()
		}
		return net.ErrClosed
	}
	if c.closed.Load() {
		return net.ErrClosed
	}

	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if c.closed.Load() {
		return net.ErrClosed
	}

	writeTimeout := c.writeTimeout
	if writeTimeout <= 0 {
		writeTimeout = defaultWebSocketWriteTimeout
	}
	if err := c.WS.SetWriteDeadline(time.Now().Add(writeTimeout)); err != nil {
		c.markClosed()
		return err
	}

	if err := c.WS.WriteMessage(messageType, data); err != nil {
		c.markClosed()
		return err
	}
	if err := c.WS.SetWriteDeadline(time.Time{}); err != nil {
		c.markClosed()
		return err
	}
	return nil
}

func (c *ConnItem) markActive() bool {
	return c.active.CompareAndSwap(false, true)
}

func (c *ConnItem) markIdle() bool {
	return c.active.CompareAndSwap(true, false)
}

// AddTarget 添加目标地址到该连接的服务集合
func (c *ConnItem) AddTarget(target string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.targets == nil {
		c.targets = make(map[string]struct{})
	}
	c.targets[target] = struct{}{}
}

// RemoveTarget 从该连接的服务集合中移除目标地址
func (c *ConnItem) RemoveTarget(target string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.targets != nil {
		delete(c.targets, target)
	}
}

// HasTarget 检查该连接是否服务于指定目标地址
func (c *ConnItem) HasTarget(target string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.targets == nil {
		return false
	}
	_, exists := c.targets[target]
	return exists
}

// GetTargetCount 获取该连接服务的目标地址数量
func (c *ConnItem) GetTargetCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.targets == nil {
		return 0
	}
	return len(c.targets)
}

// LoadFactor 计算负载因子 (0.0 - 1.0，越高越拥挤)
func (c *ConnItem) LoadFactor(maxStreams int) float64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	if maxStreams <= 0 {
		return 0
	}
	if c.Streams >= maxStreams {
		return 1.0
	}
	return float64(c.Streams) / float64(maxStreams)
}

// ============================================================================
// 质量监控方法
// ============================================================================

// RecordRTT 记录 RTT 样本
func (c *ConnItem) RecordRTT(rtt time.Duration) {
	c.qualityMu.Lock()
	defer c.qualityMu.Unlock()

	// 更新 RTT 历史（环形缓冲区）
	c.RTTHistory[c.RTTIndex] = rtt
	c.RTTIndex = (c.RTTIndex + 1) % len(c.RTTHistory)

	// 更新当前 RTT（存储纳秒值）
	c.RTT.Store(rtt.Nanoseconds())
}

// RecordSuccess 记录成功的请求
func (c *ConnItem) RecordSuccess() {
	atomic.AddInt64(&c.RequestSuccesses, 1)
}

// RecordFailure 记录失败的请求
func (c *ConnItem) RecordFailure() {
	atomic.AddInt64(&c.RequestFailures, 1)
}

// GetAverageRTT 获取平均 RTT
func (c *ConnItem) GetAverageRTT() time.Duration {
	c.qualityMu.Lock()
	defer c.qualityMu.Unlock()

	// 计算 RTT 历史的平均值
	var sum time.Duration
	count := 0
	for _, rtt := range c.RTTHistory {
		if rtt > 0 {
			sum += rtt
			count++
		}
	}

	if count == 0 {
		return time.Duration(c.RTT.Load()) // 如果没有历史数据，返回当前 RTT
	}
	return sum / time.Duration(count)
}

// GetLossRate 获取丢包率
func (c *ConnItem) GetLossRate() float64 {
	successes := atomic.LoadInt64(&c.RequestSuccesses)
	failures := atomic.LoadInt64(&c.RequestFailures)
	total := successes + failures

	if total == 0 {
		return 0
	}
	return float64(failures) / float64(total)
}

// StreamHandler 流处理器
type StreamHandler struct {
	OnMessage func(msg *protocol.Message)
	OnClose   func()
	OnError   func()
	OnCleanup func()
}

// ConnectionPool WebSocket 连接池
type ConnectionPool struct {
	mu           sync.RWMutex
	cfg          *config.Config
	log          *logger.Logger
	relayManager *relay.RelayManager
	echManager   EchManagerInterface // ECH 配置管理器

	pool               []*ConnItem
	activeConnections  int32
	pendingConnections int32
	requestQueue       chan *connRequest
	// StreamManager 集成: 每条连接对应一个 StreamManager
	managerByConn     map[*ConnItem]*StreamManager
	pendingHeartbeats map[string]time.Time

	// 目标地址亲和性映射 (用于多路复用优化)
	targetToConn map[string]*ConnItem // 目标地址 -> 当前服务的连接

	lastRelayFetchTime time.Time
	currentMinPoolSize int32

	// ECH 降级控制
	echFailureCount    int32     // ECH 连续失败次数
	echDisabledUntil   time.Time // ECH 禁用截止时间
	echFallbackEnabled bool      // 是否已启用 ECH 降级

	stats    PoolStats
	stopChan chan struct{}
}

// connRequest 连接请求
type connRequest struct {
	connCh chan *ConnItem
	errCh  chan error
}

// PoolStats 连接池统计
type PoolStats struct {
	StartTime          time.Time
	CreatedConnections int64
	ClosedConnections  int64
	Failures           int64 // 连接创建失败计数
}

// NewConnectionPool 创建连接池
func NewConnectionPool(cfg *config.Config, relayMgr *relay.RelayManager, echMgr EchManagerInterface) *ConnectionPool {
	p := &ConnectionPool{
		cfg:                cfg,
		log:                logger.GetLogger("Pool"),
		relayManager:       relayMgr,
		echManager:         echMgr,
		pool:               make([]*ConnItem, 0),
		requestQueue:       make(chan *connRequest, cfg.MaxPoolSize*2),
		managerByConn:      make(map[*ConnItem]*StreamManager),
		pendingHeartbeats:  make(map[string]time.Time),
		targetToConn:       make(map[string]*ConnItem),
		currentMinPoolSize: int32(cfg.MinPoolSize),
		stopChan:           make(chan struct{}),
		stats: PoolStats{
			StartTime: time.Now(),
		},
	}

	p.log.Debug("连接池已初始化 (Min:%d, Max:%d, 多路复用:%v)",
		cfg.MinPoolSize, cfg.MaxPoolSize, cfg.EnableMultiplex)

	// 启动后台维护
	go p.maintainLoop()
	go p.cullLoop()
	go p.statsLoop()
	go p.heartbeatLoop()
	go p.rateUpdateLoop()
	go p.congestionControlLoop() // 拥塞控制循环

	if cfg.EnableDynamicPool {
		go p.dynamicPoolLoop()
	}

	return p
}

func (p *ConnectionPool) liveConnectionCountLocked() int {
	seen := make(map[*ConnItem]struct{}, len(p.managerByConn)+len(p.pool))
	for item := range p.managerByConn {
		if !item.closed.Load() {
			seen[item] = struct{}{}
		}
	}
	for _, item := range p.pool {
		if !item.closed.Load() {
			seen[item] = struct{}{}
		}
	}
	return len(seen)
}

func (p *ConnectionPool) liveConnectionCount() int {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.liveConnectionCountLocked()
}

func (p *ConnectionPool) reserveConnectionSlot() (int, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	currentSize := p.liveConnectionCountLocked() + int(atomic.LoadInt32(&p.pendingConnections))
	if currentSize >= p.cfg.MaxPoolSize {
		return currentSize, false
	}
	atomic.AddInt32(&p.pendingConnections, 1)
	return currentSize, true
}

func (p *ConnectionPool) releaseConnectionSlot() {
	atomic.AddInt32(&p.pendingConnections, -1)
}

// Warmup 连接池预热
func (p *ConnectionPool) Warmup() error {
	if !p.cfg.EnablePoolWarmup || p.currentMinPoolSize <= 0 {
		return nil
	}

	p.log.Info("开始预热连接池，目标: %d 个连接...", p.currentMinPoolSize)

	startTime := time.Now()
	targetSize := int(p.currentMinPoolSize)
	concurrency := p.cfg.WarmupConcurrency
	created := 0
	failed := 0
	consecutiveFailures := 0 // 连续失败计数

	// 分批次创建连接
	for created < targetSize {
		remaining := targetSize - created
		batchSize := min(remaining, concurrency)

		// 并发创建一批连接，使用 err channel 等待每个完成
		type result struct {
			success bool
		}
		results := make(chan result, batchSize)

		for i := 0; i < batchSize; i++ {
			go func() {
				// createConnection 内部会同步等待连接建立完成
				success := p.createConnectionSync("预热")
				results <- result{success: success}
			}()
		}

		// 等待所有连接创建完成
		batchFailed := 0
		for i := 0; i < batchSize; i++ {
			res := <-results
			if res.success {
				created++
				consecutiveFailures = 0 // 重置连续失败计数
			} else {
				failed++
				batchFailed++
			}
		}
		close(results)

		// 检查超时
		if time.Since(startTime) > p.cfg.GetWarmupTimeout() {
			p.log.Warn("预热超时，已创建 %d/%d 个连接 (失败: %d)", created, targetSize, failed)
			break
		}

		if created >= targetSize {
			break
		}

		// 如果这批全部失败，增加连续失败计数
		if batchFailed >= batchSize {
			consecutiveFailures++
			// 如果连续失败超过 3 次，快速退出（网络可能不可用）
			if consecutiveFailures > 3 {
				p.log.Warn("预热连续失败 %d 次，跳过预热", consecutiveFailures)
				break
			}
			p.log.Warn("本批连接全部失败，等待后重试...")
			time.Sleep(500 * time.Millisecond)
		} else {
			consecutiveFailures = 0
			time.Sleep(100 * time.Millisecond)
		}
	}

	elapsed := time.Since(startTime)
	p.log.Info("预热完成，创建 %d 个连接 (失败: %d)，耗时 %dms", created, failed, elapsed.Milliseconds())

	return nil
}

// buildWSSURL 构建 WebSocket 连接 URL（包含 proxyIP 参数）
func (p *ConnectionPool) buildWSSURL() string {
	url := fmt.Sprintf("wss://%s/%s", p.cfg.WorkerHost, p.cfg.UserID)
	if p.cfg.ProxyIP != "" {
		url += "?fallbackip=" + p.cfg.ProxyIP
	}
	return url
}

// generateWSID 生成 WebSocket ID (3字节)
func (p *ConnectionPool) generateWSID() []byte {
	buf := make([]byte, 3)
	rand.Read(buf)
	return buf
}

// getTLSConfig 获取 TLS 配置（支持 ECH 和自动降级）
func (p *ConnectionPool) getTLSConfig() *tls.Config {
	// 检查 ECH 是否被临时禁用
	useECH := p.cfg.EnableECH
	if useECH && time.Now().Before(p.echDisabledUntil) {
		p.log.Debug("ECH 当前处于降级状态，使用普通 TLS")
		useECH = false
	}

	if p.echManager != nil {
		tlsConfig, err := p.echManager.GetTlsConfig(p.cfg.WorkerHost, useECH)
		if err != nil {
			p.log.Warn("获取 TLS 配置失败，使用默认配置: %v", err)
			return &tls.Config{
				MinVersion: tls.VersionTLS13,
				ServerName: p.cfg.WorkerHost,
			}
		}
		return tlsConfig
	}
	return &tls.Config{
		MinVersion: tls.VersionTLS13,
		ServerName: p.cfg.WorkerHost,
	}
}

// handleDialError 智能处理拨号错误
func (p *ConnectionPool) handleDialError(err error, relay *relay.RelayNode) {
	if err == nil {
		return
	}

	errStr := err.Error()

	// 1. 判断是否为 ECH 相关错误
	if p.cfg.EnableECH && p.echManager != nil {
		if strings.Contains(errStr, "ech") ||
			strings.Contains(errStr, "encrypted_client_hello") ||
			strings.Contains(errStr, "tls: handshake failure") {

			// 增加 ECH 失败计数
			failCount := atomic.AddInt32(&p.echFailureCount, 1)
			p.log.Warn("检测到 ECH 相关错误 (连续失败: %d 次)", failCount)

			// 如果连续失败 3 次，启用降级模式
			if failCount >= 3 {
				p.mu.Lock()
				if !p.echFallbackEnabled {
					p.echFallbackEnabled = true
					p.echDisabledUntil = time.Now().Add(5 * time.Minute) // 降级 5 分钟
					p.log.Warn("ECH 连续失败 %d 次，启用降级模式，将使用普通 TLS (持续 5 分钟)", failCount)
				}
				p.mu.Unlock()
			} else {
				// 失败次数未达到阈值，尝试刷新配置
				p.log.Info("尝试刷新 ECH 配置...")
				go func() {
					if err := p.echManager.Refresh(p.cfg.ECHDomain); err != nil {
						p.log.Error("刷新 ECH 配置失败: %v", err)
					} else {
						p.log.Info("ECH 配置已刷新")
					}
				}()
			}
			return
		}
	}

	// 2. 判断是否为中转节点连接失败
	if relay != nil && (strings.Contains(errStr, "connection refused") ||
		strings.Contains(errStr, "connection reset") ||
		strings.Contains(errStr, "timeout")) {
		p.log.Warn("中转节点连接失败，触发节点重评")
		go p.handleConnectionFailure()
		return
	}

	// 3. 其他错误，仅记录日志
	p.log.Warn("拨号失败，等待重试: %v", err)
}

// createConnectionSync 同步创建连接（用于预热），返回成功/失败
func (p *ConnectionPool) createConnectionSync(reason string) bool {
	currentSize, reserved := p.reserveConnectionSlot()
	if !reserved {
		p.log.Debug("连接池已满 (%d/%d)，跳过创建: %s", currentSize, p.cfg.MaxPoolSize, reason)
		return false
	}
	defer p.releaseConnectionSlot()

	atomic.AddInt64(&p.stats.CreatedConnections, 1)

	// 使用负载均衡选择节点
	relay := p.relayManager.GetNextRelayWithLoadBalance()
	// loadIncremented 仅在当前 goroutine 中使用，无需原子操作
	var loadIncremented bool
	if relay != nil {
		// 增加节点负载计数
		p.relayManager.UpdateNodeLoad(relay.IP, relay.Port, 1)
		loadIncremented = true
		defer func() {
			// 只有在连接失败时才减少负载计数
			// 成功时由连接关闭时处理
			if loadIncremented {
				p.relayManager.UpdateNodeLoad(relay.IP, relay.Port, -1)
			}
		}()
	}

	var url string
	var headers http.Header
	var customDial func(network, addr string) (net.Conn, error)

	if relay != nil {
		// 中转模式：URL 仍用原始 Worker，但通过 NetDial 将 TCP 连接到中转节点
		url = p.buildWSSURL()
		customDial = func(network, addr string) (net.Conn, error) {
			// addr 是 workerHost:443，替换为中转节点的 IP:PORT
			return net.DialTimeout(network, net.JoinHostPort(relay.IP, fmt.Sprintf("%d", relay.Port)), p.cfg.GetConnectionTimeout())
		}
		p.log.Debug("创建连接 (%s) -> 中转: %s:%d (TLS SNI: %s)", reason, relay.IP, relay.Port, p.cfg.WorkerHost)
	} else {
		// 直连模式：也需要设置 DialTimeout，否则会无限期等待
		url = p.buildWSSURL()
		customDial = func(network, addr string) (net.Conn, error) {
			return net.DialTimeout(network, addr, p.cfg.GetConnectionTimeout())
		}
		p.log.Debug("创建连接 (%s) -> 直连: %s", reason, p.cfg.WorkerHost)
	}

	headers = make(http.Header)
	headers.Set("Host", p.cfg.WorkerHost)
	headers.Set("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36 Edg/109.0.1518.140")

	// 获取 TLS 配置（支持 ECH）
	tlsConfig := p.getTLSConfig()

	// 配置 WebSocket Dialer
	dialer := websocket.Dialer{
		HandshakeTimeout: p.cfg.GetConnectionTimeout(),
		NetDial:          customDial,
		TLSClientConfig:  tlsConfig,
	}

	startTime := time.Now()
	ws, resp, err := dialer.Dial(url, headers)
	if err != nil {
		atomic.AddInt64(&p.stats.Failures, 1)
		p.log.Warn("连接失败 (%s): %v (目标: %s)", reason, err, url)

		// 智能处理拨号错误
		p.handleDialError(err, relay)

		return false
	}
	defer resp.Body.Close()

	// 连接成功，重置 ECH 失败计数
	if p.cfg.EnableECH && atomic.LoadInt32(&p.echFailureCount) > 0 {
		atomic.StoreInt32(&p.echFailureCount, 0)
		p.log.Debug("连接成功，重置 ECH 失败计数")
	}

	latency := time.Since(startTime)
	connectionID := p.generateWSID()

	// 构建中转节点地址字符串
	relayAddr := p.cfg.WorkerHost // 直连模式使用 Worker 地址
	if relay != nil {
		relayAddr = fmt.Sprintf("%s:%d", relay.IP, relay.Port)
	}

	item := &ConnItem{
		WS:           ws,
		ConnectionID: connectionID,
		RelayAddr:    relayAddr,
		CreatedAt:    time.Now(),
		Streams:      0,
		Traffic:      &TrafficCounter{},
		writeTimeout: p.cfg.GetHeartbeatTimeout(),
		// 初始化质量监控字段
		QualityScore:      100, // 初始满分
		BaselineRTT:       latency,
		RTTHistory:        [10]time.Duration{},
		RTTIndex:          0,
		HeartbeatFailures: 0,
		RequestFailures:   0,
		RequestSuccesses:  0,
		LastQualityCheck:  time.Now(),
		IsDegraded:        false,
	}
	item.RTT.Store(latency.Nanoseconds())

	connIDStr := fmt.Sprintf("%02x%02x%02x", connectionID[0], connectionID[1], connectionID[2])
	p.log.Debug("新连接 [%s] 已就绪 (%s), 握手延迟: %dms", connIDStr, reason, latency.Milliseconds())

	// 设置 TCP NODELAY
	if p.cfg.EnableTcpNoDelay {
		// 获取底层连接
		if nc, ok := ws.UnderlyingConn().(interface{ SetNoDelay(bool) error }); ok {
			nc.SetNoDelay(true)
		}
	}

	// 初始化 StreamManager（messageLoop 需要它来分发消息）
	// 所有连接都必须在 managerByConn 中，无论是否有活跃的 stream
	p.mu.Lock()
	p.managerByConn[item] = NewStreamManager(
		item,
		int(p.cfg.MaxStreamsPerConnection),
		p.cfg.GetDefaultWindowSize(),
		p.cfg.GetMinWindowSize(),
		p.cfg.GetMaxWindowSize(),
		p.cfg.GetWindowTimeout(),
	)
	p.mu.Unlock()

	// 启动消息处理循环
	go p.messageLoop(item)

	// 将连接加入池（同步操作，确保加入成功后才返回）
	p.mu.Lock()
	p.pool = append(p.pool, item)
	p.mu.Unlock()

	// 连接成功，取消 defer 的负载减 1（由连接关闭时处理）
	loadIncremented = false

	return true
}

// createConnection 创建新连接（异步版本，用于运行时）
func (p *ConnectionPool) createConnection(reason string) bool {
	currentSize, reserved := p.reserveConnectionSlot()
	if !reserved {
		p.log.Debug("连接池已满 (%d/%d)，跳过创建: %s", currentSize, p.cfg.MaxPoolSize, reason)
		return false
	}
	defer p.releaseConnectionSlot()

	atomic.AddInt64(&p.stats.CreatedConnections, 1)

	// 使用负载均衡选择节点
	relay := p.relayManager.GetNextRelayWithLoadBalance()
	if relay != nil {
		// 增加节点负载计数
		p.relayManager.UpdateNodeLoad(relay.IP, relay.Port, 1)
		defer p.relayManager.UpdateNodeLoad(relay.IP, relay.Port, -1)
	}

	var url string
	var headers http.Header
	var customDial func(network, addr string) (net.Conn, error)

	if relay != nil {
		// 中转模式：URL 仍用原始 Worker，但通过 NetDial 将 TCP 连接到中转节点
		url = p.buildWSSURL()
		customDial = func(network, addr string) (net.Conn, error) {
			// addr 是 workerHost:443，替换为中转节点的 IP:PORT
			return net.DialTimeout(network, net.JoinHostPort(relay.IP, fmt.Sprintf("%d", relay.Port)), p.cfg.GetConnectionTimeout())
		}
		p.log.Debug("创建连接 (%s) -> 中转: %s:%d (TLS SNI: %s)", reason, relay.IP, relay.Port, p.cfg.WorkerHost)
	} else {
		// 直连模式：也需要设置 DialTimeout，否则会无限期等待
		url = p.buildWSSURL()
		customDial = func(network, addr string) (net.Conn, error) {
			return net.DialTimeout(network, addr, p.cfg.GetConnectionTimeout())
		}
		p.log.Debug("创建连接 (%s) -> 直连: %s", reason, p.cfg.WorkerHost)
	}

	headers = make(http.Header)
	headers.Set("Host", p.cfg.WorkerHost)
	headers.Set("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36 Edg/109.0.1518.140")

	// 获取 TLS 配置（支持 ECH）
	tlsConfig := p.getTLSConfig()

	// 配置 WebSocket Dialer
	dialer := websocket.Dialer{
		HandshakeTimeout: p.cfg.GetConnectionTimeout(),
		NetDial:          customDial,
		TLSClientConfig:  tlsConfig,
	}

	// 使用 channel 和 goroutine 实现可靠的超时保护
	type dialResult struct {
		ws   *websocket.Conn
		resp *http.Response
		err  error
	}
	resultChan := make(chan dialResult, 1)

	go func() {
		ws, resp, err := dialer.Dial(url, headers)
		if err != nil {
			p.log.Debug("Dial goroutine 返回错误: %v", err)
		}
		resultChan <- dialResult{ws, resp, err}
	}()

	startTime := time.Now()
	var ws *websocket.Conn
	var resp *http.Response
	var err error

	// 等待连接完成或超时
	select {
	case res := <-resultChan:
		ws, resp, err = res.ws, res.resp, res.err
		p.log.Debug("Dial 完成，耗时: %dms", time.Since(startTime).Milliseconds())
	case <-time.After(p.cfg.GetConnectionTimeout() * 2): // 外层超时保护（2倍 ConnectionTimeout）
		atomic.AddInt64(&p.stats.Failures, 1)
		p.log.Warn("连接失败 (%s): 总体超时 (目标: %s)", reason, url)
		// 启动清理 goroutine，等待 Dial 完成后关闭可能泄漏的 ws/resp
		go func() {
			res := <-resultChan
			if res.ws != nil {
				res.ws.Close()
			}
			if res.resp != nil {
				res.resp.Body.Close()
			}
		}()
		return false
	}

	if err != nil {
		atomic.AddInt64(&p.stats.Failures, 1)
		p.log.Warn("连接失败 (%s): %v (目标: %s)", reason, err, url)

		// 智能处理拨号错误
		p.handleDialError(err, relay)

		return false
	}
	defer resp.Body.Close()

	// 连接成功，重置 ECH 失败计数
	if p.cfg.EnableECH && atomic.LoadInt32(&p.echFailureCount) > 0 {
		atomic.StoreInt32(&p.echFailureCount, 0)
		p.log.Debug("连接成功，重置 ECH 失败计数")
	}

	latency := time.Since(startTime)
	connectionID := p.generateWSID()

	// 构建中转节点地址字符串
	relayAddr := p.cfg.WorkerHost // 直连模式使用 Worker 地址
	if relay != nil {
		relayAddr = fmt.Sprintf("%s:%d", relay.IP, relay.Port)
	}

	item := &ConnItem{
		WS:           ws,
		ConnectionID: connectionID,
		RelayAddr:    relayAddr,
		CreatedAt:    time.Now(),
		Streams:      0,
		Traffic:      &TrafficCounter{},
		writeTimeout: p.cfg.GetHeartbeatTimeout(),
		// 初始化质量监控字段
		QualityScore:      100, // 初始满分
		BaselineRTT:       latency,
		RTTHistory:        [10]time.Duration{},
		RTTIndex:          0,
		HeartbeatFailures: 0,
		RequestFailures:   0,
		RequestSuccesses:  0,
		LastQualityCheck:  time.Now(),
		IsDegraded:        false,
	}
	item.RTT.Store(latency.Nanoseconds())

	connIDStr := fmt.Sprintf("%02x%02x%02x", connectionID[0], connectionID[1], connectionID[2])
	p.log.Debug("新连接 [%s] 已就绪 (%s), 握手延迟: %dms", connIDStr, reason, latency.Milliseconds())

	// 设置 TCP NODELAY
	if p.cfg.EnableTcpNoDelay {
		// 获取底层连接
		if nc, ok := ws.UnderlyingConn().(interface{ SetNoDelay(bool) error }); ok {
			nc.SetNoDelay(true)
		}
	}

	// 初始化 StreamManager（确保 messageLoop 能立即分发消息）
	p.mu.Lock()
	p.managerByConn[item] = NewStreamManager(
		item,
		int(p.cfg.MaxStreamsPerConnection),
		p.cfg.GetDefaultWindowSize(),
		p.cfg.GetMinWindowSize(),
		p.cfg.GetMaxWindowSize(),
		p.cfg.GetWindowTimeout(),
	)
	p.mu.Unlock()

	// 启动消息处理循环
	go p.messageLoop(item)

	// 检查是否有等待的请求
	select {
	case req := <-p.requestQueue:
		if item.markActive() {
			atomic.AddInt32(&p.activeConnections, 1)
		}
		req.connCh <- item
	default:
		p.mu.Lock()
		p.pool = append(p.pool, item)
		p.mu.Unlock()
	}

	return true
}

// createConnectionWithRelay 使用指定的中转节点创建连接
func (p *ConnectionPool) createConnectionWithRelay(relay *relay.RelayNode, reason string) bool {
	currentSize, reserved := p.reserveConnectionSlot()
	if !reserved {
		p.log.Debug("连接池已满 (%d/%d)，跳过创建: %s", currentSize, p.cfg.MaxPoolSize, reason)
		return false
	}
	defer p.releaseConnectionSlot()

	atomic.AddInt64(&p.stats.CreatedConnections, 1)

	// 使用指定的中转节点
	url := fmt.Sprintf("wss://%s/%s", p.cfg.WorkerHost, p.cfg.UserID)
	customDial := func(network, addr string) (net.Conn, error) {
		return net.DialTimeout(network, net.JoinHostPort(relay.IP, fmt.Sprintf("%d", relay.Port)), p.cfg.GetConnectionTimeout())
	}
	p.log.Debug("创建连接 (%s) -> 中转: %s:%d (TLS SNI: %s)", reason, relay.IP, relay.Port, p.cfg.WorkerHost)

	headers := make(http.Header)
	headers.Set("Host", p.cfg.WorkerHost)
	headers.Set("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36 Edg/109.0.1518.140")

	tlsConfig := p.getTLSConfig()

	dialer := websocket.Dialer{
		HandshakeTimeout: p.cfg.GetConnectionTimeout(),
		NetDial:          customDial,
		TLSClientConfig:  tlsConfig,
	}

	// 使用 channel 实现超时保护
	type dialResult struct {
		ws   *websocket.Conn
		resp *http.Response
		err  error
	}
	resultChan := make(chan dialResult, 1)

	go func() {
		ws, resp, err := dialer.Dial(url, headers)
		resultChan <- dialResult{ws, resp, err}
	}()

	startTime := time.Now()
	var ws *websocket.Conn
	var resp *http.Response
	var err error

	select {
	case res := <-resultChan:
		ws, resp, err = res.ws, res.resp, res.err
	case <-time.After(p.cfg.GetConnectionTimeout() * 2):
		atomic.AddInt64(&p.stats.Failures, 1)
		p.log.Warn("连接失败 (%s): 总体超时", reason)
		// 启动清理 goroutine，等待 Dial 完成后关闭可能泄漏的 ws/resp
		go func() {
			res := <-resultChan
			if res.ws != nil {
				res.ws.Close()
			}
			if res.resp != nil {
				res.resp.Body.Close()
			}
		}()
		return false
	}

	if err != nil {
		atomic.AddInt64(&p.stats.Failures, 1)
		p.log.Warn("连接失败 (%s): %v", reason, err)
		return false
	}
	defer resp.Body.Close()

	latency := time.Since(startTime)
	connectionID := p.generateWSID()
	relayAddr := fmt.Sprintf("%s:%d", relay.IP, relay.Port)

	item := &ConnItem{
		WS:           ws,
		ConnectionID: connectionID,
		RelayAddr:    relayAddr,
		CreatedAt:    time.Now(),
		Streams:      0,
		Traffic:      &TrafficCounter{},
		writeTimeout: p.cfg.GetHeartbeatTimeout(),
		// 初始化质量监控字段
		QualityScore:      100,
		BaselineRTT:       latency,
		RTTHistory:        [10]time.Duration{},
		RTTIndex:          0,
		HeartbeatFailures: 0,
		RequestFailures:   0,
		RequestSuccesses:  0,
		LastQualityCheck:  time.Now(),
		IsDegraded:        false,
	}
	item.RTT.Store(latency.Nanoseconds())

	connIDStr := fmt.Sprintf("%02x%02x%02x", connectionID[0], connectionID[1], connectionID[2])
	p.log.Debug("新连接 [%s] 已就绪 (%s), 握手延迟: %dms", connIDStr, reason, latency.Milliseconds())

	// 设置 TCP NODELAY
	if p.cfg.EnableTcpNoDelay {
		if nc, ok := ws.UnderlyingConn().(interface{ SetNoDelay(bool) error }); ok {
			nc.SetNoDelay(true)
		}
	}

	// 初始化 StreamManager
	p.mu.Lock()
	p.managerByConn[item] = NewStreamManager(
		item,
		int(p.cfg.MaxStreamsPerConnection),
		p.cfg.GetDefaultWindowSize(),
		p.cfg.GetMinWindowSize(),
		p.cfg.GetMaxWindowSize(),
		p.cfg.GetWindowTimeout(),
	)
	p.mu.Unlock()

	// 启动消息处理循环
	go p.messageLoop(item)

	// 将连接加入池
	p.mu.Lock()
	p.pool = append(p.pool, item)
	p.mu.Unlock()

	return true
}

// messageLoop 消息处理循环
func (p *ConnectionPool) messageLoop(item *ConnItem) {
	ws := item.WS
	connIDStr := fmt.Sprintf("%02x%02x%02x", item.ConnectionID[0], item.ConnectionID[1], item.ConnectionID[2])
	readTimeout := p.cfg.GetHeartbeatInterval() + p.cfg.GetHeartbeatTimeout()
	if readTimeout <= 0 {
		readTimeout = defaultWebSocketWriteTimeout
	}
	_ = ws.SetReadDeadline(time.Now().Add(readTimeout))

	// 设置 Pong 处理器，处理心跳响应
	ws.SetPongHandler(func(appData string) error {
		if err := ws.SetReadDeadline(time.Now().Add(readTimeout)); err != nil {
			return err
		}
		p.acknowledgeHeartbeat(item, connIDStr, "Pong")
		return nil
	})

	defer func() {
		// 连接关闭处理 - 记录流量统计
		sent, recv, streams := item.Traffic.GetSnapshot()
		connIDStr := formatConnID(item.ConnectionID)
		p.log.Info("WS[%s] 关闭 | %s | ↑ %s | ↓ %s | Streams: %d",
			connIDStr, item.RelayAddr,
			formatBytes(sent), formatBytes(recv), streams)

		p.handleConnectionClose(item)
		// 静默关闭 WebSocket（忽略 "already closed" 错误）
		ws.Close() // nolint:errcheck
	}()

	for {
		_, data, err := ws.ReadMessage()
		if err != nil {
			if !item.closed.Load() {
				p.log.Warn("WS[%s] 读取失败: %v", connIDStr, err)
			}
			return
		}
		if err := ws.SetReadDeadline(time.Now().Add(readTimeout)); err != nil {
			p.log.Warn("WS[%s] 刷新读取 deadline 失败: %v", connIDStr, err)
			return
		}
		// Application data is also proof that the path is alive. This matters
		// for busy streams where a Pong can be delayed behind data frames.
		p.acknowledgeHeartbeat(item, connIDStr, "数据")

		msg, err := protocol.Decode(data)
		if err != nil {
			p.log.Debug("消息解码失败: %v", err)
			continue
		}

		// 通过 StreamManager 分发消息到对应的 stream
		p.mu.RLock()
		mgr, ok := p.managerByConn[item]
		p.mu.RUnlock()

		if ok {
			mgr.DispatchMessage(msg)
		}
	}
}

func (p *ConnectionPool) acknowledgeHeartbeat(item *ConnItem, connIDStr, source string) {
	p.mu.Lock()
	lastPing, pending := p.pendingHeartbeats[connIDStr]
	if pending {
		delete(p.pendingHeartbeats, connIDStr)
	}
	p.mu.Unlock()
	if !pending {
		return
	}

	rtt := time.Since(lastPing)
	oldRTT := item.RTT.Load()
	item.RTT.Store(oldRTT*7/10 + rtt.Nanoseconds()*3/10)
	item.RecordRTT(rtt)
	if p.log != nil {
		p.log.Debug("WS[%s] 心跳确认(%s): %dms", connIDStr, source, rtt.Milliseconds())
	}
}

// handleConnectionClose removes a failed socket from every pool index before
// notifying stream owners. This prevents cleanup callbacks from returning the
// dead connection to the idle pool.
func (p *ConnectionPool) handleConnectionClose(item *ConnItem) {
	item.closed.Store(true)

	var mgrToClose *StreamManager
	removed := false
	p.mu.Lock()
	for i, candidate := range p.pool {
		if candidate == item {
			p.pool = append(p.pool[:i], p.pool[i+1:]...)
			removed = true
			break
		}
	}
	if mgr, ok := p.managerByConn[item]; ok {
		mgrToClose = mgr
		delete(p.managerByConn, item)
		removed = true
	}
	for target, candidate := range p.targetToConn {
		if candidate == item {
			delete(p.targetToConn, target)
		}
	}
	delete(p.pendingHeartbeats, formatConnID(item.ConnectionID))
	p.mu.Unlock()

	if item.markIdle() {
		atomic.AddInt32(&p.activeConnections, -1)
	}
	if removed {
		atomic.AddInt64(&p.stats.ClosedConnections, 1)
	}
	if mgrToClose != nil {
		mgrToClose.HandleConnectionClose()
	}
}

// RetireConnection removes a failed WebSocket from every pool index and closes
// it. It is safe to call more than once; the first call owns stream cleanup.
func (p *ConnectionPool) RetireConnection(item *ConnItem, reason string) {
	if item == nil {
		return
	}
	if item.closed.CompareAndSwap(false, true) && p.log != nil {
		p.log.Debug("连接 [%s] 退休: %s", formatConnID(item.ConnectionID), reason)
	}

	p.handleConnectionClose(item)
	if item.WS != nil {
		_ = item.WS.Close()
	}
}

// handleConnectionFailure 处理连接失败
func (p *ConnectionPool) handleConnectionFailure() {
	// 触发节点重新评分
	if p.relayManager.ForceRescore() {
		p.log.Info("节点重新评分完成，后续连接将使用负载均衡选择")
	}
}

// GetConnectionWithStream 原子化地获取连接并分配流 ID
// 这是推荐使用的方法，它确保获取连接和分配流是原子操作，避免阻塞
func (p *ConnectionPool) GetConnectionWithStream(ctx context.Context, targetAddr string) (*ConnItem, byte, error) {
	maxStreams := int(p.cfg.MaxStreamsPerConnection)
	deadline, hasDeadline := ctx.Deadline()

	p.log.Debug("GetConnectionWithStream 开始 -> %s", targetAddr)

	retryCount := 0
	for {
		// 快速检查：先不持有锁，只持有读锁来检查是否有可用连接
		var bestConn *ConnItem
		var bestMgr *StreamManager

		p.mu.Lock()

		// 记录当前池状态
		idleCount := len(p.pool)
		activeCount := int(atomic.LoadInt32(&p.activeConnections))
		pendingCount := int(atomic.LoadInt32(&p.pendingConnections))

		// 1. 检查空闲池
		for len(p.pool) > 0 {
			item := p.pool[len(p.pool)-1]
			p.pool = p.pool[:len(p.pool)-1]

			if item.WS != nil && !item.closed.Load() {
				// 获取或创建 StreamManager
				mgr, ok := p.managerByConn[item]
				if !ok {
					mgr = NewStreamManager(
						item,
						maxStreams,
						p.cfg.GetDefaultWindowSize(),
						p.cfg.GetMinWindowSize(),
						p.cfg.GetMaxWindowSize(),
						p.cfg.GetWindowTimeout(),
					)
					p.managerByConn[item] = mgr
				}

				// 尝试立即分配流
				streamID, allocated := mgr.tryAllocateStream(targetAddr)
				if allocated {
					if item.markActive() {
						atomic.AddInt32(&p.activeConnections, 1)
					}
					p.mu.Unlock()

					p.log.Debug("获取连接+流: [%s] Stream[%02x] -> %s (空闲连接)",
						formatConnID(item.ConnectionID), streamID, targetAddr)
					return item, streamID, nil
				}
				// 分配失败，连接已满
				// 放回池的最前面（下次优先使用）
				p.pool = append([]*ConnItem{item}, p.pool...)
			}
		}

		// 2. 检查亲和性连接
		if targetAddr != "" && p.cfg.EnableMultiplex {
			if affinityConn, exists := p.targetToConn[targetAddr]; exists {
				if mgr, ok := p.managerByConn[affinityConn]; ok && affinityConn.WS != nil && !affinityConn.closed.Load() {
					streamID, allocated := mgr.tryAllocateStream(targetAddr)
					if allocated {
						p.mu.Unlock()
						p.log.Debug("获取连接+流: [%s] Stream[%02x] -> %s (亲和连接)",
							formatConnID(affinityConn.ConnectionID), streamID, targetAddr)
						return affinityConn, streamID, nil
					}
				}
			}
		}

		// 3. 检查活跃连接中流数最少的
		if p.cfg.EnableMultiplex {
			minStreams := maxStreams + 1
			for item, mgr := range p.managerByConn {
				if item.WS == nil || item.closed.Load() {
					continue
				}
				streamCount := mgr.GetStreamCount()
				if streamCount < minStreams {
					minStreams = streamCount
					bestConn = item
					bestMgr = mgr
				}
			}

			// 尝试使用流数最少的连接
			if bestConn != nil && bestMgr != nil && minStreams < maxStreams {
				streamID, allocated := bestMgr.tryAllocateStream(targetAddr)
				if allocated {
					p.mu.Unlock()
					p.log.Debug("获取连接+流: [%s] Stream[%02x] -> %s (活跃连接,流数:%d)",
						formatConnID(bestConn.ConnectionID), streamID, targetAddr, minStreams)
					return bestConn, streamID, nil
				}
			}
		}

		// 4. 所有连接都满载，需要创建新连接
		currentSize := p.liveConnectionCountLocked() + pendingCount

		if currentSize >= p.cfg.MaxPoolSize {
			p.mu.Unlock()
			p.log.Warn("所有连接已满载（%d/%d），无法满足请求 -> %s", currentSize, p.cfg.MaxPoolSize, targetAddr)
			return nil, 0, fmt.Errorf("所有连接已满载（%d/%d）", currentSize, p.cfg.MaxPoolSize)
		}

		p.log.Debug("所有连接已满，触发新连接创建 (当前:%d/%d, 待创建:%d) -> %s",
			currentSize, p.cfg.MaxPoolSize, pendingCount, targetAddr)

		p.mu.Unlock()
		// createConnection performs an atomic capacity reservation. Multiple
		// requests may race here, but only available slots are allowed through.
		if pendingCount == 0 {
			go p.createConnection("请求触发")
		}

		retryCount++
		// 等待新连接可用
		select {
		case <-time.After(50 * time.Millisecond):
			// 继续循环重试
			p.log.Debug("重试 %d: 等待新连接 (空闲:%d, 活跃:%d, 待建:%d) -> %s",
				retryCount, idleCount, activeCount, pendingCount, targetAddr)
		case <-ctx.Done():
			return nil, 0, ctx.Err()
		}

		if !hasDeadline {
			// 没有 deadline，最多重试一定次数
			continue
		}

		// 检查是否超时
		if time.Until(deadline) <= 0 {
			return nil, 0, fmt.Errorf("获取连接超时")
		}
	}
}

// GetConnection 获取连接（支持目标地址亲和性）
// 注意：此方法只获取连接，不分配流。调用者需要调用 AllocateStreamID 分配流。
// 推荐使用 GetConnectionWithStream 代替此方法。
func (p *ConnectionPool) GetConnection(ctx context.Context, targetAddr string) (*ConnItem, error) {
	var selectedItem *ConnItem
	var selectedReason string
	var selectedScore float64
	var isAffinity bool
	var isFromPool bool

	// 选择和移除必须是原子操作，全程持有锁
	p.mu.Lock()

	maxStreams := int(p.cfg.MaxStreamsPerConnection)

	// 辅助函数：计算连接评分
	calcScore := func(item *ConnItem, streams int) float64 {
		loadFactor := 0.0
		if maxStreams > 0 {
			loadFactor = float64(streams) / float64(maxStreams)
		}
		// RTT 归一化 (假设 2000ms 为最差情况)
		rttNorm := float64(item.RTT.Load()) / (2000.0 * 1e6)
		if rttNorm > 1.0 {
			rttNorm = 1.0
		}
		// 综合评分：负载 60% + RTT 40%（越低越好）
		return loadFactor*0.6 + rttNorm*0.4
	}

	// 1. 首先检查空闲池（优先使用空闲连接）
	// 空闲池已按质量评分排序（在 ReleaseConnection 和 QualityMonitor 中维护）
	var lowQualityConns []*ConnItem // 收集低质量连接，稍后关闭
	if len(p.pool) > 0 {
		// 直接从头部取连接（已排序，头部是最高质量）
		for len(p.pool) > 0 {
			item := p.pool[0]
			p.pool = p.pool[1:]

			if item.WS == nil || item.closed.Load() {
				continue
			}

			// 检查连接质量评分
			qualityScore := atomic.LoadInt64(&item.QualityScore)
			if qualityScore < 40 {
				// 质量过低，收集起来稍后关闭（避免持有锁时调用 Close）
				lowQualityConns = append(lowQualityConns, item)
				p.log.Warn("连接 [%s] 质量过低 (分数=%d)，跳过使用", formatConnID(item.ConnectionID), qualityScore)
				continue
			}

			selectedItem = item
			selectedReason = fmt.Sprintf("空闲连接(质量=%d)", qualityScore)
			selectedScore = calcScore(item, 0)
			isFromPool = true
			break
		}
	}

	// 2. 如果没有从空闲池选择到，检查亲和性连接和活跃连接
	if selectedItem == nil && p.cfg.EnableMultiplex {
		// 检查目标地址亲和性
		if targetAddr != "" {
			if affinityConn, exists := p.targetToConn[targetAddr]; exists {
				if mgr, ok := p.managerByConn[affinityConn]; ok && affinityConn.WS != nil && !affinityConn.closed.Load() {
					streamCount := mgr.GetStreamCount()
					hasSpace := streamCount < maxStreams

					if hasSpace && mgr.HasTarget(targetAddr) {
						selectedItem = affinityConn
						selectedReason = fmt.Sprintf("亲和连接(目标:%s, streams:%d/%d)", targetAddr, streamCount, maxStreams)
						selectedScore = calcScore(affinityConn, streamCount) - 0.5 // 亲和连接有加成
						isAffinity = true
					}
				}
			}
		}

		// 3. 如果没有亲和连接，从活跃连接中找最优的
		if selectedItem == nil {
			for item, mgr := range p.managerByConn {
				if item.WS == nil || item.closed.Load() {
					continue
				}

				streamCount := mgr.GetStreamCount()
				hasSpace := streamCount < maxStreams
				if !hasSpace {
					continue
				}

				score := calcScore(item, streamCount)
				if selectedItem == nil || score < selectedScore {
					selectedItem = item
					selectedScore = score
					selectedReason = fmt.Sprintf("活跃连接(streams:%d/%d, rtt:%dms)",
						streamCount, maxStreams, time.Duration(item.RTT.Load()).Milliseconds())
				}
			}
		}
	}

	// 如果从空闲池选择了连接，增加活跃计数
	if isFromPool {
		if selectedItem.markActive() {
			atomic.AddInt32(&p.activeConnections, 1)
		}
	}

	p.mu.Unlock()

	// 释放锁后，批量关闭低质量连接（避免死锁）
	for _, conn := range lowQualityConns {
		conn.closed.Store(true)
		conn.WS.Close()
	}

	// 如果找到了连接，返回它
	if selectedItem != nil {
		if isFromPool || isAffinity {
			p.log.Debug("选择连接: [%s] %s (score:%.3f, affinity:%v)",
				formatConnID(selectedItem.ConnectionID), selectedReason, selectedScore, isAffinity)
		}
		return selectedItem, nil
	}

	// 没有可用连接，检查是否能新建
	currentSize := p.liveConnectionCount() + int(atomic.LoadInt32(&p.pendingConnections))
	if currentSize < p.cfg.MaxPoolSize {
		go p.createConnection("请求触发")
	}

	// 加入等待队列
	req := &connRequest{
		connCh: make(chan *ConnItem, 1),
		errCh:  make(chan error, 1),
	}

	select {
	case p.requestQueue <- req:
		select {
		case item := <-req.connCh:
			return item, nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// formatConnID 格式化连接 ID
func formatConnID(connID []byte) string {
	if len(connID) != 3 {
		return "??????"
	}
	return fmt.Sprintf("%02x%02x%02x", connID[0], connID[1], connID[2])
}

// ReleaseConnection 释放连接
func (p *ConnectionPool) ReleaseConnection(item *ConnItem) {
	p.mu.Lock()
	defer p.mu.Unlock()

	// 通过 StreamManager 检查活跃 stream 数量
	mgr, hasManager := p.managerByConn[item]
	streamCount := 0
	if hasManager {
		streamCount = mgr.GetStreamCount()
	}

	if streamCount == 0 {
		if item.markIdle() {
			atomic.AddInt32(&p.activeConnections, -1)
		}
		if item.closed.Load() {
			return
		}
		if _, exists := containsConn(p.pool, item); exists {
			return
		}

		// 没有活跃的 stream，放回池中以供重用
		// 注意：不删除 managerByConn 条目，因为 messageLoop 需要它来分发消息
		// 连接会在关闭时由 messageLoop 的 defer 函数清理

		// 有序插入：按质量评分降序插入
		score := atomic.LoadInt64(&item.QualityScore)
		insertPos := len(p.pool)
		for i := 0; i < len(p.pool); i++ {
			if atomic.LoadInt64(&p.pool[i].QualityScore) < score {
				insertPos = i
				break
			}
		}

		// 插入到正确位置
		p.pool = append(p.pool, nil)
		copy(p.pool[insertPos+1:], p.pool[insertPos:])
		p.pool[insertPos] = item

	}
	// 如果还有活跃的 stream，连接保持活跃状态，直到最后一个释放
}

// GetAllActiveConnections 获取所有活跃连接（包括空闲和正在使用的）
// 用于 metrics 暴露和流量统计
func (p *ConnectionPool) GetAllActiveConnections() []*ConnItem {
	p.mu.RLock()
	result := make([]*ConnItem, 0, len(p.pool)+len(p.managerByConn))
	result = append(result, p.pool...)

	for conn := range p.managerByConn {
		result = append(result, conn)
	}
	p.mu.RUnlock()

	// 在锁外进行去重（使用 map 来去重）
	seen := make(map[*ConnItem]struct{}, len(result))
	unique := make([]*ConnItem, 0, len(result))
	for _, conn := range result {
		if _, exists := seen[conn]; !exists {
			seen[conn] = struct{}{}
			unique = append(unique, conn)
		}
	}

	return unique
}

// RegisterStreamHandler 注册流处理器（支持目标地址亲和性）
func (p *ConnectionPool) RegisterStreamHandler(item *ConnItem, streamID byte, handler *StreamHandler, targetAddr string) {
	p.mu.Lock()
	defer p.mu.Unlock()

	// 获取或创建 StreamManager
	mgr, ok := p.managerByConn[item]
	if !ok {
		mgr = NewStreamManager(
			item,
			int(p.cfg.MaxStreamsPerConnection),
			p.cfg.GetDefaultWindowSize(),
			p.cfg.GetMinWindowSize(),
			p.cfg.GetMaxWindowSize(),
			p.cfg.GetWindowTimeout(),
		)
		p.managerByConn[item] = mgr
	}

	// 注册 handler
	mgr.RegisterHandler(streamID, handler)

	// 记录目标地址亲和性
	if targetAddr != "" && p.cfg.EnableMultiplex {
		// 添加目标到连接的目标集合
		item.AddTarget(targetAddr)
		// 建立目标到连接的映射
		p.targetToConn[targetAddr] = item

		p.log.Debug("记录目标亲和性: 目标=%s -> 连接=%s, Stream=%02x",
			targetAddr, formatConnID(item.ConnectionID), streamID)
	}
}

// AllocateStreamID 分配一个新的 Stream ID（阻塞等待可用）
// 返回分配的 Stream ID 和是否成功（false 表示超时）
func (p *ConnectionPool) AllocateStreamID(item *ConnItem, targetAddr string, timeout time.Duration) (byte, bool) {
	p.mu.Lock()

	// 获取或创建 StreamManager
	mgr, ok := p.managerByConn[item]
	if !ok {
		mgr = NewStreamManager(
			item,
			int(p.cfg.MaxStreamsPerConnection),
			p.cfg.GetDefaultWindowSize(),
			p.cfg.GetMinWindowSize(),
			p.cfg.GetMaxWindowSize(),
			p.cfg.GetWindowTimeout(),
		)
		p.managerByConn[item] = mgr
	}
	p.mu.Unlock()

	// 通过 StreamManager 分配 stream
	return mgr.AllocateStream(targetAddr, timeout)
}

// UnregisterStreamHandler 注销流处理器
// 返回目标地址（用于清理亲和性映射）和是否该连接已无活跃 stream
func (p *ConnectionPool) UnregisterStreamHandler(item *ConnItem, streamID byte) (targetAddr string, isEmpty bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	mgr, ok := p.managerByConn[item]
	if !ok {
		return "", true
	}

	targetAddr, isEmpty = mgr.UnregisterStream(streamID)

	// 如果该连接已无活跃 stream，清理亲和性映射
	if isEmpty && targetAddr != "" {
		delete(p.targetToConn, targetAddr)
		// 清理连接上的目标记录
		item.RemoveTarget(targetAddr)
	}

	return targetAddr, isEmpty
}

// maintainLoop 维护连接池大小
func (p *ConnectionPool) maintainLoop() {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.maintainPool()
		case <-p.stopChan:
			return
		}
	}
}

// maintainPool 维护连接池
func (p *ConnectionPool) maintainPool() {
	currentSize := p.liveConnectionCount() + int(atomic.LoadInt32(&p.pendingConnections))

	if currentSize < int(p.currentMinPoolSize) {
		go p.createConnection("维护补给")
		return
	}

	if currentSize >= p.cfg.MaxPoolSize {
		return
	}
	if !p.cfg.EnableDynamicPool {
		return
	}

	// 检查是否需要按需扩容
	needExpansion := false
	reason := ""

	// 1. 如果有等待队列，立即扩容
	if len(p.requestQueue) > 0 {
		needExpansion = true
		reason = fmt.Sprintf("等待队列(%d)", len(p.requestQueue))
	} else if p.cfg.EnableMultiplex && atomic.LoadInt32(&p.activeConnections) > 0 {
		// 2. 多路复用模式下：检查活跃连接的整体利用率
		p.mu.RLock()
		totalStreams := 0
		activeConnCount := 0
		maxStreams := int(p.cfg.MaxStreamsPerConnection)
		hasHighLoadConn := false // 是否有高负载连接

		// 流数阈值：maxStreams 的 60%，超过此值认为连接负载较高
		streamThreshold := max(int(float64(maxStreams)*0.6), 1)

		for item, mgr := range p.managerByConn {
			if item.WS == nil || item.closed.Load() {
				continue
			}
			streamCount := mgr.GetStreamCount()
			totalStreams += streamCount
			activeConnCount++

			// 只要有一个连接达到或超过阈值，就标记为高负载
			if streamCount >= streamThreshold {
				hasHighLoadConn = true
			}
		}
		p.mu.RUnlock()

		// 当存在高负载连接时，提前扩容（避免等到 100% 才触发）
		if activeConnCount > 0 && hasHighLoadConn {
			needExpansion = true
			reason = fmt.Sprintf("连接高负载(活跃:%d,总流:%d,阈值:%d)",
				activeConnCount, totalStreams, streamThreshold)
		}
	}

	if needExpansion {
		p.log.Info("触发按需扩容: %s", reason)
		go p.createConnection(fmt.Sprintf("按需扩容(%s)", reason))
	}
}

// cullLoop 清理过期连接
func (p *ConnectionPool) cullLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.cullOldConnections()
		case <-p.stopChan:
			return
		}
	}
}

// cullOldConnections 清理过期连接
func (p *ConnectionPool) cullOldConnections() {
	p.mu.Lock()
	beforeSize := len(p.pool)
	if beforeSize == 0 {
		p.mu.Unlock()
		return
	}

	now := time.Now()
	keepMin := min(p.cfg.MinPoolSize, int(p.currentMinPoolSize))

	newPool := make([]*ConnItem, 0, beforeSize)
	expired := make([]*ConnItem, 0)

	// 按创建时间排序
	for _, item := range p.pool {
		if len(expired) < beforeSize-keepMin && now.Sub(item.CreatedAt) > p.cfg.GetConnectionTTL() {
			item.closed.Store(true)
			expired = append(expired, item)
		} else {
			newPool = append(newPool, item)
		}
	}

	p.pool = newPool
	p.mu.Unlock()

	for _, item := range expired {
		item.WS.Close() // nolint:errcheck
	}
	if len(expired) > 0 {
		p.log.Debug("清理过期连接: 清除%d个 (%d -> %d)", len(expired), beforeSize, len(newPool))
	}
}

// statsLoop 统计日志
func (p *ConnectionPool) statsLoop() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.logStats()
		case <-p.stopChan:
			return
		}
	}
}

// logStats 输出统计日志
func (p *ConnectionPool) logStats() {
	total := p.liveConnectionCount() + int(atomic.LoadInt32(&p.pendingConnections))

	if total == 0 {
		return
	}

	idle := len(p.pool)
	active := int(atomic.LoadInt32(&p.activeConnections))
	pending := int(atomic.LoadInt32(&p.pendingConnections))
	queued := len(p.requestQueue)

	// 收集所有连接信息（包含 RTT 和 Stream 数）
	type connInfo struct {
		id      string
		rtt     time.Duration
		streams int
	}

	var allConns []connInfo

	// 从 managerByConn 获取所有连接
	p.mu.RLock()
	for item, mgr := range p.managerByConn {
		connIDStr := fmt.Sprintf("%02x%02x%02x", item.ConnectionID[0], item.ConnectionID[1], item.ConnectionID[2])
		allConns = append(allConns, connInfo{
			id:      connIDStr,
			rtt:     time.Duration(item.RTT.Load()),
			streams: mgr.GetStreamCount(),
		})
	}
	p.mu.RUnlock()

	if len(allConns) > 0 {
		// 按 RTT 排序
		sort.Slice(allConns, func(i, j int) bool {
			return allConns[i].rtt < allConns[j].rtt
		})

		var connParts []string
		for _, c := range allConns {
			status := "I"
			if c.streams > 0 {
				status = "A"
			}
			connParts = append(connParts, fmt.Sprintf("[%s:%dms:%ds:%s]",
				c.id, c.rtt.Milliseconds(), c.streams, status))
		}

		// 合并输出：连接池状态 + 所有连接 RTT 信息
		p.log.Debug("连接池: 空闲%d 活跃%d 建立中%d 等队列%d | 连接: %s",
			idle, active, pending, queued, strings.Join(connParts, " "))
	} else {
		p.log.Debug("连接池: 空闲%d 活跃%d 建立中%d 等队列%d",
			idle, active, pending, queued)
	}
}

// heartbeatLoop 心跳检测
func (p *ConnectionPool) heartbeatLoop() {
	ticker := time.NewTicker(p.cfg.GetHeartbeatInterval())
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.sendHeartbeat()
		case <-p.stopChan:
			return
		}
	}
}

// sendHeartbeat 发送心跳
func (p *ConnectionPool) sendHeartbeat() {
	now := time.Now()
	var pendingItems []*ConnItem
	var timeoutItems []*ConnItem

	p.mu.Lock()
	if p.pendingHeartbeats == nil {
		p.pendingHeartbeats = make(map[string]time.Time)
	}

	for item := range p.managerByConn {
		if item.WS != nil && !item.closed.Load() {
			connIDStr := fmt.Sprintf("%02x%02x%02x", item.ConnectionID[0], item.ConnectionID[1], item.ConnectionID[2])

			// 检查是否有待响应的心跳
			if lastPing, ok := p.pendingHeartbeats[connIDStr]; ok {
				if now.Sub(lastPing) > p.cfg.GetHeartbeatTimeout() {
					delete(p.pendingHeartbeats, connIDStr)
					timeoutItems = append(timeoutItems, item)
				}
			} else {
				// 先登记待响应状态，再在锁外执行可能阻塞的写操作。
				p.pendingHeartbeats[connIDStr] = now
				pendingItems = append(pendingItems, item)
			}
		}
	}
	p.mu.Unlock()

	for _, item := range timeoutItems {
		atomic.AddInt64(&item.HeartbeatFailures, 1)
		p.RetireConnection(item, "心跳超时")
	}
	for _, item := range pendingItems {
		if err := item.WriteMessage(websocket.PingMessage, nil); err != nil {
			atomic.AddInt64(&item.HeartbeatFailures, 1)
			p.RetireConnection(item, fmt.Sprintf("心跳写入失败: %v", err))
		}
	}

	if len(timeoutItems) > 0 && p.log != nil {
		p.log.Debug("心跳超时: %d 个连接", len(timeoutItems))
	}
}

// Reconnect closes all WebSockets so the maintenance loop recreates them on
// the currently available network. The VPN/TUN interface remains untouched.
func (p *ConnectionPool) Reconnect(reason string) {
	p.mu.RLock()
	items := make([]*ConnItem, 0, len(p.managerByConn))
	for item := range p.managerByConn {
		items = append(items, item)
	}
	p.mu.RUnlock()

	if len(items) == 0 {
		return
	}
	p.log.Info("网络变化，重建 %d 条 WebSocket 连接: %s", len(items), reason)
	for _, item := range items {
		p.RetireConnection(item, reason)
	}
}

// rateUpdateLoop 定期更新所有连接的速率统计
func (p *ConnectionPool) rateUpdateLoop() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.UpdateAllRates()
		case <-p.stopChan:
			return
		}
	}
}

// dynamicPoolLoop 动态调整连接池大小
func (p *ConnectionPool) dynamicPoolLoop() {
	ticker := time.NewTicker(p.cfg.GetDynamicPoolInterval())
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.adjustPoolSize()
		case <-p.stopChan:
			return
		}
	}
}

// adjustPoolSize 动态调整连接池大小
func (p *ConnectionPool) adjustPoolSize() {
	p.mu.RLock()
	idle := len(p.pool)
	active := int(atomic.LoadInt32(&p.activeConnections))
	total := idle + active
	p.mu.RUnlock()

	if total == 0 {
		return
	}

	activeRatio := float64(active) / float64(total)
	queued := len(p.requestQueue)
	utilizationRatio := float64(active+queued) / float64(total+queued)

	newSize := int(p.currentMinPoolSize)
	var reason string

	if utilizationRatio > p.cfg.DynamicPoolHighThreshold {
		newSize = min(int(float64(p.currentMinPoolSize)*1.5), p.cfg.DynamicPoolMaxSize)
		reason = fmt.Sprintf("高负载 (利用率 %.1f%%)", utilizationRatio*100)
	} else if activeRatio < p.cfg.DynamicPoolLowThreshold && int(p.currentMinPoolSize) > p.cfg.DynamicPoolMinSize {
		newSize = max(int(float64(p.currentMinPoolSize)*0.7), p.cfg.DynamicPoolMinSize)
		reason = fmt.Sprintf("低负载 (活跃率 %.1f%%)", activeRatio*100)
	} else {
		return
	}

	if newSize != int(p.currentMinPoolSize) {
		p.log.Info("调整 minPoolSize: %d -> %d (%s)", p.currentMinPoolSize, newSize, reason)
		atomic.StoreInt32(&p.currentMinPoolSize, int32(newSize))

		// 扩容时创建新连接
		if newSize > total {
			p.log.Info("触发动态扩容: %d -> %d (%s)", total, newSize, reason)
			needed := newSize - total
			for i := 0; i < min(needed, 5); i++ {
				go p.createConnection("动态扩容")
			}
		}
	}
}

// UpdateAllRates 更新所有连接的速率统计
// 直接访问 managerByConn，避免调用 GetAllActiveConnections() 造成额外开销
func (p *ConnectionPool) UpdateAllRates() {
	now := time.Now()

	p.mu.RLock()
	conns := make([]*ConnItem, 0, len(p.managerByConn))
	for conn := range p.managerByConn {
		conns = append(conns, conn)
	}
	p.mu.RUnlock()

	for _, conn := range conns {
		conn.Traffic.UpdateRates(now)
	}
}

// GetStats 获取统计信息指针（用于原子操作访问）
func (p *ConnectionPool) GetStats() *PoolStats {
	return &p.stats
}

// Close 关闭连接池
func (p *ConnectionPool) Close() {
	close(p.stopChan)

	// 在锁内收集所有需要关闭的连接和 manager，然后在锁外执行关闭操作。
	// 原因：HandleConnectionClose 会触发 OnClose 回调，回调中的 cleanup() 会
	// 调用 UnregisterStreamHandler/ReleaseConnection，它们都需要获取 p.mu.Lock()。
	// 如果在持有 p.mu 时调用，就会死锁。
	p.mu.Lock()
	poolItems := p.pool
	managerItems := make(map[*ConnItem]*StreamManager, len(p.managerByConn))
	for item, mgr := range p.managerByConn {
		// 复制 manager 引用，但排除已在 pool 中的 item（避免重复关闭）
		if _, inPool := containsConn(poolItems, item); !inPool {
			managerItems[item] = mgr
		}
	}
	// 清空 pool 和 managerByConn，防止 messageLoop defer 重复操作
	p.pool = nil
	p.managerByConn = nil
	p.mu.Unlock()

	// 锁外关闭连接（WS.Close 会触发 messageLoop 退出，不影响锁）
	for _, item := range poolItems {
		item.closed.Store(true)
		item.WS.Close()
	}
	for item, mgr := range managerItems {
		item.closed.Store(true)
		mgr.HandleConnectionClose()
		item.WS.Close()
	}
}

// containsConn 检查连接是否在列表中，返回索引和是否存在
func containsConn(list []*ConnItem, target *ConnItem) (int, bool) {
	for i, item := range list {
		if item == target {
			return i, true
		}
	}
	return -1, false
}

// GetStream 获取指定的 Stream 对象（用于流控）
func (p *ConnectionPool) GetStream(conn *ConnItem, streamID byte) *Stream {
	if conn == nil {
		return nil
	}

	p.mu.RLock()
	mgr, exists := p.managerByConn[conn]
	p.mu.RUnlock()

	if !exists || mgr == nil {
		return nil
	}

	mgr.mu.RLock()
	defer mgr.mu.RUnlock()

	return mgr.streams[streamID]
}

// congestionControlLoop 拥塞控制循环
func (p *ConnectionPool) congestionControlLoop() {
	ticker := time.NewTicker(p.cfg.GetCongestionControlInterval())
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			p.adjustAllStreamsWindow()
		case <-p.stopChan:
			return
		}
	}
}

// adjustAllStreamsWindow 调整所有活跃 Stream 的窗口大小
func (p *ConnectionPool) adjustAllStreamsWindow() {
	p.mu.RLock()
	managers := make([]*StreamManager, 0, len(p.managerByConn))
	for _, mgr := range p.managerByConn {
		managers = append(managers, mgr)
	}
	p.mu.RUnlock()

	adjustedCount := 0
	for _, mgr := range managers {
		mgr.mu.RLock()
		streams := make([]*Stream, 0, len(mgr.streams))
		for _, s := range mgr.streams {
			streams = append(streams, s)
		}
		mgr.mu.RUnlock()

		for _, stream := range streams {
			stream.AdjustWindowSize()
			adjustedCount++
		}
	}

	if adjustedCount > 0 {
		p.log.Debug("拥塞控制: 调整了 %d 个 Stream 的窗口大小", adjustedCount)
	}
}
