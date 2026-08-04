package xtunnel

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	common "xclient/xtunnel/protocol"
)

func TestBackpressureState(t *testing.T) {
	cfg := DefaultConfig()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("Failed to create client pool: %v", err)
	}

	// Test initial state
	if atomic.LoadInt32(&pool.backpressureState) != int32(common.BackpressureNormal) {
		t.Errorf("Expected initial backpressure state to be Normal, got %d", atomic.LoadInt32(&pool.backpressureState))
	}

	// Test backpressure state change
	pool.handleBackpressure(common.BackpressureSlowDown)
	if atomic.LoadInt32(&pool.backpressureState) != int32(common.BackpressureSlowDown) {
		t.Errorf("Expected state to be SlowDown, got %d", atomic.LoadInt32(&pool.backpressureState))
	}

	// Test backpressure state change to pause
	pool.handleBackpressure(common.BackpressurePause)
	if atomic.LoadInt32(&pool.backpressureState) != int32(common.BackpressurePause) {
		t.Errorf("Expected state to be Pause, got %d", atomic.LoadInt32(&pool.backpressureState))
	}

	// Test backpressure state change back to normal
	pool.handleBackpressure(common.BackpressureNormal)
	if atomic.LoadInt32(&pool.backpressureState) != int32(common.BackpressureNormal) {
		t.Errorf("Expected state to be Normal, got %d", atomic.LoadInt32(&pool.backpressureState))
	}
}

func TestBackpressureDelay(t *testing.T) {
	cfg := DefaultConfig()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("Failed to create client pool: %v", err)
	}

	// Test normal state delay (should be 0)
	atomic.StoreInt32(&pool.backpressureState, int32(common.BackpressureNormal))
	delay := pool.getBackpressureDelay()
	if delay != 0 {
		t.Errorf("Expected delay 0 for Normal state, got %v", delay)
	}

	// Test slow down state delay (should be 10ms)
	atomic.StoreInt32(&pool.backpressureState, int32(common.BackpressureSlowDown))
	delay = pool.getBackpressureDelay()
	if delay != 10*time.Millisecond {
		t.Errorf("Expected delay 10ms for SlowDown state, got %v", delay)
	}

	// Test pause state delay (should be -1)
	atomic.StoreInt32(&pool.backpressureState, int32(common.BackpressurePause))
	delay = pool.getBackpressureDelay()
	if delay != -1 {
		t.Errorf("Expected delay -1 for Pause state, got %v", delay)
	}
}

func TestBackpressureWait(t *testing.T) {
	cfg := DefaultConfig()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("Failed to create client pool: %v", err)
	}

	// Test wait in pause state
	atomic.StoreInt32(&pool.backpressureState, int32(common.BackpressurePause))

	// Start a goroutine to restore state after a short delay
	go func() {
		time.Sleep(50 * time.Millisecond)
		pool.handleBackpressure(common.BackpressureNormal)
	}()

	start := time.Now()
	result := pool.waitForBackpressure()
	elapsed := time.Since(start)

	if !result {
		t.Error("Expected waitForBackpressure to return true")
	}
	if elapsed < 40*time.Millisecond {
		t.Errorf("Expected to wait at least 40ms, but only waited %v", elapsed)
	}
}

func TestBackpressureWaitWithCancel(t *testing.T) {
	cfg := DefaultConfig()
	ctx, cancel := context.WithCancel(context.Background())

	pool, err := newClientPool(cfg, ctx, cancel)
	if err != nil {
		t.Fatalf("Failed to create client pool: %v", err)
	}

	// Test wait with context cancellation
	atomic.StoreInt32(&pool.backpressureState, int32(common.BackpressurePause))

	// Cancel context after a short delay
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
	}()

	start := time.Now()
	result := pool.waitForBackpressure()
	elapsed := time.Since(start)

	if result {
		t.Error("Expected waitForBackpressure to return false when context is cancelled")
	}
	if elapsed < 40*time.Millisecond {
		t.Errorf("Expected to wait at least 40ms, but only waited %v", elapsed)
	}
}
