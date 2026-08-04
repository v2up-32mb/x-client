package protocol

import (
	"net"
	"testing"
	"time"
)

func TestResolveWithStrategyCachesLookupResults(t *testing.T) {
	oldLookup := lookupIP
	oldTTL := dnsCacheTTL
	resetDNSCache()
	defer func() {
		lookupIP = oldLookup
		dnsCacheTTL = oldTTL
		resetDNSCache()
	}()

	calls := 0
	lookupIP = func(host string) ([]net.IP, error) {
		calls++
		return []net.IP{net.ParseIP("203.0.113.10")}, nil
	}
	dnsCacheTTL = time.Minute

	got1 := ResolveWithStrategy("example.com:443", IPStrategyIPv4Only)
	got2 := ResolveWithStrategy("example.com:443", IPStrategyIPv4Only)

	if got1 != "203.0.113.10:443" {
		t.Fatalf("first resolve = %q, want %q", got1, "203.0.113.10:443")
	}
	if got2 != "203.0.113.10:443" {
		t.Fatalf("second resolve = %q, want %q", got2, "203.0.113.10:443")
	}
	if calls != 1 {
		t.Fatalf("lookup calls = %d, want 1 due to cache", calls)
	}
}
