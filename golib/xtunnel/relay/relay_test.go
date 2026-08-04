package relay

import (
	"net"
	"testing"
	"time"
)

func TestMarkNodeSuccessRefreshesScoreAndLastTest(t *testing.T) {
	m := NewRelayNodeManager()
	node := &RelayNode{
		ID:          "1.1.1.1:443",
		Address:     "1.1.1.1:443",
		IP:          "1.1.1.1:443",
		Latency:     100 * time.Millisecond,
		LastTest:    time.Now().Add(-2 * time.Hour),
		SuccessRate: 0,
		Score:       0,
		FailCount:   3,
	}
	m.nodes = []*RelayNode{node}

	before := time.Now()
	m.MarkNodeSuccess(node.IP)

	if node.FailCount != 0 {
		t.Fatalf("FailCount = %d, want 0", node.FailCount)
	}
	if node.SuccessRate != 1.0 {
		t.Fatalf("SuccessRate = %v, want 1.0", node.SuccessRate)
	}
	if !node.LastTest.After(before.Add(-100 * time.Millisecond)) {
		t.Fatalf("LastTest = %v, want refreshed time after %v", node.LastTest, before)
	}
	if node.Score <= 0 {
		t.Fatalf("Score = %v, want refreshed positive score", node.Score)
	}
	if node.Weight != node.Score {
		t.Fatalf("Weight = %v, want match Score %v", node.Weight, node.Score)
	}
}

func TestSelectNodeExcludingFallsBackWhenNoHealthyCandidate(t *testing.T) {
	m := NewRelayNodeManager()
	m.nodes = []*RelayNode{
		{ID: "bad", Address: "bad", IP: "bad", Score: 10, FailCount: 5, SuccessRate: 0},
		{ID: "better", Address: "better", IP: "better", Score: 20, FailCount: 1, SuccessRate: 0},
	}

	node := m.SelectNodeExcluding(nil)
	if node == nil {
		t.Fatalf("SelectNodeExcluding() = nil, want fallback candidate")
	}
	if node.IP != "better" {
		t.Fatalf("fallback node = %q, want %q", node.IP, "better")
	}
}

func TestSelectNodeExcludingRefreshesHostnameNodesAndReturnsNewIP(t *testing.T) {
	m := NewRelayNodeManager()
	lookupCalls := 0
	m.lookupIP = func(host string) ([]net.IP, error) {
		lookupCalls++
		if lookupCalls == 1 {
			return []net.IP{net.ParseIP("192.0.2.10")}, nil
		}
		return []net.IP{net.ParseIP("192.0.2.20")}, nil
	}

	if err := m.AddNode("relay.example.com:443", "443"); err != nil {
		t.Fatalf("AddNode() error = %v", err)
	}
	oldIP := m.nodes[0].IP
	m.MarkNodeFailed(oldIP)

	node := m.SelectNodeExcluding([]string{oldIP})
	if node == nil {
		t.Fatalf("SelectNodeExcluding() = nil, want newly resolved node")
	}
	if node.IP != "192.0.2.20:443" {
		t.Fatalf("selected node IP = %q, want %q", node.IP, "192.0.2.20:443")
	}
	if len(m.nodes) < 2 {
		t.Fatalf("expected refreshed node list to include new IP, got %d nodes", len(m.nodes))
	}
}
