package ipopt

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
)

func TestParseBOMCommentsIPv6AndDedup(t *testing.T) {
	text := "\ufeff203.0.113.10:443#主用\r\n\r\n[2001:0db8:0:0::10]:2008#v6\n203.0.113.10:443#备选#保留\n[::ffff:203.0.113.10]:443\n203.0.113.10:8443#非标"
	r, err := ParseList(text, "manual", "manual")
	if err != nil || len(r.Errors) != 0 || len(r.Candidates) != 3 {
		t.Fatalf("%+v, %v", r, err)
	}
	if r.Candidates[0].Address != "203.0.113.10:443" || r.Candidates[0].Comment != "主用 / 备选#保留" {
		t.Fatalf("bad merge: %+v", r.Candidates[0])
	}
	if r.Candidates[1].Address != "[2001:db8::10]:2008" {
		t.Fatal(r.Candidates[1])
	}
	for _, c := range r.Candidates {
		if strings.Contains(c.Address, "#") || len(c.SourceIDs) != 1 || len(c.SourceKinds) != 1 {
			t.Fatal(c)
		}
	}
}

func TestParseErrorsAreLineNumberedAndNotSilentlyDropped(t *testing.T) {
	lines := []string{"example.com:443", "1.1.1.1", "1.1.1.1:0", "1.1.1.1:65536", "2001:db8::1:443", "[fe80::1%wlan0]:443", "104.16.0.0/13", "#only comment"}
	r, err := ParseList(strings.Join(lines, "\r\n"), "paste", "manual")
	if err != nil || len(r.Candidates) != 0 || len(r.Errors) != len(lines) {
		t.Fatalf("%+v %v", r, err)
	}
	for i, e := range r.Errors {
		if e.Line != i+1 || strings.TrimSuffix(e.Text, "\r") != lines[i] || e.Message == "" {
			t.Fatal(e)
		}
	}
}

func TestLegacyBareIPsOnly(t *testing.T) {
	r, err := ParseList("1.1.1.1\n2606:4700::1111\nexample.com\n1.1.1.1:2008", "old", "legacy")
	if err != nil || len(r.Candidates) != 3 || len(r.Errors) != 1 || r.Errors[0].Line != 3 {
		t.Fatalf("%+v %v", r, err)
	}
	if r.Candidates[1].Address != "[2606:4700::1111]:443" {
		t.Fatal(r.Candidates)
	}
}

func TestParserBoundsAndEmptyArrays(t *testing.T) {
	if _, err := ParseList(strings.Repeat("x", maxSourceBytes+1), "x", "manual"); err == nil {
		t.Fatal("oversized accepted")
	}
	if _, err := ParseList(string([]byte{0xff}), "x", "manual"); err == nil {
		t.Fatal("invalid UTF8")
	}
	if _, err := ParseList("", "x", "spoof"); err == nil {
		t.Fatal("invalid source kind")
	}
	r, err := ParseList(strings.Repeat("\n", maxListLines)+"1.1.1.1:443", "x", "manual")
	if err != nil || len(r.Errors) != 1 || r.Errors[0].Line != 10001 {
		t.Fatalf("%+v %v", r, err)
	}
	text, err := ParseJSON("", "x", "manual")
	if err != nil || text != "{\"candidates\":[],\"errors\":[]}" {
		t.Fatalf("%s %v", text, err)
	}
}

func TestMergeSourcesAndNormalizeCandidate(t *testing.T) {
	a, _ := ParseList("1.1.1.1:443#one", "a", "official")
	b, _ := ParseList("1.1.1.1:443#two", "b", "manual")
	out := []Candidate{}
	seen := map[string]int{}
	mergeCandidate(&out, seen, a.Candidates[0])
	mergeCandidate(&out, seen, b.Candidates[0])
	if len(out) != 1 || len(out[0].SourceIDs) != 2 || out[0].SourceID != "a" || out[0].Comment != "one / two" {
		t.Fatal(out)
	}
	c := out[0]
	c.Port = 2008
	if _, err := normalizeCandidate(c); err == nil {
		t.Fatal("port mismatch accepted")
	}
	c = out[0]
	c.IP = "1.0.0.1"
	if _, err := normalizeCandidate(c); err == nil {
		t.Fatal("IP mismatch accepted")
	}
	c = out[0]
	c.Address += "#bad"
	if _, err := normalizeCandidate(c); err == nil {
		t.Fatal("comment leaked into address")
	}
}

func TestRemoteParsingFiltersUnsafeButTextParserRetainsExamples(t *testing.T) {
	text := "127.0.0.1:443\n203.0.113.1:443\n1.1.1.1:443"
	r, err := parseList(text, "list", "third_party", true)
	if err != nil || len(r.Candidates) != 1 || len(r.Errors) != 2 {
		t.Fatalf("%+v %v", r, err)
	}
	plain, _ := ParseList(text, "x", "manual")
	if len(plain.Candidates) != 3 {
		t.Fatal(plain)
	}
	data, _ := json.Marshal(plain)
	if strings.Contains(string(data), "null") {
		t.Fatal(string(data))
	}
}

type zeroReader struct{}

func (zeroReader) Read(b []byte) (int, error) { clear(b); return len(b), nil }

func TestSampleIsStratifiedBoundedAndWithinRealPrefixes(t *testing.T) {
	ps := []Prefix{{"104.16.0.0/13", 443, "v4", "official"}, {"2606:4700::/32", 2008, "v6", "official"}}
	cs, err := samplePrefixes(ps, 256, zeroReader{})
	if err != nil || len(cs) != 256 {
		t.Fatalf("count=%d err=%v", len(cs), err)
	}
	counts := map[string]int{}
	seen := map[string]bool{}
	for _, c := range cs {
		if seen[c.Address] {
			t.Fatal("duplicate", c)
		}
		seen[c.Address] = true
		idx := 0
		if c.SourceID == "v6" {
			idx = 1
		}
		if !netip.MustParsePrefix(ps[idx].CIDR).Contains(netip.MustParseAddr(c.IP)) || c.Port != ps[idx].Port {
			t.Fatal(c)
		}
		counts[c.SourceID]++
	}
	if counts["v4"] != 128 || counts["v6"] != 128 {
		t.Fatal(counts)
	}
}

func TestSampleSmallRangesAndHugeIPv6NeverEnumerated(t *testing.T) {
	ps := []Prefix{{"1.1.1.0/31", 443, "a", "manual"}, {"2606:4700::1/128", 8443, "b", "manual"}}
	cs, err := samplePrefixes(ps, 4096, zeroReader{})
	if err != nil || len(cs) != 3 {
		t.Fatalf("%v %v", cs, err)
	}
	ps = []Prefix{{"2000::/3", 443, "huge", "official"}}
	cs, err = samplePrefixes(ps, 17, zeroReader{})
	if err != nil || len(cs) != 17 {
		t.Fatalf("%d %v", len(cs), err)
	}
	if _, err = samplePrefixes(ps, 4097, zeroReader{}); err == nil {
		t.Fatal("limit accepted")
	}
	ps[0].CIDR = "broken"
	if _, err = samplePrefixes(ps, 10, zeroReader{}); err == nil {
		t.Fatal("invalid CIDR accepted")
	}
}
