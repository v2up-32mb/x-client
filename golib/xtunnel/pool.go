package xtunnel

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"math/rand"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"xclient/shared/ech"
	"xclient/shared/routing"
	common "xclient/xtunnel/protocol"
	"xclient/xtunnel/relay"
)

// writeJob 写入任务
type writeJob struct {
	msgType int
	data    []byte
	size    int
}

// clientConnState 客户端连接状态
type clientConnState struct {
	id           string
	target       string
	uplinkChID   int
	downlinkChID int
	connected    chan bool

	// 内部使用字段
	reqType    string
	tcpConn    net.Conn
	udpAssoc   *udpAssociation
	uplink     int
	downlink   int32 // 使用 atomic 操作，避免每个 TCPData 包都加全局锁
	lastCh     int
	start      time.Time
	clientAddr string
	closed     bool
	pair       *HotChannelPair
}

// clientPool 客户端连接池
type clientPool struct {
	globalQueueBytes int64
	globalQueueLimit int64
	nextChannel      uint64
	bytesSent        uint64
	bytesReceived    uint64

	config        *Config
	ctx           context.Context
	cancel        context.CancelFunc
	clientID      string
	bypassMatcher *routing.Matcher
	relayManager  *relay.RelayNodeManager
	echManager    *ech.EchManager
	pairWarmer    *PairWarmer

	wsConnsMu       sync.RWMutex
	wsConns         []*websocket.Conn
	writeQueues     []chan writeJob
	connsWriteMutex []sync.Mutex

	mu    sync.RWMutex
	conns map[string]*clientConnState

	relayCount int
	socks5Sem  chan struct{} // SOCKS5 连接信号量

	// 背压控制
	backpressureState int32         // 原子操作，背压状态
	resumeCh          chan struct{} // 背压恢复信号（带缓冲，避免发送阻塞）

	// 通道就绪/失效通知（用于 PairWarmer）
	chReadyCh   chan int
	chInvalidCh chan int

	// 通道就绪计数（用于延迟启动 PairWarmer）
	readyChannels int32

	// 主动重连信号（网络切换时由 NotifyNetworkChanged 触发）
	reconnectCh chan struct{}

	// 本地代理监听器（SOCKS5/HTTP），Shutdown 时必须关闭释放端口
	listenersMu sync.Mutex
	listeners   []net.Listener

	// 优雅关闭
	shutdownOnce sync.Once
	dialWG       sync.WaitGroup // 跟踪 dialAndServe goroutine 退出
}

// newClientPool 创建新的连接池
func newClientPool(cfg *Config, ctx context.Context, cancel context.CancelFunc) (*clientPool, error) {
	limit := int64(cfg.BackpressureLimitBytes)
	if limit <= 0 {
		limit = 1024 * 1024 // 默认 1MB
	}
	p := &clientPool{
		config:            cfg,
		ctx:               ctx,
		cancel:            cancel,
		clientID:          cfg.ClientID,
		echManager:        newSharedEchManager(cfg),
		relayManager:      relay.NewRelayNodeManager(),
		wsConns:           make([]*websocket.Conn, cfg.Connections),
		writeQueues:       make([]chan writeJob, cfg.Connections),
		connsWriteMutex:   make([]sync.Mutex, cfg.Connections),
		conns:             make(map[string]*clientConnState),
		globalQueueLimit:  limit,
		nextChannel:       1,
		backpressureState: int32(common.BackpressureNormal),
		resumeCh:          make(chan struct{}, 1),
		chReadyCh:         make(chan int, 64),
		chInvalidCh:       make(chan int, 64),
		reconnectCh:       make(chan struct{}, 1),
	}

	if cfg.EnableHotPair {
		p.pairWarmer = NewPairWarmer(p, cfg)
	}

	// 初始化 SOCKS5 连接信号量
	if cfg.MaxSOCKS5Connections > 0 {
		p.socks5Sem = make(chan struct{}, cfg.MaxSOCKS5Connections)
	}

	for i := 0; i < cfg.Connections; i++ {
		p.writeQueues[i] = make(chan writeJob, 4096)
	}

	return p, nil
}

// Start 启动连接池
func (p *clientPool) Start(relayNodes []string) {
	// 网络切换重连监听（Android NotifyNetworkChanged）
	go p.reconnectLoop()

	// 启动 ECH 定时刷新（配置懒加载，首次拨号时按需获取）
	if p.config.EnableECH {
		p.echManager.StartAutoRefresh()
	}

	// 添加中转节点
	for _, addr := range relayNodes {
		if err := p.relayManager.AddNode(addr, "443"); err != nil {
			sysLog.Info("[客户端] 添加中转节点失败: %v", err)
		}
	}
	p.relayManager.Start()

	// 保存中转地址数量,用于后续按需申请节点
	p.relayCount = len(relayNodes)

	if p.relayCount > 0 {
		// 初始化时按约定申请指定个数的中转节点
		sysLog.Info("[客户端] 初始化:按约定申请 %d 个中转节点", p.relayCount)
		bestNodes := p.relayManager.SelectBestNodes(p.relayCount)

		if len(bestNodes) > 0 {
			// 使用申请到的节点建立连接
			sysLog.Info("[客户端] 初始化成功申请到 %d 个中转节点", len(bestNodes))
			for _, node := range bestNodes {
				latency := node.Latency.Milliseconds()
				sysLog.Info("[客户端] 中转节点: %s (评分: %.2f, 延迟: %dms)", node.IP, node.Score, latency)
			}
			sysLog.Info("[客户端] 每个节点建立 %d 条连接", p.config.Connections)
			sysLog.Info("[客户端] 共计建立 %d 条 WebSocket 连接", len(bestNodes)*p.config.Connections)

			// 根据申请到的节点数量重新分配连接池
			total := len(bestNodes) * p.config.Connections
			p.wsConnsMu.Lock()
			p.wsConns = make([]*websocket.Conn, total)
			p.wsConnsMu.Unlock()

			// 重新分配写队列
			newQueues := make([]chan writeJob, total)
			for i := 0; i < total; i++ {
				newQueues[i] = make(chan writeJob, 4096)
			}
			p.writeQueues = newQueues
			p.connsWriteMutex = make([]sync.Mutex, total)

			// 为每个申请到的节点建立 connectionNum 条连接
			for nodeIdx, node := range bestNodes {
				for j := 0; j < p.config.Connections; j++ {
					chIdx := nodeIdx*p.config.Connections + j
					p.goDialAndServe(chIdx, node.IP)
				}
			}

			// 启动 PairWarmer 延迟监听器（在所有通道就绪后才启动）
			if p.config.EnableHotPair && p.pairWarmer != nil {
				go p.delayedStartPairWarmer(total)
			}
			return
		}

		// 如果所有节点初始测速都失败
		sysLog.Info("[客户端] 所有中转节点初始测速失败,直连服务端,建立 %d 条连接", p.config.Connections)
	}

	// 没有指定中转节点或所有节点不可用,直连服务端
	sysLog.Info("[客户端] 未使用中转节点,直连服务端,建立 %d 条连接", p.config.Connections)
	for i := 0; i < p.config.Connections; i++ {
		p.goDialAndServe(i, "")
	}

	// 启动 PairWarmer 延迟监听器（在所有通道就绪后才启动）
	if p.config.EnableHotPair && p.pairWarmer != nil {
		go p.delayedStartPairWarmer(p.config.Connections)
	}
}

// reconnectLoop 响应主动重连信号：关闭所有当前 WebSocket，
// 由 dialAndServe 循环在新网络上立即重建通道（无需等待 TCP 死链检测）。
func (p *clientPool) reconnectLoop() {
	for {
		select {
		case <-p.ctx.Done():
			return
		case <-p.reconnectCh:
			sysLog.Info("[客户端] 收到重连信号，强制重建所有通道")
			p.wsConnsMu.Lock()
			for _, ws := range p.wsConns {
				if ws != nil {
					_ = ws.Close()
				}
			}
			p.wsConnsMu.Unlock()
		}
	}
}

// Reconnect 请求连接池立即重建通道（Android 网络切换/手动重连）。
func (p *clientPool) Reconnect(reason string) {
	if p.reconnectCh == nil {
		return
	}
	sysLog.Info("[客户端] 重连请求: %s", reason)
	select {
	case p.reconnectCh <- struct{}{}:
	default:
	}
}

// trackListener 记录本地代理监听器，供 Shutdown 统一关闭。
func (p *clientPool) trackListener(l net.Listener) {
	p.listenersMu.Lock()
	p.listeners = append(p.listeners, l)
	p.listenersMu.Unlock()
}

// Shutdown 关闭连接池
func (p *clientPool) Shutdown() {
	sysLog.Info("[客户端] 正在关闭所有连接...")

	p.shutdownOnce.Do(func() {
		p.shutdown()
	})
}

// shutdown 执行实际的关闭逻辑（仅执行一次，由 Shutdown 通过 sync.Once 保护）
func (p *clientPool) shutdown() {
	// 0. 关闭本地代理监听器，释放端口（Android 重启场景必需）
	p.listenersMu.Lock()
	for _, l := range p.listeners {
		_ = l.Close()
	}
	p.listeners = nil
	p.listenersMu.Unlock()

	// 1. 停止 ECH 定时刷新
	p.echManager.StopAutoRefresh()

	// 2. 停止中转节点管理器
	p.relayManager.Stop()

	// 3. 取消 context,通知所有 goroutine 退出
	p.cancel()

	// 4. 关闭所有写队列,停止写入
	for i, q := range p.writeQueues {
		if q != nil {
			close(q)
			p.writeQueues[i] = nil
		}
	}

	// 5. 优雅关闭所有 WebSocket 连接
	p.wsConnsMu.Lock()

	var wg sync.WaitGroup
	for i, ws := range p.wsConns {
		if ws != nil {
			wg.Add(1)
			go func(conn *websocket.Conn, chID int, id int) {
				defer wg.Done()
				p.connsWriteMutex[id].Lock()
				// 发送正常的关闭帧
				_ = conn.WriteMessage(websocket.CloseMessage,
					websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
				p.connsWriteMutex[id].Unlock()
				// 等待对方响应或超时
				_ = conn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
				_ = conn.Close()
				sysLog.Info("[客户端] 通道 %d 已关闭", chID)
			}(ws, i+1, i)
			p.wsConns[i] = nil
		}
	}
	p.wsConnsMu.Unlock()
	wg.Wait()

	// 6. 等待所有 dialAndServe goroutine 退出（带超时保护，避免卡死）
	doneCh := make(chan struct{})
	go func() { p.dialWG.Wait(); close(doneCh) }()
	select {
	case <-doneCh:
	case <-time.After(2 * time.Second):
		sysLog.Info("[客户端] 等待拨号 goroutine 退出超时，强制返回")
	}

	sysLog.Info("[客户端] 所有连接已关闭")
}

// goDialAndServe 启动 dialAndServe goroutine 并纳入 WaitGroup 跟踪，确保 Shutdown 时能等待其退出。
func (p *clientPool) goDialAndServe(idx int, ip string) {
	p.dialWG.Add(1)
	go func() {
		defer p.dialWG.Done()
		p.dialAndServe(idx, ip)
	}()
}

// delayedStartPairWarmer 等待所有通道就绪后再启动 PairWarmer
func (p *clientPool) delayedStartPairWarmer(expectedCount int) {
	sysLog.Info("[PairWarmer] 等待 %d 个通道就绪后启动...", expectedCount)
	timeout := time.NewTimer(30 * time.Second)
	defer timeout.Stop()

	checkTicker := time.NewTicker(500 * time.Millisecond)
	defer checkTicker.Stop()

	for {
		select {
		case <-p.ctx.Done():
			sysLog.Info("[PairWarmer] 启动取消（context 已关闭）")
			return
		case <-timeout.C:
			ready := int(atomic.LoadInt32(&p.readyChannels))
			if ready > 0 {
				sysLog.Info("[PairWarmer] 启动超时，但已有 %d/%d 通道就绪，继续启动", ready, expectedCount)
				p.pairWarmer.Run()
			} else {
				sysLog.Info("[PairWarmer] 启动超时且无就绪通道，放弃启动")
			}
			return
		case <-checkTicker.C:
			ready := int(atomic.LoadInt32(&p.readyChannels))
			if ready >= expectedCount {
				sysLog.Info("[PairWarmer] 全部 %d 个通道已就绪，启动 PairWarmer", ready)
				p.pairWarmer.Run()
				return
			}
		}
	}
}

// chIndex 获取通道索引
func (p *clientPool) chIndex(chID int) (int, error) {
	idx := chID - 1
	if idx < 0 || idx >= len(p.writeQueues) {
		return -1, fmt.Errorf("无效的通道ID %d", chID)
	}
	return idx, nil
}

var (
	dialAndServeMaxRetries = 20
	dialAndServeBaseDelay  = 3 * time.Second
	dialAndServeMaxDelay   = 60 * time.Second

	// writeQueueSize 单通道写队列容量（会话级队列）
	writeQueueSize = 4096
)

// fastRetryState 记录快速重连状态
type fastRetryState struct {
	consecutiveFailures int
	lastFailure         time.Time
}

func (f *fastRetryState) OnFailure() {
	f.consecutiveFailures++
	f.lastFailure = time.Now()
}

func (f *fastRetryState) OnSuccess() {
	f.consecutiveFailures = 0
	f.lastFailure = time.Time{}
}

func (f *fastRetryState) ShouldFastRetry(maxConsecutive int) bool {
	return f.consecutiveFailures < maxConsecutive
}

func (f *fastRetryState) ShouldFastRetryWithinWindow(maxConsecutive int, window time.Duration) bool {
	if f.consecutiveFailures >= maxConsecutive {
		return false
	}
	if f.lastFailure.IsZero() {
		return false
	}
	return time.Since(f.lastFailure) <= window
}

// dialAndServe 连接并服务 WebSocket
func (p *clientPool) dialAndServe(idx int, ip string) {
	chID := idx + 1
	var relayInfo string
	var lastIP string
	firstAttempt := true // 标记是否是首次尝试

	// 重试控制
	retryCount := 0
	slowRetryMode := false
	frs := &fastRetryState{}
	fastRetryCount := 0

	// 指数退避参数
	currentDelay := dialAndServeBaseDelay

	for {
		// 检查是否需要退出
		select {
		case <-p.ctx.Done():
			sysLog.Info("[客户端] 通道 %d 已收到退出信号", chID)
			return
		default:
		}

		// 检查重试次数
		if retryCount >= dialAndServeMaxRetries && !slowRetryMode {
			sysLog.Info("[客户端] 通道 %d 重试次数超限 (%d 次)，转入慢速持续重试", chID, dialAndServeMaxRetries)
			slowRetryMode = true
			retryCount = 0
			currentDelay = dialAndServeMaxDelay
		}

		// 如果有中转节点配置,在重连时申请新节点
		// 修改: 只要不是首次尝试且有中转节点,就尝试换节点
		if p.relayCount > 0 && !firstAttempt {
			// 修复: 排除当前失败的节点，而不是排除所有健康节点
			excludeIPs := []string{}
			if lastIP != "" {
				excludeIPs = append(excludeIPs, lastIP)
			}
			if ip != "" && ip != lastIP {
				excludeIPs = append(excludeIPs, ip)
			}
			newNode := p.relayManager.SelectNodeExcluding(excludeIPs)
			if newNode != nil {
				sysLog.Info("[客户端] 通道 %d 重连:申请新中转节点 %s (评分: %.2f, 延迟: %dms)",
					chID, newNode.IP, newNode.Score, newNode.Latency.Milliseconds())
				ip = newNode.IP
				relayInfo = fmt.Sprintf(" [中转: %s]", ip)
			} else {
				sysLog.Info("[客户端] 通道 %d 重连:无可用的健康中转节点,使用原有节点", chID)
			}
		}
		firstAttempt = false // 首次尝试后标记为 false

		wsConn, err := p.dialWebSocket(chID, ip)
		if err != nil {
			frs.OnFailure()
			retryCount++
			if relayInfo == "" && ip != "" {
				relayInfo = fmt.Sprintf(" [中转: %s]", ip)
			}
			sysLog.Info("[客户端] 通道 %d%s 连接失败: %v (重试 %d/%d)", chID, relayInfo, err, retryCount, dialAndServeMaxRetries)

			// 标记节点失败
			if ip != "" && p.relayCount > 0 {
				p.relayManager.MarkNodeFailed(ip)
			}

			// 快速重试：在窗口内且未超过连续阈值时，使用短延迟抖动重试
			if !slowRetryMode && frs.ShouldFastRetryWithinWindow(p.config.MaxFastRetryConsecutive, p.config.FastRetryWindow) && fastRetryCount < p.config.FastRetryAttempts {
				fastRetryCount++
				jitter := time.Duration(rand.Intn(300)) * time.Millisecond
				delay := 100*time.Millisecond + jitter
				select {
				case <-p.ctx.Done():
					return
				case <-time.After(delay):
					continue
				}
			}
			fastRetryCount = 0

			// 计算退避时间（指数退避）
			if currentDelay < dialAndServeMaxDelay {
				currentDelay = time.Duration(float64(currentDelay) * 1.5)
				if currentDelay > dialAndServeMaxDelay {
					currentDelay = dialAndServeMaxDelay
				}
			}

			// 检查是否需要退出（避免在重连延迟时阻塞）
			select {
			case <-p.ctx.Done():
				return
			case <-time.After(currentDelay):
				continue
			}
		}

		// 连接成功，重置重试计数和退避时间
		retryCount = 0
		slowRetryMode = false
		fastRetryCount = 0
		currentDelay = dialAndServeBaseDelay
		frs.OnSuccess()

		// 标记节点成功并计入负载（gcm 风格负载均衡）
		if ip != "" && p.relayCount > 0 {
			p.relayManager.MarkNodeSuccess(ip)
			p.relayManager.Acquire(ip)
		}

		if ip != "" {
			relayInfo = fmt.Sprintf(" [中转: %s]", ip)
			lastIP = ip
		}
		sysLog.Info("[客户端] 通道 %d%s 已连接", chID, relayInfo)
		// 会话级写队列：每次连接使用独立队列，旧连接遗留的 writeWorker
		// 只能消费旧队列（其连接已关闭，很快自行退出），不会与新连接的
		// writeWorker 抢数据包（修复断线重连后请求被旧 worker 吞掉的问题）。
		queue := make(chan writeJob, writeQueueSize)
		p.wsConnsMu.Lock()
		p.wsConns[idx] = wsConn
		p.writeQueues[idx] = queue
		p.wsConnsMu.Unlock()

		// 先启动写工作者，确保 availableChannels 返回该通道时已有消费者就绪
		go p.writeWorker(idx, wsConn, queue)

		// 增加就绪通道计数
		atomic.AddInt32(&p.readyChannels, 1)

		// 非阻塞发送就绪通知
		select {
		case p.chReadyCh <- chID:
		default:
		}

		p.handleChannel(chID, wsConn)

		_ = wsConn.Close()

		p.wsConnsMu.Lock()
		p.wsConns[idx] = nil
		p.wsConnsMu.Unlock()

		// 减少就绪通道计数
		atomic.AddInt32(&p.readyChannels, -1)

		// 非阻塞发送失效通知
		select {
		case p.chInvalidCh <- chID:
		default:
		}
		if p.pairWarmer != nil {
			p.pairWarmer.InvalidateChannel(chID)
		}

		p.cleanupChannel(chID)

		// 释放节点负载占用
		if ip != "" && p.relayCount > 0 {
			p.relayManager.Release(ip)
		}

		sysLog.Info("[客户端] 通道 %d%s 断开,重连中...", chID, relayInfo)
		select {
		case <-p.ctx.Done():
			return
		case <-time.After(p.config.ReconnectDelay):
		}
	}
}

// reserveQueueBytes 预留写队列字节数，超限时不修改计数并返回 false
func (p *clientPool) reserveQueueBytes(size int64) bool {
	if size <= 0 {
		return true
	}
	for {
		current := atomic.LoadInt64(&p.globalQueueBytes)
		if current+size > p.globalQueueLimit {
			return false
		}
		if atomic.CompareAndSwapInt64(&p.globalQueueBytes, current, current+size) {
			return true
		}
	}
}

// releaseQueueBytes 释放写队列字节数，并保证计数不会小于 0
func (p *clientPool) releaseQueueBytes(size int64) {
	if size <= 0 {
		return
	}
	for {
		current := atomic.LoadInt64(&p.globalQueueBytes)
		next := current - size
		if next < 0 {
			next = 0
		}
		if atomic.CompareAndSwapInt64(&p.globalQueueBytes, current, next) {
			return
		}
	}
}

// writeWorker 写入协程
func (p *clientPool) writeWorker(id int, conn *websocket.Conn, queue chan writeJob) {
	ticker := time.NewTicker(p.config.PingInterval)
	defer ticker.Stop()

	// 退出时尽量回收尚未处理的队列字节数
	defer func() {
		if queue != nil {
			for {
				select {
				case j, ok := <-queue:
					if !ok {
						return
					}
					p.releaseQueueBytes(int64(j.size))
				default:
					return
				}
			}
		}
	}()

	var pending *writeJob
	pendingReleased := false
	for {
		var job writeJob
		released := false
		if pending != nil {
			job = *pending
			pending = nil
			released = pendingReleased
			pendingReleased = false
		} else {
			select {
			case <-p.ctx.Done():
				return
			case j, ok := <-queue:
				if !ok {
					// 队列已关闭,正常退出
					return
				}
				job = j
			case <-ticker.C:
				p.connsWriteMutex[id].Lock()
				_ = conn.SetWriteDeadline(time.Now().Add(p.config.WriteTimeout))
				if err := conn.WriteMessage(websocket.PingMessage, []byte{}); err != nil {
					sysLog.Info("[客户端] 通道 %d ping发送失败: %v", id+1, err)
					p.connsWriteMutex[id].Unlock()
					_ = conn.Close()
					return
				}
				_ = conn.SetWriteDeadline(time.Time{})
				p.connsWriteMutex[id].Unlock()
				continue
			}
		}

		if !released {
			p.releaseQueueBytes(int64(job.size))
		}

		// 非二进制消息直接写
		if job.msgType != websocket.BinaryMessage {
			p.connsWriteMutex[id].Lock()
			_ = conn.SetWriteDeadline(time.Now().Add(p.config.WriteTimeout))
			if err := conn.WriteMessage(job.msgType, job.data); err != nil {
				p.connsWriteMutex[id].Unlock()
				_ = conn.Close()
				return
			}
			p.addSentBytes(len(job.data))
			_ = conn.SetWriteDeadline(time.Time{})
			p.connsWriteMutex[id].Unlock()
			continue
		}

		// TCPData 聚合:减少帧数
		t, connID, meta, payload, err := common.DecodeMessage(job.data)
		if err != nil || t != common.MsgTCPData {
			p.connsWriteMutex[id].Lock()
			_ = conn.SetWriteDeadline(time.Now().Add(p.config.WriteTimeout))
			if err := conn.WriteMessage(job.msgType, job.data); err != nil {
				p.connsWriteMutex[id].Unlock()
				_ = conn.Close()
				return
			}
			p.addSentBytes(len(job.data))
			_ = conn.SetWriteDeadline(time.Time{})
			p.connsWriteMutex[id].Unlock()
			continue
		}

		maxAgg := p.config.ReadBufferSize * 4
		total := len(payload)
		parts := [][]byte{payload}

	AggLoop:
		for {
			select {
			case next, ok := <-queue:
				if !ok {
					// 队列已关闭,正常退出
					break AggLoop
				}
				p.releaseQueueBytes(int64(next.size))
				if next.msgType != websocket.BinaryMessage {
					pending = &next
					pendingReleased = true
					break AggLoop
				}
				tt, cid, mm, pl, e := common.DecodeMessage(next.data)
				if e != nil || tt != common.MsgTCPData || cid != connID || len(mm) != 0 {
					pending = &next
					pendingReleased = true
					break AggLoop
				}
				if total+len(pl) > maxAgg {
					pending = &next
					pendingReleased = true
					break AggLoop
				}
				parts = append(parts, pl)
				total += len(pl)
			default:
				break AggLoop
			}
		}

		var merged []byte
		if len(parts) == 1 {
			merged = parts[0]
		} else {
			merged = make([]byte, total)
			off := 0
			for _, pt := range parts {
				copy(merged[off:], pt)
				off += len(pt)
			}
		}

		p.connsWriteMutex[id].Lock()
		_ = conn.SetWriteDeadline(time.Now().Add(p.config.WriteTimeout))
		encoded := common.EncodeMessage(common.MsgTCPData, connID, meta, merged)
		if err := conn.WriteMessage(websocket.BinaryMessage, encoded); err != nil {
			p.connsWriteMutex[id].Unlock()
			_ = conn.Close()
			return
		}
		p.addSentBytes(len(encoded))
		_ = conn.SetWriteDeadline(time.Time{})
		p.connsWriteMutex[id].Unlock()
	}
}

// writeControlDirect 直接发送控制帧（Ping/Pong/Close），不经过写队列，也不受背压影响。
// 控制帧是 WebSocket 保活机制的一部分，不能在背压暂停时阻塞 readLoop。
func (p *clientPool) writeControlDirect(chID int, msgType int, data []byte) error {
	idx, err := p.chIndex(chID)
	if err != nil {
		return err
	}

	p.wsConnsMu.RLock()
	conn := p.wsConns[idx]
	p.wsConnsMu.RUnlock()
	if conn == nil {
		return fmt.Errorf("通道 %d 不可用", chID)
	}

	deadline := time.Now().Add(p.config.WriteTimeout)
	// WriteControl 自身保证并发安全，且可与其他 WriteMessage 并发调用。
	return conn.WriteControl(msgType, data, deadline)
}

// asyncWriteDirect 异步直接写入指定通道
func (p *clientPool) asyncWriteDirect(chID int, msgType int, data []byte) error {
	idx, err := p.chIndex(chID)
	if err != nil {
		return err
	}

	// 检查背压状态
	delay := p.getBackpressureDelay()
	if delay < 0 {
		// 需要等待恢复
		if !p.waitForBackpressure() {
			return fmt.Errorf("客户端正在关闭")
		}
	} else if delay > 0 {
		// 减速状态，添加延迟
		time.Sleep(delay)
	}

	size := int64(len(data))
	if !p.reserveQueueBytes(size) {
		return fmt.Errorf("全局写队列超限")
	}

	queue := p.writeQueues[idx]
	if queue == nil {
		p.releaseQueueBytes(size)
		return fmt.Errorf("通道 %d 不可用", chID)
	}

	waitTimeout := p.config.WriteQueueWaitTimeout
	if waitTimeout <= 0 {
		waitTimeout = 100 * time.Millisecond
	}
	job := writeJob{msgType: msgType, data: data, size: int(size)}
	select {
	case queue <- job:
		return nil
	default:
		timer := time.NewTimer(waitTimeout)
		defer timer.Stop()
		select {
		case queue <- job:
			return nil
		case <-timer.C:
			p.releaseQueueBytes(size)
			sysLog.Info("[客户端] 通道 %d 写队列满,队列长度: %d", chID, len(queue))
			return fmt.Errorf("通道 %d 缓冲区拥堵", chID)
		case <-p.ctx.Done():
			p.releaseQueueBytes(size)
			return fmt.Errorf("客户端正在关闭")
		}
	}
}

// broadcastWrite 广播写入所有通道，返回成功写入的通道数
func (p *clientPool) broadcastWrite(msgType int, data []byte) int {
	// 先在锁内收集活跃通道索引，释放锁后再写入。
	// asyncWriteDirect 内部会再次获取 wsConnsMu，若在此处持有读锁的同时调用，
	// 会形成同 goroutine 对 sync.RWMutex 的可重入读锁，在有 writer 排队时自死锁。
	p.wsConnsMu.RLock()
	idxs := make([]int, 0, len(p.wsConns))
	for i, c := range p.wsConns {
		if c != nil {
			idxs = append(idxs, i)
		}
	}
	p.wsConnsMu.RUnlock()

	successCount := 0
	for _, idx := range idxs {
		if err := p.asyncWriteDirect(idx+1, msgType, data); err == nil {
			successCount++
		}
	}
	return successCount
}

// noteUplink 记录上行通道
func (p *clientPool) noteUplink(connID string, chID int) {
	p.mu.Lock()
	st := p.conns[connID]
	if st == nil {
		p.mu.Unlock()
		return
	}
	if st.uplink == 0 {
		st.uplink = chID
	} else if st.uplink != chID {
		// 调试日志:上行通道被覆盖时记录（需要时可解除注释）
		// sysLog.Info("[客户端] 警告: 连接 %s 的上行通道从 %d 变更为 %d", common.ShortID(connID), st.uplink, chID)
	}
	p.mu.Unlock()
}

// noteLastChannel 记录最后操作的通道
func (p *clientPool) noteLastChannel(connID string, chID int) {
	p.mu.Lock()
	st := p.conns[connID]
	if st != nil {
		st.lastCh = chID
	}
	p.mu.Unlock()
}

// GetUplinkChannel 获取上行通道
func (p *clientPool) GetUplinkChannel(connID string) (int, bool) {
	p.mu.RLock()
	st := p.conns[connID]
	p.mu.RUnlock()
	if st == nil || st.uplink == 0 {
		return 0, false
	}
	return st.uplink, true
}

// RegisterAndBroadcastTCP 注册 TCP 连接并广播连接请求
func (p *clientPool) RegisterAndBroadcastTCP(connID, target string, first []byte, tcpConn net.Conn, reqType string) {
	p.mu.Lock()
	st := p.conns[connID]
	if st == nil {
		st = &clientConnState{}
		p.conns[connID] = st
	}
	st.tcpConn = tcpConn
	st.target = target
	st.connected = make(chan bool, 1)
	st.start = time.Now()
	if reqType != "" {
		st.reqType = reqType
	}
	if tcpConn != nil {
		if ra := tcpConn.RemoteAddr(); ra != nil {
			st.clientAddr = ra.String()
		}
	}
	st.uplink = 0
	st.downlink = 0
	st.lastCh = 0
	st.closed = false
	p.mu.Unlock()

	meta := make([]byte, 1+len(target))
	meta[0] = byte(p.config.IPStrategy)
	copy(meta[1:], target)

	// Hot Pair 路径：尝试获取主 Pair 并直接发送
	if p.config.EnableHotPair && p.pairWarmer != nil {
		pair := p.pairWarmer.AcquirePrimary()
		if pair != nil {
			p.mu.Lock()
			st = p.conns[connID]
			if st != nil {
				st.pair = pair
			}
			p.mu.Unlock()
			msg := common.EncodeMessage(common.MsgTCPConnect, connID, meta, first)
			sysLog.Info("[客户端] %s 使用 Hot Pair %s (TX %d RX %d) 发送首包，ID:%s", reqType, pair.ID, pair.UplinkChID, pair.DownlinkChID, common.ShortID(connID))
			if err := p.asyncWriteDirect(pair.UplinkChID, websocket.BinaryMessage, msg); err == nil {
				return
			}
			// Hot Pair 上行通道发送失败：释放 Pair 并回退到广播，避免连接状态残留
			sysLog.Info("[客户端] %s Hot Pair 上行通道 %d 发送失败，回退广播，ID:%s", reqType, pair.UplinkChID, common.ShortID(connID))
			p.mu.Lock()
			if st = p.conns[connID]; st != nil {
				st.pair = nil
			}
			p.mu.Unlock()
			p.pairWarmer.ReleasePair(pair)
			p.pairWarmer.InvalidateChannel(pair.UplinkChID)
			// 继续走广播路径
		}
	}

	msg := common.EncodeMessage(common.MsgTCPConnect, connID, meta, first)
	sent := p.broadcastWrite(websocket.BinaryMessage, msg)
	if sent == 0 {
		sysLog.Info("[客户端] %s 广播 TCP 连接请求失败，无可用通道，ID:%s", reqType, common.ShortID(connID))
		// 通知等待方连接失败（由等待方发送协议失败响应并清理），
		// 避免 SOCKS5 客户端只收到 EOF 而非标准失败应答。
		if st != nil {
			select {
			case st.connected <- false:
			default:
			}
		}
		return
	}
}

// RegisterUDP 注册 UDP 连接
func (p *clientPool) RegisterUDP(connID string, assoc *udpAssociation) {
	p.mu.Lock()
	st := p.conns[connID]
	if st == nil {
		st = &clientConnState{}
		p.conns[connID] = st
	}
	st.udpAssoc = assoc
	if st.connected == nil {
		st.connected = make(chan bool, 1)
	}
	if st.reqType == "" {
		st.reqType = "SOCKS5 UDP"
	}
	if assoc != nil && assoc.tcpConn != nil {
		if ra := assoc.tcpConn.RemoteAddr(); ra != nil {
			st.clientAddr = ra.String()
		}
	}
	p.mu.Unlock()
}

// StartUDPRace 启动 UDP 竞争
func (p *clientPool) StartUDPRace(connID, target string) {
	p.mu.Lock()
	st := p.conns[connID]
	if st == nil {
		st = &clientConnState{}
		p.conns[connID] = st
	}
	st.target = target
	st.start = time.Now()
	st.reqType = "SOCKS5 UDP"
	st.uplink = 0
	st.downlink = 0
	st.lastCh = 0
	p.mu.Unlock()

	meta := make([]byte, 1+len(target))
	meta[0] = byte(p.config.IPStrategy)
	copy(meta[1:], target)

	sent := p.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgUDPConnect, connID, meta, nil))
	if sent == 0 {
		sysLog.Info("[客户端] SOCKS5 UDP 广播连接请求失败，无可用通道，ID:%s", common.ShortID(connID))
		p.Unregister(connID)
	}
}

// Unregister 注销连接
func (p *clientPool) Unregister(connID string) {
	p.mu.Lock()
	st := p.conns[connID]
	if st == nil {
		p.mu.Unlock()
		return
	}
	if st.closed {
		p.mu.Unlock()
		return
	}
	st.closed = true

	target := st.target
	up, down := st.uplink, int(atomic.LoadInt32(&st.downlink))
	if up == 0 && st.lastCh > 0 {
		up = st.lastCh
	}
	if down == 0 && st.lastCh > 0 {
		down = st.lastCh
	}

	u := "-"
	d := "-"
	if up > 0 {
		u = fmt.Sprintf("%d", up)
	}
	if down > 0 {
		d = fmt.Sprintf("%d", down)
	}

	client := "-"
	typ := st.reqType
	if typ == "" {
		typ = "请求"
	}
	if st.clientAddr != "" {
		client = st.clientAddr
	}
	if target == "" {
		target = "-"
	}

	// 预绑定临时状态：仅清理 map，不输出访问日志、不关闭资源
	if target == common.PrebindTarget {
		delete(p.conns, connID)
		p.mu.Unlock()
		return
	}

	tcpConn := st.tcpConn
	udpAssoc := st.udpAssoc
	var pair *HotChannelPair
	if st != nil {
		pair = st.pair
		st.pair = nil
	}
	delete(p.conns, connID)
	p.mu.Unlock()

	sysLog.Info("[客户端] %s %s 访问: %s, 通道: TX %s RX %s, ID:%s, 已关闭",
		client, typ, target, u, d, common.ShortID(connID))

	if tcpConn != nil {
		_ = tcpConn.Close()
	}
	if udpAssoc != nil {
		udpAssoc.Close()
	}
	if p.pairWarmer != nil {
		p.pairWarmer.ReleasePair(pair)
	}
}

// selectDownlink 选择下行通道，返回是否首次选中以及已选中的通道号。
// 如果连接关联了 HotChannelPair，则直接使用 Pair 中预绑定的下行通道，避免竞态。
func (p *clientPool) selectDownlink(connID string, chID int) (selected bool, chosen int, start time.Time, target string, uplink int, typ string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	st := p.conns[connID]
	if st == nil || st.target == "" {
		return
	}

	// Hot Pair 路径：使用预绑定阶段确定的下行通道
	if st.pair != nil {
		pairDownlink := st.pair.DownlinkChID
		if pairDownlink > 0 {
			if atomic.CompareAndSwapInt32(&st.downlink, 0, int32(pairDownlink)) {
				chosen = pairDownlink
				selected = true
				start = st.start
			} else {
				chosen = int(atomic.LoadInt32(&st.downlink))
				selected = false
			}
			target = st.target
			uplink = st.pair.UplinkChID
			if uplink <= 0 && st.uplink > 0 {
				uplink = st.uplink
			}
			typ = st.reqType
			return
		}
	}

	chosen = int(atomic.LoadInt32(&st.downlink))
	if chosen > 0 {
		selected = false
	} else {
		// 预绑定路径：强制 uplink≠downlink。若当前通道即为 uplink，则让出，
		// 由其他通道竞争成为下行，避免预绑定 Pair 上下行落入同一通道。
		// 普通 TCP 路径（target 非 PrebindTarget）不受影响。
		if st.target == common.PrebindTarget && st.uplink > 0 && chID == st.uplink {
			// 让出，不设置 downlink，chosen 保持 0
		} else if atomic.CompareAndSwapInt32(&st.downlink, 0, int32(chID)) {
			// CAS：只有从未设置过 downlink 时才设置，避免竞态
			chosen = chID
			selected = true
			start = st.start
		} else {
			chosen = int(atomic.LoadInt32(&st.downlink))
			selected = false
		}
	}
	target = st.target
	uplink = -1
	if st.uplink > 0 {
		uplink = st.uplink
	}
	typ = st.reqType
	return
}

// getDownlink 快速读取已选中的下行通道（不加全局锁）。
func (p *clientPool) getDownlink(connID string) (int, bool) {
	p.mu.RLock()
	st := p.conns[connID]
	p.mu.RUnlock()
	if st == nil {
		return 0, false
	}
	ch := int(atomic.LoadInt32(&st.downlink))
	if ch > 0 {
		return ch, true
	}
	return 0, false
}

func (p *clientPool) connectTimeout() time.Duration {
	if p == nil || p.config == nil || p.config.ConnectTimeout <= 0 {
		return 15 * time.Second
	}
	return p.config.ConnectTimeout
}

func (p *clientPool) addSentBytes(n int) {
	if n > 0 {
		atomic.AddUint64(&p.bytesSent, uint64(n))
	}
}

func (p *clientPool) addReceivedBytes(n int) {
	if n > 0 {
		atomic.AddUint64(&p.bytesReceived, uint64(n))
	}
}

func (p *clientPool) proxyHandshakeTimeout() time.Duration {
	if p == nil || p.config == nil || p.config.HandshakeTimeout <= 0 {
		return 3 * time.Second
	}
	return p.config.HandshakeTimeout
}

// signalConnected 发送连接成功信号
func (p *clientPool) signalConnected(id string) {
	p.mu.RLock()
	st := p.conns[id]
	var ch chan bool
	if st != nil {
		ch = st.connected
	}
	p.mu.RUnlock()
	if ch != nil {
		select {
		case ch <- true:
		default:
		}
	}
}

// SendDataDirect 发送数据到指定通道
func (p *clientPool) SendDataDirect(chID int, connID string, b []byte) error {
	return p.asyncWriteDirect(chID, websocket.BinaryMessage, common.EncodeMessage(common.MsgTCPData, connID, nil, b))
}

// SendCloseDirect 发送关闭消息到指定通道
func (p *clientPool) SendCloseDirect(chID int, connID string) error {
	return p.asyncWriteDirect(chID, websocket.BinaryMessage, common.EncodeMessage(common.MsgTCPClose, connID, nil, nil))
}

// SendUDPDataDirect 发送 UDP 数据到指定通道
func (p *clientPool) SendUDPDataDirect(chID int, connID string, data []byte) error {
	return p.asyncWriteDirect(chID, websocket.BinaryMessage, common.EncodeMessage(common.MsgUDPData, connID, nil, data))
}

// SendUDPCloseDirect 发送 UDP 关闭消息到指定通道
func (p *clientPool) SendUDPCloseDirect(chID int, connID string) {
	_ = p.asyncWriteDirect(chID, websocket.BinaryMessage, common.EncodeMessage(common.MsgUDPClose, connID, nil, nil))
	p.Unregister(connID)
}

// availableChannels 返回当前可用的通道 ID 列表（连接已就绪的通道）
func (p *clientPool) availableChannels() []int {
	p.wsConnsMu.RLock()
	defer p.wsConnsMu.RUnlock()

	var available []int
	for i, c := range p.wsConns {
		if c != nil {
			// 验证写队列是否还存在
			if i < len(p.writeQueues) && p.writeQueues[i] != nil {
				available = append(available, i+1)
			}
		}
	}
	return available
}

// cleanupChannel 清理通道
func (p *clientPool) cleanupChannel(chID int) {
	p.mu.Lock()
	var toClose []string
	for id, st := range p.conns {
		if st.uplink == chID || int(atomic.LoadInt32(&st.downlink)) == chID {
			toClose = append(toClose, id)
		}
	}
	p.mu.Unlock()

	for _, id := range toClose {
		p.mu.RLock()
		st := p.conns[id]
		p.mu.RUnlock()
		if st == nil {
			continue
		}
		if st.tcpConn != nil {
			_ = st.tcpConn.Close()
		}
		if st.udpAssoc != nil {
			st.udpAssoc.Close()
		}
		p.Unregister(id)
	}
}

// handleChannel 处理通道消息
func (p *clientPool) handleChannel(chID int, conn *websocket.Conn) {
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(p.config.ReadTimeout))
		return nil
	})
	_ = conn.SetReadDeadline(time.Now().Add(p.config.ReadTimeout))
	conn.SetPingHandler(func(m string) error {
		_ = conn.SetReadDeadline(time.Now().Add(p.config.ReadTimeout))
		if err := p.writeControlDirect(chID, websocket.PongMessage, []byte(m)); err != nil {
			sysLog.Info("[客户端] 通道 %d pong发送失败: %v", chID, err)
		}
		// pong 发送失败不影响 ping/pong 循环,总是返回 nil
		return nil
	})

	for {
		mt, msg, err := conn.ReadMessage()
		if err != nil {
			if !common.IsNormalCloseError(err) {
				sysLog.Info("[客户端] 通道 %d 读取消息失败: %v", chID, err)
			} else {
				sysLog.Info("[客户端] 通道 %d 正常关闭: %v", chID, err)
			}
			return
		}
		// 每次成功读取消息后重置读超时
		_ = conn.SetReadDeadline(time.Now().Add(p.config.ReadTimeout))

		if mt != websocket.BinaryMessage {
			continue
		}
		p.addReceivedBytes(len(msg))
		mtype, connID, meta, payload, err := common.DecodeMessage(msg)
		if err != nil {
			continue
		}

		p.noteLastChannel(connID, chID)

		switch mtype {
		case common.MsgSelectUplink:
			// 从 meta 中解析服务端选择的上行通道ID
			var uplinkChID int
			if len(meta) >= 4 {
				uplinkChID = int(binary.BigEndian.Uint32(meta[0:4]))
			} else {
				// 兼容旧版本:使用当前处理通道
				uplinkChID = chID
			}
			p.noteUplink(connID, uplinkChID)

			// 选择当前通道作为下行通道（最快收到 MsgSelectUplink 的获胜）
			selected, chosen, _, target, up, _ := p.selectDownlink(connID, chID)
			if selected {
				clientAddr := ""
				p.mu.RLock()
				if st := p.conns[connID]; st != nil {
					clientAddr = st.clientAddr
				}
				p.mu.RUnlock()
				// 预绑定目标不输出访问日志
				if chosen > 0 && target != "" && target != common.PrebindTarget {
					sysLog.Info("[客户端] %s 访问: %s, 通道: TX %d RX %d, ID:%s",
						clientAddr, target, up, chosen, common.ShortID(connID))
				}
				// 通过 uplink 通道发送 MsgSelectDownlink,meta 中携带已选中的下行通道号。
				// 注意：对于 Hot Pair 请求，chosen 是 Pair 预绑定的下行通道；
				// 对于预绑定请求，chosen 是当前收到 MsgSelectUplink 的通道。
				downlinkBytes := make([]byte, 4)
				binary.BigEndian.PutUint32(downlinkBytes, uint32(chosen))
				_ = p.asyncWriteDirect(uplinkChID, websocket.BinaryMessage, common.EncodeMessage(common.MsgSelectDownlink, connID, downlinkBytes, nil))

				// 如果是预绑定请求，通知 PairWarmer 完成 Pair 构建
				if p.pairWarmer != nil && strings.HasPrefix(connID, "prebind-") {
					p.pairWarmer.HandlePrebindResult(connID, uplinkChID, chosen, nil)
				}
			}

		case common.MsgConnStatus:
			if len(meta) < 1 {
				continue
			}
			if common.ConnStatus(meta[0]) == common.StatusOK {
				// 不做阻塞等待,这里只作为"连接建立"的信号
				p.signalConnected(connID)
			} else {
				p.Unregister(connID)
			}

		case common.MsgTCPData:
			// 下行数据:只处理来自已选中下行通道的数据
			chosen, ok := p.getDownlink(connID)
			if ok {
				if chosen != chID {
					continue
				}
			} else {
				// 尚未选择下行通道，使用 selectDownlink 竞争（仅首次）
				_, chosen, _, _, _, _ = p.selectDownlink(connID, chID)
				if chosen != chID {
					continue
				}
			}
			p.mu.RLock()
			var c net.Conn
			if st := p.conns[connID]; st != nil {
				c = st.tcpConn
			}
			p.mu.RUnlock()
			if c != nil {
				_ = c.SetWriteDeadline(time.Now().Add(p.config.WriteTimeout))
				if _, err := c.Write(payload); err != nil {
					_ = p.SendCloseDirect(chID, connID)
					_ = c.Close()
					p.Unregister(connID)
				}
				_ = c.SetWriteDeadline(time.Time{})
			} else {
				_ = p.SendCloseDirect(chID, connID)
				p.Unregister(connID)
			}

		case common.MsgTCPClose:
			p.noteUplink(connID, chID)
			p.mu.RLock()
			var c net.Conn
			if st := p.conns[connID]; st != nil {
				c = st.tcpConn
			}
			p.mu.RUnlock()
			if c != nil {
				_ = c.Close()
			}
			p.Unregister(connID)

		case common.MsgUDPData:
			// 先尝试无锁读取下行通道；未设置再走 selectDownlink 竞争
			chosen, ok := p.getDownlink(connID)
			selected := false
			start := time.Time{}
			target := ""
			up := -1
			typ := ""
			if ok {
				if chosen != chID {
					continue
				}
			} else {
				selected, chosen, start, target, up, typ = p.selectDownlink(connID, chID)
				if chosen != chID {
					continue
				}
			}
			if selected {
				downlinkBytes := make([]byte, 4)
				binary.BigEndian.PutUint32(downlinkBytes, uint32(chID))
				_ = p.asyncWriteDirect(chID, websocket.BinaryMessage, common.EncodeMessage(common.MsgSelectDownlink, connID, downlinkBytes, nil))
				if !start.IsZero() && up > 0 {
					if typ == "" {
						typ = "SOCKS5 UDP"
					}
					client := "-"
					p.mu.RLock()
					if st := p.conns[connID]; st != nil && st.clientAddr != "" {
						client = st.clientAddr
					}
					p.mu.RUnlock()
					ms := float64(time.Since(start)) / float64(time.Millisecond)
					sysLog.Info("[客户端] %s %s 访问: %s, 通道: TX %d RX %d, ID:%s, 延迟 %.1f ms",
						client, typ, target, up, chID, common.ShortID(connID), ms)
				}
			}
			p.mu.RLock()
			var assoc *udpAssociation
			if st := p.conns[connID]; st != nil {
				assoc = st.udpAssoc
			}
			p.mu.RUnlock()
			if assoc != nil {
				assoc.handleUDPResponse(string(meta), payload)
			}

		case common.MsgUDPClose:
			p.noteUplink(connID, chID)
			p.mu.RLock()
			var assoc *udpAssociation
			if st := p.conns[connID]; st != nil {
				assoc = st.udpAssoc
			}
			p.mu.RUnlock()
			if assoc != nil {
				assoc.Close()
			} else {
				p.Unregister(connID)
			}

		case common.MsgBackpressure:
			// 处理背压通知
			if len(meta) >= 1 {
				state := common.BackpressureState(meta[0])
				p.handleBackpressure(state)
			}

		case common.MsgChannelReset:
			if len(meta) >= 4 {
				resetChID := int(binary.BigEndian.Uint32(meta[0:4]))
				select {
				case p.chInvalidCh <- resetChID:
				default:
				}
				if p.pairWarmer != nil {
					p.pairWarmer.InvalidateChannel(resetChID)
				}
			}
		}
	}
}

// Stats 返回统计信息
func (p *clientPool) Stats() *Stats {
	p.mu.RLock()
	defer p.mu.RUnlock()

	// 计算活跃通道数
	p.wsConnsMu.RLock()
	activeChannels := 0
	for _, conn := range p.wsConns {
		if conn != nil {
			activeChannels++
		}
	}
	p.wsConnsMu.RUnlock()

	// 获取中转节点数
	relayNodes := p.relayManager.NodeCount()

	return &Stats{
		Connections:    len(p.conns),
		ActiveChannels: activeChannels,
		RelayNodes:     relayNodes,
		BytesSent:      atomic.LoadUint64(&p.bytesSent),
		BytesReceived:  atomic.LoadUint64(&p.bytesReceived),
	}
}

// handleBackpressure 处理背压状态变化
func (p *clientPool) handleBackpressure(state common.BackpressureState) {
	oldState := common.BackpressureState(atomic.SwapInt32(&p.backpressureState, int32(state)))

	if oldState == state {
		return // 状态未变化
	}

	switch state {
	case common.BackpressureNormal:
		// 恢复正常，向 resumeCh 发送信号（带缓冲，不阻塞发送方）
		select {
		case p.resumeCh <- struct{}{}:
		default:
		}
		sysLog.Info("[客户端] 背压恢复，继续正常发送")

	case common.BackpressureSlowDown:
		sysLog.Info("[客户端] 收到减速通知，降低发送速率")

	case common.BackpressurePause:
		sysLog.Info("[客户端] 收到暂停通知，等待恢复")
	}
}

// waitForBackpressure 等待背压恢复正常（用于暂停状态）
// 返回 true 表示可以继续，false 表示应该放弃
func (p *clientPool) waitForBackpressure() bool {
	for {
		state := common.BackpressureState(atomic.LoadInt32(&p.backpressureState))
		if state != common.BackpressurePause {
			return true
		}

		select {
		case <-p.resumeCh:
			// 收到恢复信号后继续循环，重新检查状态
		case <-p.ctx.Done():
			return false
		}
	}
}

// getBackpressureDelay 获取背压延迟（用于减速状态）
func (p *clientPool) getBackpressureDelay() time.Duration {
	state := common.BackpressureState(atomic.LoadInt32(&p.backpressureState))

	switch state {
	case common.BackpressureSlowDown:
		return 10 * time.Millisecond // 减速时增加延迟
	case common.BackpressurePause:
		return -1 // 需要等待
	default:
		return 0 // 正常，无延迟
	}
}

// shouldBypass 判断目标地址是否命中路由绕过规则。
// xtunnel 不做客户端 DNS 解析（域名由服务端解析），因此：
//   - IP/CIDR/private 规则与域名规则（domain/suffix/full/geosite）直接生效；
//   - GEOIP 类规则只对 IP 目标生效（域名目标不触发本地解析）。
func (p *clientPool) shouldBypass(target string) bool {
	if p.bypassMatcher == nil {
		return false
	}
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return false
	}
	return p.bypassMatcher.Match(host, host)
}

// dialBypassTarget 建立绕过隧道的直连 TCP 连接。
func (p *clientPool) dialBypassTarget(target string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(p.ctx, p.connectTimeout())
	defer cancel()
	return (&net.Dialer{}).DialContext(ctx, "tcp", target)
}

// relayBypassConnections 双向转发两个数据流直到一端结束。
// 对 *net.TCPConn 主动半关闭（CloseWrite），保证流式协议（SOCKS5/HTTP）
// 的 EOF 语义；a 侧可以是 bufio 包装（HTTP 普通请求的已缓冲字节）。
func relayBypassConnections(a io.ReadWriter, aTCP *net.TCPConn, b io.ReadWriter, bTCP *net.TCPConn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(b, a)
		if bTCP != nil {
			_ = bTCP.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(a, b)
		if aTCP != nil {
			_ = aTCP.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
}

// asTCP 返回连接对应的 *net.TCPConn（用于半关闭），非 TCP 连接返回 nil。
func asTCP(c net.Conn) *net.TCPConn {
	if tc, ok := c.(*net.TCPConn); ok {
		return tc
	}
	return nil
}
