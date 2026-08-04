package relay

import (
	"testing"
	"time"
)

func TestHealthScoreAdjustsInterval(t *testing.T) {
	mgr := NewRelayNodeManager()
	mgr.SetHealthScore(20)
	interval := mgr.CurrentTestInterval()
	if interval != 15*time.Second {
		t.Fatalf("interval = %v, want 15s", interval)
	}
	mgr.SetHealthScore(50)
	interval = mgr.CurrentTestInterval()
	if interval != 30*time.Second {
		t.Fatalf("interval = %v, want 30s", interval)
	}
	mgr.SetHealthScore(80)
	interval = mgr.CurrentTestInterval()
	if interval != 60*time.Second {
		t.Fatalf("interval = %v, want 60s", interval)
	}
}

func TestHealthScoreClampsToRange(t *testing.T) {
	mgr := NewRelayNodeManager()
	mgr.SetHealthScore(-10)
	if got := mgr.GetHealthScore(); got != 0 {
		t.Fatalf("score = %d, want 0", got)
	}
	mgr.SetHealthScore(150)
	if got := mgr.GetHealthScore(); got != 100 {
		t.Fatalf("score = %d, want 100", got)
	}
}

func TestUpdateHealthScoreFromNodes(t *testing.T) {
	mgr := NewRelayNodeManager()
	mgr.nodes = []*RelayNode{
		{ID: "good", IP: "good", FailCount: 0},
		{ID: "bad", IP: "bad", FailCount: 1},
	}
	mgr.updateHealthScore()
	if got := mgr.GetHealthScore(); got != 50 {
		t.Fatalf("score = %d, want 50", got)
	}
}
