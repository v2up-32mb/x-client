package ipopt

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
	"unicode/utf8"
)

func validKind(s string) bool {
	return s == "official" || s == "third_party" || s == "manual" || s == "legacy"
}

// ParseList only parses text; documentation addresses are retained here, but
// rejected at the network boundary. Legacy bare IPs alone receive port 443.
func ParseList(content, sourceID, sourceKind string) (ParseResponse, error) {
	return parseList(content, sourceID, sourceKind, false)
}

func parseList(content, sourceID, sourceKind string, forNetwork bool) (ParseResponse, error) {
	out := ParseResponse{Candidates: []Candidate{}, Errors: []LineError{}}
	if len(content) > maxSourceBytes {
		return out, fmt.Errorf("列表超过2MiB限制")
	}
	if !utf8.ValidString(content) {
		return out, fmt.Errorf("列表必须为UTF-8文本")
	}
	if sourceID == "" || len(sourceID) > 256 || !validKind(sourceKind) {
		return out, fmt.Errorf("无效的来源ID或类型")
	}
	seen := make(map[string]int)
	for i, raw := range strings.Split(strings.TrimPrefix(content, "\ufeff"), "\n") {
		if i >= maxListLines {
			out.Errors = append(out.Errors, LineError{i + 1, "超过10000行限制；后续内容未导入", raw})
			break
		}
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		address, comment, _ := strings.Cut(line, "#")
		address = strings.TrimSpace(address)
		if sourceKind == "legacy" {
			if ip, err := netip.ParseAddr(address); err == nil && ip.Zone() == "" {
				address = netip.AddrPortFrom(ip.Unmap(), 443).String()
			}
		}
		ap, err := parseAddress(address)
		if err != nil {
			out.Errors = append(out.Errors, LineError{i + 1, err.Error(), raw})
			continue
		}
		if forNetwork && !publicIP(ap.Addr()) {
			out.Errors = append(out.Errors, LineError{i + 1, "非公网地址已过滤，不能探测本地服务", raw})
			continue
		}
		c := Candidate{Address: ap.String(), IP: ap.Addr().String(), Port: int(ap.Port()), Comment: strings.TrimSpace(comment),
			SourceID: sourceID, SourceKind: sourceKind, SourceIDs: []string{sourceID}, SourceKinds: []string{sourceKind}}
		mergeCandidate(&out.Candidates, seen, c)
	}
	return out, nil
}

func ParseJSON(content, sourceID, sourceKind string) (string, error) {
	r, err := ParseList(content, sourceID, sourceKind)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(r)
	return string(b), err
}

func parseAddress(s string) (netip.AddrPort, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil || !ap.IsValid() || ap.Port() == 0 || ap.Addr().Zone() != "" {
		return netip.AddrPort{}, fmt.Errorf("需要字面IP:端口（1..65535），IPv6必须使用[IP]:端口")
	}
	return netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port()), nil
}

func appendUnique(dst []string, values ...string) []string {
	for _, v := range values {
		found := false
		for _, old := range dst {
			if old == v {
				found = true
				break
			}
		}
		if v != "" && !found {
			dst = append(dst, v)
		}
	}
	return dst
}

func mergeCandidate(out *[]Candidate, seen map[string]int, c Candidate) {
	if idx, ok := seen[c.Address]; ok {
		old := &(*out)[idx]
		old.SourceIDs = appendUnique(old.SourceIDs, c.SourceIDs...)
		old.SourceKinds = appendUnique(old.SourceKinds, c.SourceKinds...)
		if c.Comment != "" && c.Comment != old.Comment {
			if old.Comment == "" {
				old.Comment = c.Comment
			} else {
				old.Comment += " / " + c.Comment
			}
		}
		return
	}
	seen[c.Address] = len(*out)
	*out = append(*out, c)
}

func normalizeCandidate(c Candidate) (Candidate, error) {
	if c.Address == "" {
		return c, fmt.Errorf("候选缺少address")
	}
	ap, err := parseAddress(c.Address)
	if err != nil {
		return c, err
	}
	if c.IP != "" {
		ip, e := netip.ParseAddr(c.IP)
		if e != nil || ip.Zone() != "" || ip.Unmap() != ap.Addr() {
			return c, fmt.Errorf("候选ip与address不一致")
		}
	}
	if c.Port != 0 && c.Port != int(ap.Port()) {
		return c, fmt.Errorf("候选port与address不一致")
	}
	if !validKind(c.SourceKind) || c.SourceID == "" || len(c.SourceID) > 256 || len(c.Comment) > 4096 {
		return c, fmt.Errorf("无效的候选来源或备注过长")
	}
	if len(c.SourceIDs) > 64 || len(c.SourceKinds) > 4 {
		return c, fmt.Errorf("候选来源数量过多")
	}
	for _, id := range c.SourceIDs {
		if id == "" || len(id) > 256 {
			return c, fmt.Errorf("无效的合并来源ID")
		}
	}
	for _, kind := range c.SourceKinds {
		if !validKind(kind) {
			return c, fmt.Errorf("无效的合并来源类型")
		}
	}
	c.Address, c.IP, c.Port = ap.String(), ap.Addr().String(), int(ap.Port())
	c.SourceIDs = appendUnique([]string{c.SourceID}, c.SourceIDs...)
	c.SourceKinds = appendUnique([]string{c.SourceKind}, c.SourceKinds...)
	return c, nil
}

func parsePrefixes(content string, source Source) ([]Prefix, []LineError) {
	out, errs := []Prefix{}, []LineError{}
	seen := map[string]bool{}
	for i, raw := range strings.Split(strings.TrimPrefix(content, "\ufeff"), "\n") {
		if i >= maxListLines {
			errs = append(errs, LineError{i + 1, "超过10000行限制", raw})
			break
		}
		s := strings.TrimSpace(raw)
		if s == "" {
			continue
		}
		p, err := netip.ParsePrefix(s)
		if err != nil || p.Addr().Is4In6() || (source.Kind == "cidr4" && !p.Addr().Is4()) || (source.Kind == "cidr6" && !p.Addr().Is6()) {
			errs = append(errs, LineError{i + 1, "无效或地址族不符的CIDR", raw})
			continue
		}
		p = p.Masked()
		if !seen[p.String()] {
			out = append(out, Prefix{p.String(), 443, source.ID, "official"})
			seen[p.String()] = true
		}
	}
	return out, errs
}

func portString(p int) string { return strconv.Itoa(p) }
