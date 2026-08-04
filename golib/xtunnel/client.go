package xtunnel

import (
	"context"
	"fmt"
	"sync"

	"github.com/google/uuid"
)

// Client 客户端接口
type Client struct {
	config  *Config
	pool    *clientPool
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	started bool
}

// NewClient 创建新的客户端实例
func NewClient(cfg *Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}

	if cfg.ClientID == "" {
		cfg.ClientID = uuid.NewString()
	}

	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{
		config: cfg,
		ctx:    ctx,
		cancel: cancel,
	}

	// 创建连接池
	pool, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		cancel()
		return nil, err
	}
	c.pool = pool

	return c, nil
}

// Start 建立与服务器的连接
func (c *Client) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.started {
		return fmt.Errorf("client already started")
	}

	// 准备中转节点
	var relayNodes []string
	if len(c.config.RelayNodes) > 0 {
		for _, addr := range c.config.RelayNodes {
			relayNodes = append(relayNodes, addr)
		}
	}

	// 启动连接池
	c.pool.Start(relayNodes)

	c.started = true
	sysLog.Info("[客户端] 已启动")
	return nil
}

// Shutdown 优雅关闭客户端
func (c *Client) Shutdown() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.started {
		return nil
	}

	c.cancel()
	c.pool.Shutdown()

	c.started = false
	sysLog.Info("[客户端] 已关闭")
	return nil
}

// ListenSOCKS5 启动 SOCKS5 代理监听器
func (c *Client) ListenSOCKS5(addr string) error {
	return c.pool.ListenSOCKS5(addr)
}

// ListenHTTP 启动 HTTP Proxy 监听器
func (c *Client) ListenHTTP(addr string) error {
	return c.pool.ListenHTTP(addr)
}

// Stats 返回客户端统计信息
func (c *Client) Stats() *Stats {
	return c.pool.Stats()
}

// Stats 客户端统计信息
type Stats struct {
	Connections    int
	ActiveChannels int
	RelayNodes     int
	BytesSent      uint64
	BytesReceived  uint64
}

// RegisterTCP 注册 TCP 连接
func (c *Client) RegisterTCP(target string) (string, *clientConnState, error) {
	connID := uuid.New().String()
	connState := &clientConnState{
		id:        connID,
		target:    target,
		connected: make(chan bool, 1),
	}

	c.pool.mu.Lock()
	c.pool.conns[connID] = connState
	c.pool.mu.Unlock()

	return connID, connState, nil
}
