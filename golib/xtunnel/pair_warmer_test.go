package xtunnel

import (
	"context"
	"testing"
	"time"
)

func newTestClientPool(cfg *Config) *clientPool {
	ctx, cancel := context.WithCancel(context.Background())
	p, _ := newClientPool(cfg, ctx, cancel)
	return p
}

func TestPairWarmerNew(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)
	if w == nil {
		t.Fatal("NewPairWarmer() returned nil")
	}
	if w.config.PairCount != cfg.HotPairCount {
		t.Fatalf("expected PairCount %d, got %d", cfg.HotPairCount, w.config.PairCount)
	}
}

func TestPairWarmerAcquireReleaseKeepsPairReady(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	pair := &HotChannelPair{
		ID:           "test-pair-1",
		UplinkChID:   1,
		DownlinkChID: 2,
	}
	pair.SetStateForTest(PairStateReady)

	w.SetPrimaryForTest(pair)
	// 手动加入 pairs 列表
	w.mu.Lock()
	w.pairs = append(w.pairs, pair)
	w.mu.Unlock()

	acquired := w.AcquirePrimary()
	if acquired == nil {
		t.Fatal("AcquirePrimary() returned nil for ready pair")
	}
	if acquired.ID != pair.ID {
		t.Fatalf("expected acquired ID %q, got %q", pair.ID, acquired.ID)
	}

	w.ReleasePair(acquired)

	// Pair 应该保持 Ready 供后续请求复用
	if pair.State() != PairStateReady {
		t.Fatalf("expected state Ready after release, got %d", pair.State())
	}
	if w.PairCountForTest() != 1 {
		t.Fatalf("expected 1 pair, got %d", w.PairCountForTest())
	}

	// 再次获取应仍然成功
	acquired2 := w.AcquirePrimary()
	if acquired2 == nil {
		t.Fatal("expected to re-acquire released pair")
	}
	w.ReleasePair(acquired2)
}

func TestPairWarmerReleaseDrainingPair(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	pair := &HotChannelPair{
		ID:           "test-pair-1",
		UplinkChID:   1,
		DownlinkChID: 2,
	}
	pair.SetStateForTest(PairStateDraining)
	pair.refs = 1

	w.SetPrimaryForTest(pair)
	// 手动加入 pairs 列表以便 removePair 工作
	w.mu.Lock()
	w.pairs = append(w.pairs, pair)
	w.mu.Unlock()

	w.ReleasePair(pair)

	if pair.State() != PairStateClosed {
		t.Fatalf("expected state Closed after release, got %d", pair.State())
	}
	if w.PairCountForTest() != 0 {
		t.Fatalf("expected 0 pairs, got %d", w.PairCountForTest())
	}
}

func TestPairWarmerInvalidateChannel(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	pair1 := &HotChannelPair{
		ID:           "pair-1",
		UplinkChID:   1,
		DownlinkChID: 2,
	}
	pair1.SetStateForTest(PairStateReady)

	pair2 := &HotChannelPair{
		ID:           "pair-2",
		UplinkChID:   3,
		DownlinkChID: 4,
	}
	pair2.SetStateForTest(PairStateReady)

	w.mu.Lock()
	w.pairs = append(w.pairs, pair1, pair2)
	w.primary = pair1
	w.mu.Unlock()

	w.InvalidateChannel(2)

	if pair1.State() != PairStateClosed {
		t.Fatalf("expected pair1 state Closed, got %d", pair1.State())
	}
	if pair2.State() != PairStateReady {
		t.Fatalf("expected pair2 state Ready, got %d", pair2.State())
	}
	if w.PairCountForTest() != 1 {
		t.Fatalf("expected 1 pair remaining, got %d", w.PairCountForTest())
	}

	// primary 失效后应自动选举 pair2 为新的 primary
	w.mu.RLock()
	prim := w.primary
	w.mu.RUnlock()
	if prim != pair2 {
		t.Fatal("expected primary to be pair2 after invalidating pair1")
	}
}

func TestPairWarmerInvalidateChannelDoesNotCloseReadyPair(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	pair := &HotChannelPair{
		ID:           "pair-1",
		UplinkChID:   1,
		DownlinkChID: 2,
	}
	pair.SetStateForTest(PairStateReady)
	pair.refs = 1

	w.mu.Lock()
	w.pairs = append(w.pairs, pair)
	w.primary = pair
	w.mu.Unlock()

	w.InvalidateChannel(2)

	if pair.State() != PairStateDraining {
		t.Fatalf("expected state Draining, got %d", pair.State())
	}
	if w.PairCountForTest() != 1 {
		t.Fatalf("expected 1 pair (refs > 0), got %d", w.PairCountForTest())
	}

	w.ReleasePair(pair)

	if pair.State() != PairStateClosed {
		t.Fatalf("expected state Closed after release, got %d", pair.State())
	}
	if w.PairCountForTest() != 0 {
		t.Fatalf("expected 0 pairs after release, got %d", w.PairCountForTest())
	}
}

func TestPairWarmerBuildPairTimeout(t *testing.T) {
	cfg := DefaultConfig()
	cfg.HotPairCount = 1
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)
	w.config.PrebindTimeout = 100 * time.Millisecond

	// 使用没有真实连接的 clientPool，BuildPair 应超时返回错误
	available := []int{1, 2, 3}
	_, err := w.BuildPair(available)
	if err == nil {
		t.Fatal("expected BuildPair to return error on timeout")
	}
	if err.Error() != "预绑定超时" {
		t.Fatalf("expected timeout error, got: %v", err)
	}
}

func TestPairWarmerHandlePrebindResultMatchesConnID(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	// 发送一个不匹配的 connID 结果
	w.HandlePrebindResult("wrong-conn-id", 1, 2, nil)

	// 发送匹配的 connID 结果
	w.HandlePrebindResult("prebind-test-123", 1, 2, nil)

	// 验证通道中有两个结果
	if len(w.prebindResultCh) != 2 {
		t.Fatalf("expected 2 prebind results in channel, got %d", len(w.prebindResultCh))
	}

	// 读取第一个（不匹配的）
	res1 := <-w.prebindResultCh
	if res1.connID != "wrong-conn-id" {
		t.Fatalf("expected first result connID wrong-conn-id, got %s", res1.connID)
	}

	// 读取第二个（匹配的）
	res2 := <-w.prebindResultCh
	if res2.connID != "prebind-test-123" {
		t.Fatalf("expected second result connID prebind-test-123, got %s", res2.connID)
	}
	if res2.uplinkChID != 1 || res2.downlinkChID != 2 {
		t.Fatalf("expected uplink 1 downlink 2, got %d %d", res2.uplinkChID, res2.downlinkChID)
	}
}

func TestPairWarmerAcquireReturnsNilForDrainingPair(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	pair := &HotChannelPair{
		ID:           "test-pair",
		UplinkChID:   1,
		DownlinkChID: 2,
	}
	pair.SetStateForTest(PairStateDraining)

	w.SetPrimaryForTest(pair)

	acquired := w.AcquirePrimary()
	if acquired != nil {
		t.Fatal("expected AcquirePrimary to return nil for draining pair")
	}
}

func TestPairWarmerAcquireReturnsNilForClosedPair(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	pair := &HotChannelPair{
		ID:           "test-pair",
		UplinkChID:   1,
		DownlinkChID: 2,
	}
	pair.SetStateForTest(PairStateClosed)

	w.SetPrimaryForTest(pair)

	acquired := w.AcquirePrimary()
	if acquired != nil {
		t.Fatal("expected AcquirePrimary to return nil for closed pair")
	}
}

func TestPairWarmerBuildPairInsufficientChannels(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	_, err := w.BuildPair([]int{1})
	if err == nil {
		t.Fatal("expected BuildPair to return error with insufficient channels")
	}
}

func TestPairWarmerHandlePrebindResultChannelFull(t *testing.T) {
	cfg := DefaultConfig()
	p := newTestClientPool(cfg)
	w := NewPairWarmer(p, cfg)

	// 填满通道
	for i := 0; i < 8; i++ {
		w.HandlePrebindResult("fill", 1, 2, nil)
	}

	// 第9个应该被丢弃（不会panic）
	w.HandlePrebindResult("overflow", 1, 2, nil)

	if len(w.prebindResultCh) != 8 {
		t.Fatalf("expected channel to remain at capacity 8, got %d", len(w.prebindResultCh))
	}
}
