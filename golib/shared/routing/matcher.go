package routing

import (
	"bufio"
	_ "embed"
	"fmt"
	"net/netip"
	"regexp"
	"strings"
)

//go:embed data/geoip_cn.txt
var geoIPCNData string

//go:embed data/geosite_cn.txt
var geoSiteCNData string

var privatePrefixes = []string{
	"10.0.0.0/8",
	"100.64.0.0/10",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"::1/128",
	"fc00::/7",
	"fe80::/10",
}

// Matcher decides whether a SOCKS destination should bypass the GCM tunnel.
type Matcher struct {
	ipv4           [33]map[netip.Addr]struct{}
	ipv6           [129]map[netip.Addr]struct{}
	domainSuffixes map[string]struct{}
	exactDomains   map[string]struct{}
	keywords       []string
	regexps        []*regexp.Regexp
}

// NewMatcher builds a matcher from predefined groups and newline-separated
// manual rules. Manual rules support IP, CIDR, domain:, suffix:, and full:.
func NewMatcher(bypassPrivate, bypassGeoIPCN, bypassGeoSiteCN bool, manualRules string) (*Matcher, error) {
	m := &Matcher{
		domainSuffixes: make(map[string]struct{}),
		exactDomains:   make(map[string]struct{}),
	}

	if err := m.parseManualRules(manualRules, &bypassPrivate, &bypassGeoIPCN, &bypassGeoSiteCN); err != nil {
		return nil, err
	}
	if bypassPrivate {
		for _, raw := range privatePrefixes {
			if err := m.addPrefix(raw); err != nil {
				return nil, fmt.Errorf("parse built-in private rule %q: %w", raw, err)
			}
		}
		m.domainSuffixes["localhost"] = struct{}{}
		m.domainSuffixes["local"] = struct{}{}
	}
	if bypassGeoIPCN {
		if err := m.parseIPData(geoIPCNData); err != nil {
			return nil, fmt.Errorf("parse built-in GEOIP:CN rules: %w", err)
		}
	}
	if bypassGeoSiteCN {
		if err := m.parseDomainData(geoSiteCNData); err != nil {
			return nil, fmt.Errorf("parse built-in GEOSITE:CN rules: %w", err)
		}
	}
	return m, nil
}

// ValidateManualRules validates the syntax accepted by NewMatcher.
func ValidateManualRules(rules string) error {
	_, err := NewMatcher(false, false, false, rules)
	return err
}

// Match reports whether either the original SOCKS host or its resolved IP
// matches a bypass rule.
func (m *Matcher) Match(originalHost, resolvedHost string) bool {
	if m == nil {
		return false
	}
	if addr, err := parseAddr(originalHost); err == nil && m.matchAddr(addr) {
		return true
	}
	if domain, ok := normalizeDomain(originalHost); ok && m.matchDomain(domain) {
		return true
	}
	if addr, err := parseAddr(resolvedHost); err == nil && m.matchAddr(addr) {
		return true
	}
	return false
}

func (m *Matcher) parseManualRules(rules string, private, geoIPCN, geoSiteCN *bool) error {
	scanner := bufio.NewScanner(strings.NewReader(rules))
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		line := strings.TrimSpace(scanner.Text())
		if comment := strings.IndexByte(line, '#'); comment >= 0 {
			line = strings.TrimSpace(line[:comment])
		}
		if line == "" {
			continue
		}

		switch strings.ToLower(line) {
		case "private", "lan", "local":
			*private = true
			continue
		case "geoip:cn":
			*geoIPCN = true
			continue
		case "geosite:cn":
			*geoSiteCN = true
			continue
		}

		if err := m.addManualRule(line); err != nil {
			return fmt.Errorf("line %d: %w", lineNumber, err)
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read manual rules: %w", err)
	}
	return nil
}

func (m *Matcher) addManualRule(rule string) error {
	if prefix, err := netip.ParsePrefix(rule); err == nil {
		return m.addParsedPrefix(prefix)
	}
	if addr, err := parseAddr(rule); err == nil {
		bits := 128
		if addr.Is4() {
			bits = 32
		}
		return m.addParsedPrefix(netip.PrefixFrom(addr, bits))
	}

	kind := "domain"
	value := rule
	if left, right, found := strings.Cut(rule, ":"); found {
		kind = strings.ToLower(strings.TrimSpace(left))
		value = strings.TrimSpace(right)
	}
	domain, ok := normalizeDomain(value)
	if !ok {
		return fmt.Errorf("invalid rule %q", rule)
	}
	switch kind {
	case "domain", "suffix":
		m.domainSuffixes[domain] = struct{}{}
	case "full":
		m.exactDomains[domain] = struct{}{}
	default:
		return fmt.Errorf("unsupported rule type %q", kind)
	}
	return nil
}

func (m *Matcher) parseIPData(data string) error {
	scanner := bufio.NewScanner(strings.NewReader(data))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if err := m.addPrefix(line); err != nil {
			return err
		}
	}
	return scanner.Err()
}

func (m *Matcher) parseDomainData(data string) error {
	scanner := bufio.NewScanner(strings.NewReader(data))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		kind, value, found := strings.Cut(line, ":")
		if !found {
			kind, value = "domain", line
		}
		if attrs := strings.Index(value, ":@"); attrs >= 0 {
			value = value[:attrs]
		}
		switch kind {
		case "domain":
			domain, ok := normalizeDomain(value)
			if !ok {
				return fmt.Errorf("invalid domain rule %q", line)
			}
			m.domainSuffixes[domain] = struct{}{}
		case "full":
			domain, ok := normalizeDomain(value)
			if !ok {
				return fmt.Errorf("invalid full-domain rule %q", line)
			}
			m.exactDomains[domain] = struct{}{}
		case "keyword":
			if value == "" {
				return fmt.Errorf("invalid keyword rule %q", line)
			}
			m.keywords = append(m.keywords, strings.ToLower(value))
		case "regexp":
			re, err := regexp.Compile(value)
			if err != nil {
				return fmt.Errorf("invalid regexp rule %q: %w", line, err)
			}
			m.regexps = append(m.regexps, re)
		default:
			return fmt.Errorf("unsupported domain rule type %q", kind)
		}
	}
	return scanner.Err()
}

func (m *Matcher) addPrefix(raw string) error {
	prefix, err := netip.ParsePrefix(strings.TrimSpace(raw))
	if err != nil {
		return err
	}
	return m.addParsedPrefix(prefix)
}

func (m *Matcher) addParsedPrefix(prefix netip.Prefix) error {
	if prefix.Addr().Is4In6() && prefix.Bits() < 96 {
		return fmt.Errorf("IPv4-mapped prefix length %d must be at least 96", prefix.Bits())
	}
	prefix = prefix.Masked()
	addr := prefix.Addr()
	bits := prefix.Bits()
	if addr.Is4In6() {
		addr = addr.Unmap()
		bits -= 96
	}
	if addr.Is4() {
		if m.ipv4[bits] == nil {
			m.ipv4[bits] = make(map[netip.Addr]struct{})
		}
		m.ipv4[bits][netip.PrefixFrom(addr, bits).Masked().Addr()] = struct{}{}
		return nil
	}
	if m.ipv6[bits] == nil {
		m.ipv6[bits] = make(map[netip.Addr]struct{})
	}
	m.ipv6[bits][prefix.Addr()] = struct{}{}
	return nil
}

func (m *Matcher) matchAddr(addr netip.Addr) bool {
	addr = addr.WithZone("").Unmap()
	if addr.Is4() {
		for bits := 32; bits >= 0; bits-- {
			if table := m.ipv4[bits]; table != nil {
				if _, ok := table[netip.PrefixFrom(addr, bits).Masked().Addr()]; ok {
					return true
				}
			}
		}
		return false
	}
	for bits := 128; bits >= 0; bits-- {
		if table := m.ipv6[bits]; table != nil {
			if _, ok := table[netip.PrefixFrom(addr, bits).Masked().Addr()]; ok {
				return true
			}
		}
	}
	return false
}

func (m *Matcher) matchDomain(domain string) bool {
	if _, ok := m.exactDomains[domain]; ok {
		return true
	}
	for suffix := domain; suffix != ""; {
		if _, ok := m.domainSuffixes[suffix]; ok {
			return true
		}
		dot := strings.IndexByte(suffix, '.')
		if dot < 0 {
			break
		}
		suffix = suffix[dot+1:]
	}
	for _, keyword := range m.keywords {
		if strings.Contains(domain, keyword) {
			return true
		}
	}
	for _, re := range m.regexps {
		if re.MatchString(domain) {
			return true
		}
	}
	return false
}

func parseAddr(value string) (netip.Addr, error) {
	value = strings.TrimSpace(value)
	value = strings.TrimPrefix(value, "[")
	value = strings.TrimSuffix(value, "]")
	addr, err := netip.ParseAddr(value)
	if err != nil {
		return netip.Addr{}, err
	}
	return addr.WithZone("").Unmap(), nil
}

func normalizeDomain(value string) (string, bool) {
	domain := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(value)), ".")
	if domain == "" || len(domain) > 253 {
		return "", false
	}
	for _, label := range strings.Split(domain, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return "", false
		}
		for i := 0; i < len(label); i++ {
			c := label[i]
			if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' {
				continue
			}
			return "", false
		}
	}
	return domain, true
}
