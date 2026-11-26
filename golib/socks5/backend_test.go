package socks5

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/v2up-32mb/xshared/config"
	"github.com/v2up-32mb/xshared/logger"
)

type recordedConnect struct {
	atyp byte
	host string
	port uint16
}

type mockSOCKS5Server struct {
	listener    net.Listener
	requireAuth bool
	username    string
	password    string

	mu       sync.Mutex
	connects []recordedConnect
	conns    map[net.Conn]struct{}
	closed   atomic.Bool
}

func startMockSOCKS5Server(t *testing.T, requireAuth bool, username, password string) *mockSOCKS5Server {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen mock SOCKS5: %v", err)
	}
	s := &mockSOCKS5Server{
		listener:    ln,
		requireAuth: requireAuth,
		username:    username,
		password:    password,
		conns:       make(map[net.Conn]struct{}),
	}
	go s.serve()
	t.Cleanup(func() { s.close() })
	return s
}

func (s *mockSOCKS5Server) hostPort() (string, int) {
	addr := s.listener.Addr().(*net.TCPAddr)
	return addr.IP.String(), addr.Port
}

func (s *mockSOCKS5Server) close() {
	s.closed.Store(true)
	_ = s.listener.Close()
	s.mu.Lock()
	for c := range s.conns {
		_ = c.Close()
	}
	s.mu.Unlock()
}

func (s *mockSOCKS5Server) recordedConnects() []recordedConnect {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]recordedConnect, len(s.connects))
	copy(out, s.connects)
	return out
}

func (s *mockSOCKS5Server) activeConnCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}

func (s *mockSOCKS5Server) serve() {
	for {
		c, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.conns[c] = struct{}{}
		s.mu.Unlock()
		go s.handleConn(c)
	}
}

func (s *mockSOCKS5Server) handleConn(c net.Conn) {
	defer func() {
		s.mu.Lock()
		delete(s.conns, c)
		s.mu.Unlock()
		_ = c.Close()
	}()

	var head [2]byte
	if _, err := io.ReadFull(c, head[:]); err != nil {
		return
	}
	if head[0] != 0x05 {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}

	if s.requireAuth {
		if !bytes.Contains(methods, []byte{0x02}) {
			_, _ = c.Write([]byte{0x05, 0xFF})
			return
		}
		if _, err := c.Write([]byte{0x05, 0x02}); err != nil {
			return
		}
		var verLen [2]byte
		if _, err := io.ReadFull(c, verLen[:]); err != nil {
			return
		}
		user := make([]byte, verLen[1])
		if _, err := io.ReadFull(c, user); err != nil {
			return
		}
		var plen [1]byte
		if _, err := io.ReadFull(c, plen[:]); err != nil {
			return
		}
		pass := make([]byte, plen[0])
		if _, err := io.ReadFull(c, pass); err != nil {
			return
		}
		if string(user) != s.username || string(pass) != s.password {
			_, _ = c.Write([]byte{0x01, 0x01})
			return
		}
		if _, err := c.Write([]byte{0x01, 0x00}); err != nil {
			return
		}
	} else {
		if !bytes.Contains(methods, []byte{0x00}) {
			_, _ = c.Write([]byte{0x05, 0xFF})
			return
		}
		if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
			return
		}
	}

	var reqHead [4]byte
	if _, err := io.ReadFull(c, reqHead[:]); err != nil {
		return
	}
	if reqHead[0] != 0x05 || reqHead[1] != 0x01 {
		_, _ = c.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	atyp := reqHead[3]
	var host string
	switch atyp {
	case 0x01:
		var buf [4]byte
		if _, err := io.ReadFull(c, buf[:]); err != nil {
			return
		}
		host = net.IP(buf[:]).String()
	case 0x03:
		var l [1]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return
		}
		buf := make([]byte, l[0])
		if _, err := io.ReadFull(c, buf); err != nil {
			return
		}
		host = string(buf)
	case 0x04:
		var buf [16]byte
		if _, err := io.ReadFull(c, buf[:]); err != nil {
			return
		}
		host = net.IP(buf[:]).String()
	default:
		_, _ = c.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(c, portBuf[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf[:])

	s.mu.Lock()
	s.connects = append(s.connects, recordedConnect{atyp: atyp, host: host, port: port})
	s.mu.Unlock()

	if _, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}); err != nil {
		return
	}

	// Echo payload prefixed by "ECHO:" so tests verify full bidirectional relay
	// through the mock upstream SOCKS5 server.
	buf := make([]byte, 4096)
	for {
		n, err := c.Read(buf)
		if n > 0 {
			if _, werr := c.Write(append([]byte("ECHO:"), buf[:n]...)); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

func freeTCPAddr(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("probe free port: %v", err)
	}
	addr := l.Addr().String()
	_ = l.Close()
	return addr
}

func dialLocalSOCKS5(t *testing.T, proxyAddr string, atyp byte, targetHost string, targetPort uint16, user, pass string) (net.Conn, byte, error) {
	t.Helper()
	conn, err := net.DialTimeout("tcp", proxyAddr, 3*time.Second)
	if err != nil {
		return nil, 0, err
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	var methods []byte
	if user != "" || pass != "" {
		methods = []byte{0x05, 0x02, 0x00, 0x02}
	} else {
		methods = []byte{0x05, 0x01, 0x00}
	}
	if _, err := conn.Write(methods); err != nil {
		_ = conn.Close()
		return nil, 0, err
	}
	var mReply [2]byte
	if _, err := io.ReadFull(conn, mReply[:]); err != nil {
		_ = conn.Close()
		return nil, 0, err
	}
	if mReply[1] == 0x02 {
		authReq := append([]byte{0x01, byte(len(user))}, user...)
		authReq = append(authReq, byte(len(pass)))
		authReq = append(authReq, pass...)
		if _, err := conn.Write(authReq); err != nil {
			_ = conn.Close()
			return nil, 0, err
		}
		var aReply [2]byte
		if _, err := io.ReadFull(conn, aReply[:]); err != nil {
			_ = conn.Close()
			return nil, 0, err
		}
		if aReply[1] != 0x00 {
			_ = conn.Close()
			return nil, 0, fmt.Errorf("local auth failed: %d", aReply[1])
		}
	} else if mReply[1] == 0xFF {
		_ = conn.Close()
		return nil, 0, errors.New("local auth rejected")
	}

	req := []byte{0x05, 0x01, 0x00, atyp}
	switch atyp {
	case 0x01:
		req = append(req, net.ParseIP(targetHost).To4()...)
	case 0x03:
		req = append(req, byte(len(targetHost)))
		req = append(req, targetHost...)
	case 0x04:
		req = append(req, net.ParseIP(targetHost).To16()...)
	}
	req = append(req, byte(targetPort>>8), byte(targetPort))
	if _, err := conn.Write(req); err != nil {
		_ = conn.Close()
		return nil, 0, err
	}
	var rep [10]byte
	if _, err := io.ReadFull(conn, rep[:]); err != nil {
		_ = conn.Close()
		return nil, 0, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, rep[1], nil
}

func TestBuildConfigDefaultsAndIPv6(t *testing.T) {
	cfg, err := buildConfig(map[string]string{ParamServerHost: "[2001:db8::1]"})
	if err != nil {
		t.Fatalf("buildConfig() error = %v", err)
	}
	if cfg.ServerHost != "2001:db8::1" || cfg.ServerPort != 1080 {
		t.Fatalf("got host=%q port=%d, want 2001:db8::1/1080", cfg.ServerHost, cfg.ServerPort)
	}
	if got := cfg.upstreamAddr(); got != "[2001:db8::1]:1080" {
		t.Fatalf("upstreamAddr() = %q, want [2001:db8::1]:1080", got)
	}

	cfg2, err := buildConfig(map[string]string{
		ParamServerHost: "::1",
		ParamServerPort: "10808",
		ParamUsername:   "user",
		ParamPassword:   "pass",
	})
	if err != nil {
		t.Fatalf("buildConfig(ipv6 bare) error = %v", err)
	}
	if got := cfg2.upstreamAddr(); got != "[::1]:10808" {
		t.Fatalf("upstreamAddr() = %q, want [::1]:10808", got)
	}
}

func TestStartValidationErrorsDoNotTouchLogger(t *testing.T) {
	logger.ClearRuntimeLogs()
	logger.AppendRuntimeLog("Test", "keep-me-before-validation-failure")

	longStr := strings.Repeat("a", 256)
	cases := []struct {
		name       string
		listenAddr string
		params     map[string]string
		want       string
	}{
		{"empty listen", "", map[string]string{ParamServerHost: "127.0.0.1"}, "listen address is required"},
		{"empty host in auth addr", "socks5://u:p@", map[string]string{ParamServerHost: "127.0.0.1"}, "listen address is required"},
		{"missing server host", "127.0.0.1:1080", map[string]string{}, "server host is required"},
		{"empty brackets", "127.0.0.1:1080", map[string]string{ParamServerHost: "[]"}, "server host is required"},
		{"unmatched bracket", "127.0.0.1:1080", map[string]string{ParamServerHost: "[::1"}, "invalid server host"},
		{"bad port alpha", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamServerPort: "abc"}, `"server_port"`},
		{"port zero", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamServerPort: "0"}, "out of range"},
		{"port too high", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamServerPort: "65536"}, "out of range"},
		{"password without username", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamPassword: "secret"}, "username is required"},
		{"username too long", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamUsername: longStr}, "exceeds max length"},
		{"password too long", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamUsername: "u", ParamPassword: longStr}, "exceeds max length"},
		{"bad bypass bool", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamBypassPrivate: "yes"}, `"bypass_private"`},
		{"bad bypass rules", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamBypassRules: "not a valid rule!"}, "invalid bypass rules"},
		{"bad force proxy rules", "127.0.0.1:1080", map[string]string{ParamServerHost: "127.0.0.1", ParamForceProxyRules: "not a valid rule!"}, "invalid bypass rules"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := NewBackend()
			err := b.Start(tc.listenAddr, tc.params, false)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("Start() error = %v, want containing %q", err, tc.want)
			}
			if !strings.Contains(logger.GetRuntimeLogs(), "keep-me-before-validation-failure") {
				t.Fatal("runtime logs were cleared despite validation failure")
			}
		})
	}
}

func TestStartupLogOmitsPlaintextPassword(t *testing.T) {
	mock := startMockSOCKS5Server(t, true, "alice", "super-secret-password-999")
	host, port := mock.hostPort()
	listenAddr := freeTCPAddr(t)

	b := NewBackend()
	err := b.Start(listenAddr, map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
		ParamUsername:   "alice",
		ParamPassword:   "super-secret-password-999",
		ParamLogLevel:   "debug",
	}, true)
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	defer func() { _ = b.Stop() }()

	logs := logger.GetRuntimeLogs()
	if strings.Contains(logs, "super-secret-password-999") {
		t.Fatalf("runtime logs leaked plaintext password: %s", logs)
	}
}

func TestNoAuthTCPRelayAndAddressTypes(t *testing.T) {
	mock := startMockSOCKS5Server(t, false, "", "")
	host, port := mock.hostPort()
	listenAddr := freeTCPAddr(t)

	b := NewBackend()
	err := b.Start(listenAddr, map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
	}, false)
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	defer func() { _ = b.Stop() }()

	targets := []struct {
		name string
		atyp byte
		host string
		port uint16
	}{
		{"domain", 0x03, "nonexistent.example.test", 8080},
		{"ipv4", 0x01, "198.51.100.10", 8081},
		{"ipv6", 0x04, "2001:db8::42", 8082},
	}

	for _, tc := range targets {
		t.Run(tc.name, func(t *testing.T) {
			c, rep, err := dialLocalSOCKS5(t, listenAddr, tc.atyp, tc.host, tc.port, "", "")
			if err != nil {
				t.Fatalf("dialLocalSOCKS5(%s) error = %v", tc.name, err)
			}
			defer c.Close()
			if rep != 0x00 {
				t.Fatalf("SOCKS5 reply = 0x%02x, want 0x00", rep)
			}
			msg := "hello-" + tc.name
			if _, err := c.Write([]byte(msg)); err != nil {
				t.Fatalf("write: %v", err)
			}
			buf := make([]byte, 64)
			_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
			n, err := c.Read(buf)
			if err != nil {
				t.Fatalf("read: %v", err)
			}
			want := "ECHO:" + msg
			if string(buf[:n]) != want {
				t.Fatalf("got %q, want %q", string(buf[:n]), want)
			}
		})
	}

	recorded := mock.recordedConnects()
	if len(recorded) != len(targets) {
		t.Fatalf("recorded %d connects, want %d", len(recorded), len(targets))
	}
	for i, tc := range targets {
		if recorded[i].atyp != tc.atyp || recorded[i].host != tc.host || recorded[i].port != tc.port {
			t.Fatalf("connect[%d] = %+v, want atyp=0x%02x host=%q port=%d", i, recorded[i], tc.atyp, tc.host, tc.port)
		}
	}
}

func TestUserPassAuthSuccessAndFailure(t *testing.T) {
	mock := startMockSOCKS5Server(t, true, "alice", "s3cr3t")
	host, port := mock.hostPort()

	// Success case
	listenAddr := freeTCPAddr(t)
	b := NewBackend()
	if err := b.Start(listenAddr, map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
		ParamUsername:   "alice",
		ParamPassword:   "s3cr3t",
	}, false); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	c, rep, err := dialLocalSOCKS5(t, listenAddr, 0x03, "auth-ok.test", 443, "", "")
	if err != nil {
		t.Fatalf("dialLocalSOCKS5() error = %v", err)
	}
	if rep != 0x00 {
		t.Fatalf("reply = 0x%02x, want 0x00", rep)
	}
	_, _ = c.Write([]byte("ping"))
	buf := make([]byte, 32)
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := c.Read(buf)
	if err != nil || string(buf[:n]) != "ECHO:ping" {
		t.Fatalf("echo = %q, err = %v", string(buf[:n]), err)
	}
	_ = c.Close()
	_ = b.Stop()

	// Wrong password case
	listenAddr2 := freeTCPAddr(t)
	b2 := NewBackend()
	if err := b2.Start(listenAddr2, map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
		ParamUsername:   "alice",
		ParamPassword:   "wrong-pass",
	}, false); err != nil {
		t.Fatalf("Start(wrong pass) error = %v", err)
	}
	defer func() { _ = b2.Stop() }()

	c2, rep2, err := dialLocalSOCKS5(t, listenAddr2, 0x03, "auth-fail.test", 443, "", "")
	if err == nil {
		_ = c2.Close()
		if rep2 == 0x00 {
			t.Fatal("expected non-zero SOCKS5 reply for wrong upstream password")
		}
	}
}

func TestLocalSocks5ListenAuth(t *testing.T) {
	mock := startMockSOCKS5Server(t, false, "", "")
	host, port := mock.hostPort()
	listenHostPort := freeTCPAddr(t)

	b := NewBackend()
	err := b.Start("socks5://localu:localp@"+listenHostPort, map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
	}, false)
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	defer func() { _ = b.Stop() }()

	// Wrong local credentials -> rejected
	if _, _, err := dialLocalSOCKS5(t, listenHostPort, 0x03, "a.test", 80, "localu", "wrong"); err == nil {
		t.Fatal("expected wrong local credentials to be rejected")
	}

	// Valid local credentials -> succeeds
	c, rep, err := dialLocalSOCKS5(t, listenHostPort, 0x03, "a.test", 80, "localu", "localp")
	if err != nil || rep != 0x00 {
		t.Fatalf("authenticated local dial failed: rep=0x%02x err=%v", rep, err)
	}
	_ = c.Close()
}

func TestUDPAssociateReturnsCommandNotSupported(t *testing.T) {
	mock := startMockSOCKS5Server(t, false, "", "")
	host, port := mock.hostPort()
	listenAddr := freeTCPAddr(t)

	b := NewBackend()
	if err := b.Start(listenAddr, map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
	}, false); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	defer func() { _ = b.Stop() }()

	conn, err := net.DialTimeout("tcp", listenAddr, 3*time.Second)
	if err != nil {
		t.Fatalf("dial local socks5: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))

	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatal(err)
	}
	var mReply [2]byte
	if _, err := io.ReadFull(conn, mReply[:]); err != nil {
		t.Fatal(err)
	}
	// CMD=0x03 (UDP ASSOCIATE)
	if _, err := conn.Write([]byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	var rep [10]byte
	if _, err := io.ReadFull(conn, rep[:]); err != nil {
		t.Fatal(err)
	}
	if rep[1] != 0x07 {
		t.Fatalf("UDP ASSOCIATE reply = 0x%02x, want 0x07", rep[1])
	}
}

func TestBypassPrivateDirectConnection(t *testing.T) {
	// Direct local echo server
	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen direct echo: %v", err)
	}
	defer echoLn.Close()
	go func() {
		for {
			c, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()
	echoAddr := echoLn.Addr().(*net.TCPAddr)

	mock := startMockSOCKS5Server(t, false, "", "")
	host, port := mock.hostPort()
	listenAddr := freeTCPAddr(t)

	b := NewBackend()
	if err := b.Start(listenAddr, map[string]string{
		ParamServerHost:    host,
		ParamServerPort:    strconv.Itoa(port),
		ParamBypassPrivate: "true",
	}, false); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	defer func() { _ = b.Stop() }()

	c, rep, err := dialLocalSOCKS5(t, listenAddr, 0x01, echoAddr.IP.String(), uint16(echoAddr.Port), "", "")
	if err != nil || rep != 0x00 {
		t.Fatalf("dialLocalSOCKS5 bypass error=%v rep=0x%02x", err, rep)
	}
	defer c.Close()

	if _, err := c.Write([]byte("direct-msg")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 32)
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := c.Read(buf)
	if err != nil || string(buf[:n]) != "direct-msg" {
		t.Fatalf("got %q, err=%v, want direct-msg", string(buf[:n]), err)
	}
	if got := len(mock.recordedConnects()); got != 0 {
		t.Fatalf("upstream mock received %d connects, want 0 when bypassed", got)
	}
}

func TestDialStreamContextCancellation(t *testing.T) {
	// Upstream listener that accepts connections and stalls during handshake.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				time.Sleep(5 * time.Second)
			}(c)
		}
	}()

	d := newUpstreamDialer(ln.Addr().String(), "", "", 5*time.Second)
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	start := time.Now()
	conn, err := d.DialStream(ctx, "example.com:443")
	elapsed := time.Since(start)
	if err == nil {
		_ = conn.Close()
		t.Fatal("DialStream() succeeded despite cancelled ctx")
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("DialStream() error = %v, want context.DeadlineExceeded", err)
	}
	if elapsed > time.Second {
		t.Fatalf("DialStream() took %v to abort on cancelled ctx", elapsed)
	}
}

func TestReconnectAndStopLifecycle(t *testing.T) {
	mock := startMockSOCKS5Server(t, false, "", "")
	host, port := mock.hostPort()
	listenAddr := freeTCPAddr(t)

	b := NewBackend()
	params := map[string]string{
		ParamServerHost: host,
		ParamServerPort: strconv.Itoa(port),
	}
	if err := b.Start(listenAddr, params, false); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	// Duplicate Start must error
	if err := b.Start(listenAddr, params, false); err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("second Start() error = %v, want already running", err)
	}

	// Open an active tunnel
	c1, rep, err := dialLocalSOCKS5(t, listenAddr, 0x03, "active.test", 80, "", "")
	if err != nil || rep != 0x00 {
		t.Fatalf("dialLocalSOCKS5() err=%v rep=0x%02x", err, rep)
	}
	defer c1.Close()

	// Wait briefly to confirm upstream conn is tracked
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && mock.activeConnCount() == 0 {
		time.Sleep(10 * time.Millisecond)
	}
	if mock.activeConnCount() == 0 {
		t.Fatal("expected active upstream connection before Reconnect")
	}

	// Reconnect closes active upstream connections
	b.NotifyNetworkChanged()
	_ = c1.SetReadDeadline(time.Now().Add(2 * time.Second))
	var tmp [1]byte
	if _, err := c1.Read(tmp[:]); err == nil {
		t.Fatal("expected active connection to close after Reconnect")
	}

	// Subsequent requests after Reconnect still succeed
	c2, rep2, err := dialLocalSOCKS5(t, listenAddr, 0x03, "after-reconnect.test", 80, "", "")
	if err != nil || rep2 != 0x00 {
		t.Fatalf("dial after Reconnect failed: err=%v rep=0x%02x", err, rep2)
	}
	_, _ = c2.Write([]byte("ok"))
	buf := make([]byte, 16)
	_ = c2.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := c2.Read(buf)
	if err != nil || string(buf[:n]) != "ECHO:ok" {
		t.Fatalf("echo after Reconnect = %q, err=%v", string(buf[:n]), err)
	}
	_ = c2.Close()

	// Stop releases listener port and is idempotent
	if err := b.Stop(); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
	l2, err := net.Listen("tcp", listenAddr)
	if err != nil {
		t.Fatalf("SOCKS5 port not released after Stop(): %v", err)
	}
	_ = l2.Close()

	if err := b.Stop(); err != nil {
		t.Fatalf("second Stop() error = %v", err)
	}
}

func TestLogLevelFromParams(t *testing.T) {
	if got := logLevelFromParams(map[string]string{ParamLogLevel: "warn"}, true); got != config.WARN {
		t.Fatalf("explicit warn = %v, want WARN", got)
	}
	if got := logLevelFromParams(nil, true); got != config.DEBUG {
		t.Fatalf("verbose = %v, want DEBUG", got)
	}
	if got := logLevelFromParams(nil, false); got != config.INFO {
		t.Fatalf("default = %v, want INFO", got)
	}
}

func TestConfiguredCredentialsOnlyOfferUserPassAndRejectNoAuth(t *testing.T) {
	// Case 1: Server prefers 0x00 if offered, otherwise accepts 0x02 -> must see only [0x02]
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	offeredMethodsCh := make(chan []byte, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		var head [2]byte
		if _, err := io.ReadFull(c, head[:]); err != nil {
			return
		}
		methods := make([]byte, head[1])
		if _, err := io.ReadFull(c, methods); err != nil {
			return
		}
		offeredMethodsCh <- methods
		if bytes.Contains(methods, []byte{0x00}) {
			_, _ = c.Write([]byte{0x05, 0x00})
			return
		}
		_, _ = c.Write([]byte{0x05, 0x02})
		var verLen [2]byte
		if _, err := io.ReadFull(c, verLen[:]); err != nil {
			return
		}
		buf := make([]byte, int(verLen[1])+1)
		if _, err := io.ReadFull(c, buf); err != nil {
			return
		}
		pass := make([]byte, buf[len(buf)-1])
		if _, err := io.ReadFull(c, pass); err != nil {
			return
		}
		_, _ = c.Write([]byte{0x01, 0x00})
		var req [10]byte
		_, _ = io.ReadFull(c, req[:])
		_, _ = c.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0})
	}()

	d := newUpstreamDialer(ln.Addr().String(), "alice", "s3cr3t", 3*time.Second)
	conn, err := d.DialStream(context.Background(), "127.0.0.1:80")
	if err != nil {
		t.Fatalf("DialStream() error = %v", err)
	}
	_ = conn.Close()

	offered := <-offeredMethodsCh
	if !bytes.Equal(offered, []byte{0x02}) {
		t.Fatalf("offered methods = %x, want [02]", offered)
	}

	// Case 2: Misbehaving server replies 0x00 anyway -> client must reject
	ln2, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln2.Close()
	go func() {
		c, err := ln2.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		var head [2]byte
		if _, err := io.ReadFull(c, head[:]); err != nil {
			return
		}
		methods := make([]byte, head[1])
		if _, err := io.ReadFull(c, methods); err != nil {
			return
		}
		_, _ = c.Write([]byte{0x05, 0x00})
	}()

	d2 := newUpstreamDialer(ln2.Addr().String(), "alice", "s3cr3t", 3*time.Second)
	if conn2, err := d2.DialStream(context.Background(), "127.0.0.1:80"); err == nil {
		_ = conn2.Close()
		t.Fatal("DialStream() succeeded when upstream forced no-auth despite configured username")
	} else if !strings.Contains(err.Error(), "no-auth") {
		t.Fatalf("DialStream() error = %v, want mentioning no-auth", err)
	}
}
