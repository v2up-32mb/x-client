package ipopt

import (
	"math"
	"sort"
)

func fullStable(r Result) bool {
	return r.Status == "valid" && r.TCPAttempts == tcpProbeCount && r.TCPSuccess == tcpProbeCount && r.HTTPAttempts == httpProbeCount && r.HTTPSuccess == httpProbeCount
}

func official(r Result) bool {
	if r.SourceKind == "official" {
		return true
	}
	for _, k := range r.SourceKinds {
		if k == "official" {
			return true
		}
	}
	return false
}

func sameStability(a, b Result) bool {
	return a.Status == b.Status && a.TCPSuccess == b.TCPSuccess && a.TCPAttempts == b.TCPAttempts && a.HTTPSuccess == b.HTTPSuccess && a.HTTPAttempts == b.HTTPAttempts
}

// rank uses anchored similarity groups, not a pairwise fuzzy comparator (which
// would violate sort's transitivity). Only successful comparable body samples
// can outrank by download speed; peak speed never participates.
func rank(results []Result) {
	sort.SliceStable(results, func(i, j int) bool {
		a, b := results[i], results[j]
		if (a.Status == "valid") != (b.Status == "valid") {
			return a.Status == "valid"
		}
		if a.HTTPSuccess != b.HTTPSuccess {
			return a.HTTPSuccess > b.HTTPSuccess
		}
		if a.TCPSuccess != b.TCPSuccess {
			return a.TCPSuccess > b.TCPSuccess
		}
		if a.HTTPMedianMS != b.HTTPMedianMS {
			return a.HTTPMedianMS < b.HTTPMedianMS
		}
		return a.HTTPJitterMS < b.HTTPJitterMS
	})
	for start := 0; start < len(results); {
		anchor := results[start]
		end := start + 1
		tolerance := math.Max(20, anchor.HTTPMedianMS*0.2)
		for end < len(results) && sameStability(anchor, results[end]) && results[end].HTTPMedianMS <= anchor.HTTPMedianMS+tolerance {
			end++
		}
		group := results[start:end]
		sort.SliceStable(group, func(i, j int) bool {
			a, b := group[i], group[j]
			if (a.DownloadState == "ok") != (b.DownloadState == "ok") {
				return a.DownloadState == "ok"
			}
			if a.DownloadState == "ok" && a.DownloadMiBS != b.DownloadMiBS {
				return a.DownloadMiBS > b.DownloadMiBS
			}
			return a.HTTPJitterMS < b.HTTPJitterMS
		})
		// A second anchored 20% speed group gives provenance the tie-break,
		// without claiming a materially slower official address is faster.
		for first := 0; first < len(group); {
			last := first + 1
			for last < len(group) && group[last].DownloadState == group[first].DownloadState &&
				(group[first].DownloadState != "ok" || group[last].DownloadMiBS >= group[first].DownloadMiBS*0.8) &&
				math.Abs(group[last].HTTPJitterMS-group[first].HTTPJitterMS) <= 20 {
				last++
			}
			sort.SliceStable(group[first:last], func(i, j int) bool { return official(group[first+i]) && !official(group[first+j]) })
			first = last
		}
		start = end
	}
}

func recommend(results []Result, o Options) int {
	rank(results)
	n := 0
	for i := range results {
		results[i].Recommended = false
		if n < o.RecommendCount && fullStable(results[i]) && (o.DownloadTop == 0 || results[i].DownloadState == "ok") {
			results[i].Recommended = true
			n++
		}
	}
	return n
}
