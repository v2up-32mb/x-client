package socks5

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"

	"xclient/shared/config"
	"xclient/shared/routing"
)

func TestBypassRequestsUseDirectConnection(t *testing.T) {
	tests := []struct {
		name string
		host string
	}{
		{name: "IP", host: "127.0.0.1"},
		{name: "domain", host: "localhost"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			testDirectBypass(t, tt.host)
		})
	}
}

func testDirectBypass(t *testing.T, host string) {
	t.Helper()
	target, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen target: %v", err)
	}
	defer target.Close()
	go func() {
		conn, acceptErr := target.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_, _ = io.Copy(conn, conn)
	}()

	matcher, err := routing.NewMatcher(false, false, false, host)
	if err != nil {
		t.Fatalf("NewMatcher(): %v", err)
	}
	cfg := config.DefaultConfig()
	cfg.EnableDoH = false
	server := NewServer(cfg, nil, nil)
	server.SetBypassMatcher(matcher)

	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	go server.handleConnection(serverConn)
	_ = clientConn.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := clientConn.Write([]byte{socks5Version, 1, authNone}); err != nil {
		t.Fatalf("write auth: %v", err)
	}
	authReply := make([]byte, 2)
	if _, err := io.ReadFull(clientConn, authReply); err != nil {
		t.Fatalf("read auth reply: %v", err)
	}
	if authReply[0] != socks5Version || authReply[1] != authNone {
		t.Fatalf("auth reply = %v", authReply)
	}

	request := buildConnectRequest(t, host, uint16(target.Addr().(*net.TCPAddr).Port))
	if _, err := clientConn.Write(request); err != nil {
		t.Fatalf("write request: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(clientConn, reply); err != nil {
		t.Fatalf("read request reply: %v", err)
	}
	if reply[1] != 0 {
		t.Fatalf("request reply = %v", reply)
	}

	payload := []byte("direct bypass")
	if _, err := clientConn.Write(payload); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(clientConn, got); err != nil {
		t.Fatalf("read payload: %v", err)
	}
	if string(got) != string(payload) {
		t.Fatalf("payload = %q, want %q", got, payload)
	}
}

func buildConnectRequest(t *testing.T, host string, port uint16) []byte {
	t.Helper()
	if ip := net.ParseIP(host).To4(); ip != nil {
		request := []byte{socks5Version, cmdConnect, 0, atypIPv4, ip[0], ip[1], ip[2], ip[3], 0, 0}
		binary.BigEndian.PutUint16(request[8:], port)
		return request
	}
	if len(host) > 255 {
		t.Fatalf("test host too long: %q", host)
	}
	request := []byte{socks5Version, cmdConnect, 0, atypDomain, byte(len(host))}
	request = append(request, host...)
	request = append(request, 0, 0)
	binary.BigEndian.PutUint16(request[len(request)-2:], port)
	return request
}
