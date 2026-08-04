package xtunnel

import (
	"testing"
	"time"
)

func TestDefaultConfigHasHotPairDefaults(t *testing.T) {
	cfg := DefaultConfig()
	if cfg.EnableHotPair {
		t.Fatal("EnableHotPair default should be false")
	}
	if cfg.HotPairCount != 1 {
		t.Fatalf("HotPairCount default = %d, want 1", cfg.HotPairCount)
	}
	if cfg.HotPairRefreshInterval != 30*time.Second {
		t.Fatalf("HotPairRefreshInterval = %v, want 30s", cfg.HotPairRefreshInterval)
	}
	if cfg.FastRetryAttempts != 1 {
		t.Fatalf("FastRetryAttempts default = %d, want 1", cfg.FastRetryAttempts)
	}
	if cfg.FastRetryWindow != 1*time.Second {
		t.Fatalf("FastRetryWindow = %v, want 1s", cfg.FastRetryWindow)
	}
	if cfg.MaxFastRetryConsecutive != 3 {
		t.Fatalf("MaxFastRetryConsecutive default = %d, want 3", cfg.MaxFastRetryConsecutive)
	}
}
