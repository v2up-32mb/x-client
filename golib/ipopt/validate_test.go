package ipopt

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
)

func testCandidate(n int) Candidate {
	ip := fmt.Sprintf("104.16.%d.%d", n/256, n%256)
	return Candidate{Address: ip + ":443", IP: ip, Port: 443, SourceID: "manual", SourceKind: "manual", SourceIDs: []string{"manual"}, SourceKinds: []string{"manual"}}
}

func testRequest(count int) Request {
	r := Request{Operation: "test", NetworkID: "wifi-generation-1", Options: defaultOptions()}
	r.Options.TargetHost = "node.example.com"
	for i := 0; i < count; i++ {
		r.Candidates = append(r.Candidates, testCandidate(i+1))
	}
	return r
}

func requestJSON(t *testing.T, r Request) string {
	t.Helper()
	b, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestDefaultsAndExplicitDisable(t *testing.T) {
	c, _ := json.Marshal(testCandidate(1))
	text := `{"operation":"test","network_id":"net","candidates":[` + string(c) + `],"options":{"target_host":"node.example.com","download_top":0,"recommend_count":0}}`
	r, err := decodeRequest(text)
	if err != nil {
		t.Fatal(err)
	}
	if r.Options.SampleLimit != 256 || r.Options.ProbeConcurrency != 8 || r.Options.DownloadTop != 0 || r.Options.RecommendCount != 0 || r.Options.DownloadBytesPerIP != 20<<20 {
		t.Fatal(r.Options)
	}
	text = strings.ReplaceAll(text, `,"download_top":0,"recommend_count":0`, "")
	r, err = decodeRequest(text)
	if err != nil || r.Options.DownloadTop != 5 || r.Options.RecommendCount != 3 {
		t.Fatalf("%+v %v", r.Options, err)
	}
}

func TestEmptyECHSettingsUseBackendDefaults(t *testing.T) {
	for _, protocol := range []string{"gcm", "xtunnel"} {
		t.Run(protocol, func(t *testing.T) {
			r := testRequest(1)
			r.Options.Protocol = protocol
			r.Options.EnableECH = true
			r.Options.ECHDomain = "  "
			r.Options.DNSServer = "  "
			got, err := decodeRequest(requestJSON(t, r))
			if err != nil {
				t.Fatal(err)
			}
			wantDNS := "" // GCM uses the shared built-in list.
			if protocol == "xtunnel" {
				wantDNS = "https://v.recipes/dns-query"
			}
			if got.Options.ECHDomain != "cloudflare-ech.com" || got.Options.DNSServer != wantDNS {
				t.Fatalf("backend default mismatch: %+v", got.Options)
			}
		})
	}
}

func TestUnsafeOptionsAndCandidateBoundsRejected(t *testing.T) {
	mutations := []func(*Request){
		func(r *Request) { r.Options.TargetHost = "https://node.example.com/path" },
		func(r *Request) { r.Options.TargetHost = "node.example.com:443" },
		func(r *Request) { r.Options.TargetPort = 65536 },
		func(r *Request) { r.Options.SampleLimit = 0 },
		func(r *Request) { r.Options.SampleLimit = 4097 },
		func(r *Request) { r.Options.ProbeConcurrency = 33 },
		func(r *Request) { r.Options.DownloadTop = 6 },
		func(r *Request) { r.Options.DownloadTop = -1 },
		func(r *Request) { r.Options.RecommendCount = -1 },
		func(r *Request) { r.Options.DownloadSeconds = 6 },
		func(r *Request) { r.Options.DownloadBytesPerIP = 21 << 20 },
		func(r *Request) { r.Options.DownloadURL = "https://127.0.0.1/private" },
		func(r *Request) { r.Options.EnableECH = true; r.Options.DNSServer = "http://dns.example.com/query" },
		func(r *Request) { r.Options.Protocol = "unknown" },
		func(r *Request) { r.NetworkID = "" },
		func(r *Request) { r.Candidates[0].Address = "127.0.0.1:443"; r.Candidates[0].IP = "127.0.0.1" },
		func(r *Request) { r.Candidates[0].Address = "[fec0::1]:443"; r.Candidates[0].IP = "fec0::1" },
		func(r *Request) { r.Options.DownloadURL = "http://[fec0::1]/" },
		func(r *Request) { r.Prefixes = []Prefix{{"fec0::/10", 443, "manual", "manual"}} },
		func(r *Request) { r.Candidates = make([]Candidate, 4097) },
		func(r *Request) { r.Candidates = nil },
		func(r *Request) { r.Prefixes = []Prefix{{"10.0.0.0/8", 443, "cf", "official"}} },
	}
	for i, mutate := range mutations {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			r := testRequest(1)
			mutate(&r)
			if _, err := decodeRequest(requestJSON(t, r)); err == nil {
				t.Fatal("unsafe request accepted")
			}
		})
	}
	for _, text := range []string{"null", "[]", "{} {}", `{"operation":"test","insecure":true}`} {
		if _, err := decodeRequest(text); err == nil {
			t.Fatal(text)
		}
	}
}

func TestPublicAddressBoundary(t *testing.T) {
	for _, s := range []string{"127.0.0.1", "10.1.1.1", "172.16.0.1", "192.168.1.1", "169.254.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "100.64.0.1", "198.18.0.1", "203.0.113.1", "::", "::1", "fc00::1", "fe80::1", "fec0::1", "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "ff02::1", "2001:db8::1", "64:ff9b::7f00:1", "::ffff:127.0.0.1"} {
		if publicIP(netip.MustParseAddr(s)) {
			t.Fatal("unsafe", s)
		}
	}
	for _, s := range []string{"1.1.1.1", "104.16.0.1", "2606:4700::1111"} {
		if !publicIP(netip.MustParseAddr(s)) {
			t.Fatal("public rejected", s)
		}
	}
}

func TestOfficialProvenanceAndSafeURLs(t *testing.T) {
	for _, s := range []Source{{"cf", "https://www.cloudflare.com/ips-v4", "cidr4"}, {"cf6", "https://www.cloudflare.com/ips-v6", "cidr6"}, {"third", "https://776771.xyz/all.txt", "list"}} {
		if err := validateSource(s); err != nil {
			t.Fatal(err)
		}
	}
	for _, s := range []Source{{"cf", "https://evil.example.com/ips-v4", "cidr4"}, {"cf", "http://www.cloudflare.com/ips-v4", "cidr4"}, {"cf", "https://www.cloudflare.com/ips-v6", "cidr4"}} {
		if err := validateSource(s); err == nil {
			t.Fatal(s)
		}
	}
	for _, s := range []string{"file:///tmp/list", "https://user:pass@example.com/", "https://example.com/#secret", "http://[::1]/", "http://[fec0::1]/", "http://example.com:0/"} {
		if _, err := safeURL(s); err == nil {
			t.Fatal(s)
		}
	}
	viaURL, _ := url.Parse("https://example.com/list")
	down, _ := url.Parse("http://example.com/list")
	if err := safeRedirect(&http.Request{URL: down}, []*http.Request{{URL: viaURL}}); err == nil {
		t.Fatal("downgrade accepted")
	}
	if err := safeRedirect(&http.Request{URL: viaURL}, []*http.Request{{URL: viaURL}, {URL: viaURL}, {URL: viaURL}, {URL: viaURL}}); err == nil {
		t.Fatal("fourth redirect accepted")
	}
}
