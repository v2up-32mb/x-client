// Package socks5 implements the upstream SOCKS5 proxy backend: TCP relay over
// RFC 1928/1929 SOCKS5 with optional username/password authentication,
// local bypass routing, and active connection tracking for clean lifecycle
// management.
package socks5

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/v2up-32mb/xshared/config"
	"github.com/v2up-32mb/xshared/logger"
	"github.com/v2up-32mb/xshared/routing"
	xsocks5 "github.com/v2up-32mb/xshared/socks5"
)

const (
	defaultServerPort  = 1080
	defaultDialTimeout = 15 * time.Second
	tunnelIdleTimeout  = 120 * time.Second

	socksVersion = 0x05
	authNone     = 0x00
	authUserPass = 0x02
	authNoAccept = 0xFF
	authSubVer   = 0x01
	cmdConnect   = 0x01
	atypIPv4     = 0x01
	atypDomain   = 0x03
	atypIPv6     = 0x04
)

// Param keys accepted by Backend.Start (aligned with Android SOCKS5 Profile fields).
const (
	ParamServerHost      = "server_host"
	ParamServerPort      = "server_port"
	ParamUsername        = "username"
	ParamPassword        = "password"
	ParamLogLevel        = "log_level"
	ParamBypassPrivate   = "bypass_private"
	ParamBypassGeoIPCN   = "bypass_geoip_cn"
	ParamBypassGeoSiteCN = "bypass_geosite_cn"
	ParamBypassRules     = "bypass_rules"
	ParamForceProxyRules = "force_proxy_rules"
)

type backendConfig struct {
	ServerHost string
	ServerPort int
	Username   string
	Password   string
}

func (c *backendConfig) upstreamAddr() string {
	return net.JoinHostPort(c.ServerHost, strconv.Itoa(c.ServerPort))
}

// Backend runs the upstream SOCKS5 proxy backend (TCP only in Phase 1).
// It satisfies xclient.ProxyBackend structurally.
type Backend struct {
	mu           sync.Mutex
	dialer       *upstreamDialer
	socks5Server *xsocks5.Server
}

// NewBackend returns an idle SOCKS5 backend.
func NewBackend() *Backend {
	return &Backend{}
}

// Start validates parameters, initializes logging, and starts the local SOCKS5
// server forwarding non-bypassed TCP connections to the configured upstream SOCKS5 server.
func (b *Backend) Start(listenAddr string, params map[string]string, verbose bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socks5Server != nil {
		return fmt.Errorf("SOCKS5 proxy is already running")
	}

	listenAddr = strings.TrimSpace(listenAddr)
	if listenAddr == "" {
		return fmt.Errorf("SOCKS5 listen address is required")
	}
	host, localUser, localPass, err := xsocks5.ParseSocks5Auth(listenAddr)
	if err != nil {
		return err
	}
	if strings.TrimSpace(host) == "" {
		return fmt.Errorf("SOCKS5 listen address is required")
	}

	cfg, err := buildConfig(params)
	if err != nil {
		return err
	}

	bypassPrivate, err := boolParam(params, ParamBypassPrivate, false)
	if err != nil {
		return err
	}
	bypassGeoIPCN, err := boolParam(params, ParamBypassGeoIPCN, false)
	if err != nil {
		return err
	}
	bypassGeoSiteCN, err := boolParam(params, ParamBypassGeoSiteCN, false)
	if err != nil {
		return err
	}
	bypassMatcher, err := routing.NewMatcherWithForceProxy(
		bypassPrivate,
		bypassGeoIPCN,
		bypassGeoSiteCN,
		stringParam(params, ParamBypassRules, ""),
		stringParam(params, ParamForceProxyRules, ""),
	)
	if err != nil {
		return fmt.Errorf("invalid bypass rules: %w", err)
	}

	sharedCfg := config.DefaultConfig()
	sharedCfg.ListenAddress = host
	sharedCfg.LogLevel = logLevelFromParams(params, verbose)
	sharedCfg.EnableLogFile = false

	logger.ClearRuntimeLogs()
	logger.InitGlobalLogger(sharedCfg)
	systemLog := logger.GetLogger("System")

	authMode := "none"
	if cfg.Username != "" {
		authMode = fmt.Sprintf("userpass(user=%s)", cfg.Username)
	}
	systemLog.Info("启动 SOCKS5: Upstream=%s, Auth=%s", cfg.upstreamAddr(), authMode)

	d := newUpstreamDialer(cfg.upstreamAddr(), cfg.Username, cfg.Password, defaultDialTimeout)
	opts := []xsocks5.Option{
		xsocks5.WithBypassMatcher(bypassMatcher),
		xsocks5.WithTunnelIdleTimeout(tunnelIdleTimeout),
	}
	if localUser != "" || localPass != "" {
		opts = append(opts, xsocks5.WithUserPassAuth(func(u, p string) bool {
			return xsocks5.AuthEqual(u, localUser) && xsocks5.AuthEqual(p, localPass)
		}))
	}

	srv := xsocks5.NewServer(&config.Config{ListenAddress: host}, d, opts...)
	if err := srv.Start(); err != nil {
		d.closeAll(true)
		logger.Close()
		return fmt.Errorf("start SOCKS5 server: %w", err)
	}

	b.dialer = d
	b.socks5Server = srv
	systemLog.Info("SOCKS5 已就绪")
	return nil
}

// Stop stops the local SOCKS5 listener and closes all active upstream connections.
func (b *Backend) Stop() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socks5Server == nil {
		return nil
	}
	logger.GetLogger("System").Info("正在停止 SOCKS5")
	err := b.socks5Server.Close()
	b.socks5Server = nil
	if b.dialer != nil {
		b.dialer.closeAll(true)
		b.dialer = nil
	}
	logger.Close()
	return err
}

// Reconnect closes all active upstream connections so subsequent requests open
// fresh sockets on the current network path.
func (b *Backend) Reconnect(reason string) {
	if reason = strings.TrimSpace(reason); reason == "" {
		reason = "Android reconnect requested"
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socks5Server != nil && b.dialer != nil {
		logger.GetLogger("System").Info("触发 SOCKS5 重连: %s", reason)
		b.dialer.closeAll(false)
	}
}

// NotifyNetworkChanged triggers Reconnect when the underlying Android network changes.
func (b *Backend) NotifyNetworkChanged() {
	b.Reconnect("Android default network changed")
}

func buildConfig(params map[string]string) (*backendConfig, error) {
	rawHost := strings.TrimSpace(stringParam(params, ParamServerHost, ""))
	host := rawHost
	if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		host = strings.TrimSpace(host[1 : len(host)-1])
	}
	if host == "" {
		return nil, fmt.Errorf("server host is required")
	}
	if strings.ContainsAny(host, "[]") {
		return nil, fmt.Errorf("param %q: invalid server host %q", ParamServerHost, rawHost)
	}

	port, err := intParam(params, ParamServerPort, defaultServerPort)
	if err != nil {
		return nil, err
	}
	if port < 1 || port > 65535 {
		return nil, fmt.Errorf("param %q: port %d out of range (1..65535)", ParamServerPort, port)
	}

	username := stringParam(params, ParamUsername, "")
	password := stringParam(params, ParamPassword, "")
	if password != "" && username == "" {
		return nil, fmt.Errorf("param %q: username is required when password is set", ParamPassword)
	}
	if len(username) > 255 {
		return nil, fmt.Errorf("param %q: exceeds max length 255", ParamUsername)
	}
	if len(password) > 255 {
		return nil, fmt.Errorf("param %q: exceeds max length 255", ParamPassword)
	}

	for _, key := range []string{ParamBypassPrivate, ParamBypassGeoIPCN, ParamBypassGeoSiteCN} {
		if _, err := boolParam(params, key, false); err != nil {
			return nil, err
		}
	}

	return &backendConfig{
		ServerHost: host,
		ServerPort: port,
		Username:   username,
		Password:   password,
	}, nil
}

type upstreamDialer struct {
	upstreamAddr string
	username     string
	password     string
	dialTimeout  time.Duration

	mu          sync.Mutex
	closed      bool
	activeConns map[*trackedConn]struct{}
}

func newUpstreamDialer(upstreamAddr, username, password string, dialTimeout time.Duration) *upstreamDialer {
	return &upstreamDialer{
		upstreamAddr: upstreamAddr,
		username:     username,
		password:     password,
		dialTimeout:  dialTimeout,
		activeConns:  make(map[*trackedConn]struct{}),
	}
}

func (d *upstreamDialer) isClosed() bool {
	d.mu.Lock()
	closed := d.closed
	d.mu.Unlock()
	return closed
}

func (d *upstreamDialer) trackConn(conn net.Conn) (*trackedConn, error) {
	tc := &trackedConn{
		Conn:   conn,
		dialer: d,
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return nil, errors.New("SOCKS5 backend is stopped")
	}
	d.activeConns[tc] = struct{}{}
	return tc, nil
}

func (d *upstreamDialer) untrackConn(tc *trackedConn) {
	d.mu.Lock()
	delete(d.activeConns, tc)
	d.mu.Unlock()
}

func (d *upstreamDialer) closeAll(markClosed bool) {
	d.mu.Lock()
	if markClosed {
		d.closed = true
	}
	conns := make([]*trackedConn, 0, len(d.activeConns))
	for c := range d.activeConns {
		conns = append(conns, c)
	}
	d.mu.Unlock()

	for _, c := range conns {
		_ = c.Close()
	}
}

func (d *upstreamDialer) DialStream(ctx context.Context, target string) (net.Conn, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if d.isClosed() {
		return nil, errors.New("SOCKS5 backend is stopped")
	}

	rawConn, err := (&net.Dialer{Timeout: d.dialTimeout}).DialContext(ctx, "tcp", d.upstreamAddr)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("dial upstream SOCKS5 %s: %w", d.upstreamAddr, err)
	}

	tc, err := d.trackConn(rawConn)
	if err != nil {
		_ = rawConn.Close()
		return nil, err
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = tc.SetDeadline(deadline)
	}
	stopWatch := context.AfterFunc(ctx, func() {
		_ = tc.Close()
	})

	if err := socks5Handshake(tc, target, d.username, d.password); err != nil {
		_ = stopWatch()
		_ = tc.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}

	if !stopWatch() {
		_ = tc.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errors.New("SOCKS5 handshake cancelled")
	}
	_ = tc.SetDeadline(time.Time{})
	return tc, nil
}

func socks5Handshake(conn net.Conn, target, username, password string) error {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return fmt.Errorf("invalid target address %q: %w", target, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("invalid target port %q", portStr)
	}

	// 1. Method negotiation (RFC 1928)
	var methods []byte
	if username != "" {
		methods = []byte{socksVersion, 0x01, authUserPass}
	} else {
		methods = []byte{socksVersion, 0x01, authNone}
	}
	if err := writeAll(conn, methods); err != nil {
		return fmt.Errorf("SOCKS5 send greeting: %w", err)
	}

	methodReply := make([]byte, 2)
	if _, err := io.ReadFull(conn, methodReply); err != nil {
		return fmt.Errorf("SOCKS5 read greeting reply: %w", err)
	}
	if methodReply[0] != socksVersion {
		return fmt.Errorf("SOCKS5 invalid version in greeting reply: %d", methodReply[0])
	}

	switch methodReply[1] {
	case authNone:
		if username != "" {
			return errors.New("SOCKS5 upstream selected no-auth despite configured username")
		}
	case authUserPass:
		if username == "" {
			return errors.New("SOCKS5 upstream requires authentication but no username is configured")
		}
		authReq := make([]byte, 0, 3+len(username)+len(password))
		authReq = append(authReq, authSubVer, byte(len(username)))
		authReq = append(authReq, username...)
		authReq = append(authReq, byte(len(password)))
		authReq = append(authReq, password...)
		if err := writeAll(conn, authReq); err != nil {
			return fmt.Errorf("SOCKS5 send credentials: %w", err)
		}
		authReply := make([]byte, 2)
		if _, err := io.ReadFull(conn, authReply); err != nil {
			return fmt.Errorf("SOCKS5 read auth reply: %w", err)
		}
		if authReply[0] != authSubVer {
			return fmt.Errorf("SOCKS5 invalid auth subnegotiation version: %d", authReply[0])
		}
		if authReply[1] != 0x00 {
			return fmt.Errorf("SOCKS5 authentication failed (status=%d)", authReply[1])
		}
	case authNoAccept:
		return errors.New("SOCKS5 no acceptable authentication method")
	default:
		return fmt.Errorf("SOCKS5 unsupported auth method: 0x%02x", methodReply[1])
	}

	// 2. CONNECT request (RFC 1928)
	req := make([]byte, 0, 22)
	req = append(req, socksVersion, cmdConnect, 0x00)
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, atypIPv4)
			req = append(req, ip4...)
		} else if ip6 := ip.To16(); ip6 != nil {
			req = append(req, atypIPv6)
			req = append(req, ip6...)
		} else {
			return fmt.Errorf("unsupported IP address %q", host)
		}
	} else {
		if len(host) == 0 || len(host) > 255 {
			return fmt.Errorf("invalid domain length for %q", host)
		}
		req = append(req, atypDomain, byte(len(host)))
		req = append(req, host...)
	}
	req = append(req, byte(port>>8), byte(port))

	if err := writeAll(conn, req); err != nil {
		return fmt.Errorf("SOCKS5 send connect request: %w", err)
	}

	// 3. CONNECT reply
	replyHead := make([]byte, 4)
	if _, err := io.ReadFull(conn, replyHead); err != nil {
		return fmt.Errorf("SOCKS5 read connect reply: %w", err)
	}
	if replyHead[0] != socksVersion {
		return fmt.Errorf("SOCKS5 invalid version in connect reply: %d", replyHead[0])
	}
	if replyHead[1] != 0x00 {
		return fmt.Errorf("SOCKS5 connect rejected (reply=0x%02x)", replyHead[1])
	}

	switch replyHead[3] {
	case atypIPv4:
		var discard [6]byte
		if _, err := io.ReadFull(conn, discard[:]); err != nil {
			return fmt.Errorf("SOCKS5 read IPv4 bind addr: %w", err)
		}
	case atypIPv6:
		var discard [18]byte
		if _, err := io.ReadFull(conn, discard[:]); err != nil {
			return fmt.Errorf("SOCKS5 read IPv6 bind addr: %w", err)
		}
	case atypDomain:
		var lenBuf [1]byte
		if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
			return fmt.Errorf("SOCKS5 read domain bind len: %w", err)
		}
		discard := make([]byte, int(lenBuf[0])+2)
		if _, err := io.ReadFull(conn, discard); err != nil {
			return fmt.Errorf("SOCKS5 read domain bind addr: %w", err)
		}
	default:
		return fmt.Errorf("SOCKS5 invalid bind address type: 0x%02x", replyHead[3])
	}

	return nil
}

func writeAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
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

type trackedConn struct {
	net.Conn
	dialer    *upstreamDialer
	closeOnce sync.Once
}

func (c *trackedConn) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.dialer.untrackConn(c)
		err = c.Conn.Close()
	})
	return err
}

func (c *trackedConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}

func logLevelFromParams(params map[string]string, verbose bool) config.LogLevel {
	if v := strings.TrimSpace(stringParam(params, ParamLogLevel, "")); v != "" {
		return config.ParseLogLevel(v)
	}
	if verbose {
		return config.DEBUG
	}
	return config.INFO
}

func stringParam(params map[string]string, key, def string) string {
	if v, ok := params[key]; ok {
		return v
	}
	return def
}

func boolParam(params map[string]string, key string, def bool) (bool, error) {
	v, ok := params[key]
	if !ok || strings.TrimSpace(v) == "" {
		return def, nil
	}
	b, err := strconv.ParseBool(strings.TrimSpace(v))
	if err != nil {
		return false, fmt.Errorf("param %q: invalid boolean %q", key, v)
	}
	return b, nil
}

func intParam(params map[string]string, key string, def int) (int, error) {
	v, ok := params[key]
	if !ok || strings.TrimSpace(v) == "" {
		return def, nil
	}
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return 0, fmt.Errorf("param %q: invalid integer %q", key, v)
	}
	return n, nil
}
