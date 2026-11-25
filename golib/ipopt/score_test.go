package ipopt

import (
	"context"
	"math"
	"testing"
)

func scoredFixture(t *testing.T) Result {
	t.Helper()
	r := newResult(testCandidate(1), false)
	ops := fakeOperations()
	r, _ = ops.connectivity(context.Background(), r, defaultOptions(), nil)
	r, _ = ops.stability(context.Background(), r)
	r, _ = ops.latency(context.Background(), r, defaultOptions(), nil)
	r.ProbeComplete = true
	scoreResult(&r)
	return r
}

func TestV2ScoreFormulaMissingFailedPartialAndDownloadIndependent(t *testing.T) {
	r := scoredFixture(t)
	want := 30.0 + 30 + 15*(1-25.0/500) + 15*(1-30.0/1000) + 10*(1-5.0/500)
	if !r.ScoreReady || r.ScoreVersion != 1 || math.Abs(r.Score-want) > 1e-9 || len(r.ScoreBreakdown) != 5 {
		t.Fatal(r)
	}
	for i, key := range []string{"tcp_success", "http_success", "tcp_latency", "http_latency", "http_jitter"} {
		if r.ScoreBreakdown[i].Key != key {
			t.Fatal(r.ScoreBreakdown)
		}
	}
	base := r.Score
	r.DownloadMiBS, r.DownloadState = 10000, "ok"
	scoreResult(&r)
	if r.Score != base {
		t.Fatal("download affected base score")
	}
	r.ProbeComplete = false
	scoreResult(&r)
	if r.ScoreReady || r.Score != 0 {
		t.Fatal("partial scored")
	}
	r = scoredFixture(t)
	r.Status = "failed"
	scoreResult(&r)
	if !r.ScoreReady || r.Score != 0 || r.ScoreBreakdown[0].Points != 30 {
		t.Fatal("failed gate/disclosure", r)
	}
	r = scoredFixture(t)
	r.TCPMeasured = false
	r.TCPAvgMS = 0
	scoreResult(&r)
	if r.Score != 0 || r.Status != "failed" || r.ScoreBreakdown[2].Points != 0 {
		t.Fatal("missing rewarded", r)
	}
	r = scoredFixture(t)
	r.HTTPSuccess = 0
	r.HTTPMeasured = false
	r.HTTPMedianMS = 0
	r.HTTPJitterMS = 0
	scoreResult(&r)
	if r.Score != 0 || r.ScoreBreakdown[3].Points != 0 || r.ScoreBreakdown[4].Points != 0 {
		t.Fatal(r)
	}
	r = scoredFixture(t)
	r.TCPAvgMS = 1000
	r.HTTPMedianMS = 2000
	r.HTTPJitterMS = 1000
	scoreResult(&r)
	if r.Score != 60 {
		t.Fatal("clamp", r)
	}
	r = scoredFixture(t)
	r.HTTPSuccess = 2
	scoreResult(&r)
	want2 := 30.0 + 20.0 + 15*(1-25.0/500) + 15*(1-30.0/1000) + 10*(1-5.0/500)
	if !r.ScoreReady || r.Status != "valid" || math.Abs(r.ScoreBreakdown[1].Points-20) > 1e-9 || math.Abs(r.Score-want2) > 1e-9 || fullStable(r) {
		t.Fatal("partial 2/3 HTTPS", r)
	}
	r = scoredFixture(t)
	r.HTTPSuccess = 1
	r.HTTPJitterMS = 0
	scoreResult(&r)
	want1 := 30.0 + 10.0 + 15*(1-25.0/500) + 15*(1-30.0/1000)
	if !r.ScoreReady || r.Status != "valid" || math.Abs(r.ScoreBreakdown[1].Points-10) > 1e-9 || r.ScoreBreakdown[4].Points != 0 || math.Abs(r.Score-want1) > 1e-9 || fullStable(r) {
		t.Fatal("partial 1/3 HTTPS", r)
	}
}

func TestV2ScoreSortStablePriorityAndNoDownload(t *testing.T) {
	a := scoredFixture(t)
	b := scoredFixture(t)
	b.Candidate = testCandidate(2)
	pending := newResult(testCandidate(3), false)
	failed := scoredFixture(t)
	failed.Candidate = testCandidate(4)
	failed.Status = "failed"
	scoreResult(&failed)
	b.DownloadMiBS = 999
	b.DownloadState = "ok"
	rs := []Result{failed, b, pending, a}
	rankScores(rs)
	for i, want := range []string{a.Address, b.Address, pending.Address, failed.Address} {
		if rs[i].Address != want {
			t.Fatal(rs)
		}
	}
	// Provenance only breaks equal scores, never adds score points.
	b.SourceKind = "legacy"
	b.SourceKinds = []string{"legacy"}
	rs = []Result{a, b}
	rankScores(rs)
	if rs[0].Address != b.Address || a.Score != b.Score {
		t.Fatal(rs)
	}
}
