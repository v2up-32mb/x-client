package ipopt

import (
	"crypto/rand"
	"fmt"
	"io"
	"math/big"
	"net/netip"
	"sort"
)

// samplePrefixes allocates a round-robin quota then draws one host from each
// equal-sized stratum. Work and storage depend on limit, never prefix size.
func samplePrefixes(prefixes []Prefix, limit int, entropy io.Reader) ([]Candidate, error) {
	out := []Candidate{}
	if limit < 1 || limit > maxCandidates {
		return nil, fmt.Errorf("sample_limit必须为1..4096")
	}
	if len(prefixes) > maxCandidates {
		return nil, fmt.Errorf("地址段超过4096个")
	}
	if len(prefixes) == 0 {
		return out, nil
	}
	ps := make([]netip.Prefix, len(prefixes))
	caps := make([]*big.Int, len(prefixes))
	quota := make([]int, len(prefixes))
	for i, p := range prefixes {
		n, err := netip.ParsePrefix(p.CIDR)
		if err != nil || n.Addr().Is4In6() || p.Port < 1 || p.Port > 65535 || !validKind(p.SourceKind) || p.SourceID == "" {
			return nil, fmt.Errorf("无效的CIDR/端口/来源: %s", p.CIDR)
		}
		ps[i] = n.Masked()
		caps[i] = new(big.Int).Lsh(big.NewInt(1), uint(n.Addr().BitLen()-n.Bits()))
	}
	for remaining := limit; remaining > 0; {
		added := false
		for i := range ps {
			if remaining == 0 {
				break
			}
			if caps[i].Cmp(big.NewInt(int64(quota[i]))) > 0 {
				quota[i]++
				remaining--
				added = true
			}
		}
		if !added {
			break
		}
	}
	seen := map[string]int{}
	for i, p := range ps {
		n := quota[i]
		if n == 0 {
			continue
		}
		base := new(big.Int).SetBytes(p.Addr().AsSlice())
		for j := 0; j < n; j++ {
			lo := new(big.Int).Div(new(big.Int).Mul(caps[i], big.NewInt(int64(j))), big.NewInt(int64(n)))
			hi := new(big.Int).Div(new(big.Int).Mul(caps[i], big.NewInt(int64(j+1))), big.NewInt(int64(n)))
			r, err := rand.Int(entropy, new(big.Int).Sub(hi, lo))
			if err != nil {
				return nil, fmt.Errorf("CIDR抽样随机数: %w", err)
			}
			v := new(big.Int).Add(base, new(big.Int).Add(lo, r))
			buf := make([]byte, p.Addr().BitLen()/8)
			v.FillBytes(buf)
			ip, _ := netip.AddrFromSlice(buf)
			c := Candidate{Address: netip.AddrPortFrom(ip, uint16(prefixes[i].Port)).String(), IP: ip.String(), Port: prefixes[i].Port,
				SourceID: prefixes[i].SourceID, SourceKind: prefixes[i].SourceKind, SourceIDs: []string{prefixes[i].SourceID}, SourceKinds: []string{prefixes[i].SourceKind}}
			mergeCandidate(&out, seen, c)
		}
	}
	return out, nil
}

// sourcePriority does not change the legacy parser's first-source semantics.
// Mixed provenance uses its highest priority; official family is only a sort
// category, never a new wire source_kind.
func sourcePriority(c Candidate) int {
	priority := 4
	for _, kind := range append([]string{c.SourceKind}, c.SourceKinds...) {
		v := 4
		switch kind {
		case "legacy":
			v = 0
		case "manual":
			v = 1
		case "third_party":
			v = 2
		case "official":
			if netip.MustParseAddr(c.IP).Is4() {
				v = 3
			}
		}
		if v < priority {
			priority = v
		}
	}
	return priority
}

func candidateLess(a, b Candidate) bool {
	if sourcePriority(a) != sourcePriority(b) {
		return sourcePriority(a) < sourcePriority(b)
	}
	x, y := netip.MustParseAddr(a.IP), netip.MustParseAddr(b.IP)
	if x != y {
		return x.Compare(y) < 0
	}
	if a.Port != b.Port {
		return a.Port < b.Port
	}
	return a.SourceID < b.SourceID
}

func mergePriorityCandidate(out *[]Candidate, seen map[string]int, c Candidate) {
	if i, ok := seen[c.Address]; ok {
		old := (*out)[i]
		promote := sourcePriority(c) < sourcePriority(old) || (sourcePriority(c) == sourcePriority(old) && c.SourceID < old.SourceID)
		mergeCandidate(out, seen, c)
		if promote {
			(*out)[i].SourceID, (*out)[i].SourceKind = c.SourceID, c.SourceKind
		}
		return
	}
	mergeCandidate(out, seen, c)
}

func materialize(r Request) ([]Candidate, error) {
	cs, seen := []Candidate{}, map[string]int{}
	for _, c := range r.Candidates {
		mergePriorityCandidate(&cs, seen, c)
	}
	sampled, err := samplePrefixes(r.Prefixes, r.Options.SampleLimit, rand.Reader)
	if err != nil {
		return nil, err
	}
	for _, c := range sampled {
		mergePriorityCandidate(&cs, seen, c)
	}
	if len(cs) == 0 || len(cs) > maxCandidates {
		return nil, fmt.Errorf("整轮候选必须为1..4096个，实际%d", len(cs))
	}
	for _, c := range cs {
		if !publicIP(netip.MustParseAddr(c.IP)) {
			return nil, fmt.Errorf("抽样得到非公网地址: %s", c.IP)
		}
		if len(c.SourceIDs) > 64 || len(c.Comment) > 4096 {
			return nil, fmt.Errorf("合并来源或备注超限: %s", c.Address)
		}
	}
	sort.SliceStable(cs, func(i, j int) bool { return candidateLess(cs[i], cs[j]) })
	return cs, nil
}
