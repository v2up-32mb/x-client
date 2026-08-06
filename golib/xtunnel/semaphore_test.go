package xtunnel

import (
	"io"
	"net"
	"testing"
	"time"
)

// TestSOCKS5MaxConnectionsIsConcurrentNotCumulative 验证 SOCKS5 连接数限制是并发语义：
// 顺序短连接不应占用信号量；并发占满时才拒绝新连接。
func TestSOCKS5MaxConnectionsIsConcurrentNotCumulative(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxSOCKS5Connections = 3
	p := newTestClientPool(cfg)
	if err := p.ListenSOCKS5("127.0.0.1:0"); err != nil {
		t.Fatalf("ListenSOCKS5: %v", err)
	}
	addr := p.listeners[0].Addr().String()

	// 场景 1：顺序发起 10 次短连接（握手后关闭），信号量应全部释放
	for i := 0; i < 10; i++ {
		c, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatalf("dial %d: %v", i, err)
		}
		if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
			c.Close()
			t.Fatalf("write %d: %v", i, err)
		}
		buf := make([]byte, 2)
		if _, err := io.ReadFull(c, buf); err != nil {
			c.Close()
			t.Fatalf("read greeting %d: %v", i, err)
		}
		_ = c.Close()
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if len(p.socks5Sem) == 0 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if len(p.socks5Sem) != 0 {
		t.Fatalf("semaphore leaked: %d/3 slots still held after 10 sequential short connections", len(p.socks5Sem))
	}

	// 场景 2：3 个连接保持打开占满信号量后，第 4 个连接应被拒绝
	var conns []net.Conn
	for i := 0; i < 3; i++ {
		c, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatalf("concurrent dial %d: %v", i, err)
		}
		// 不发握手数据，让 handleSOCKS5 阻塞在读握手，保持连接占用信号量
		conns = append(conns, c)
	}
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if len(p.socks5Sem) == 3 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if len(p.socks5Sem) != 3 {
		t.Fatalf("expected 3/3 slots held, got %d", len(p.socks5Sem))
	}

	c4, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("4th dial: %v", err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(c4, buf); err == nil {
		t.Fatal("expected 4th concurrent connection to be rejected (greeting should fail)")
	}
	_ = c4.Close()

	for _, c := range conns {
		_ = c.Close()
	}
}

// TestSOCKS5SoftLimitWaitWindowAbsorbsBurst 验证并发上限前的软等待窗口：
// 一个连接占满槽位后，紧随其后的连接先等待 softLimitWait 窗口；
// 若窗口内槽位释放（突发短连接），则照常完成握手，而不是被拒绝。
func TestSOCKS5SoftLimitWaitWindowAbsorbsBurst(t *testing.T) {
	cfg := DefaultConfig()
	cfg.MaxSOCKS5Connections = 1
	p := newTestClientPool(cfg)
	if err := p.ListenSOCKS5("127.0.0.1:0"); err != nil {
		t.Fatalf("ListenSOCKS5: %v", err)
	}
	addr := p.listeners[0].Addr().String()

	// 连接 A 占满唯一槽位（不发握手数据，阻塞在读取问候阶段）
	connA, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial A: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if len(p.socks5Sem) == 1 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if len(p.socks5Sem) != 1 {
		connA.Close()
		t.Fatalf("expected A to hold the only slot, got %d", len(p.socks5Sem))
	}

	// 连接 B 紧随其后：应在等待窗口内等到 A 释放槽位，握手成功
	connB, err := net.Dial("tcp", addr)
	if err != nil {
		connA.Close()
		t.Fatalf("dial B: %v", err)
	}
	time.Sleep(20 * time.Millisecond)
	_ = connA.Close() // 短连接立即结束，槽位在窗口内释放

	if _, err := connB.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		connB.Close()
		t.Fatalf("B greeting write: %v", err)
	}
	buf := make([]byte, 2)
	_ = connB.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(connB, buf); err != nil {
		connB.Close()
		t.Fatalf("B greeting reply failed (should be accepted after slot released): %v", err)
	}
	_ = connB.Close()
}
