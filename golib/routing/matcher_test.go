package routing

import "testing"

func TestMatcherManualRules(t *testing.T) {
	m, err := NewMatcher(false, false, false, `
192.0.2.10
198.51.100.0/24
domain:example.com
full:exact.example.net
suffix:example.org
`)
	if err != nil {
		t.Fatalf("NewMatcher() error = %v", err)
	}

	tests := []struct {
		name     string
		original string
		resolved string
		want     bool
	}{
		{name: "single IP", original: "192.0.2.10", want: true},
		{name: "CIDR via resolved host", original: "site.test", resolved: "198.51.100.42", want: true},
		{name: "domain root", original: "example.com", want: true},
		{name: "domain child", original: "www.example.com", want: true},
		{name: "full exact", original: "exact.example.net", want: true},
		{name: "full excludes child", original: "www.exact.example.net", want: false},
		{name: "suffix", original: "api.example.org", want: true},
		{name: "boundary", original: "notexample.com", want: false},
		{name: "unmatched", original: "example.edu", resolved: "203.0.113.1", want: false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := m.Match(tt.original, tt.resolved); got != tt.want {
				t.Fatalf("Match(%q, %q) = %v, want %v", tt.original, tt.resolved, got, tt.want)
			}
		})
	}
}

func TestMatcherPrivateRules(t *testing.T) {
	m, err := NewMatcher(true, false, false, "")
	if err != nil {
		t.Fatalf("NewMatcher() error = %v", err)
	}
	for _, host := range []string{"127.0.0.1", "10.1.2.3", "172.16.4.5", "192.168.1.1", "100.64.1.2", "fd00::1", "fe80::1", "printer.local"} {
		if !m.Match(host, host) {
			t.Errorf("private host %q did not match", host)
		}
	}
	for _, host := range []string{"8.8.8.8", "1.1.1.1", "example.com"} {
		if m.Match(host, host) {
			t.Errorf("public host %q unexpectedly matched", host)
		}
	}
}

func TestMatcherGeoIPCN(t *testing.T) {
	m, err := NewMatcher(false, true, false, "")
	if err != nil {
		t.Fatalf("NewMatcher() error = %v", err)
	}
	if !m.Match("example.test", "1.1.8.8") {
		t.Fatal("known GEOIP:CN address did not match")
	}
	if m.Match("example.test", "8.8.8.8") {
		t.Fatal("non-CN test address unexpectedly matched")
	}
}

func TestMatcherGeoSiteCN(t *testing.T) {
	m, err := NewMatcher(false, false, true, "")
	if err != nil {
		t.Fatalf("NewMatcher() error = %v", err)
	}
	for _, host := range []string{"qq.com", "www.qq.com", "ntp.aliyun.com", "foo-mihayo.akamaized.net"} {
		if !m.Match(host, "") {
			t.Errorf("known GEOSITE:CN domain %q did not match", host)
		}
	}
	if m.Match("example.invalid", "") {
		t.Fatal("unlisted domain unexpectedly matched")
	}
}

func TestValidateManualRulesReportsLine(t *testing.T) {
	err := ValidateManualRules("example.com\ninvalid rule !\n")
	if err == nil || err.Error() != `line 2: invalid rule "invalid rule !"` {
		t.Fatalf("ValidateManualRules() error = %v", err)
	}
}

func TestValidateManualRulesRejectsBroadMappedIPv4Prefix(t *testing.T) {
	err := ValidateManualRules("::ffff:0:0/80")
	if err == nil {
		t.Fatal("ValidateManualRules() error = nil")
	}
}
