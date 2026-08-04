package relay

import (
	"net"
	"strconv"
	"testing"
	"time"

	"xclient/config"
)

func TestRescoreAllReleasesManagerLock(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()

	host, portText, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatalf("split listener address: %v", err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatalf("parse listener port: %v", err)
	}

	rm := NewRelayManager(nil, config.DefaultConfig(), nil)
	node := &RelayNode{IP: host, Port: port, Source: "test"}
	rm.isInitialized = true
	rm.allNodes = []*RelayNode{node}
	rm.optimalRelays = []*RelayNode{node}

	done := make(chan struct{})
	go func() {
		rm.rescoreAll()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("rescoreAll deadlocked while logging relays under the manager lock")
	}

	selected := make(chan *RelayNode, 1)
	go func() {
		selected <- rm.GetNextRelayWithLoadBalance()
	}()
	select {
	case got := <-selected:
		if got == nil {
			t.Fatal("relay selection returned nil after successful rescore")
		}
	case <-time.After(time.Second):
		t.Fatal("relay selection remained blocked after rescore")
	}
}
