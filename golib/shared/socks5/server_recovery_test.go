package socks5

import (
	"testing"
	"time"
)

func TestWaitForTunnelDoesNotExpireEstablishedStream(t *testing.T) {
	closed := make(chan struct{})
	connected := make(chan struct{})
	result := make(chan tunnelWaitResult, 1)

	go func() {
		result <- waitForTunnel(closed, connected, 10*time.Millisecond)
	}()
	close(connected)

	select {
	case got := <-result:
		t.Fatalf("established tunnel returned before endpoint close: %v", got)
	case <-time.After(30 * time.Millisecond):
	}

	close(closed)
	select {
	case got := <-result:
		if got != tunnelClosed {
			t.Fatalf("result = %v, want tunnelClosed", got)
		}
	case <-time.After(time.Second):
		t.Fatal("established tunnel did not finish after endpoint close")
	}
}

func TestWaitForTunnelTimesOutBeforeConnected(t *testing.T) {
	closed := make(chan struct{})
	connected := make(chan struct{})

	if got := waitForTunnel(closed, connected, 10*time.Millisecond); got != tunnelConnectTimeout {
		t.Fatalf("result = %v, want tunnelConnectTimeout", got)
	}
}

func TestWaitForTunnelAcceptsEarlyClose(t *testing.T) {
	closed := make(chan struct{})
	connected := make(chan struct{})
	close(closed)

	if got := waitForTunnel(closed, connected, time.Second); got != tunnelClosed {
		t.Fatalf("result = %v, want tunnelClosed", got)
	}
}

func TestEnqueueDownstreamAppliesBriefBackpressure(t *testing.T) {
	queue := make(chan []byte, 1)
	closed := make(chan struct{})
	queue <- []byte("first")

	go func() {
		time.Sleep(20 * time.Millisecond)
		<-queue
	}()

	if got := enqueueDownstream(queue, closed, []byte("second"), time.Second); got != downstreamQueued {
		t.Fatalf("enqueue result = %v, want downstreamQueued", got)
	}
	if got := string(<-queue); got != "second" {
		t.Fatalf("queued payload = %q, want second", got)
	}
}

func TestEnqueueDownstreamStopsAfterTimeout(t *testing.T) {
	queue := make(chan []byte, 1)
	closed := make(chan struct{})
	queue <- []byte("first")

	start := time.Now()
	if got := enqueueDownstream(queue, closed, []byte("second"), 10*time.Millisecond); got != downstreamTimedOut {
		t.Fatalf("enqueue result = %v, want downstreamTimedOut", got)
	}
	if elapsed := time.Since(start); elapsed < 8*time.Millisecond {
		t.Fatalf("enqueue returned before applying backpressure: %v", elapsed)
	}
}

func TestEnqueueDownstreamDistinguishesClosedTunnel(t *testing.T) {
	queue := make(chan []byte, 1)
	closed := make(chan struct{})
	close(closed)

	if got := enqueueDownstream(queue, closed, []byte("data"), time.Second); got != downstreamClosed {
		t.Fatalf("enqueue result = %v, want downstreamClosed", got)
	}
}
