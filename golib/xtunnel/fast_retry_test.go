package xtunnel

import (
	"testing"
	"time"
)

func TestFastRetryStateTransitions(t *testing.T) {
	state := &fastRetryState{}

	state.OnFailure()
	state.OnFailure()
	if !state.ShouldFastRetry(3) {
		t.Fatal("should fast retry after 2 failures")
	}

	state.OnFailure()
	if state.ShouldFastRetry(3) {
		t.Fatal("should not fast retry after threshold")
	}

	state.OnSuccess()
	if !state.ShouldFastRetry(3) {
		t.Fatal("should reset and allow fast retry")
	}
}

func TestFastRetryStateWithinWindow(t *testing.T) {
	state := &fastRetryState{}

	state.OnFailure()
	if !state.ShouldFastRetryWithinWindow(3, time.Second) {
		t.Fatal("should fast retry within window")
	}

	state.lastFailure = time.Now().Add(-2 * time.Second)
	if state.ShouldFastRetryWithinWindow(3, time.Second) {
		t.Fatal("should not fast retry outside window")
	}
}
