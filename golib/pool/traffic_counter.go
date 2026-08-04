package pool

import (
	"fmt"
	"sync/atomic"
	"time"
)

// TrafficCounter 原子流量计数器
// 用于统计连接级别的上传/下载流量和 Stream 数量
type TrafficCounter struct {
	bytesSent     int64
	bytesRecv     int64
	streamCount   int64 // 经过的 Stream 总数（累计）
	activeStreams int64 // 当前活跃 Stream 数量

	// 速率统计
	lastSentUpdate   int64   // 上次发送流量更新时间 (Unix 纳秒)
	lastRecvUpdate   int64   // 上次接收流量更新时间 (Unix 纳秒)
	lastSentBytes    int64   // 上次发送字节数
	lastRecvBytes    int64   // 上次接收字节数
	avgSendRate      float64 // 平均发送速率 (字节/秒)
	avgRecvRate      float64 // 平均接收速率 (字节/秒)
	maxSendRate      float64 // 最大发送速率 (字节/秒)
	maxRecvRate      float64 // 最大接收速率 (字节/秒)
	totalSendSamples int64   // 发送速率采样次数
	totalRecvSamples int64   // 接收速率采样次数
}

// AddSent 增加发送字节数（客户端 → WebSocket → Worker）
func (c *TrafficCounter) AddSent(n int64) {
	atomic.AddInt64(&c.bytesSent, n)
}

// AddRecv 增加接收字节数（Worker → WebSocket → 客户端）
func (c *TrafficCounter) AddRecv(n int64) {
	atomic.AddInt64(&c.bytesRecv, n)
}

// IncStream 增加 Stream 计数（累计 + 活跃）
func (c *TrafficCounter) IncStream() {
	atomic.AddInt64(&c.streamCount, 1)
	atomic.AddInt64(&c.activeStreams, 1)
}

// DecStream 减少 Stream 活跃计数
func (c *TrafficCounter) DecStream() {
	atomic.AddInt64(&c.activeStreams, -1)
}

// GetSnapshot 获取当前快照（原子操作）
// 返回：发送字节数、接收字节数、当前活跃 Stream 数量
func (c *TrafficCounter) GetSnapshot() (sent, recv, streams int64) {
	return atomic.LoadInt64(&c.bytesSent),
		atomic.LoadInt64(&c.bytesRecv),
		atomic.LoadInt64(&c.activeStreams) // 返回活跃 Stream 数量
}

// Reset 重置计数器
func (c *TrafficCounter) Reset() {
	atomic.StoreInt64(&c.bytesSent, 0)
	atomic.StoreInt64(&c.bytesRecv, 0)
	atomic.StoreInt64(&c.streamCount, 0)
	atomic.StoreInt64(&c.activeStreams, 0)
	c.lastSentUpdate = 0
	c.lastRecvUpdate = 0
	c.lastSentBytes = 0
	c.lastRecvBytes = 0
	c.avgSendRate = 0
	c.avgRecvRate = 0
	c.maxSendRate = 0
	c.maxRecvRate = 0
	c.totalSendSamples = 0
	c.totalRecvSamples = 0
}

// UpdateRates 更新速率统计（应该定期调用，如每秒）
func (c *TrafficCounter) UpdateRates(now time.Time) {
	nowNano := now.UnixNano()
	currentSent := atomic.LoadInt64(&c.bytesSent)
	currentRecv := atomic.LoadInt64(&c.bytesRecv)

	// 计算发送速率
	if c.lastSentUpdate > 0 {
		timeDelta := nowNano - c.lastSentUpdate
		if timeDelta > 0 {
			bytesDelta := currentSent - c.lastSentBytes
			if bytesDelta >= 0 {
				// 计算瞬时速率 (字节/秒)
				instantRate := float64(bytesDelta) * 1e9 / float64(timeDelta)

				// 更新最大速率
				if instantRate > c.maxSendRate {
					c.maxSendRate = instantRate
				}

				// 更新平均速率 (使用移动平均)
				c.totalSendSamples++
				// 新平均 = 旧平均 * (n-1)/n + 当前值/n
				c.avgSendRate = c.avgSendRate*float64(c.totalSendSamples-1)/float64(c.totalSendSamples) + instantRate/float64(c.totalSendSamples)
			}
		}
	}
	c.lastSentUpdate = nowNano
	c.lastSentBytes = currentSent

	// 计算接收速率
	if c.lastRecvUpdate > 0 {
		timeDelta := nowNano - c.lastRecvUpdate
		if timeDelta > 0 {
			bytesDelta := currentRecv - c.lastRecvBytes
			if bytesDelta >= 0 {
				// 计算瞬时速率 (字节/秒)
				instantRate := float64(bytesDelta) * 1e9 / float64(timeDelta)

				// 更新最大速率
				if instantRate > c.maxRecvRate {
					c.maxRecvRate = instantRate
				}

				// 更新平均速率 (使用移动平均)
				c.totalRecvSamples++
				c.avgRecvRate = c.avgRecvRate*float64(c.totalRecvSamples-1)/float64(c.totalRecvSamples) + instantRate/float64(c.totalRecvSamples)
			}
		}
	}
	c.lastRecvUpdate = nowNano
	c.lastRecvBytes = currentRecv
}

// GetRateSnapshot 获取速率统计快照
// 返回：平均发送速率、最大发送速率、平均接收速率、最大接收速率 (单位: 字节/秒)
func (c *TrafficCounter) GetRateSnapshot() (avgSent, maxSent, avgRecv, maxRecv float64) {
	return c.avgSendRate, c.maxSendRate, c.avgRecvRate, c.maxRecvRate
}

// ConnStats 连接统计（用于历史记录，可选）
type ConnStats struct {
	WSID        [3]byte
	RelayAddr   string
	CreatedAt   time.Time
	ClosedAt    time.Time
	BytesSent   int64
	BytesRecv   int64
	StreamCount int64
}

// formatBytes 格式化字节数为可读格式
// 例如: 1024 -> "1.0 KB", 1536000 -> "1.5 MB"
func formatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}
