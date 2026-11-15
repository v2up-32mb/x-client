package ipopt

import (
	"crypto/rand"
	"fmt"
	"io"
	"math/big"
	"net/netip"
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
