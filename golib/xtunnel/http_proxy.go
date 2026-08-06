package xtunnel

import (
	"bufio"
	"bytes"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	common "xclient/xtunnel/protocol"
)

// ListenHTTP 启动 HTTP Proxy 监听器
func (p *clientPool) ListenHTTP(addr string) error {
	h, u, pswd, err := parseAuthAndAddr(strings.TrimPrefix(addr, "http://"))
	if err != nil {
		return fmt.Errorf("HTTP代理地址解析失败: %v", err)
	}
	l, err := net.Listen("tcp", h)
	if err != nil {
		return fmt.Errorf("HTTP代理监听失败: %v", err)
	}
	p.trackListener(l)
	sysLog.Info("[客户端] HTTP 代理: %s", h)
	cfgp := &ProxyConfig{Username: u, Password: pswd, Host: h}
	go p.acceptProxyLoop(l, cfgp, p.handleHTTPProxyConn)
	return nil
}

func (p *clientPool) acceptProxyLoop(l net.Listener, cfgp *ProxyConfig, handler func(net.Conn, *ProxyConfig)) {
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) || p.ctx.Err() != nil {
				return
			}
			continue
		}
		if p.socks5Sem != nil {
			// 与 SOCKS5 相同的并发语义：限制并发连接数，拒绝前预留突发等待窗口
			p.acquireProxySlot(c, "HTTP 代理", cfgp, handler)
		} else {
			go handler(c, cfgp)
		}
	}
}

func (p *clientPool) handleHTTPProxyConn(c net.Conn, cfgp *ProxyConfig) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(p.proxyHandshakeTimeout()))

	reader := bufio.NewReader(c)
	req, err := http.ReadRequest(reader)
	if err != nil {
		return
	}
	if !validateHTTPProxyAuth(req, cfgp) {
		writeHTTPProxyResponse(c, http.StatusProxyAuthRequired, "Proxy Authentication Required", map[string]string{
			"Proxy-Authenticate": `Basic realm="x-tunnel"`,
		})
		return
	}
	_ = c.SetDeadline(time.Time{})

	switch req.Method {
	case http.MethodConnect:
		target := ensureHTTPProxyTarget(req.Host, "443")
		// 路由绕过：命中规则直接连接目标，不经过隧道。
		if p.shouldBypass(target) {
			p.handleHTTPProxyDirectConnect(c, target)
			return
		}
		p.handleHTTPProxyConnect(c, target)
	default:
		target := httpProxyTargetFromRequest(req)
		if target == "" {
			writeHTTPProxyResponse(c, http.StatusBadRequest, "Bad Request", nil)
			return
		}
		first, err := buildHTTPRequestForUpstream(req)
		if err != nil {
			writeHTTPProxyResponse(c, http.StatusBadRequest, "Bad Request", nil)
			return
		}
		pending, err := readBufferedProxyBytes(reader)
		if err != nil {
			writeHTTPProxyResponse(c, http.StatusBadRequest, "Bad Request", nil)
			return
		}
		// 路由绕过：命中规则把请求直接转发到目标，不经过隧道。
		if p.shouldBypass(target) {
			p.handleHTTPProxyDirectForward(c, reader, target, first, pending)
			return
		}
		p.handleHTTPProxyForward(c, reader, target, first, pending, "HTTP Proxy")
	}
}

// bufioProxyConn 让 bufio.Reader 中已缓冲的数据优先被读取（HTTP 普通请求直连时，
// 客户端剩余数据可能已读入 bufio 缓冲区，不能直接从底层 conn 读取）。
type bufioProxyConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufioProxyConn) Read(p []byte) (int, error) {
	return b.r.Read(p)
}

// handleHTTPProxyDirectConnect 处理命中路由绕过的 HTTP CONNECT：直接连接目标。
func (p *clientPool) handleHTTPProxyDirectConnect(c net.Conn, target string) {
	targetConn, err := p.dialBypassTarget(target)
	if err != nil {
		sysLog.Warn("[客户端] HTTP CONNECT 直连绕过失败 -> %s: %v", target, err)
		writeHTTPProxyResponse(c, http.StatusBadGateway, "Bad Gateway", nil)
		return
	}
	defer targetConn.Close()

	if _, err := c.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		sysLog.Debug("[客户端] HTTP CONNECT 直连绕过响应失败: %v", err)
		return
	}
	sysLog.Info("[客户端] HTTP CONNECT 直连绕过 -> %s", target)
	relayBypassConnections(c, asTCP(c), targetConn, asTCP(targetConn))
}

// handleHTTPProxyDirectForward 处理命中路由绕过的普通 HTTP 请求：把重建的
// 上游请求与缓冲字节直接发送到目标，再双向转发。
func (p *clientPool) handleHTTPProxyDirectForward(c net.Conn, reader *bufio.Reader, target string, first, pending []byte) {
	targetConn, err := p.dialBypassTarget(target)
	if err != nil {
		sysLog.Warn("[客户端] HTTP 直连绕过失败 -> %s: %v", target, err)
		writeHTTPProxyResponse(c, http.StatusBadGateway, "Bad Gateway", nil)
		return
	}
	defer targetConn.Close()

	if _, err := targetConn.Write(first); err != nil {
		return
	}
	if len(pending) > 0 {
		if _, err := targetConn.Write(pending); err != nil {
			return
		}
	}
	sysLog.Info("[客户端] HTTP 直连绕过 -> %s", target)
	relayBypassConnections(&bufioProxyConn{Conn: c, r: reader}, nil, targetConn, asTCP(targetConn))
}

func (p *clientPool) handleHTTPProxyConnect(c net.Conn, target string) {
	connID, ok := p.waitForHTTPProxyTarget(c, target, "HTTP CONNECT")
	if !ok {
		return
	}
	if _, err := c.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		p.closeProxyConn(connID)
		return
	}
	p.proxyStreamLoop(c, connID)
}

func (p *clientPool) handleHTTPProxyForward(c net.Conn, reader *bufio.Reader, target string, first, pending []byte, reqType string) {
	connID, ok := p.waitForHTTPProxyTarget(c, target, reqType)
	if !ok {
		return
	}
	if err := p.sendProxyData(connID, first); err != nil {
		p.closeProxyConn(connID)
		return
	}
	if err := p.sendProxyData(connID, pending); err != nil {
		p.closeProxyConn(connID)
		return
	}
	p.proxyStreamLoop(reader, connID)
}

func (p *clientPool) waitForHTTPProxyTarget(c net.Conn, target string, reqType string) (string, bool) {
	connID := uuid.New().String()
	p.RegisterAndBroadcastTCP(connID, target, nil, c, reqType)

	p.mu.RLock()
	st := p.conns[connID]
	var connected chan bool
	if st != nil {
		connected = st.connected
	}
	p.mu.RUnlock()
	if connected == nil {
		writeHTTPProxyResponse(c, http.StatusBadGateway, "Bad Gateway", nil)
		p.Unregister(connID)
		return "", false
	}
	select {
	case ok := <-connected:
		if !ok {
			// 无可用通道：返回 502 并清理
			writeHTTPProxyResponse(c, http.StatusBadGateway, "Bad Gateway", nil)
			p.Unregister(connID)
			return "", false
		}
		_ = c.SetDeadline(time.Time{})
		_ = c.SetReadDeadline(time.Time{})
		_ = c.SetWriteDeadline(time.Time{})
		return connID, true
	case <-time.After(p.connectTimeout()):
		writeHTTPProxyResponse(c, http.StatusGatewayTimeout, "Gateway Timeout", nil)
		p.Unregister(connID)
		return "", false
	}
}

func (p *clientPool) closeProxyConn(connID string) {
	if chID, ok := p.GetUplinkChannel(connID); ok {
		_ = p.SendCloseDirect(chID, connID)
	} else {
		p.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgTCPClose, connID, nil, nil))
	}
	p.Unregister(connID)
}

func (p *clientPool) sendProxyData(connID string, data []byte) error {
	if len(data) == 0 {
		return nil
	}
	if chID, ok := p.GetUplinkChannel(connID); ok {
		return p.SendDataDirect(chID, connID, data)
	}
	p.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgTCPData, connID, nil, data))
	return nil
}

func (p *clientPool) proxyStreamLoop(src io.Reader, connID string) {
	defer p.closeProxyConn(connID)

	buf := make([]byte, 32*1024)
	for {
		n, err := src.Read(buf)
		if err != nil {
			return
		}
		if err := p.sendProxyData(connID, buf[:n]); err != nil {
			return
		}
	}
}

func validateHTTPProxyAuth(req *http.Request, cfgp *ProxyConfig) bool {
	if cfgp == nil || cfgp.Username == "" {
		return true
	}
	auth := req.Header.Get("Proxy-Authorization")
	if !strings.HasPrefix(auth, "Basic ") {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(auth, "Basic "))
	if err != nil {
		return false
	}
	// 使用常量时间比较避免 timing attack；凭证含多个 ':' 时按整体 user:pass 比对
	expected := []byte(cfgp.Username + ":" + cfgp.Password)
	return subtle.ConstantTimeCompare(decoded, expected) == 1
}

func writeHTTPProxyResponse(c net.Conn, status int, text string, headers map[string]string) {
	buf := &bytes.Buffer{}
	fmt.Fprintf(buf, "HTTP/1.1 %d %s\r\nConnection: close\r\n", status, text)
	for k, v := range headers {
		fmt.Fprintf(buf, "%s: %s\r\n", k, v)
	}
	buf.WriteString("\r\n")
	_, _ = c.Write(buf.Bytes())
}

func httpProxyTargetFromRequest(req *http.Request) string {
	host := req.URL.Host
	if host == "" {
		host = req.Host
	}
	if host == "" {
		return ""
	}
	defaultPort := "80"
	if req.URL.Scheme == "https" {
		defaultPort = "443"
	}
	return ensureHTTPProxyTarget(host, defaultPort)
}

func ensureHTTPProxyTarget(host, defaultPort string) string {
	if host == "" {
		return ""
	}
	if _, _, err := net.SplitHostPort(host); err == nil {
		return host
	}
	return net.JoinHostPort(host, defaultPort)
}

func buildHTTPRequestForUpstream(req *http.Request) ([]byte, error) {
	headers := req.Header.Clone()
	headers.Del("Proxy-Connection")
	headers.Del("Proxy-Authorization")
	if req.Host != "" && headers.Get("Host") == "" {
		headers.Set("Host", req.Host)
	}

	uri := "/"
	if req.URL != nil {
		uri = req.URL.EscapedPath()
		if uri == "" {
			uri = "/"
		}
		if req.URL.RawQuery != "" {
			uri += "?" + req.URL.RawQuery
		}
	}

	var buf bytes.Buffer
	fmt.Fprintf(&buf, "%s %s HTTP/1.1\r\n", req.Method, uri)
	if err := headers.Write(&buf); err != nil {
		return nil, err
	}
	buf.WriteString("\r\n")
	return buf.Bytes(), nil
}

func readBufferedProxyBytes(reader *bufio.Reader) ([]byte, error) {
	n := reader.Buffered()
	if n == 0 {
		return nil, nil
	}
	buf := make([]byte, n)
	_, err := io.ReadFull(reader, buf)
	if err != nil {
		return nil, err
	}
	return buf, nil
}
