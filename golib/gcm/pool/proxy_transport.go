package pool

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"xclient/gcm/protocol"
)

// tunnelConn WebSocket 隧道连接
// 实现 net.Conn 接口，用于在 WebSocket Stream 上进行 TLS/HTTP 通信
type tunnelConn struct {
	connItem *ConnItem
	streamID byte
	target   string

	readChan  chan []byte
	closeChan chan struct{}
	mu        sync.Mutex
	closed    bool

	// 本地/远程地址
	localAddr  net.Addr
	remoteAddr net.Addr
}

// newTunnelConn 创建隧道连接
func newTunnelConn(connItem *ConnItem, streamID byte, target string) *tunnelConn {
	return &tunnelConn{
		connItem:   connItem,
		streamID:   streamID,
		target:     target,
		readChan:   make(chan []byte, 100), // 缓冲区
		closeChan:  make(chan struct{}),
		localAddr:  &tunnelAddr{net: "tcp", addr: "127.0.0.1:0"},
		remoteAddr: &tunnelAddr{net: "tcp", addr: target},
	}
}

// tunnelAddr 实现 net.Addr
type tunnelAddr struct {
	net, addr string
}

func (a *tunnelAddr) Network() string { return a.net }
func (a *tunnelAddr) String() string  { return a.addr }

// Read 实现net.Conn.Read
func (c *tunnelConn) Read(b []byte) (n int, err error) {
	select {
	case data := <-c.readChan:
		if len(data) > len(b) {
			copy(b, data[:len(b)])
			// 将剩余数据放回队列
			c.mu.Lock()
			select {
			case c.readChan <- data[len(b):]:
			default:
				// 队列满，丢弃
			}
			c.mu.Unlock()
			return len(b), nil
		}
		copy(b, data)
		return len(data), nil
	case <-c.closeChan:
		return 0, io.EOF
	}
}

// Write 实现net.Conn.Write
func (c *tunnelConn) Write(b []byte) (n int, err error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, io.ErrClosedPipe
	}
	c.mu.Unlock()

	dataMsg := protocol.NewDataMessage(c.streamID, b)
	if err := c.connItem.WriteMessage(websocket.BinaryMessage, dataMsg.Encode()); err != nil {
		return 0, fmt.Errorf("tunnelConn write error: %w", err)
	}
	return len(b), nil
}

// Close 实现net.Conn.Close
func (c *tunnelConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.closed {
		return nil
	}
	c.closed = true

	closeMsg := protocol.NewCloseMessage(c.streamID)
	if err := c.connItem.WriteMessage(websocket.BinaryMessage, closeMsg.Encode()); err != nil {
		// 记录但继续关闭（连接即将关闭，无法恢复）
	}

	close(c.closeChan)
	return nil
}

// LocalAddr 实现net.Conn.LocalAddr
func (c *tunnelConn) LocalAddr() net.Addr {
	return c.localAddr
}

// RemoteAddr 实现net.Conn.RemoteAddr
func (c *tunnelConn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

// SetDeadline 实现net.Conn.SetDeadline
func (c *tunnelConn) SetDeadline(t time.Time) error {
	// 暂不支持
	return nil
}

// SetReadDeadline 实现net.Conn.SetReadDeadline
func (c *tunnelConn) SetReadDeadline(t time.Time) error {
	// 暂不支持
	return nil
}

// SetWriteDeadline 实现net.Conn.SetWriteDeadline
func (c *tunnelConn) SetWriteDeadline(t time.Time) error {
	// 暂不支持
	return nil
}

// ProxyTransport HTTP over WebSocket 代理传输层
// 实现 http.RoundTripper 接口，通过 WebSocket 隧道发送 HTTP/HTTPS 请求
type ProxyTransport struct {
	pool *ConnectionPool
}

// NewProxyTransport 创建代理传输层
func NewProxyTransport(pool *ConnectionPool) *ProxyTransport {
	return &ProxyTransport{
		pool: pool,
	}
}

// RoundTrip 实现 http.RoundTripper 接口
func (t *ProxyTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	startTime := time.Now()

	// 1. 解析目标地址
	host := req.URL.Host
	port := uint16(443) // 默认 HTTPS 端口
	if req.URL.Scheme == "http" {
		port = 80
	}

	if strings.Contains(host, ":") {
		parts := strings.Split(host, ":")
		host = parts[0]
		if len(parts) > 1 {
			fmt.Sscanf(parts[1], "%d", &port)
		}
	}

	targetAddr := fmt.Sprintf("%s:%d", host, port)

	// 2. 从连接池获取连接和 Stream
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	connItem, streamID, err := t.pool.GetConnectionWithStream(ctx, targetAddr)
	if err != nil {
		return nil, fmt.Errorf("获取连接失败: %w", err)
	}

	// 3. 先注册 handler（在发送 CONNECT 之前），避免竞态条件
	connectedChan := make(chan struct{}, 1)
	dataChan := make(chan []byte, 100) // DATA 消息缓冲
	closeChan := make(chan struct{}, 1)
	streamRegistered := true // 标记 Stream 是否需要清理

	handler := &StreamHandler{
		OnMessage: func(msg *protocol.Message) {
			if msg.StreamID != streamID {
				return
			}

			switch msg.Type {
			case protocol.MsgTypeConnected:
				select {
				case connectedChan <- struct{}{}:
				default:
				}
			case protocol.MsgTypeData:
				select {
				case dataChan <- msg.Data:
				default:
					// 缓冲区满，丢弃
				}
			case protocol.MsgTypeClose:
				select {
				case closeChan <- struct{}{}:
				default:
				}
			}
		},
		OnClose: func() {
			select {
			case closeChan <- struct{}{}:
			default:
			}
		},
	}

	t.pool.RegisterStreamHandler(connItem, streamID, handler, targetAddr)

	// 4. 发送 CONNECT 消息
	connectMsg := protocol.NewConnectMessage(streamID, host, port)
	if err := connItem.WriteMessage(websocket.BinaryMessage, connectMsg.Encode()); err != nil {
		t.pool.RetireConnection(connItem, "Proxy CONNECT 写入失败")
		t.pool.UnregisterStreamHandler(connItem, streamID)
		return nil, fmt.Errorf("发送 CONNECT 失败: %w", err)
	}

	// 5. 等待 CONNECTED 响应
	select {
	case <-connectedChan:
	case <-closeChan:
		t.pool.UnregisterStreamHandler(connItem, streamID)
		return nil, fmt.Errorf("连接被关闭")
	case <-time.After(5 * time.Second):
		t.pool.RetireConnection(connItem, "Proxy CONNECT 超时")
		t.pool.UnregisterStreamHandler(connItem, streamID)
		return nil, fmt.Errorf("连接超时")
	}

	// 6. 创建 tunnel（使用已注册的 dataChan 接收数据）
	tunnel := &tunnelConn{
		connItem:   connItem,
		streamID:   streamID,
		target:     targetAddr,
		readChan:   dataChan,
		closeChan:  make(chan struct{}),
		localAddr:  &tunnelAddr{net: "tcp", addr: "127.0.0.1:0"},
		remoteAddr: &tunnelAddr{net: "tcp", addr: targetAddr},
	}

	// 启动后台 goroutine 监听 closeChan 并关闭 tunnel
	go func() {
		<-closeChan
		tunnel.Close()
	}()

	// 清理函数：确保 Stream 总是被正确释放
	defer func() {
		if streamRegistered {
			t.pool.UnregisterStreamHandler(connItem, streamID)
			t.pool.ReleaseConnection(connItem)
		}
		// 关闭 tunnel（如果还没有关闭）
		tunnel.Close()
	}()

	// 7. 如果是 HTTPS，建立 TLS 连接
	var conn net.Conn = tunnel

	if req.URL.Scheme == "https" {
		tlsConfig := &tls.Config{
			ServerName: host,
			// 不验证证书（因为是用于 DoH）
			InsecureSkipVerify: true,
			// 使用较旧的 TLS 版本以提高兼容性
			MinVersion: tls.VersionTLS12,
		}

		tlsConn := tls.Client(tunnel, tlsConfig)

		// TLS 握手
		if err := tlsConn.Handshake(); err != nil {
			return nil, fmt.Errorf("TLS 握手失败: %w", err)
		}

		conn = tlsConn
	}

	// 8. 创建 HTTP 客户端连接并发送请求
	// 使用 http.NewRequestWithContext 创建新请求
	// 注意：需要复制请求体

	var bodyReader io.Reader
	if req.Body != nil {
		body, err := io.ReadAll(req.Body)
		if err != nil {
			return nil, fmt.Errorf("读取请求体失败: %w", err)
		}
		bodyReader = bytes.NewReader(body)
	}

	// 构建新的 HTTP 请求
	proxyReq, err := http.NewRequestWithContext(
		context.Background(),
		req.Method,
		req.URL.String(),
		bodyReader,
	)
	if err != nil {
		return nil, fmt.Errorf("创建请求失败: %w", err)
	}

	// 复制请求头
	for key, values := range req.Header {
		for _, value := range values {
			proxyReq.Header.Add(key, value)
		}
	}

	// 9. 发送请求并读取响应
	err = proxyReq.Write(conn)
	if err != nil {
		return nil, fmt.Errorf("发送请求失败: %w", err)
	}

	// 读取响应
	resp, err := http.ReadResponse(bufio.NewReader(conn), proxyReq)
	if err != nil {
		return nil, fmt.Errorf("读取响应失败: %w", err)
	}

	elapsed := time.Since(startTime)
	fmt.Printf("[ProxyTransport] 请求完成: %s://%s%s -> %d，耗时: %dms\n",
		req.URL.Scheme, req.URL.Host, req.URL.RequestURI(), resp.StatusCode, elapsed.Milliseconds())

	// 如果是 4xx/5xx 错误，读取响应体内容用于调试
	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		fmt.Printf("[ProxyTransport] 错误响应 (%d): %s\n", resp.StatusCode, string(body))
		// 错误响应也需要清理资源
		t.pool.UnregisterStreamHandler(connItem, streamID)
		t.pool.ReleaseConnection(connItem)
		tunnel.Close()
		return resp, nil
	}

	// 包装响应体，在关闭时自动清理资源
	// 这样确保无论调用方是否正确关闭响应体，资源都能被释放
	resp.Body = &cleanupReadCloser{
		ReadCloser: resp.Body,
		onClose: func() {
			t.pool.UnregisterStreamHandler(connItem, streamID)
			t.pool.ReleaseConnection(connItem)
			tunnel.Close()
		},
	}

	// 标记为已管理，跳过 defer 清理
	streamRegistered = false

	return resp, nil
}

// cleanupReadCloser 包装 ReadCloser，在关闭时执行清理回调
type cleanupReadCloser struct {
	io.ReadCloser
	onClose func()
}

func (c *cleanupReadCloser) Close() error {
	err := c.ReadCloser.Close()
	if c.onClose != nil {
		c.onClose()
		c.onClose = nil // 防止重复调用
	}
	return err
}
