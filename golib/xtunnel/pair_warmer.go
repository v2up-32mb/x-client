package xtunnel

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	common "xclient/xtunnel/protocol"
)

const (
	PairStateReady = iota
	PairStateDraining
	PairStateClosed
)

// HotChannelPair 表示一个热通道对
type HotChannelPair struct {
	ID           string
	UplinkChID   int
	DownlinkChID int
	state        int32
	createdAt    time.Time
	refs         int32
}

// State 返回当前状态
func (p *HotChannelPair) State() int { return int(atomic.LoadInt32(&p.state)) }

func (p *HotChannelPair) setState(s int) { atomic.StoreInt32(&p.state, int32(s)) }

// SetStateForTest 仅用于测试设置状态
func (p *HotChannelPair) SetStateForTest(s int) { p.setState(s) }

// PairWarmerConfig PairWarmer 配置
type PairWarmerConfig struct {
	PairCount       int
	RefreshInterval time.Duration
	PrebindTimeout  time.Duration
}

// PairWarmer 热通道对管理器
type PairWarmer struct {
	pool    *clientPool
	mu      sync.RWMutex
	pairs   []*HotChannelPair
	primary *HotChannelPair
	config  PairWarmerConfig
	ctx     context.Context
	cancel  context.CancelFunc

	prebindResultCh chan prebindResult

	// pairIDCounter 用于生成展示用的两位十六进制 Pair ID（预绑定内部 connID 仍用 UUID）
	pairIDCounter uint32
}

// prebindResult 预绑定结果
type prebindResult struct {
	connID       string
	uplinkChID   int
	downlinkChID int
	err          error
}

// NewPairWarmer 创建新的 PairWarmer
func NewPairWarmer(pool *clientPool, cfg *Config) *PairWarmer {
	ctx, cancel := context.WithCancel(pool.ctx)
	return &PairWarmer{
		pool: pool,
		config: PairWarmerConfig{
			PairCount:       cfg.HotPairCount,
			RefreshInterval: cfg.HotPairRefreshInterval,
			PrebindTimeout:  3 * time.Second,
		},
		ctx:             ctx,
		cancel:          cancel,
		prebindResultCh: make(chan prebindResult, 8),
	}
}

// AcquirePrimary 获取一个 Ready 状态的 Pair 并增加引用计数。
// 优先返回当前 primary；若 primary 不可用，则扫描 pairs 列表。
// 引用计数自增在读锁内完成，确保 InvalidateChannel（需要写锁）无法在自增与状态检查之间移除 Pair。
func (w *PairWarmer) AcquirePrimary() *HotChannelPair {
	w.mu.RLock()
	defer w.mu.RUnlock()

	tryAcquire := func(pair *HotChannelPair) bool {
		if pair == nil || pair.State() != PairStateReady {
			return false
		}
		atomic.AddInt32(&pair.refs, 1)
		// 二次检查：自增后状态可能已被置为 Draining/Closed，此时放弃
		if pair.State() == PairStateReady {
			return true
		}
		atomic.AddInt32(&pair.refs, -1)
		return false
	}

	if tryAcquire(w.primary) {
		return w.primary
	}
	for _, pair := range w.pairs {
		if pair == w.primary {
			continue
		}
		if tryAcquire(pair) {
			return pair
		}
	}
	return nil
}

// ReleasePair 减少 Pair 引用计数。
// Pair 本身保持 Ready 供后续请求复用；只有处于 Draining 状态且 refs 归零时才会移除。
func (w *PairWarmer) ReleasePair(pair *HotChannelPair) {
	if pair == nil {
		return
	}

	refs := atomic.AddInt32(&pair.refs, -1)
	if refs <= 0 && pair.State() == PairStateDraining && pair.State() != PairStateClosed {
		pair.setState(PairStateClosed)
		w.mu.Lock()
		w.removePair(pair)
		w.ensurePrimaryLocked()
		w.mu.Unlock()
	}
}

// removePair 从池中移除 Pair（调用者需持有 mu.Lock）
func (w *PairWarmer) removePair(pair *HotChannelPair) {
	removedID := pair.ID
	for i := 0; i < len(w.pairs); i++ {
		if w.pairs[i] == pair {
			w.pairs = append(w.pairs[:i], w.pairs[i+1:]...)
			i--
		}
	}
	if w.primary == pair {
		w.primary = nil
	}
	sysLog.Info("[PairWarmer] 移除 Pair %s (状态=%s, refs=%d)", removedID, pairStateString(pair.State()), atomic.LoadInt32(&pair.refs))
}

// InvalidateChannel 废弃包含指定通道的所有 Pair。
// 将 Pair 标记为 Draining；若当前无请求使用（refs<=0）则立即移除，否则等待使用方释放。
func (w *PairWarmer) InvalidateChannel(chID int) {
	w.mu.Lock()
	defer w.mu.Unlock()

	for i := 0; i < len(w.pairs); i++ {
		pair := w.pairs[i]
		if pair.State() == PairStateClosed {
			continue
		}
		if pair.UplinkChID == chID || pair.DownlinkChID == chID {
			if pair.State() != PairStateDraining {
				pair.setState(PairStateDraining)
				sysLog.Info("[PairWarmer] Pair %s 因通道 %d 失效进入 Draining (refs=%d)", pair.ID, chID, atomic.LoadInt32(&pair.refs))
			}
			if atomic.LoadInt32(&pair.refs) <= 0 {
				pair.setState(PairStateClosed)
				sysLog.Info("[PairWarmer] Pair %s 立即移除 (无活跃引用)", pair.ID)
				w.pairs = append(w.pairs[:i], w.pairs[i+1:]...)
				i--
				if w.primary == pair {
					w.primary = nil
				}
			}
		}
	}
	w.ensurePrimaryLocked()
}

// ensurePrimaryLocked 在 primary 为 nil 或不可用时，从 Ready 的 Pair 中选举新的 primary。
// 调用者需持有 mu.Lock。
func (w *PairWarmer) ensurePrimaryLocked() {
	if w.primary != nil && w.primary.State() == PairStateReady {
		return
	}
	oldPrimary := ""
	if w.primary != nil {
		oldPrimary = w.primary.ID
	}
	for _, pair := range w.pairs {
		if pair.State() == PairStateReady {
			w.primary = pair
			if oldPrimary != "" {
				sysLog.Info("[PairWarmer] primary 重新选举: %s -> %s", oldPrimary, pair.ID)
			}
			return
		}
	}
	w.primary = nil
}

// SetPrimaryForTest 仅用于测试设置主 Pair
func (w *PairWarmer) SetPrimaryForTest(pair *HotChannelPair) {
	w.mu.Lock()
	w.primary = pair
	w.mu.Unlock()
}

// PairCountForTest 仅用于测试返回 Pair 数量
func (w *PairWarmer) PairCountForTest() int {
	w.mu.RLock()
	defer w.mu.RUnlock()
	return len(w.pairs)
}

// pairStateString 返回 Pair 状态的可读字符串
func pairStateString(state int) string {
	switch state {
	case PairStateReady:
		return "Ready"
	case PairStateDraining:
		return "Draining"
	case PairStateClosed:
		return "Closed"
	default:
		return fmt.Sprintf("Unknown(%d)", state)
	}
}

// deletePrebindState 删除预绑定临时状态（不调用 Unregister，避免重复加锁和额外日志）
func (w *PairWarmer) deletePrebindState(connID string) {
	w.pool.mu.Lock()
	st := w.pool.conns[connID]
	if st != nil {
		st.closed = true
		delete(w.pool.conns, connID)
	}
	w.pool.mu.Unlock()
}

// BuildPair 使用可用通道列表构建一个 Hot Pair，同步等待预绑定结果
func (w *PairWarmer) BuildPair(available []int) (*HotChannelPair, error) {
	if w.ctx.Err() != nil {
		return nil, fmt.Errorf("PairWarmer 已关闭")
	}
	if len(available) < 2 {
		return nil, fmt.Errorf("可用通道不足，需要至少 2 个通道")
	}

	connID := "prebind-" + uuid.New().String()

	// 在客户端连接池中注册临时状态，使 handleChannel 的 selectDownlink 能正常竞争下行通道。
	w.pool.mu.Lock()
	w.pool.conns[connID] = &clientConnState{
		id:        connID,
		target:    common.PrebindTarget,
		start:     time.Now(),
		connected: make(chan bool, 1),
		closed:    false,
	}
	w.pool.mu.Unlock()

	meta := make([]byte, 1+len(common.PrebindTarget))
	meta[0] = byte(w.pool.config.IPStrategy)
	copy(meta[1:], common.PrebindTarget)

	msg := common.EncodeMessage(common.MsgPrebindRequest, connID, meta, nil)

	// 广播到可用通道
	sent := 0
	for _, chID := range available {
		if err := w.pool.asyncWriteDirect(chID, websocket.BinaryMessage, msg); err != nil {
			// 记录日志但继续其他通道
			sysLog.Info("[PairWarmer] 预绑定请求发送到通道 %d 失败: %v", chID, err)
		} else {
			sent++
		}
	}
	if sent == 0 {
		w.deletePrebindState(connID)
		return nil, fmt.Errorf("无法发送预绑定请求到任何可用通道")
	}

	// 等待预绑定结果
	timer := time.NewTimer(w.config.PrebindTimeout)
	defer timer.Stop()

	for {
		select {
		case <-w.ctx.Done():
			w.deletePrebindState(connID)
			return nil, fmt.Errorf("PairWarmer 已关闭")
		case <-timer.C:
			w.deletePrebindState(connID)
			return nil, fmt.Errorf("预绑定超时")
		case res := <-w.prebindResultCh:
			if res.connID == connID {
				if res.err != nil {
					w.deletePrebindState(connID)
					return nil, res.err
				}
				if w.ctx.Err() != nil {
					w.deletePrebindState(connID)
					return nil, fmt.Errorf("PairWarmer 已关闭")
				}
				pair := &HotChannelPair{
					ID:           w.generatePairID(),
					UplinkChID:   res.uplinkChID,
					DownlinkChID: res.downlinkChID,
					state:        int32(PairStateReady),
					createdAt:    time.Now(),
				}
				w.mu.Lock()
				w.pairs = append(w.pairs, pair)
				wasPrimary := w.primary
				if w.primary == nil || w.primary.State() != PairStateReady {
					w.primary = pair
				}
				w.mu.Unlock()
				// 预绑定成功，清理临时连接状态
				w.deletePrebindState(connID)
				if wasPrimary == nil {
					sysLog.Info("[PairWarmer] 首次构建 Pair %s (上行: %d, 下行: %d)，设为 primary", pair.ID, pair.UplinkChID, pair.DownlinkChID)
				} else if wasPrimary.ID != pair.ID {
					sysLog.Info("[PairWarmer] 构建新 Pair %s (上行: %d, 下行: %d)，当前 primary=%s", pair.ID, pair.UplinkChID, pair.DownlinkChID, wasPrimary.ID)
				} else {
					sysLog.Info("[PairWarmer] 成功构建 Pair %s (上行: %d, 下行: %d)，primary 不变", pair.ID, pair.UplinkChID, pair.DownlinkChID)
				}
				return pair, nil
			}
			// 不匹配的 connID 忽略，继续等待
		}
	}
}

// HandlePrebindResult 由 clientPool.handleChannel 在收到 MsgSelectUplink 时调用
func (w *PairWarmer) HandlePrebindResult(connID string, uplinkChID, downlinkChID int, err error) {
	res := prebindResult{
		connID:       connID,
		uplinkChID:   uplinkChID,
		downlinkChID: downlinkChID,
		err:          err,
	}
	select {
	case w.prebindResultCh <- res:
	default:
	}
}

// pairChannelsEqual 判断两个 Hot Pair 的通道是否完全一致
func pairChannelsEqual(a, b *HotChannelPair) bool {
	if a == nil || b == nil {
		return false
	}
	return a.UplinkChID == b.UplinkChID && a.DownlinkChID == b.DownlinkChID
}

// generatePairID 生成两位十六进制的 Pair ID（用于日志展示），保证与当前存在的 Pair 不重复
func (w *PairWarmer) generatePairID() string {
	for i := 0; i < 256; i++ {
		n := atomic.AddUint32(&w.pairIDCounter, 1) & 0xff
		id := fmt.Sprintf("%02x", n)
		used := false
		w.mu.RLock()
		for _, pair := range w.pairs {
			if pair.ID == id {
				used = true
				break
			}
		}
		w.mu.RUnlock()
		if !used {
			return id
		}
	}
	return "ff" // 理论不可达：Pair 上限 8
}

// discardCandidatePair 移除重建时通道与旧 Pair 一致的冗余候选，保留旧 Pair 继续服务。
// 若候选已被会话引用，则标记 Draining，等引用归零后由 ReleasePair 移除。
func (w *PairWarmer) discardCandidatePair(pair *HotChannelPair) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if atomic.LoadInt32(&pair.refs) <= 0 {
		pair.setState(PairStateClosed)
		w.removePair(pair)
		w.ensurePrimaryLocked()
		return
	}
	pair.setState(PairStateDraining)
}

// invalidatePair 将指定 Pair 标记为 Draining；若当前无会话引用（refs==0）则立即移除并重新选举 primary，
// 避免周期刷新替换路径积累大量无人使用的 Draining Pair。
func (w *PairWarmer) invalidatePair(pair *HotChannelPair) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if pair == nil || pair.State() != PairStateReady {
		return
	}
	pair.setState(PairStateDraining)
	if atomic.LoadInt32(&pair.refs) <= 0 {
		pair.setState(PairStateClosed)
		w.removePair(pair)
		w.ensurePrimaryLocked()
	}
}

// pruneIdleDrainingPairs 清理 refs 为 0 的 Draining Pair（修复历史版本积累的存量）。
func (w *PairWarmer) pruneIdleDrainingPairs() {
	w.mu.Lock()
	defer w.mu.Unlock()
	pruned := false
	for _, pair := range w.pairs {
		if pair.State() == PairStateDraining && atomic.LoadInt32(&pair.refs) <= 0 {
			pair.setState(PairStateClosed)
			w.removePair(pair)
			pruned = true
		}
	}
	if pruned {
		w.ensurePrimaryLocked()
	}
}

// validatePrimaryChannels 验证 primary 的通道是否仍可用；失效时清理并触发重建。
// 返回 true 表示通道全部有效。mode 用于日志显示（单 Pair / 多 Pair）。
func (w *PairWarmer) validatePrimaryChannels(primary *HotChannelPair, mode string) bool {
	if primary == nil || primary.State() != PairStateReady {
		return true
	}
	available := w.pool.availableChannels()
	uplinkValid := false
	downlinkValid := false
	for _, chID := range available {
		if chID == primary.UplinkChID {
			uplinkValid = true
		}
		if chID == primary.DownlinkChID {
			downlinkValid = true
		}
	}
	if !uplinkValid || !downlinkValid {
		sysLog.Info("[PairWarmer] %s模式下 primary %s 的通道已失效 (上行:%d 有效:%v, 下行:%d 有效:%v)，触发重建",
			mode, primary.ID, primary.UplinkChID, uplinkValid, primary.DownlinkChID, downlinkValid)
		if !uplinkValid {
			w.InvalidateChannel(primary.UplinkChID)
		}
		if !downlinkValid {
			w.InvalidateChannel(primary.DownlinkChID)
		}
		return false
	}
	return true
}

// Run 启动 PairWarmer 主循环，监听通道就绪/失效通知并构建/刷新 Pair
func (w *PairWarmer) Run() {
	sysLog.Info("[PairWarmer] 启动运行循环")
	defer sysLog.Info("[PairWarmer] 运行循环已退出")

	var refreshTicker *time.Ticker
	if w.config.RefreshInterval > 0 {
		refreshTicker = time.NewTicker(w.config.RefreshInterval)
		defer refreshTicker.Stop()
	}

	for {
		select {
		case <-w.ctx.Done():
			return
		case chID := <-w.pool.chReadyCh:
			w.tryBuildPairs()
			_ = chID // 日志中可记录，但当前版本不依赖具体 chID
		case chID := <-w.pool.chInvalidCh:
			w.tryRefresh()
			_ = chID
		case <-func() <-chan time.Time {
			if refreshTicker == nil {
				return nil
			}
			return refreshTicker.C
		}():
			w.periodicRefresh()
		}
	}
}

// tryBuildPairs 尝试构建 Hot Pair，直到 Ready 的 Pair 数量达到 PairCount
func (w *PairWarmer) tryBuildPairs() {
	readyCount := 0
	w.mu.RLock()
	for _, pair := range w.pairs {
		if pair.State() == PairStateReady {
			readyCount++
		}
	}
	w.mu.RUnlock()

	if readyCount >= w.config.PairCount {
		return
	}

	available := w.pool.availableChannels()
	if len(available) < 2 {
		sysLog.Info("[PairWarmer] 可用通道不足 (%d)，无法构建 Pair", len(available))
		return
	}

	for readyCount < w.config.PairCount {
		sysLog.Info("[PairWarmer] 尝试构建 Pair (%d/%d)，可用通道: %v", readyCount+1, w.config.PairCount, available)
		pair, err := w.BuildPair(available)
		if err != nil {
			sysLog.Info("[PairWarmer] 构建 Pair 失败: %v", err)
			return
		}
		sysLog.Info("[PairWarmer] 成功构建 Pair %s (上行: %d, 下行: %d)", pair.ID, pair.UplinkChID, pair.DownlinkChID)
		readyCount++
	}
}

// tryRefresh 尝试刷新 Pair，当 primary 不可用时触发重建
func (w *PairWarmer) tryRefresh() {
	w.mu.Lock()
	if w.primary == nil || w.primary.State() != PairStateReady {
		w.ensurePrimaryLocked()
	}
	w.mu.Unlock()

	// 验证 primary 的通道是否真实可用
	w.mu.RLock()
	primary := w.primary
	w.mu.RUnlock()

	if primary != nil && primary.State() == PairStateReady {
		available := w.pool.availableChannels()
		uplinkValid := false
		downlinkValid := false
		for _, chID := range available {
			if chID == primary.UplinkChID {
				uplinkValid = true
			}
			if chID == primary.DownlinkChID {
				downlinkValid = true
			}
		}
		if !uplinkValid || !downlinkValid {
			sysLog.Info("[PairWarmer] primary %s 的通道已失效 (上行:%d 有效:%v, 下行:%d 有效:%v)，标记为 Draining",
				primary.ID, primary.UplinkChID, uplinkValid, primary.DownlinkChID, downlinkValid)
			if !uplinkValid {
				w.InvalidateChannel(primary.UplinkChID)
			}
			if !downlinkValid {
				w.InvalidateChannel(primary.DownlinkChID)
			}
		}
	}

	w.tryBuildPairs()
}

// periodicRefresh 周期性刷新：评估当前 Pair 状态并尝试补充/轮换 Pair。
// 在单 Pair 模式下，通过发送探测请求验证通道质量；
// 在多 Pair 模式下，会将最老的 Ready Pair 标记为 Draining 并重建，
// 从而持续验证通道质量并避免 Pair 长期不变。
func (w *PairWarmer) periodicRefresh() {
	// 清理历史版本遗留的 refs=0 的 Draining Pair，避免列表无限膨胀
	w.pruneIdleDrainingPairs()

	w.mu.RLock()
	var primaryID string
	if w.primary != nil {
		primaryID = w.primary.ID
	}
	readyCount := 0
	stateList := make([]string, 0, len(w.pairs))
	for _, pair := range w.pairs {
		stateList = append(stateList, fmt.Sprintf("%s[%s,refs=%d]", pair.ID, pairStateString(pair.State()), atomic.LoadInt32(&pair.refs)))
		if pair.State() == PairStateReady {
			readyCount++
		}
	}
	w.mu.RUnlock()

	if readyCount >= w.config.PairCount {
		if readyCount <= 1 {
			// 单 Pair 模式：验证通道是否真实可用，而不是直接返回
			sysLog.Info("[PairWarmer] 周期性刷新触发: Ready=%d/%d, primary=%s, allPairs=%v，单 Pair 模式验证通道质量", readyCount, w.config.PairCount, primaryID, stateList)
			w.mu.RLock()
			primary := w.primary
			w.mu.RUnlock()

			if primary != nil && primary.State() == PairStateReady {
				available := w.pool.availableChannels()
				uplinkValid := false
				downlinkValid := false
				for _, chID := range available {
					if chID == primary.UplinkChID {
						uplinkValid = true
					}
					if chID == primary.DownlinkChID {
						downlinkValid = true
					}
				}
				if !uplinkValid || !downlinkValid {
					sysLog.Info("[PairWarmer] 单 Pair 模式下 primary %s 的通道已失效 (上行:%d 有效:%v, 下行:%d 有效:%v)，触发重建",
						primary.ID, primary.UplinkChID, uplinkValid, primary.DownlinkChID, downlinkValid)
					if !uplinkValid {
						w.InvalidateChannel(primary.UplinkChID)
					}
					if !downlinkValid {
						w.InvalidateChannel(primary.DownlinkChID)
					}
					// 触发重建
					w.tryBuildPairs()
					return
				}
				// 通道有效，不需要额外操作
				sysLog.Info("[PairWarmer] 单 Pair 模式下 primary %s 的通道验证通过，保持不变", primary.ID)
				return
			}
		} else {
			// 多 Pair 模式：先验证 primary 通道仍可用（息屏/网络切换后通道可能已断），
			// 再构建候选 Pair 决策。
			// 若候选通道与选定的最老 Ready Pair 完全一致，则放弃候选、保留旧 Pair 继续服务，
			// 避免无意义的重建与废弃；否则将旧 Pair 标记为 Draining，由候选 Pair 顶替。
			w.mu.RLock()
			primary := w.primary
			w.mu.RUnlock()
			if primary != nil && primary.State() == PairStateReady {
				if !w.validatePrimaryChannels(primary, "多 Pair") {
					// InvalidateChannel 已废弃失效 pair，Ready 数不足时补建
					w.tryBuildPairs()
					return
				}
			}
			w.mu.RLock()
			var oldest *HotChannelPair
			for _, pair := range w.pairs {
				if pair.State() == PairStateReady {
					if oldest == nil || pair.createdAt.Before(oldest.createdAt) {
						oldest = pair
					}
				}
			}
			w.mu.RUnlock()
			if oldest == nil {
				w.tryBuildPairs()
				return
			}
			available := w.pool.availableChannels()
			if len(available) < 2 {
				sysLog.Info("[PairWarmer] 周期性刷新触发: Ready=%d/%d，可用通道不足 (%d)，无法构建候选，保留现有 Pair", readyCount, w.config.PairCount, len(available))
				return
			}
			candidate, err := w.BuildPair(available)
			if err != nil {
				sysLog.Info("[PairWarmer] 周期性刷新触发: Ready=%d/%d, 构建候选 Pair 失败: %v，保留现有 Pair", readyCount, w.config.PairCount, err)
				return
			}
			if pairChannelsEqual(candidate, oldest) {
				w.discardCandidatePair(candidate)
				sysLog.Info("[PairWarmer] 周期性刷新触发: 候选 Pair %s 与最旧 Pair %s 通道完全一致 (上行:%d, 下行:%d)，跳过重建，旧 Pair 继续服务",
					candidate.ID, oldest.ID, oldest.UplinkChID, oldest.DownlinkChID)
				return
			}
			if oldest.State() == PairStateReady {
				sysLog.Info("[PairWarmer] 周期性刷新触发: Ready=%d/%d, primary=%s, allPairs=%v，将最老 Pair %s 标记为 Draining 以触发替换", readyCount, w.config.PairCount, primaryID, stateList, oldest.ID)
				// refs==0 时立即移除，避免积累无引用的 Draining Pair
				w.invalidatePair(oldest)
			}
			return
		}
	} else {
		sysLog.Info("[PairWarmer] 周期性刷新触发: Ready=%d/%d (不足), primary=%s, allPairs=%v，尝试补充", readyCount, w.config.PairCount, primaryID, stateList)
	}

	w.tryBuildPairs()

	// 刷新后再次检查 primary 是否变化
	w.mu.RLock()
	var newPrimaryID string
	if w.primary != nil {
		newPrimaryID = w.primary.ID
	}
	w.mu.RUnlock()
	if newPrimaryID != "" && newPrimaryID != primaryID {
		sysLog.Info("[PairWarmer] primary 已切换: %s -> %s", primaryID, newPrimaryID)
	}
}
