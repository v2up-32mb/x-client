package dns

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
	"time"

	"xclient/shared/config"
	"xclient/shared/logger"
)

func initTestLogger() {
	dft := config.DefaultConfig()
	dft.LogLevel = config.DEBUG
	logger.InitGlobalLogger(dft)
}

// 验证内置列表顺序与内容
func TestDefaultDoHServers(t *testing.T) {
	want := []string{
		"https://v.recipes/dns-query",
		"https://doh.090227.xyz/CMLiussss",
		"https://doh.pub/dns-query",
	}
	if len(DefaultDoHServers) != 3 {
		t.Fatalf("expect 3 servers, got %d", len(DefaultDoHServers))
	}
	for i := range want {
		if DefaultDoHServers[i] != want[i] {
			t.Errorf("server[%d] = %q, want %q", i, DefaultDoHServers[i], want[i])
		}
	}
}

// 用户手动指定 → 只用用户的
func TestUserSpecifiedDoH(t *testing.T) {
	initTestLogger()
	dft := config.DefaultConfig()
	dft.DoHUrl = "https://my.custom.doh/dns-query"
	c := NewDoHClient(dft)
	if len(c.dohURLs) != 1 {
		t.Fatalf("expect 1 url, got %d: %v", len(c.dohURLs), c.dohURLs)
	}
	if c.dohURLs[0] != "https://my.custom.doh/dns-query" {
		t.Errorf("url = %q", c.dohURLs[0])
	}
}

// 用户不指定 → 内置 3 个
func TestBuiltinDoHList(t *testing.T) {
	initTestLogger()
	dft := config.DefaultConfig()
	dft.DoHUrl = ""
	c := NewDoHClient(dft)
	if len(c.dohURLs) != 3 {
		t.Fatalf("expect 3 urls, got %d: %v", len(c.dohURLs), c.dohURLs)
	}
	for i := range DefaultDoHServers {
		if c.dohURLs[i] != DefaultDoHServers[i] {
			t.Errorf("url[%d] = %q, want %q", i, c.dohURLs[i], DefaultDoHServers[i])
		}
	}
}

// 所有 DoH 失败 → Resolve 返回 "所有 DoH 服务器均失败"
func TestAllDoHFail(t *testing.T) {
	initTestLogger()
	dft := config.DefaultConfig()
	dft.DoHUrl = ""
	c := NewDoHClient(dft)
	// 用不可路由地址，连接会快速失败（无需等满超时）
	c.dohURLs = []string{
		"https://127.0.0.1:1/dns-query",
		"https://127.0.0.1:2/dns-query",
		"https://127.0.0.1:3/dns-query",
	}
	// 缩短超时加速（连接拒绝快速失败，超时作为上限）
	c.client.Timeout = 500 * time.Millisecond
	start := time.Now()
	_, err := c.Resolve("example.com", "A")
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("expect error when all DoH fail, got nil")
	}
	if !strings.Contains(err.Error(), "所有 DoH") {
		t.Errorf("error = %q, expect contains '所有 DoH'", err.Error())
	}
	// 全失败总耗时应小于单台超时的多倍
	if elapsed > 8*time.Second {
		t.Errorf("too slow: %v", elapsed)
	}
}

// 系统 DNS 解析冒烟
func TestSystemDNSFallback(t *testing.T) {
	ips, err := LookupIP("localhost")
	if err != nil {
		t.Fatal(err)
	}
	if len(ips) == 0 {
		t.Fatal("expect at least one IP for localhost")
	}
	if net.ParseIP(ips[0]) == nil {
		t.Errorf("not an IP: %s", ips[0])
	}
}

func TestResolveHTTPSUDP(t *testing.T) {
	// 本地 UDP DNS 服务器：回应 HTTPS(65) 记录（ECH key=5）
	pc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp failed: %v", err)
	}
	defer pc.Close()

	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			query := buf[:n]
			id := binary.BigEndian.Uint16(query[0:2])

			// 手工构造响应（dnsmessage.Builder 的 UnknownResource 会把 TYPE
			// 覆盖为 0，无法表达 HTTPS(65) 记录）：问题段回显 + 一条 HTTPS answer
			qname := []byte{3, 'e', 'c', 'h', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0}
			var resp []byte
			resp = binary.BigEndian.AppendUint16(resp, id)
			resp = append(resp, 0x81, 0x80)               // QR=1 RD=1 RA=1
			resp = binary.BigEndian.AppendUint16(resp, 1) // qdcount
			resp = binary.BigEndian.AppendUint16(resp, 1) // ancount
			resp = binary.BigEndian.AppendUint16(resp, 0)
			resp = binary.BigEndian.AppendUint16(resp, 0)
			resp = append(resp, qname...)
			resp = binary.BigEndian.AppendUint16(resp, 65) // HTTPS
			resp = binary.BigEndian.AppendUint16(resp, 1)  // IN
			resp = append(resp, 0xC0, 0x0C)                // 回答名：指针指向问题名
			resp = binary.BigEndian.AppendUint16(resp, 65)
			resp = binary.BigEndian.AppendUint16(resp, 1)
			resp = binary.BigEndian.AppendUint32(resp, 300)
			// rdata: priority=1, target=".", ech(5)=0xAABB
			rdata := []byte{0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x02, 0xAA, 0xBB}
			resp = binary.BigEndian.AppendUint16(resp, uint16(len(rdata)))
			resp = append(resp, rdata...)
			_, _ = pc.WriteToUDP(resp, addr)
		}
	}()

	d := NewDoHClient(config.DefaultConfig())
	ech, err := d.ResolveHTTPSUDP("ech.example.com", pc.LocalAddr().String())
	if err != nil {
		t.Fatalf("ResolveHTTPSUDP() error = %v", err)
	}
	want := []byte{0xAA, 0xBB}
	if len(ech) != len(want) || ech[0] != want[0] || ech[1] != want[1] {
		t.Fatalf("ECH = %v, want %v", ech, want)
	}
}
