package ipopt

import "testing"

func rankedResult(n int, median, speed float64, source string) Result {
	r := newResult(testCandidate(n), false)
	r.SourceKind = source
	r.SourceKinds = []string{source}
	r.Status = "valid"
	r.TCPAttempts = 10
	r.TCPSuccess = 10
	r.HTTPAttempts = 5
	r.HTTPSuccess = 5
	r.HTTPMedianMS = median
	r.HTTPJitterMS = 3
	r.DownloadState = "ok"
	r.DownloadSeconds = 2
	r.DownloadMiBS = speed
	return r
}

func TestRankStabilityThenRealHTTPNotFastTCP(t *testing.T) {
	fastTCP := rankedResult(1, 150, 30, "third_party")
	fastTCP.TCPAvgMS = 1
	good := rankedResult(2, 30, 5, "official")
	good.TCPAvgMS = 30
	unstable := rankedResult(3, 10, 100, "official")
	unstable.TCPSuccess = 9
	rs := []Result{fastTCP, unstable, good}
	rank(rs)
	if rs[0].Address != good.Address || rs[1].Address != fastTCP.Address || rs[2].Address != unstable.Address {
		t.Fatal(rs)
	}
}

func TestComparableDownloadAndOfficialTieBreak(t *testing.T) {
	rs := []Result{rankedResult(1, 30, 10, "third_party"), rankedResult(2, 35, 9, "official"), rankedResult(3, 200, 100, "third_party")}
	rank(rs)
	if rs[0].Port != 443 || rs[0].IP != testCandidate(2).IP || rs[2].IP != testCandidate(3).IP {
		t.Fatal(rs)
	}
	rs = []Result{rankedResult(1, 30, 100, "third_party"), rankedResult(2, 35, 9, "official")}
	rank(rs)
	if rs[0].IP != testCandidate(1).IP {
		t.Fatal("official overwhelmed material speed difference", rs)
	}
}

func TestRecommendationsExcludePartialFailedAndShortSamples(t *testing.T) {
	rs := []Result{rankedResult(1, 30, 10, "official"), rankedResult(2, 20, 20, "manual"), rankedResult(3, 10, 100, "manual"), rankedResult(4, 10, 100, "manual")}
	rs[1].DownloadState = "short"
	rs[2].Status = "failed"
	rs[2].Error = "certificate"
	rs[3].HTTPSuccess = 4
	o := defaultOptions()
	if n := recommend(rs, o); n != 1 {
		t.Fatalf("padded recommendations %d", n)
	}
	for _, r := range rs {
		if r.Recommended && r.IP != testCandidate(1).IP {
			t.Fatal(r)
		}
	}
	o.RecommendCount = 0
	if n := recommend(rs, o); n != 0 {
		t.Fatal(n)
	}
	for _, r := range rs {
		if r.Recommended {
			t.Fatal("zero ignored")
		}
	}
}
