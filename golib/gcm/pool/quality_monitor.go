package pool

import (
	"fmt"
	"net"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"xclient/gcm/relay"
	"xclient/shared/config"
	"xclient/shared/logger"
)

// ConnectionQualityMonitor 连接质量监控器
type ConnectionQualityMonitor struct {
	pool             *ConnectionPool
	checkInterval    time.Duration // 检查间隔（默认 10 秒）
	degradeThreshold int64         // 劣化阈值（分数 < 60）
	switchCooldown   time.Duration // 切换冷却期（默认 5 分钟）
	lastSwitchTime   time.Time     // 上次切换时间
	mu               sync.Mutex
	log              *logger.Logger
	stopChan         chan struct{}
}

// NewConnectionQualityMonitor 创建连接质量监控器
func NewConnectionQualityMonitor(pool *ConnectionPool, cfg *config.Config, log *logger.Logger) *ConnectionQualityMonitor {
	return &ConnectionQualityMonitor{
		pool:             pool,
		checkInterval:    cfg.GetQualityCheckInterval(),
		degradeThreshold: cfg.QualityDegradeThreshold,
		switchCooldown:   cfg.GetQualityRelaySwitchCooldown(),
		log:              log,
		stopChan:         make(chan struct{}),
	}
}

// Start 启动质量监控循环
func (m *ConnectionQualityMonitor) Start() {
	go m.qualityMonitorLoop()
	m.log.Info("连接质量监控器已启动")
}

// Stop 停止质量监控循环
func (m *ConnectionQualityMonitor) Stop() {
	close(m.stopChan)
	m.log.Info("连接质量监控器已停止")
}

// calculateQualityScore 计算连接质量评分 (0-100)
func (m *ConnectionQualityMonitor) calculateQualityScore(conn *ConnItem) int64 {
	score := int64(100)

	// 1. RTT 评分（权重 40%）
	avgRTT := conn.GetAverageRTT()
	baselineRTT := conn.BaselineRTT

	if baselineRTT > 0 && avgRTT > 0 {
		if avgRTT > baselineRTT*2 {
			score -= 40 // RTT 翻倍，扣 40 分
		} else if avgRTT > baselineRTT*3/2 {
			score -= 20 // RTT 增加 50%，扣 20 分
		}
	}

	// 2. 丢包率评分（权重 40%）
	lossRate := conn.GetLossRate()
	if lossRate > 0.05 {
		score -= 40 // 丢包率 > 5%，扣 40 分
	} else if lossRate > 0.02 {
		score -= 20 // 丢包率 > 2%，扣 20 分
	}

	// 3. 心跳失败评分（权重 20%）
	heartbeatFailures := atomic.LoadInt64(&conn.HeartbeatFailures)
	if heartbeatFailures > 3 {
		score -= 20
	} else if heartbeatFailures > 1 {
		score -= 10
	}

	// 确保分数在 0-100 范围内
	if score < 0 {
		score = 0
	}
	return score
}

// qualityMonitorLoop 质量监控循环
func (m *ConnectionQualityMonitor) qualityMonitorLoop() {
	ticker := time.NewTicker(m.checkInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			m.checkAllConnections()
		case <-m.stopChan:
			return
		}
	}
}

// checkAllConnections 检查所有连接质量
func (m *ConnectionQualityMonitor) checkAllConnections() {
	m.pool.mu.RLock()

	// 收集所有连接（空闲池 + 活跃连接）
	allConns := make([]*ConnItem, 0, len(m.pool.pool)+len(m.pool.managerByConn))
	allConns = append(allConns, m.pool.pool...)

	for conn := range m.pool.managerByConn {
		allConns = append(allConns, conn)
	}
	m.pool.mu.RUnlock()

	degradedCount := 0
	for _, conn := range allConns {
		score := m.calculateQualityScore(conn)
		atomic.StoreInt64(&conn.QualityScore, score)

		// ✅ 新增：同步质量评分到 RelayManager（失败不影响后续劣化检测）
		if conn.RelayAddr != "" {
			host, portStr, err := net.SplitHostPort(conn.RelayAddr)
			if err == nil {
				port, err := strconv.Atoi(portStr)
				if err == nil && port > 0 {
					m.pool.relayManager.UpdateNodeQuality(host, port, float64(score))
				} else {
					m.log.Debug("端口解析失败: %s", portStr)
				}
			} else {
				m.log.Debug("解析 RelayAddr 失败: %s", conn.RelayAddr)
			}
		}

		// 检测劣化（始终执行，不受上面解析失败影响）
		if score < m.degradeThreshold && !conn.IsDegraded {
			conn.qualityMu.Lock()
			conn.IsDegraded = true
			conn.DegradedSince = time.Now()
			conn.qualityMu.Unlock()
			degradedCount++

			connIDStr := formatConnID(conn.ConnectionID)
			m.log.Warn("连接 [%s] 质量劣化 (分数=%d, RTT=%v, 丢包率=%.2f%%)",
				connIDStr, score, conn.GetAverageRTT(), conn.GetLossRate()*100)
		}
	}

	// 重新排序空闲池（评分更新后需要重新排序）
	m.resortIdlePool()

	// 如果有劣化连接，触发节点切换检查
	if degradedCount > 0 {
		m.considerRelaySwitching()
	}
}

// considerRelaySwitching 考虑切换中转节点
func (m *ConnectionQualityMonitor) considerRelaySwitching() {
	m.mu.Lock()
	defer m.mu.Unlock()

	// 检查冷却期
	if time.Since(m.lastSwitchTime) < m.switchCooldown {
		m.log.Debug("切换冷却期内，跳过切换检查")
		return
	}

	// 统计劣化连接使用的节点
	degradedRelays := make(map[string]int)
	m.pool.mu.RLock()
	for conn := range m.pool.managerByConn {
		if conn.IsDegraded {
			degradedRelays[conn.RelayAddr]++
		}
	}
	m.pool.mu.RUnlock()

	// 如果某个节点的劣化连接数 >= 2，触发切换
	for relayAddr, count := range degradedRelays {
		if count >= 2 {
			m.log.Warn("节点 %s 有 %d 个劣化连接，触发节点切换", relayAddr, count)
			m.switchToNewRelay(relayAddr)
			m.lastSwitchTime = time.Now()
			break
		}
	}
}

// switchToNewRelay 切换到新节点
func (m *ConnectionQualityMonitor) switchToNewRelay(oldRelayAddr string) {
	// 1. 使用负载均衡选择新节点（最多尝试 3 次避免选中旧节点）
	var newRelay *relay.RelayNode
	for i := 0; i < 3; i++ {
		newRelay = m.pool.relayManager.GetNextRelayWithLoadBalance()
		if newRelay == nil {
			break
		}
		// 如果选中的不是旧节点，成功
		if fmt.Sprintf("%s:%d", newRelay.IP, newRelay.Port) != oldRelayAddr {
			break
		}
		// 否则继续尝试
		newRelay = nil
	}

	if newRelay == nil {
		m.log.Warn("没有可用的替代节点")
		return
	}

	// 如果 3 次尝试后仍是旧节点，说明只有一个节点
	if fmt.Sprintf("%s:%d", newRelay.IP, newRelay.Port) == oldRelayAddr {
		m.log.Warn("无法找到不同的替代节点（可能只有一个节点）")
		return
	}

	m.log.Info("切换节点: %s -> %s:%d (延迟: %v)",
		oldRelayAddr, newRelay.IP, newRelay.Port, newRelay.Latency)

	// 2. 标记旧节点的连接为"待淘汰"
	m.pool.mu.Lock()
	for conn := range m.pool.managerByConn {
		if conn.RelayAddr == oldRelayAddr {
			conn.qualityMu.Lock()
			conn.IsDegraded = true
			// 缩短 TTL，加速淘汰（将创建时间提前 4 分钟）
			conn.CreatedAt = conn.CreatedAt.Add(-4 * time.Minute)
			conn.qualityMu.Unlock()
		}
	}
	m.pool.mu.Unlock()

	// 3. 预创建新节点的连接（3个）
	for i := 0; i < 3; i++ {
		go m.pool.createConnectionWithRelay(newRelay, "节点切换预热")
	}
}

// resortIdlePool 重新排序空闲池（按质量评分降序）
func (m *ConnectionQualityMonitor) resortIdlePool() {
	m.pool.mu.Lock()
	defer m.pool.mu.Unlock()

	// 使用稳定排序，按质量评分降序排列
	sort.SliceStable(m.pool.pool, func(i, j int) bool {
		scoreI := atomic.LoadInt64(&m.pool.pool[i].QualityScore)
		scoreJ := atomic.LoadInt64(&m.pool.pool[j].QualityScore)
		return scoreI > scoreJ // 降序：高分在前
	})
}
