package pool

import (
	"sync"
	"testing"
	"time"
)

func TestHandleConnectionCloseAllowsCleanupCallbacks(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 1, 256*1024, 32*1024, 1024*1024, 5*time.Second)
	streamID, ok := sm.tryAllocateStream("example.com:443")
	if !ok {
		t.Fatal("stream allocation failed")
	}
	sm.RegisterHandler(streamID, &StreamHandler{
		OnClose: func() {
			// This mirrors the SOCKS cleanup path, which unregisters the stream.
			sm.UnregisterStream(streamID)
		},
	})

	done := make(chan struct{})
	go func() {
		sm.HandleConnectionClose()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("HandleConnectionClose deadlocked while running cleanup callback")
	}

	if got := sm.GetStreamCount(); got != 0 {
		t.Fatalf("stream manager retained %d streams after connection close", got)
	}
}

func TestStreamTrafficCountBalancesAtAllocationBoundary(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x04, 0x05, 0x06},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 1, 256*1024, 32*1024, 1024*1024, 5*time.Second)
	streamID, ok := sm.tryAllocateStream("example.com:443")
	if !ok {
		t.Fatal("stream allocation failed")
	}

	_, _, active := conn.Traffic.GetSnapshot()
	if active != 1 {
		t.Fatalf("active streams after allocation = %d, want 1", active)
	}

	sm.UnregisterStream(streamID)
	_, _, active = conn.Traffic.GetSnapshot()
	if active != 0 {
		t.Fatalf("active streams after unregister = %d, want 0", active)
	}
}

// TestBitmapAllocation 测试位图分配算法的基本功能
func TestBitmapAllocation(t *testing.T) {
	// 创建测试用的 StreamManager
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	// 测试 1: 分配第一个 Stream ID
	id1, ok := sm.tryAllocateStream("test1.com:443")
	if !ok {
		t.Fatal("第一次分配失败")
	}
	if id1 != 0 {
		t.Errorf("期望第一个 ID 为 0，实际为 %d", id1)
	}

	// 测试 2: 分配第二个 Stream ID
	id2, ok := sm.tryAllocateStream("test2.com:443")
	if !ok {
		t.Fatal("第二次分配失败")
	}
	if id2 != 1 {
		t.Errorf("期望第二个 ID 为 1，实际为 %d", id2)
	}

	// 测试 3: 释放第一个 ID，再次分配应该复用
	sm.UnregisterStream(id1)
	id3, ok := sm.tryAllocateStream("test3.com:443")
	if !ok {
		t.Fatal("第三次分配失败")
	}
	// 注意：nextHint 已经移动到 2，所以会分配 2 而不是 0
	if id3 != 2 {
		t.Errorf("期望第三个 ID 为 2，实际为 %d", id3)
	}

	// 测试 4: 验证位图状态
	if !sm.isBitSet(id2) {
		t.Error("ID 1 应该被标记为已分配")
	}
	if !sm.isBitSet(id3) {
		t.Error("ID 2 应该被标记为已分配")
	}
	if sm.isBitSet(id1) {
		t.Error("ID 0 应该被标记为已释放")
	}
}

// TestBitmapFullAllocation 测试分配所有 256 个 Stream ID
func TestBitmapFullAllocation(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	// 分配所有 256 个 ID
	allocated := make(map[byte]bool)
	for i := 0; i < 256; i++ {
		id, ok := sm.tryAllocateStream("test.com:443")
		if !ok {
			t.Fatalf("第 %d 次分配失败", i+1)
		}
		if allocated[id] {
			t.Fatalf("ID %d 被重复分配", id)
		}
		allocated[id] = true
	}

	// 验证所有 ID 都已分配
	if len(allocated) != 256 {
		t.Errorf("期望分配 256 个 ID，实际分配 %d 个", len(allocated))
	}

	// 尝试再次分配应该失败
	_, ok := sm.tryAllocateStream("test.com:443")
	if ok {
		t.Error("所有 ID 已分配，应该返回失败")
	}
}

// TestBitmapConcurrentAllocation 测试并发分配的正确性
func TestBitmapConcurrentAllocation(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	// 并发分配 100 个 Stream
	const numGoroutines = 10
	const perGoroutine = 10
	var wg sync.WaitGroup
	allocated := make(chan byte, numGoroutines*perGoroutine)

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < perGoroutine; j++ {
				id, ok := sm.tryAllocateStream("test.com:443")
				if ok {
					allocated <- id
				}
			}
		}()
	}

	wg.Wait()
	close(allocated)

	// 验证没有重复分配
	seen := make(map[byte]bool)
	count := 0
	for id := range allocated {
		if seen[id] {
			t.Errorf("ID %d 被重复分配", id)
		}
		seen[id] = true
		count++
	}

	if count != numGoroutines*perGoroutine {
		t.Errorf("期望分配 %d 个 ID，实际分配 %d 个",
			numGoroutines*perGoroutine, count)
	}
}

// BenchmarkStreamAllocation 基准测试：位图分配性能
func BenchmarkStreamAllocation(b *testing.B) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		// 分配
		id, ok := sm.tryAllocateStream("test.com:443")
		if !ok {
			// 池满了，清空重新开始
			sm.mu.Lock()
			sm.streams = make(map[byte]*Stream)
			sm.allocBitmap = [4]uint64{}
			sm.nextHint = 0
			sm.mu.Unlock()
			continue
		}
		// 立即释放
		sm.UnregisterStream(id)
	}
}

// BenchmarkStreamAllocationWithTimeout 基准测试：带超时的分配
func BenchmarkStreamAllocationWithTimeout(b *testing.B) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		id, ok := sm.AllocateStream("test.com:443", 100*time.Millisecond)
		if !ok {
			sm.mu.Lock()
			sm.streams = make(map[byte]*Stream)
			sm.allocBitmap = [4]uint64{}
			sm.nextHint = 0
			sm.mu.Unlock()
			continue
		}
		sm.UnregisterStream(id)
	}
}

// ============================================================================
// 窗口流控测试
// ============================================================================

// TestFlowControl 测试基本的窗口流控功能
func TestFlowControl(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	// 分配一个 Stream
	id, ok := sm.tryAllocateStream("test.com:443")
	if !ok {
		t.Fatal("分配 Stream 失败")
	}

	sm.mu.RLock()
	s := sm.streams[id]
	sm.mu.RUnlock()

	// 测试初始窗口大小
	if s.sendWindow != DefaultWindowSize {
		t.Errorf("期望发送窗口为 %d，实际为 %d", DefaultWindowSize, s.sendWindow)
	}

	// 测试消耗发送窗口
	err := s.WaitForSendWindow(1024)
	if err != nil {
		t.Errorf("消耗发送窗口失败: %v", err)
	}

	// 测试补充发送窗口
	s.RefillSendWindow(1024)
	if s.sendWindow != DefaultWindowSize {
		t.Errorf("期望发送窗口恢复为 %d，实际为 %d", DefaultWindowSize, s.sendWindow)
	}
}

// TestCongestionControl 测试拥塞控制功能
func TestCongestionControl(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	// 分配一个 Stream
	id, ok := sm.tryAllocateStream("test.com:443")
	if !ok {
		t.Fatal("分配 Stream 失败")
	}

	sm.mu.RLock()
	s := sm.streams[id]
	sm.mu.RUnlock()

	// 测试 RTT 记录
	s.RecordRTT(50 * time.Millisecond)
	s.RecordRTT(60 * time.Millisecond)
	s.RecordRTT(55 * time.Millisecond)

	avgRTT := s.GetAverageRTT()
	if avgRTT == 0 {
		t.Error("平均 RTT 不应该为 0")
	}

	// 测试成功/超时记录
	s.RecordSuccess()
	s.RecordSuccess()
	s.RecordTimeout()

	lossRate := s.GetLossRate()
	expectedRate := 1.0 / 3.0
	if lossRate < expectedRate-0.01 || lossRate > expectedRate+0.01 {
		t.Errorf("期望丢包率约为 %.2f，实际为 %.2f", expectedRate, lossRate)
	}
}

// TestStateMachine 测试状态机功能
func TestStateMachine(t *testing.T) {
	conn := &ConnItem{
		ConnectionID: []byte{0x01, 0x02, 0x03},
		Traffic:      &TrafficCounter{},
	}
	sm := NewStreamManager(conn, 256, 256*1024, 32*1024, 1024*1024, 5*time.Second)

	// 分配一个 Stream
	id, ok := sm.tryAllocateStream("test.com:443")
	if !ok {
		t.Fatal("分配 Stream 失败")
	}

	sm.mu.RLock()
	s := sm.streams[id]
	sm.mu.RUnlock()

	// 测试初始状态
	if s.GetState() != StreamStateIdle {
		t.Errorf("期望初始状态为 Idle，实际为 %v", s.GetState())
	}

	// 测试合法的状态转换
	err := s.TransitionState(StreamStateSynSent)
	if err != nil {
		t.Errorf("Idle -> SynSent 转换失败: %v", err)
	}

	err = s.TransitionState(StreamStateEstablished)
	if err != nil {
		t.Errorf("SynSent -> Established 转换失败: %v", err)
	}

	// 测试非法的状态转换
	err = s.TransitionState(StreamStateSynSent)
	if err == nil {
		t.Error("Established -> SynSent 应该失败")
	}
}
