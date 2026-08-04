package socks5

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"xclient/gcm/pool"
	"xclient/gcm/protocol"
	"xclient/shared/config"
	"xclient/shared/dns"
	"xclient/shared/logger"
	"xclient/shared/routing"
)

const (
	socks5Version           = 0x05
	authNone                = 0x00
	cmdConnect              = 0x01
	atypIPv4                = 0x01
	atypDomain              = 0x03
	atypIPv6                = 0x04
	downstreamQueueSize     = 64
	downstreamQueueTimeout  = 2 * time.Second
	localClientWriteTimeout = 10 * time.Second
)

// Server SOCKS5 服务器
type Server struct {
	cfg           *config.Config
	log           *logger.Logger
	pool          *pool.ConnectionPool
	dnsCache      *dns.DNSCache
	bypassMatcher *routing.Matcher
	server        net.Listener
	activeTunnels int32
}

// SetBypassMatcher configures destinations that should use a direct socket
// instead of a GCM WebSocket stream.
func (s *Server) SetBypassMatcher(matcher *routing.Matcher) {
	s.bypassMatcher = matcher
}

// NewServer 创建 SOCKS5 服务器
func NewServer(cfg *config.Config, p *pool.ConnectionPool, dc *dns.DNSCache) *Server {
	return &Server{
		cfg:      cfg,
		log:      logger.GetLogger("Socks5"),
		pool:     p,
		dnsCache: dc,
	}
}

// Start 启动 SOCKS5 服务器
func (s *Server) Start() error {
	listener, err := net.Listen("tcp", s.cfg.ListenAddress)
	if err != nil {
		return fmt.Errorf("监听失败: %w", err)
	}

	s.server = listener
	s.log.Info("监听地址: %s", s.cfg.ListenAddress)

	go s.acceptLoop()

	return nil
}

// acceptLoop 接受连接循环
func (s *Server) acceptLoop() {
	for {
		conn, err := s.server.Accept()
		if err != nil {
			// 检查是否为可恢复的系统错误
			if errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EWOULDBLOCK) || errors.Is(err, syscall.EINTR) {
				s.log.Warn("接受连接临时错误: %v, 1秒后重试", err)
				time.Sleep(time.Second)
				continue
			}
			// 永久错误（如 listener 关闭），退出循环
			s.log.Error("接受连接失败: %v", err)
			return
		}

		go s.handleConnection(conn)
	}
}

// handleConnection 处理连接
func (s *Server) handleConnection(clientConn net.Conn) {
	defer clientConn.Close()

	// 设置 TCP_NODELAY，减少小数据包的 Nagle 延迟
	// 对 SOCKS5 握手和 TLS ClientHello 等小包尤为重要
	if tcpConn, ok := clientConn.(*net.TCPConn); ok {
		tcpConn.SetNoDelay(true)
	}

	clientAddr := clientConn.RemoteAddr().String()
	s.log.Debug("新客户端连接: %s", clientAddr)

	// 1. 认证阶段
	if err := s.handleAuth(clientConn); err != nil {
		s.log.Debug("认证失败: %v", err)
		return
	}

	// 2. 请求阶段
	originalHost, resolvedHost, port, err := s.handleRequest(clientConn)
	if err != nil {
		s.log.Debug("请求处理失败: %v", err)
		return
	}

	// 3. 创建隧道
	s.createTunnel(clientConn, originalHost, resolvedHost, port)
}

// handleAuth 处理认证
func (s *Server) handleAuth(conn net.Conn) error {
	// 设置读取超时
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	defer conn.SetReadDeadline(time.Time{})

	// 读取前 2 字节: [版本, 方法数量]
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return fmt.Errorf("读取认证头失败: %w", err)
	}

	if header[0] != socks5Version {
		return fmt.Errorf("不支持的 SOCKS 版本: %d", header[0])
	}

	// 读取方法列表
	nMethods := int(header[1])
	if nMethods > 0 {
		methods := make([]byte, nMethods)
		if _, err := io.ReadFull(conn, methods); err != nil {
			return fmt.Errorf("读取认证方法失败: %w", err)
		}
	}

	// 响应：无需认证
	_, err := conn.Write([]byte{socks5Version, authNone})
	return err
}

// handleRequest 处理请求
// 返回: 原始主机名(用于日志), 解析后的主机(用于连接), 端口, 错误
func (s *Server) handleRequest(conn net.Conn) (originalHost, resolvedHost string, port uint16, err error) {
	// 设置读取超时
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	defer conn.SetReadDeadline(time.Time{})

	// 读取固定头部: [版本, 命令, 保留, 地址类型] = 4 字节
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil {
		return "", "", 0, fmt.Errorf("读取请求头失败: %w", err)
	}

	// 检查版本和命令
	if header[0] != socks5Version {
		return "", "", 0, fmt.Errorf("不支持的 SOCKS 版本: %d", header[0])
	}

	if header[1] != cmdConnect {
		_, err := conn.Write([]byte{socks5Version, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		if err != nil {
			return "", "", 0, fmt.Errorf("发送错误响应失败: %w", err)
		}
		return "", "", 0, fmt.Errorf("不支持的命令: %d", header[1])
	}

	// 解析目标地址
	addrType := header[3]

	switch addrType {
	case atypIPv4:
		// IPv4: 4 字节 IP + 2 字节端口
		addrBuf := make([]byte, 6)
		if _, err := io.ReadFull(conn, addrBuf); err != nil {
			return "", "", 0, fmt.Errorf("读取 IPv4 地址失败: %w", err)
		}
		originalHost = fmt.Sprintf("%d.%d.%d.%d", addrBuf[0], addrBuf[1], addrBuf[2], addrBuf[3])
		resolvedHost = originalHost
		port = binary.BigEndian.Uint16(addrBuf[4:6])
		s.log.Debug("IPv4 请求: %s:%d", originalHost, port)

	case atypDomain:
		// 先读取域名长度 (1 字节)
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return "", "", 0, fmt.Errorf("读取域名长度失败: %w", err)
		}
		domainLen := int(lenBuf[0])

		// 读取域名 + 端口
		domainBuf := make([]byte, domainLen+2)
		if _, err := io.ReadFull(conn, domainBuf); err != nil {
			return "", "", 0, fmt.Errorf("读取域名数据失败: %w", err)
		}
		domain := string(domainBuf[:domainLen])
		port = binary.BigEndian.Uint16(domainBuf[domainLen:])
		originalHost = domain
		resolvedHost = domain

		s.log.Debug("域名请求: %s", domain)

		// DNS 预解析
		if s.cfg.EnableDoH {
			if ip, _, err := s.dnsCache.ResolveAny(domain); err == nil {
				s.log.Debug("DNS 解析: %s -> %s", domain, ip)
				resolvedHost = ip
			}
		}

	case atypIPv6:
		// IPv6: 16 字节 IP + 2 字节端口
		addrBuf := make([]byte, 18)
		if _, err := io.ReadFull(conn, addrBuf); err != nil {
			return "", "", 0, fmt.Errorf("读取 IPv6 地址失败: %w", err)
		}
		originalHost = net.IP(addrBuf[:16]).String()
		resolvedHost = originalHost
		port = binary.BigEndian.Uint16(addrBuf[16:18])
		s.log.Debug("IPv6 请求: %s:%d", originalHost, port)

	default:
		_, err := conn.Write([]byte{socks5Version, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		if err != nil {
			return "", "", 0, fmt.Errorf("发送错误响应失败: %w", err)
		}
		return "", "", 0, fmt.Errorf("不支持的地址类型: %d", addrType)
	}

	// IPv6 格式修正
	if ip := net.ParseIP(resolvedHost); ip != nil && ip.To4() == nil {
		resolvedHost = fmt.Sprintf("[%s]", resolvedHost)
	}

	s.log.Debug("收到代理请求 -> %s:%d (解析后: %s:%d)", originalHost, port, resolvedHost, port)

	return originalHost, resolvedHost, port, nil
}

// createTunnel 创建隧道
// originalHost: 原始主机名（用于日志显示）
// resolvedHost: 解析后的主机（用于实际连接）
func (s *Server) createTunnel(clientConn net.Conn, originalHost, resolvedHost string, port uint16) {
	if s.bypassMatcher != nil && s.bypassMatcher.Match(originalHost, resolvedHost) {
		s.createDirectTunnel(clientConn, originalHost, resolvedHost, port)
		return
	}

	targetAddr := fmt.Sprintf("%s:%d", resolvedHost, port)
	requestStartTime := time.Now()

	// TunnelTimeout only bounds connection acquisition and the CONNECT handshake.
	// It must not impose a lifetime on an established TCP tunnel.
	ctx, cancel := context.WithTimeout(context.Background(), s.cfg.GetTunnelTimeout())
	connItem, streamID, err := s.pool.GetConnectionWithStream(ctx, targetAddr)
	cancel()
	if err != nil {
		s.log.Warn("获取连接失败: %v", err)
		return
	}

	wsID := connItem.ConnectionID
	connIDStr := fmt.Sprintf("%02x%02x%02x", wsID[0], wsID[1], wsID[2])
	streamIDStr := protocol.StreamIDToString(streamID)

	// 日志使用原始主机名
	s.log.Info("新请求 -> %s:%d | WS[%s] Stream[%s]", originalHost, port, connIDStr, streamIDStr)

	atomic.AddInt32(&s.activeTunnels, 1)
	defer atomic.AddInt32(&s.activeTunnels, -1)

	closed := make(chan struct{})
	connectedSignal := make(chan struct{})
	downstream := make(chan []byte, downstreamQueueSize)
	var connected atomic.Bool
	var bytesSent atomic.Int64
	var bytesReceived atomic.Int64

	var cleanupStarted atomic.Bool
	cleanup := func() {
		if !cleanupStarted.CompareAndSwap(false, true) {
			return
		}

		// 主动发送 CLOSE 消息到 Worker，通知 Stream 关闭
		closeMsg := protocol.NewCloseMessage(streamID)
		if err := connItem.WriteMessage(websocket.BinaryMessage, closeMsg.Encode()); err != nil {
			s.log.Debug("发送 CLOSE 消息失败: %v", err)
		} else {
			s.log.Debug("发送 CLOSE 消息 -> Stream[%s]", streamIDStr)
		}

		clientConn.Close()
		targetAddr, _ := s.pool.UnregisterStreamHandler(connItem, streamID)
		s.pool.ReleaseConnection(connItem)
		elapsed := time.Since(requestStartTime)
		sent := bytesSent.Load()
		received := bytesReceived.Load()
		if sent > 0 || received > 0 {
			totalBytes := sent + received
			speedKBps := float64(totalBytes) / 1024.0 / elapsed.Seconds()
			s.log.Info("请求完成 -> %s:%d | WS[%s] Stream[%s] 耗时=%dms ↑%s ↓%s 速度=%.1fKB/s",
				originalHost, port, connIDStr, streamIDStr, elapsed.Milliseconds(),
				formatBytes(sent), formatBytes(received), speedKBps)
		}
		s.log.Debug("清理完成: WS[%s] Stream[%s] -> %s", connIDStr, streamIDStr, targetAddr)

		close(closed)
	}

	handler := &pool.StreamHandler{
		OnMessage: func(msg *protocol.Message) {
			if msg.StreamID != streamID {
				return
			}

			switch msg.Type {
			case protocol.MsgTypeConnected:
				if connected.CompareAndSwap(false, true) {
					// 记录请求成功
					connItem.RecordSuccess()
					connectLatency := time.Since(requestStartTime)
					stream := s.pool.GetStream(connItem, streamID)
					if stream != nil {
						stream.RecordRTT(connectLatency)
						stream.RecordSuccess()
					}
					s.log.Info("连接建立 -> %s:%d | WS[%s] Stream[%s] 延迟=%dms",
						originalHost, port, connIDStr, streamIDStr, connectLatency.Milliseconds())
					close(connectedSignal)
				}
			case protocol.MsgTypeData:
				if !connected.Load() || len(msg.Data) == 0 {
					return
				}
				data := append([]byte(nil), msg.Data...)
				switch enqueueDownstream(downstream, closed, data, downstreamQueueTimeout) {
				case downstreamClosed:
					return
				case downstreamTimedOut:
					s.log.Warn("下行队列拥塞，关闭 Stream[%s] 以保护 WebSocket 读循环", streamIDStr)
					connItem.RecordFailure()
					go cleanup()
				}
			case protocol.MsgTypeClose:
				if !connected.Load() {
					connItem.RecordFailure()
					stream := s.pool.GetStream(connItem, streamID)
					if stream != nil {
						stream.RecordTimeout()
					}
					s.log.Warn("连接建立前失败: %s:%d | WS[%s] Stream[%s]",
						originalHost, port, connIDStr, streamIDStr)
				}
				go cleanup()
			}
		},
		OnClose: func() {
			cleanup()
		},
		OnCleanup: func() {
			// 清理工作已在 cleanup 中处理
		},
	}

	// Register before CONNECT so a fast CONNECTED response cannot be lost.
	s.pool.RegisterStreamHandler(connItem, streamID, handler, targetAddr)

	connectMsg := protocol.NewConnectMessage(streamID, resolvedHost, port)
	if err := connItem.WriteMessage(websocket.BinaryMessage, connectMsg.Encode()); err != nil {
		s.log.Error("发送 CONNECT 消息失败: %v", err)
		connItem.RecordFailure()
		s.pool.RetireConnection(connItem, "发送 CONNECT 消息失败")
		return
	}

	// Optimistically acknowledge SOCKS after CONNECT is on the wire. The
	// registered handler now guarantees CONNECTED cannot be missed.
	socks5Reply := []byte{socks5Version, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
	if err := writeAllWithDeadline(clientConn, socks5Reply, localClientWriteTimeout); err != nil {
		s.log.Debug("乐观发送 SOCKS5 响应失败: %v", err)
		connItem.RecordFailure()
		cleanup()
		return
	}

	// Keep local TCP backpressure away from the single WebSocket read loop so
	// control frames and other multiplexed streams continue to make progress.
	// Start this only after the SOCKS reply to preserve write ordering.
	go func() {
		for {
			select {
			case <-closed:
				return
			case data := <-downstream:
				if err := writeAllWithDeadline(clientConn, data, localClientWriteTimeout); err != nil {
					s.log.Debug("写入客户端失败: %v", err)
					cleanup()
					return
				}
				bytesReceived.Add(int64(len(data)))
				connItem.Traffic.AddRecv(int64(len(data)))
			}
		}
	}()

	// 客户端数据转发
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := clientConn.Read(buf)
			if err != nil {
				// 连接关闭（包括 EOF）都需要清理
				cleanup()
				return
			}

			// The protocol has no WINDOW_UPDATE message. WebSocket write
			// backpressure is the only valid upload flow control; a cumulative
			// local window would permanently stall long-lived streams.
			dataMsg := protocol.NewDataMessage(streamID, buf[:n])
			if err := connItem.WriteMessage(websocket.BinaryMessage, dataMsg.Encode()); err != nil {
				s.pool.RetireConnection(connItem, "发送数据消息失败")
				cleanup()
				return
			}
			bytesSent.Add(int64(n))
			connItem.Traffic.AddSent(int64(n))
		}
	}()

	if waitForTunnel(closed, connectedSignal, s.cfg.GetTunnelTimeout()) == tunnelClosed {
		if !connected.Load() {
			s.log.Debug("连接未建立即关闭: %s:%d | WS[%s] Stream[%s]",
				originalHost, port, connIDStr, streamIDStr)
		}
		return
	}

	s.log.Warn("隧道超时: %s:%d | WS[%s] Stream[%s] 延迟=%dms",
		originalHost, port, connIDStr, streamIDStr, time.Since(requestStartTime).Milliseconds())
	connItem.RecordFailure()
	stream := s.pool.GetStream(connItem, streamID)
	if stream != nil {
		stream.RecordTimeout()
	}
	s.pool.RetireConnection(connItem, "隧道建立超时")
	cleanup()
}

type tunnelWaitResult uint8

const (
	tunnelClosed tunnelWaitResult = iota
	tunnelConnectTimeout
)

func waitForTunnel(closed, connected <-chan struct{}, timeout time.Duration) tunnelWaitResult {
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case <-closed:
		return tunnelClosed
	case <-connected:
		<-closed
		return tunnelClosed
	case <-timer.C:
		// CONNECTED and the timer can become ready together. Prefer the
		// established tunnel and wait for a real endpoint close.
		select {
		case <-connected:
			<-closed
			return tunnelClosed
		default:
			return tunnelConnectTimeout
		}
	}
}

func writeAllWithDeadline(conn net.Conn, data []byte, timeout time.Duration) error {
	if timeout > 0 {
		if err := conn.SetWriteDeadline(time.Now().Add(timeout)); err != nil {
			return err
		}
		defer conn.SetWriteDeadline(time.Time{}) //nolint:errcheck
	}
	for len(data) > 0 {
		n, err := conn.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrUnexpectedEOF
		}
		data = data[n:]
	}
	return nil
}

type downstreamEnqueueResult uint8

const (
	downstreamQueued downstreamEnqueueResult = iota
	downstreamClosed
	downstreamTimedOut
)

func enqueueDownstream(queue chan<- []byte, closed <-chan struct{}, data []byte, timeout time.Duration) downstreamEnqueueResult {
	select {
	case <-closed:
		return downstreamClosed
	default:
	}

	select {
	case queue <- data:
		return downstreamQueued
	case <-closed:
		return downstreamClosed
	default:
	}

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case queue <- data:
		return downstreamQueued
	case <-closed:
		return downstreamClosed
	case <-timer.C:
		return downstreamTimedOut
	}
}

func (s *Server) createDirectTunnel(clientConn net.Conn, originalHost, resolvedHost string, port uint16) {
	host := strings.TrimSuffix(strings.TrimPrefix(resolvedHost, "["), "]")
	targetAddr := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	startedAt := time.Now()

	ctx, cancel := context.WithTimeout(context.Background(), s.cfg.GetTunnelTimeout())
	targetConn, err := (&net.Dialer{}).DialContext(ctx, "tcp", targetAddr)
	cancel()
	if err != nil {
		s.log.Warn("直连绕过失败 -> %s:%d: %v", originalHost, port, err)
		_, _ = clientConn.Write([]byte{socks5Version, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer targetConn.Close()

	if _, err := clientConn.Write([]byte{socks5Version, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		s.log.Debug("发送直连 SOCKS5 响应失败: %v", err)
		return
	}

	s.log.Info("直连绕过 -> %s:%d", originalHost, port)
	atomic.AddInt32(&s.activeTunnels, 1)
	defer atomic.AddInt32(&s.activeTunnels, -1)

	type transferResult struct {
		upload bool
		bytes  int64
	}
	completed := make(chan transferResult)
	go func() {
		n, _ := io.Copy(targetConn, clientConn)
		if tcpConn, ok := targetConn.(*net.TCPConn); ok {
			_ = tcpConn.CloseWrite()
		}
		completed <- transferResult{upload: true, bytes: n}
	}()
	go func() {
		n, _ := io.Copy(clientConn, targetConn)
		if tcpConn, ok := clientConn.(*net.TCPConn); ok {
			_ = tcpConn.CloseWrite()
		}
		completed <- transferResult{bytes: n}
	}()

	first := <-completed
	second := <-completed
	var bytesSent, bytesReceived int64
	for _, result := range []transferResult{first, second} {
		if result.upload {
			bytesSent = result.bytes
		} else {
			bytesReceived = result.bytes
		}
	}
	s.log.Info("直连完成 -> %s:%d | 耗时=%dms ↑%s ↓%s",
		originalHost, port, time.Since(startedAt).Milliseconds(), formatBytes(bytesSent), formatBytes(bytesReceived))
}

// Close 关闭服务器
func (s *Server) Close() error {
	if s.server != nil {
		return s.server.Close()
	}
	return nil
}

// formatBytes 将字节数格式化为人类可读的字符串
func formatBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%dB", n)
	}
	div, exp := int64(unit), 0
	for n2 := n; n2/unit >= unit && exp < 5; n2 /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f%cB", float64(n)/float64(div), "KMGTPE"[exp])
}
