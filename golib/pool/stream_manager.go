package pool

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"xclient/logger"
	"xclient/protocol"
)

// 窗口流控常量
const (
	DefaultWindowSize = 256 * 1024      // 默认窗口大小 256KB
	MinWindowSize     = 32 * 1024       // 最小窗口大小 32KB
	MaxWindowSize     = 1024 * 1024     // 最大窗口大小 1MB
	WindowTimeout     = 5 * time.Second // 窗口等待超时
)

// StreamState Stream 状态枚举
type StreamState int

const (
	StreamStateIdle        StreamState = iota
	StreamStateSynSent                 // 已发送 CONNECT
	StreamStateEstablished             // 已收到 CONNECTED
	StreamStateFinWait                 // 已发送/收到 CLOSE
	StreamStateClosed                  // 完全关闭
)

// Stream 表示单个流的状态
type Stream struct {
	ID           byte
	TargetAddr   string
	CreatedAt    time.Time
	Handler      *StreamHandler
	BytesSent    int64
	BytesRecv    int64
	LastActiveAt time.Time

	// 窗口流控 (借鉴 yamux 设计)
	sendWindow  int64         // 可发送字节数（原子操作）
	recvWindow  int64         // 可接收字节数（原子操作）
	windowSize  int64         // 窗口大小（默认 256KB）
	sendBlocked chan struct{} // 发送阻塞通知
	windowMu    sync.Mutex    // 保护窗口操作

	// 拥塞控制 (借鉴 smux 设计)
	rttHistory   [10]time.Duration // RTT 历史记录（环形缓冲区）
	rttIndex     int               // RTT 历史索引
	baselineRTT  time.Duration     // 基线 RTT（最小值）
	timeoutCount int64             // 超时次数（原子操作）
	successCount int64             // 成功次数（原子操作）

	// 状态机与优先级
	state    StreamState // 当前状态
	priority int         // 优先级（0=低，1=中，2=高）
	stateMu  sync.Mutex  // 保护状态转换

	// 流控配置
	minWindowSize int64         // 最小窗口大小
	maxWindowSize int64         // 最大窗口大小
	windowTimeout time.Duration // 窗口等待超时
}

// StreamManager 管理单个 WebSocket 连接上的所有 stream
// 每条 WebSocket 连接对应一个 StreamManager
type StreamManager struct {
	conn    *ConnItem        // 所属的连接
	max     int              // 最大 stream 数量
	streams map[byte]*Stream // Stream ID -> Stream
	mu      sync.RWMutex     // 保护 streams 映射
	log     *logger.Logger   // 日志器

	// 位图分配优化 (借鉴 smux 设计)
	allocBitmap [4]uint64 // 256 bits = 4 x 64-bit words，跟踪 Stream ID 占用状态
	nextHint    byte      // 上次分配的 ID + 1，避免重复扫描

	// 窗口流控配置
	defaultWindowSize int64         // 默认窗口大小
	minWindowSize     int64         // 最小窗口大小
	maxWindowSize     int64         // 最大窗口大小
	windowTimeout     time.Duration // 窗口等待超时
}

// NewStreamManager 创建新的 StreamManager
func NewStreamManager(conn *ConnItem, maxStreams int, defaultWindowSize, minWindowSize, maxWindowSize int64, windowTimeout time.Duration) *StreamManager {
	return &StreamManager{
		conn:              conn,
		max:               maxStreams,
		streams:           make(map[byte]*Stream),
		log:               logger.GetLogger("StreamMgr"),
		nextHint:          0, // 从 0 开始分配
		defaultWindowSize: defaultWindowSize,
		minWindowSize:     minWindowSize,
		maxWindowSize:     maxWindowSize,
		windowTimeout:     windowTimeout,
	}
}

// ============================================================================
// 位图分配算法 (借鉴 smux 设计)
// ============================================================================

// setBit 标记指定 Stream ID 为已分配
func (sm *StreamManager) setBit(id byte) {
	word := id / 64
	bit := id % 64
	sm.allocBitmap[word] |= (1 << bit)
}

// clearBit 标记指定 Stream ID 为已释放
func (sm *StreamManager) clearBit(id byte) {
	word := id / 64
	bit := id % 64
	sm.allocBitmap[word] &^= (1 << bit)
}

// isBitSet 检查指定 Stream ID 是否已分配
func (sm *StreamManager) isBitSet(id byte) bool {
	word := id / 64
	bit := id % 64
	return (sm.allocBitmap[word] & (1 << bit)) != 0
}

// findFreeBit 查找空闲的 Stream ID (O(1) 平均时间复杂度)
// 从 nextHint 开始循环查找，返回 (streamID, found)
func (sm *StreamManager) findFreeBit() (byte, bool) {
	// 从 nextHint 开始循环扫描 256 个位置
	for offset := 0; offset < 256; offset++ {
		id := byte((int(sm.nextHint) + offset) % 256)
		word := id / 64
		bit := id % 64

		// 检查该位是否空闲
		if (sm.allocBitmap[word] & (1 << bit)) == 0 {
			// 更新 nextHint 为下一个位置
			sm.nextHint = byte((int(id) + 1) % 256)
			return id, true
		}
	}

	// 所有 256 个 ID 都已占用
	return 0, false
}

// tryAllocateStream 尝试立即分配一个 Stream ID（不阻塞）
// 返回 Stream ID 和是否成功（如果连接已满则立即返回 false）
func (sm *StreamManager) tryAllocateStream(targetAddr string) (byte, bool) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	// 检查是否已达到最大数量
	if len(sm.streams) >= sm.max {
		return 0, false
	}

	connIDStr := formatConnID(sm.conn.ConnectionID)

	// 使用位图算法查找空闲 Stream ID (O(1) 平均复杂度)
	streamID, found := sm.findFreeBit()
	if !found {
		// 理论上不应该发生（len < max 但找不到空闲位）
		sm.log.Warn("连接 [%s] 位图分配失败，但 len=%d < max=%d",
			connIDStr, len(sm.streams), sm.max)
		return 0, false
	}

	// 标记位图为已占用
	sm.setBit(streamID)

	// 注册 Stream（初始化窗口流控）
	sm.streams[streamID] = &Stream{
		ID:            streamID,
		TargetAddr:    targetAddr,
		CreatedAt:     time.Now(),
		LastActiveAt:  time.Now(),
		sendWindow:    sm.defaultWindowSize,
		recvWindow:    sm.defaultWindowSize,
		windowSize:    sm.defaultWindowSize,
		sendBlocked:   make(chan struct{}, 1),
		minWindowSize: sm.minWindowSize,
		maxWindowSize: sm.maxWindowSize,
		windowTimeout: sm.windowTimeout,
	}

	sm.conn.mu.Lock()
	sm.conn.Streams++
	sm.conn.Traffic.IncStream()
	sm.conn.mu.Unlock()

	sm.log.Debug("连接 [%s] 分配 Stream[%02x] -> %s (位图优化)",
		connIDStr, streamID, targetAddr)

	return streamID, true
}

// AllocateStream 分配一个新的 Stream ID（带超时）
// 返回 Stream ID 和是否成功（超时返回 false）
func (sm *StreamManager) AllocateStream(targetAddr string, timeout time.Duration) (byte, bool) {
	deadline := time.Now().Add(timeout)
	connIDStr := formatConnID(sm.conn.ConnectionID)

	for time.Now().Before(deadline) {
		sm.mu.Lock()

		// 检查是否已达到最大数量
		if len(sm.streams) >= sm.max {
			sm.mu.Unlock()
			sm.log.Debug("连接 [%s] 已达到最大 stream 数量 (%d)，等待中...",
				connIDStr, sm.max)
			time.Sleep(10 * time.Millisecond)
			continue
		}

		// 使用位图算法查找空闲 Stream ID (O(1) 平均复杂度)
		streamID, found := sm.findFreeBit()
		if !found {
			// 理论上不应该发生（len < max 但找不到空闲位）
			sm.mu.Unlock()
			sm.log.Warn("连接 [%s] 位图分配失败，但 len=%d < max=%d",
				connIDStr, len(sm.streams), sm.max)
			time.Sleep(10 * time.Millisecond)
			continue
		}

		// 标记位图为已占用
		sm.setBit(streamID)

		// 注册 Stream
		sm.streams[streamID] = &Stream{
			ID:           streamID,
			TargetAddr:   targetAddr,
			CreatedAt:    time.Now(),
			LastActiveAt: time.Now(),
			sendWindow:   sm.defaultWindowSize,
			recvWindow:   sm.defaultWindowSize,
			windowSize:   sm.defaultWindowSize,
			sendBlocked:  make(chan struct{}, 1),
		}

		sm.conn.mu.Lock()
		sm.conn.Streams++
		sm.conn.Traffic.IncStream()
		sm.conn.mu.Unlock()
		sm.mu.Unlock()

		sm.log.Debug("连接 [%s] 分配 Stream[%02x] -> %s (位图优化)",
			connIDStr, streamID, targetAddr)

		return streamID, true
	}

	return 0, false
}

// RegisterHandler 注册 stream 的消息处理器
func (sm *StreamManager) RegisterHandler(streamID byte, handler *StreamHandler) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if s, exists := sm.streams[streamID]; exists {
		s.Handler = handler
		s.LastActiveAt = time.Now()
		connIDStr := formatConnID(sm.conn.ConnectionID)
		sm.log.Debug("连接 [%s] Stream[%02x] 注册处理器", connIDStr, streamID)
	}
}

// UnregisterStream 注销一个 stream
// 返回目标地址（用于清理亲和性映射）和是否该连接已无活跃 stream
func (sm *StreamManager) UnregisterStream(streamID byte) (targetAddr string, isEmpty bool) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	connIDStr := formatConnID(sm.conn.ConnectionID)

	if s, exists := sm.streams[streamID]; exists {
		targetAddr = s.TargetAddr

		// 调用清理回调
		if s.Handler != nil && s.Handler.OnCleanup != nil {
			s.Handler.OnCleanup()
		}

		delete(sm.streams, streamID)

		// 清除位图标记（释放 Stream ID）
		sm.clearBit(streamID)

		sm.conn.mu.Lock()
		if sm.conn.Streams > 0 {
			sm.conn.Streams--
		}
		sm.conn.mu.Unlock()

		// 减少活跃 Stream 计数
		sm.conn.Traffic.DecStream()

		sm.log.Debug("连接 [%s] Stream[%02x] 已注销 -> %s (剩余: %d)",
			connIDStr, streamID, targetAddr, len(sm.streams))

		return targetAddr, len(sm.streams) == 0
	}

	return "", len(sm.streams) == 0
}

// DispatchMessage 分发消息到对应的 stream
func (sm *StreamManager) DispatchMessage(msg *protocol.Message) {
	sm.mu.RLock()
	s, exists := sm.streams[msg.StreamID]
	sm.mu.RUnlock()

	if exists && s.Handler != nil && s.Handler.OnMessage != nil {
		s.LastActiveAt = time.Now()
		s.Handler.OnMessage(msg)
	}
}

// HandleConnectionClose 处理连接关闭
func (sm *StreamManager) HandleConnectionClose() {
	connIDStr := formatConnID(sm.conn.ConnectionID)
	sm.mu.RLock()
	handlers := make([]*StreamHandler, 0, len(sm.streams))
	for _, stream := range sm.streams {
		if stream.Handler != nil {
			handlers = append(handlers, stream.Handler)
		}
	}
	streamCount := len(sm.streams)
	sm.mu.RUnlock()

	sm.log.Debug("连接 [%s] 关闭，清理 %d 个 stream", connIDStr, streamCount)

	// 回调可能注销 stream，不能在持有 sm.mu 时执行。
	for _, handler := range handlers {
		if handler.OnClose != nil {
			handler.OnClose()
		}
	}

	// 清理回调未主动注销的 stream。
	sm.mu.Lock()
	remaining := len(sm.streams)
	sm.streams = make(map[byte]*Stream)
	sm.allocBitmap = [4]uint64{}
	sm.nextHint = 0
	sm.mu.Unlock()

	if remaining > 0 {
		sm.conn.mu.Lock()
		sm.conn.Streams -= remaining
		if sm.conn.Streams < 0 {
			sm.conn.Streams = 0
		}
		sm.conn.mu.Unlock()
		for i := 0; i < remaining; i++ {
			sm.conn.Traffic.DecStream()
		}
	}
}

// GetStreamCount 获取当前活跃 stream 数量
func (sm *StreamManager) GetStreamCount() int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return len(sm.streams)
}

// HasTarget 检查是否正在服务指定目标地址
func (sm *StreamManager) HasTarget(targetAddr string) bool {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	for _, s := range sm.streams {
		if s.TargetAddr == targetAddr {
			return true
		}
	}
	return false
}

// GetLoadFactor 获取负载因子 (0.0 - 1.0)
func (sm *StreamManager) GetLoadFactor() float64 {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	if sm.max <= 0 {
		return 0
	}
	if len(sm.streams) >= sm.max {
		return 1.0
	}
	return float64(len(sm.streams)) / float64(sm.max)
}

// GetTargetCount 获取服务的不同目标地址数量
func (sm *StreamManager) GetTargetCount() int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	targetSet := make(map[string]struct{})
	for _, s := range sm.streams {
		if s.TargetAddr != "" {
			targetSet[s.TargetAddr] = struct{}{}
		}
	}
	return len(targetSet)
}

// GetStreamInfo 获取所有 stream 的信息（用于调试）
func (sm *StreamManager) GetStreamInfo() []StreamInfo {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	info := make([]StreamInfo, 0, len(sm.streams))
	for _, s := range sm.streams {
		info = append(info, StreamInfo{
			ID:         s.ID,
			TargetAddr: s.TargetAddr,
			Duration:   time.Since(s.CreatedAt),
			IdleTime:   time.Since(s.LastActiveAt),
		})
	}
	return info
}

// StreamInfo stream 信息（用于调试）
type StreamInfo struct {
	ID         byte
	TargetAddr string
	Duration   time.Duration
	IdleTime   time.Duration
}

// String 返回 StreamInfo 的字符串表示
func (si StreamInfo) String() string {
	return fmt.Sprintf("[%02x:%s:%.1fs:%.1fs]",
		si.ID, si.TargetAddr,
		si.Duration.Seconds(), si.IdleTime.Seconds())
}

// ============================================================================
// 窗口流控方法 (借鉴 yamux 设计)
// ============================================================================

// WaitForSendWindow 等待发送窗口有足够空间
// 返回 error 表示超时或 Stream 已关闭
func (s *Stream) WaitForSendWindow(n int) error {
	if n <= 0 {
		return nil
	}

	deadline := time.Now().Add(s.windowTimeout)
	for {
		// 原子读取当前窗口
		window := atomic.LoadInt64(&s.sendWindow)
		if window >= int64(n) {
			// 窗口足够，原子减少
			if atomic.CompareAndSwapInt64(&s.sendWindow, window, window-int64(n)) {
				return nil
			}
			// CAS 失败，重试
			continue
		}

		// 窗口不足，等待通知或超时
		if time.Now().After(deadline) {
			return fmt.Errorf("send window timeout after %v", WindowTimeout)
		}

		select {
		case <-s.sendBlocked:
			// 窗口可能已恢复，重新检查
		case <-time.After(100 * time.Millisecond):
			// 定期重试
		}
	}
}

// ConsumeRecvWindow 消耗接收窗口（接收数据时调用）
func (s *Stream) ConsumeRecvWindow(n int) error {
	if n <= 0 {
		return nil
	}

	// 原子减少接收窗口
	newWindow := atomic.AddInt64(&s.recvWindow, -int64(n))
	if newWindow < 0 {
		// 窗口耗尽，需要补充
		return fmt.Errorf("recv window exhausted")
	}

	// 如果窗口低于 50%，自动补充
	windowSize := atomic.LoadInt64(&s.windowSize)
	if newWindow < windowSize/2 {
		s.RefillRecvWindow()
	}

	return nil
}

// RefillRecvWindow 补充接收窗口
func (s *Stream) RefillRecvWindow() {
	windowSize := atomic.LoadInt64(&s.windowSize)
	atomic.StoreInt64(&s.recvWindow, windowSize)
}

// RefillSendWindow 补充发送窗口（接收到对端确认时调用）
func (s *Stream) RefillSendWindow(n int) {
	if n <= 0 {
		return
	}

	// 原子增加发送窗口
	atomic.AddInt64(&s.sendWindow, int64(n))

	// 通知等待的发送者
	select {
	case s.sendBlocked <- struct{}{}:
	default:
	}
}

// ============================================================================
// 拥塞控制方法 (借鉴 smux 设计)
// ============================================================================

// RecordRTT 记录 RTT 样本
func (s *Stream) RecordRTT(rtt time.Duration) {
	s.windowMu.Lock()
	defer s.windowMu.Unlock()

	// 更新 RTT 历史（环形缓冲区）
	s.rttHistory[s.rttIndex] = rtt
	s.rttIndex = (s.rttIndex + 1) % len(s.rttHistory)

	// 更新基线 RTT（取最小值）
	if s.baselineRTT == 0 || rtt < s.baselineRTT {
		s.baselineRTT = rtt
	}
}

// GetAverageRTT 获取平均 RTT
func (s *Stream) GetAverageRTT() time.Duration {
	s.windowMu.Lock()
	defer s.windowMu.Unlock()
	return s.getAverageRTTLocked()
}

// getAverageRTTLocked 获取平均 RTT（调用者必须持有 windowMu 锁）
func (s *Stream) getAverageRTTLocked() time.Duration {
	var sum time.Duration
	count := 0
	for _, rtt := range s.rttHistory {
		if rtt > 0 {
			sum += rtt
			count++
		}
	}

	if count == 0 {
		return 0
	}
	return sum / time.Duration(count)
}

// DetectCongestion 检测是否发生拥塞
// 返回 true 表示检测到拥塞
func (s *Stream) DetectCongestion() bool {
	s.windowMu.Lock()
	defer s.windowMu.Unlock()

	// 如果没有足够的 RTT 样本，不判断拥塞
	if s.baselineRTT == 0 {
		return false
	}

	avgRTT := s.getAverageRTTLocked()
	if avgRTT == 0 {
		return false
	}

	// 计算丢包率
	totalCount := atomic.LoadInt64(&s.successCount) + atomic.LoadInt64(&s.timeoutCount)
	if totalCount == 0 {
		return false
	}
	lossRate := float64(atomic.LoadInt64(&s.timeoutCount)) / float64(totalCount)

	// 拥塞判断条件：RTT 翻倍 或 丢包率 > 5%
	return avgRTT > s.baselineRTT*2 || lossRate > 0.05
}

// RecordSuccess 记录成功的操作
func (s *Stream) RecordSuccess() {
	atomic.AddInt64(&s.successCount, 1)
}

// RecordTimeout 记录超时的操作
func (s *Stream) RecordTimeout() {
	atomic.AddInt64(&s.timeoutCount, 1)
}

// AdjustWindowSize 自适应调整窗口大小（TCP 风格的 AIMD）
func (s *Stream) AdjustWindowSize() {
	if s.DetectCongestion() {
		// 拥塞：乘性减（减半）
		currentSize := atomic.LoadInt64(&s.windowSize)
		newSize := currentSize / 2
		if newSize < s.minWindowSize {
			newSize = s.minWindowSize
		}
		atomic.StoreInt64(&s.windowSize, newSize)

		// 同时调整发送窗口（使用 CAS 循环避免覆盖已消耗的窗口）
		for {
			oldWindow := atomic.LoadInt64(&s.sendWindow)
			// 只有当前窗口大于新窗口时才需要缩小
			if oldWindow > newSize {
				if atomic.CompareAndSwapInt64(&s.sendWindow, oldWindow, newSize) {
					break
				}
				// CAS 失败，重试
			} else {
				// 当前窗口已经小于等于新窗口，无需调整
				break
			}
		}
	} else {
		// 无拥塞：加性增（每次增加 8KB）
		currentSize := atomic.LoadInt64(&s.windowSize)
		newSize := currentSize + 8*1024
		if newSize > s.maxWindowSize {
			newSize = s.maxWindowSize
		}
		atomic.StoreInt64(&s.windowSize, newSize)
	}
}

// GetLossRate 获取丢包率
func (s *Stream) GetLossRate() float64 {
	totalCount := atomic.LoadInt64(&s.successCount) + atomic.LoadInt64(&s.timeoutCount)
	if totalCount == 0 {
		return 0
	}
	return float64(atomic.LoadInt64(&s.timeoutCount)) / float64(totalCount)
}

// ============================================================================
// 状态机方法 (借鉴 yamux 设计)
// ============================================================================

// TransitionState 状态转换（带合法性检查）
func (s *Stream) TransitionState(newState StreamState) error {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()

	// 验证状态转换合法性
	valid := false
	switch s.state {
	case StreamStateIdle:
		valid = newState == StreamStateSynSent
	case StreamStateSynSent:
		valid = newState == StreamStateEstablished || newState == StreamStateClosed
	case StreamStateEstablished:
		valid = newState == StreamStateFinWait || newState == StreamStateClosed
	case StreamStateFinWait:
		valid = newState == StreamStateClosed
	case StreamStateClosed:
		valid = false // 已关闭，不能再转换
	}

	if !valid {
		return fmt.Errorf("invalid state transition: %v -> %v", s.state, newState)
	}

	s.state = newState
	return nil
}

// GetState 获取当前状态
func (s *Stream) GetState() StreamState {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	return s.state
}

// SetPriority 设置 Stream 优先级
func (s *Stream) SetPriority(priority int) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	if priority < 0 {
		priority = 0
	}
	if priority > 2 {
		priority = 2
	}
	s.priority = priority
}

// GetPriority 获取 Stream 优先级
func (s *Stream) GetPriority() int {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	return s.priority
}
