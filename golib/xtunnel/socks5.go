package xtunnel

import (
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	common "xclient/xtunnel/protocol"
)

// ProxyConfig SOCKS5 代理配置
type ProxyConfig struct {
	Username, Password, Host string
}

// parseAuthAndAddr 解析认证信息和地址
func parseAuthAndAddr(full string) (string, string, string, error) {
	u, p, h := "", "", full
	if strings.Contains(full, "@") {
		parts := strings.SplitN(full, "@", 2)
		if len(parts) != 2 {
			return "", "", "", fmt.Errorf("格式错误")
		}
		auth := parts[0]
		if strings.Contains(auth, ":") {
			ap := strings.SplitN(auth, ":", 2)
			u, p = ap[0], ap[1]
		}
		h = parts[1]
	}
	return h, u, p, nil
}

// udpAssociation UDP 关联
type udpAssociation struct {
	connID        string
	tcpConn       net.Conn
	udpListener   *net.UDPConn
	clientUDPAddr *net.UDPAddr
	pool          *clientPool

	mu        sync.Mutex
	closed    bool
	done      chan bool
	receiving bool
	channelID int
}

func containsSOCKS5Method(methods []byte, want byte) bool {
	for _, method := range methods {
		if method == want {
			return true
		}
	}
	return false
}

func (a *udpAssociation) notifyDone() {
	if a.done == nil {
		return
	}
	select {
	case a.done <- true:
	default:
	}
}

// handleUDPResponse 处理 UDP 响应
func (a *udpAssociation) handleUDPResponse(addrStr string, data []byte) {
	host, portStr, _ := net.SplitHostPort(addrStr)
	port, _ := strconv.Atoi(portStr)

	pkt, err := buildSOCKS5UDPPacket(host, port, data)
	if err != nil {
		return
	}

	a.mu.Lock()
	defer a.mu.Unlock()
	if a.clientUDPAddr != nil {
		_, _ = a.udpListener.WriteToUDP(pkt, a.clientUDPAddr)
	}
}

// Close 关闭 UDP 关联
func (a *udpAssociation) Close() {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	closedHadReceiving := a.receiving
	chID := a.channelID
	connID := a.connID
	a.closed = true
	a.mu.Unlock()

	a.notifyDone()
	_ = a.udpListener.Close()

	if closedHadReceiving {
		if chID >= 0 {
			a.pool.SendUDPCloseDirect(chID, connID)
		} else {
			a.pool.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgUDPClose, connID, nil, nil))
			a.pool.Unregister(connID)
		}
	} else {
		a.pool.Unregister(connID)
	}
}

// ListenSOCKS5 启动 SOCKS5 监听器
func (p *clientPool) ListenSOCKS5(addr string) error {
	h, u, pswd, err := parseAuthAndAddr(strings.TrimPrefix(addr, "socks5://"))
	if err != nil {
		return fmt.Errorf("SOCKS5地址解析失败: %v", err)
	}
	l, err := net.Listen("tcp", h)
	if err != nil {
		return fmt.Errorf("SOCKS5监听失败: %v", err)
	}
	p.trackListener(l)
	sysLog.Info("[客户端] SOCKS5 代理: %s", h)
	cfgp := &ProxyConfig{Username: u, Password: pswd, Host: h}

	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				if errors.Is(err, net.ErrClosed) || p.ctx.Err() != nil {
					return
				}
				continue
			}
			// 检查连接数限制
			if p.socks5Sem != nil {
				select {
				case p.socks5Sem <- struct{}{}:
					// 获得信号量,继续处理
					go func(conn net.Conn) {
						defer func() { <-p.socks5Sem }()
						p.handleSOCKS5(conn, cfgp)
					}(c)
				default:
					// 达到连接上限,拒绝连接
					sysLog.Info("[客户端] SOCKS5 连接数已达上限,拒绝新连接")
					c.Close()
				}
			} else {
				// 无连接限制
				go p.handleSOCKS5(c, cfgp)
			}
		}
	}()

	return nil
}

// handleSOCKS5 处理 SOCKS5 连接
func (p *clientPool) handleSOCKS5(c net.Conn, cfgp *ProxyConfig) {
	defer c.Close()

	_ = c.SetDeadline(time.Now().Add(p.proxyHandshakeTimeout()))

	// VER, NMETHODS
	buf := make([]byte, 2)
	if _, err := io.ReadFull(c, buf); err != nil || buf[0] != 0x05 {
		return
	}
	methods := make([]byte, buf[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}

	// METHOD selection
	if cfgp.Username != "" {
		if !containsSOCKS5Method(methods, 0x02) {
			_, _ = c.Write([]byte{0x05, 0xFF})
			return
		}
		_, _ = c.Write([]byte{0x05, 0x02}) // username/password
		if err := p.handleSOCKS5UserPassAuth(c, cfgp); err != nil {
			return
		}
	} else {
		if !containsSOCKS5Method(methods, 0x00) {
			_, _ = c.Write([]byte{0x05, 0xFF})
			return
		}
		_, _ = c.Write([]byte{0x05, 0x00}) // no auth
	}

	// Request: VER CMD RSV ATYP ...
	head := make([]byte, 4)
	if _, err := io.ReadFull(c, head); err != nil {
		return
	}
	if head[0] != 0x05 || head[2] != 0x00 {
		_, _ = c.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var target string
	switch head[3] {
	case 0x01: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		target = net.IP(b).String()
	case 0x03: // DOMAIN
		b := make([]byte, 1)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		addr := make([]byte, b[0])
		if _, err := io.ReadFull(c, addr); err != nil {
			return
		}
		target = string(addr)
	case 0x04: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		target = net.IP(b).String()
	default:
		_, _ = c.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	pb := make([]byte, 2)
	if _, err := io.ReadFull(c, pb); err != nil {
		return
	}
	port := int(pb[0])<<8 | int(pb[1])

	if head[3] == 0x04 {
		target = fmt.Sprintf("[%s]:%d", target, port)
	} else {
		target = fmt.Sprintf("%s:%d", target, port)
	}

	_ = c.SetDeadline(time.Time{})

	switch head[1] {
	case 0x01: // CONNECT
		p.handleSOCKS5Connect(c, cfgp, target)
	case 0x03: // UDP ASSOCIATE
		p.handleSOCKS5UDP(c, cfgp)
	default:
		// command not supported
		_, _ = c.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
}

// handleSOCKS5UserPassAuth 处理 SOCKS5 用户密码认证
func (p *clientPool) handleSOCKS5UserPassAuth(c net.Conn, cfgp *ProxyConfig) error {
	// RFC1929: VER=1, ULEN, UNAME, PLEN, PASSWD
	b := make([]byte, 2)
	if _, err := io.ReadFull(c, b); err != nil {
		return err
	}
	if b[0] != 0x01 {
		_, _ = c.Write([]byte{0x01, 0x01})
		return errors.New("认证版本无效")
	}
	u := make([]byte, b[1])
	if _, err := io.ReadFull(c, u); err != nil {
		return err
	}
	if _, err := io.ReadFull(c, b[:1]); err != nil {
		return err
	}
	pswd := make([]byte, b[0])
	if _, err := io.ReadFull(c, pswd); err != nil {
		return err
	}

	if string(u) == cfgp.Username && string(pswd) == cfgp.Password {
		_, _ = c.Write([]byte{0x01, 0x00})
		return nil
	}
	_, _ = c.Write([]byte{0x01, 0x01})
	return errors.New("认证失败")
}

// handleSOCKS5Connect 处理 SOCKS5 CONNECT 请求
func (p *clientPool) handleSOCKS5Connect(c net.Conn, cfgp *ProxyConfig, target string) {
	connID := uuid.New().String()

	// reply success (BND.ADDR/BND.PORT ignored)
	_, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	if err != nil {
		_ = c.Close()
		return
	}

	p.RegisterAndBroadcastTCP(connID, target, nil, c, "SOCKS5")

	// 获取 connected 通道，等待连接建立或超时
	p.mu.RLock()
	st := p.conns[connID]
	var connected chan bool
	if st != nil {
		connected = st.connected
	}
	p.mu.RUnlock()

	// 等待连接建立或超时
	if connected != nil {
		select {
		case ok := <-connected:
			if !ok {
				// 无可用通道：发送标准失败应答并关闭连接
				sysLog.Info("[客户端] SOCKS5 连接 %s 失败: 无可用通道", target)
				_, _ = c.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
				_ = c.Close()
				p.Unregister(connID)
				return
			}
			// 连接成功，继续正常处理
		case <-time.After(p.connectTimeout()):
			// 连接超时，发送错误响应并关闭连接
			sysLog.Info("[客户端] SOCKS5 连接 %s 超时", target)
			_, _ = c.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			_ = c.Close()
			p.Unregister(connID)
			return
		}
	} else {
		// 状态已消失（罕见竞态）：按连接失败处理
		_, _ = c.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		_ = c.Close()
		return
	}

	buf := make([]byte, 32*1024)

	defer func() {
		if chID, ok := p.GetUplinkChannel(connID); ok {
			_ = p.SendCloseDirect(chID, connID)
		} else {
			p.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgTCPClose, connID, nil, nil))
		}
		_ = c.Close()
		p.Unregister(connID)
	}()

	for {
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		if chID, ok := p.GetUplinkChannel(connID); ok {
			if err := p.SendDataDirect(chID, connID, buf[:n]); err != nil {
				sysLog.Info("[客户端] 发送数据失败: %v, ID:%s", err, common.ShortID(connID))
				return
			}
		} else {
			// uplink 还未确定,使用广播发送
			p.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgTCPData, connID, nil, buf[:n]))
		}
	}
}

// handleSOCKS5UDP 处理 SOCKS5 UDP ASSOCIATE 请求
func (p *clientPool) handleSOCKS5UDP(c net.Conn, cfgp *ProxyConfig) {
	host, _, _ := net.SplitHostPort(cfgp.Host)
	uAddr, _ := net.ResolveUDPAddr("udp", net.JoinHostPort(host, "0"))
	ul, err := net.ListenUDP("udp", uAddr)
	if err != nil {
		// general failure
		_, _ = c.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer ul.Close()

	actual := ul.LocalAddr().(*net.UDPAddr)
	resp := []byte{0x05, 0x00, 0x00}
	if ip4 := actual.IP.To4(); ip4 != nil {
		resp = append(resp, 0x01)
		resp = append(resp, ip4...)
	} else {
		resp = append(resp, 0x04)
		resp = append(resp, actual.IP...)
	}
	resp = append(resp, byte(actual.Port>>8), byte(actual.Port))
	_, _ = c.Write(resp)

	connID := uuid.New().String()
	assoc := &udpAssociation{
		connID:      connID,
		tcpConn:     c,
		udpListener: ul,
		pool:        p,
		done:        make(chan bool, 1),
		channelID:   -1,
	}
	p.RegisterUDP(connID, assoc)

	go assoc.loop()

	// keep TCP alive until closed: 丢弃控制连接上的任何数据，直到对端关闭
	io.Copy(io.Discard, c)
	assoc.notifyDone()
	assoc.Close()
}

// loop UDP 接收循环
func (a *udpAssociation) loop() {
	buf := make([]byte, 64*1024)

	for {
		select {
		case <-a.done:
			return
		default:
		}

		n, addr, err := a.udpListener.ReadFromUDP(buf)
		if err != nil {
			a.notifyDone()
			return
		}

		a.mu.Lock()
		if a.clientUDPAddr == nil {
			a.clientUDPAddr = addr
		} else if a.clientUDPAddr.String() != addr.String() {
			a.mu.Unlock()
			continue
		}
		a.mu.Unlock()

		tgt, data, err := parseSOCKS5UDPPacket(buf[:n])
		if err != nil {
			continue
		}

		// 本地 IP 策略过滤（仅对"已经是 IP 的目标"有意义）
		h, ps, _ := net.SplitHostPort(tgt)
		if ip := net.ParseIP(h); ip != nil {
			if a.pool.config.IPStrategy == common.IPStrategyIPv4Only && ip.To4() == nil {
				continue
			}
			if a.pool.config.IPStrategy == common.IPStrategyIPv6Only && ip.To4() != nil {
				continue
			}
		}

		// UDP 端口拦截（例如拦截 QUIC 443）
		port, _ := strconv.Atoi(ps)
		blocked := false
		for _, bp := range a.pool.config.UDPBlockedPorts {
			if bp == port {
				blocked = true
				break
			}
		}
		if blocked {
			continue
		}

		a.send(tgt, data)
	}
}

// send 发送 UDP 数据
func (a *udpAssociation) send(target string, data []byte) {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	needStart := !a.receiving
	if needStart {
		a.receiving = true
	}
	chID := a.channelID
	a.mu.Unlock()

	if needStart {
		a.pool.StartUDPRace(a.connID, target)
	}

	if chID < 0 {
		if id, ok := a.pool.GetUplinkChannel(a.connID); ok {
			a.mu.Lock()
			a.channelID = id
			chID = id
			a.mu.Unlock()
		} else {
			a.pool.broadcastWrite(websocket.BinaryMessage, common.EncodeMessage(common.MsgUDPData, a.connID, nil, data))
			return
		}
	}
	_ = a.pool.SendUDPDataDirect(chID, a.connID, data)
}

// parseSOCKS5UDPPacket 解析 SOCKS5 UDP 数据包
func parseSOCKS5UDPPacket(b []byte) (string, []byte, error) {
	// RSV(2)=0, FRAG(1)=0
	if len(b) < 10 || b[0] != 0 || b[1] != 0 || b[2] != 0 {
		return "", nil, errors.New("数据不合法")
	}
	off := 4
	var h string
	switch b[3] {
	case 0x01: // IPv4
		if off+4 > len(b) {
			return "", nil, errors.New("IPv4地址长度过短")
		}
		h = net.IP(b[off : off+4]).String()
		off += 4
	case 0x03: // DOMAIN
		if off+1 > len(b) {
			return "", nil, errors.New("域名长度不足")
		}
		l := int(b[off])
		off++
		if off+l > len(b) {
			return "", nil, errors.New("域名长度不足")
		}
		h = string(b[off : off+l])
		off += l
	case 0x04: // IPv6
		if off+16 > len(b) {
			return "", nil, errors.New("IPv6地址长度过短")
		}
		h = net.IP(b[off : off+16]).String()
		off += 16
	default:
		return "", nil, errors.New("地址类型无效")
	}
	if off+2 > len(b) {
		return "", nil, errors.New("端口字段过短")
	}
	p := int(b[off])<<8 | int(b[off+1])
	off += 2

	t := fmt.Sprintf("%s:%d", h, p)
	if b[3] == 0x04 {
		t = fmt.Sprintf("[%s]:%d", h, p)
	}
	return t, b[off:], nil
}

// buildSOCKS5UDPPacket 构建 SOCKS5 UDP 数据包
func buildSOCKS5UDPPacket(h string, p int, d []byte) ([]byte, error) {
	if p < 0 || p > 65535 {
		return nil, errors.New("端口无效")
	}

	buf := []byte{0, 0, 0} // RSV(2), FRAG(1)
	ip := net.ParseIP(h)
	if ip4 := ip.To4(); ip4 != nil {
		buf = append(buf, 0x01)
		buf = append(buf, ip4...)
	} else if ip != nil {
		buf = append(buf, 0x04)
		buf = append(buf, ip...)
	} else {
		if len(h) > 255 {
			return nil, errors.New("域名过长")
		}
		buf = append(buf, 0x03, byte(len(h)))
		buf = append(buf, h...)
	}
	buf = append(buf, byte(p>>8), byte(p))
	buf = append(buf, d...)
	return buf, nil
}
