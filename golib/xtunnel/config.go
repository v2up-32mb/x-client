package xtunnel

import (
	"errors"
	"strings"
	"time"

	common "xclient/xtunnel/protocol"
)

// 各种错误定义
var (
	ErrInvalidServerAddr   = errors.New("invalid server address")
	ErrInvalidConnections  = errors.New("connections must be positive")
	ErrInvalidTimeout      = errors.New("timeout must be positive")
	ErrClientNotStarted    = errors.New("client not started")
	ErrClientAlreadyClosed = errors.New("client already closed")
)

// Config 客户端配置
type Config struct {
	// 服务端配置
	ServerAddr  string // WebSocket 服务器地址 (wss://...)
	Token       string // 认证令牌
	Connections int    // 每个 WebSocket 连接数量
	ClientID    string // 客户端唯一标识（空则自动生成）

	// 网络配置
	DialTimeout      time.Duration // 拨号超时
	HandshakeTimeout time.Duration // 握手超时
	ReadTimeout      time.Duration // 读超时
	WriteTimeout     time.Duration // 写超时
	PingInterval     time.Duration // Ping 间隔
	ReconnectDelay   time.Duration // 重连延迟
	ConnectTimeout   time.Duration // 本地代理等待远端建链超时

	// ECH 配置
	EnableECH          bool   // 是否启用 ECH
	ECHDomain          string // ECH 查询域名
	DNSServer          string // DNS 服务器
	InsecureSkipVerify bool   // 是否跳过证书验证

	// IP 策略
	IPStrategy common.IPStrategy // IP 地址解析策略

	// 中转节点
	RelayNodes []string // 中转节点列表

	// UDP 拦截端口
	UDPBlockedPorts []int // UDP 拦截端口列表

	// 缓冲区大小
	ReadBufferSize  int // 读缓冲区大小
	WriteBufferSize int // 写缓冲区大小

	// 背压控制
	BackpressureLimitBytes int           // 全局队列背压阈值（字节），0 表示使用默认值 1MB
	WriteQueueWaitTimeout  time.Duration // 写队列满时的等待超时，0 表示使用默认值 100ms

	// SOCKS5 连接限制
	MaxSOCKS5Connections int // SOCKS5 最大并发连接数 (0 表示无限制)

	// Hot Pair 配置
	EnableHotPair          bool          // 是否启用热通道对
	HotPairCount           int           // Hot Pair 数量，默认 1
	HotPairRefreshInterval time.Duration // Pair 刷新间隔，默认 30s

	// 快速重连配置
	FastRetryAttempts       int           // 快速重试次数，默认 1
	FastRetryWindow         time.Duration // 快速重试窗口，默认 1s
	MaxFastRetryConsecutive int           // 连续进入 fast retry 的最大次数，默认 3
}

// DefaultConfig 返回带有合理默认值的配置
func DefaultConfig() *Config {
	return &Config{
		Connections:             3,
		DialTimeout:             3 * time.Second,
		HandshakeTimeout:        5 * time.Second,
		ReadTimeout:             15 * time.Second,
		WriteTimeout:            5 * time.Second,
		PingInterval:            5 * time.Second,
		ReconnectDelay:          1 * time.Second,
		ConnectTimeout:          15 * time.Second,
		EnableECH:               true,
		ECHDomain:               "cloudflare-ech.com",
		DNSServer:               "https://v.recipes/dns-query",
		IPStrategy:              common.IPStrategyDefault,
		ReadBufferSize:          64 * 1024,
		WriteBufferSize:         64 * 1024,
		BackpressureLimitBytes:  1024 * 1024, // 默认 1MB
		WriteQueueWaitTimeout:   100 * time.Millisecond,
		UDPBlockedPorts:         []int{443},
		MaxSOCKS5Connections:    1024, // 默认最大 1024 个并发连接
		EnableHotPair:           false,
		HotPairCount:            1,
		HotPairRefreshInterval:  30 * time.Second,
		FastRetryAttempts:       1,
		FastRetryWindow:         1 * time.Second,
		MaxFastRetryConsecutive: 3,
	}
}

// Validate 验证配置
func (c *Config) Validate() error {
	if c.ServerAddr == "" {
		return ErrInvalidServerAddr
	}
	if !strings.HasPrefix(c.ServerAddr, "wss://") && !strings.HasPrefix(c.ServerAddr, "ws://") {
		return errors.New("server address must start with wss:// or ws://")
	}

	if c.Connections <= 0 {
		return ErrInvalidConnections
	}

	if c.DialTimeout <= 0 || c.HandshakeTimeout <= 0 || c.ReadTimeout <= 0 || c.WriteTimeout <= 0 || c.PingInterval <= 0 || c.ReconnectDelay <= 0 || c.ConnectTimeout <= 0 {
		return ErrInvalidTimeout
	}
	if c.MaxSOCKS5Connections < 0 {
		return errors.New("max socks5 connections cannot be negative")
	}

	return nil
}
