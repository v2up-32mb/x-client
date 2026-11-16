package ipopt

import (
	"math"
	"sort"
)

func scoreParts() []ScorePart {
	return []ScorePart{{"tcp_success", 0, 30}, {"http_success", 0, 30}, {"tcp_latency", 0, 15}, {"http_latency", 0, 15}, {"http_jitter", 0, 10}}
}

func scoreResult(r *Result) {
	r.ScoreReady, r.ScoreVersion, r.Score = r.ProbeComplete, 1, 0
	r.ScoreBreakdown = scoreParts()
	if !r.ProbeComplete {
		return
	}
	clamp := func(v float64) float64 { return math.Max(0, math.Min(1, v)) }
	validMS := func(v float64) bool { return v >= 0 && !math.IsNaN(v) && !math.IsInf(v, 0) }
	parts := r.ScoreBreakdown
	parts[0].Points = 30 * clamp(float64(r.TCPSuccess)/10)
	parts[1].Points = 30 * clamp(float64(r.HTTPSuccess)/5)
	// A missing measurement is not a zero-latency bonus. Success counters are
	// only set by Go measurements, never by request JSON.
	if r.TCPMeasured && r.TCPSuccess > 0 && validMS(r.TCPAvgMS) {
		parts[2].Points = 15 * clamp(1-r.TCPAvgMS/500)
	}
	if r.HTTPMeasured && r.HTTPSuccess > 0 && validMS(r.HTTPMedianMS) {
		parts[3].Points = 15 * clamp(1-r.HTTPMedianMS/1000)
	}
	if r.HTTPMeasured && r.HTTPSuccess > 1 && validMS(r.HTTPJitterMS) {
		parts[4].Points = 10 * clamp(1-r.HTTPJitterMS/500)
	}
	if r.Status != "valid" {
		return
	}
	if !r.TCPMeasured || !r.HTTPMeasured || r.TCPAttempts != 10 || r.HTTPAttempts != 5 || r.TCPSuccess < 1 || r.TCPSuccess > 10 || r.HTTPSuccess < 1 || r.HTTPSuccess > 5 || !validMS(r.TCPAvgMS) || !validMS(r.HTTPMedianMS) || !validMS(r.HTTPJitterMS) {
		r.Status, r.Error, r.NAReason = "failed", "完整指标缺失，不能评分为合格", "完整指标缺失"
		return
	}
	for _, part := range parts {
		r.Score += part.Points
	}
}

// Used only on a copy at snapshot serialization; worker indices never move.
func rankScores(rs []Result) {
	group := func(r Result) int {
		if !r.ProbeComplete {
			return 1
		}
		if r.Status == "valid" && r.ScoreReady {
			return 0
		}
		return 2
	}
	sort.SliceStable(rs, func(i, j int) bool {
		a, b := rs[i], rs[j]
		if group(a) != group(b) {
			return group(a) < group(b)
		}
		if group(a) == 0 && a.Score != b.Score {
			return a.Score > b.Score
		}
		return candidateLess(a.Candidate, b.Candidate)
	})
}
