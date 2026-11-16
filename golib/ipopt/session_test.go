package ipopt

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func startV2(t *testing.T, m *Manager, r Request) Snapshot {
	t.Helper()
	id, err := m.Start(requestJSON(t, r))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "completed" || !s.WorkerDone {
		t.Fatalf("not complete: %+v", s)
	}
	return s
}

func preparedV2(t *testing.T, m *Manager, n int) (Request, Snapshot) {
	t.Helper()
	r := testRequest(n)
	r.Operation = "prepare"
	s := startV2(t, m, r)
	r.Operation, r.SessionID, r.DownloadRoundID = "probe", s.SessionID, s.DownloadRoundID
	r.Options.DownloadTop, r.Options.RecommendCount = 0, 0
	return r, s
}

func downloadV2(r Request) Request {
	r.Operation = "download"
	r.Candidates = r.Candidates[:1]
	r.Options.DownloadBytesPerIP, r.Options.DownloadSeconds, r.Options.DownloadURL = maxManualDownloadBytes, 10, manualDownloadURL
	return r
}

func TestV2WireDefaultsAndStrictAdmission(t *testing.T) {
	c, _ := json.Marshal(testCandidate(1))
	for _, op := range []string{"test", "download"} {
		text := `{"operation":"` + op + `","network_id":"net","candidates":[` + string(c) + `],"options":{"target_host":"node.example.com"}`
		if op == "download" {
			text += `,"session_id":"s","download_round_id":"r"`
		}
		text += `}`
		r, err := decodeRequest(text)
		if err != nil {
			t.Fatal(err)
		}
		wantBytes, wantSeconds, wantURL := int64(maxDownloadBytes), 5, defaultDownloadURL
		if op == "download" {
			wantBytes, wantSeconds, wantURL = maxManualDownloadBytes, 10, manualDownloadURL
		}
		if r.Options.DownloadBytesPerIP != wantBytes || r.Options.DownloadSeconds != wantSeconds || r.Options.DownloadURL != wantURL {
			t.Fatal(r)
		}
	}
	r := testRequest(1)
	r.Operation, r.SessionID = "download", "missing"
	r.Options.DownloadBytesPerIP, r.Options.DownloadSeconds = maxManualDownloadBytes+1, 11
	if _, err := decodeRequest(requestJSON(t, r)); err == nil {
		t.Fatal("manual cap exceeded")
	}
	r.Options.DownloadBytesPerIP, r.Options.DownloadSeconds = maxManualDownloadBytes, 10
	if _, err := decodeRequest(requestJSON(t, r)); err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{`"valid":true`, `"allow_private":true`, `"ech_config":"AA=="`, `"score":100`} {
		text := strings.TrimSuffix(requestJSON(t, r), "}") + "," + field + "}"
		if _, err := decodeRequest(text); err == nil {
			t.Fatal("untrusted field accepted", field)
		}
	}
	r.Operation = "probe"
	r.Options = defaultOptions()
	r.Options.TargetHost = "node.example.com"
	r.Candidates = testRequest(9).Candidates
	if _, err := decodeRequest(requestJSON(t, r)); err == nil {
		t.Fatal("batch exceeds N")
	}
}

func TestV2MaterializePriorityAndWholeRoundLimit(t *testing.T) {
	r := testRequest(1)
	r.Operation = "prepare"
	r.Options.SampleLimit = 1
	r.Candidates = nil
	for _, v := range []struct{ address, kind, id string }{
		{"1.1.1.10:443", "official", "cf"}, {"1.1.1.2:443", "official", "cf"},
		{"1.0.0.1:443", "manual", "paste"}, {"1.1.1.10:443", "legacy", "old"},
		{"[2606:4700::1111]:443", "official", "cf6"}, {"1.0.0.2:443", "third_party", "list"},
	} {
		c, err := normalizeCandidate(Candidate{Address: v.address, SourceKind: v.kind, SourceID: v.id, Comment: v.id})
		if err != nil {
			t.Fatal(err)
		}
		r.Candidates = append(r.Candidates, c)
	}
	cs, err := materialize(r)
	if err != nil || len(cs) != 5 {
		t.Fatal(cs, err)
	}
	want := []string{"1.1.1.10:443", "1.0.0.1:443", "1.0.0.2:443", "1.1.1.2:443", "[2606:4700::1111]:443"}
	for i, c := range cs {
		if c.Address != want[i] {
			t.Fatal(cs)
		}
	}
	if cs[0].SourceKind != "legacy" || len(cs[0].SourceIDs) != 2 || !strings.Contains(cs[0].Comment, "cf") {
		t.Fatal(cs[0])
	}
	a, _ := normalizeCandidate(Candidate{Address: "1.1.1.2:443", SourceKind: "manual", SourceID: "a"})
	b, _ := normalizeCandidate(Candidate{Address: "1.1.1.10:443", SourceKind: "manual", SourceID: "a"})
	if !candidateLess(a, b) {
		t.Fatal("lexical IP ordering")
	}
	r = testRequest(4096)
	r.Operation = "prepare"
	r.Options.SampleLimit = 1
	r.Prefixes = []Prefix{{"1.1.1.1/32", 443, "cf", "official"}}
	m := newManager(fakeOperations(), time.Now)
	id, err := m.Start(requestJSON(t, r))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "failed" || s.SessionID != "" || m.session != nil || m.ledger != nil || !strings.Contains(s.Error, "4096") {
		t.Fatal(s)
	}
	r.Prefixes = nil
	s = startV2(t, m, r)
	if len(s.Candidates) != 4096 {
		t.Fatal("sample quota truncated explicit list")
	}
	r.Operation, r.SessionID, r.DownloadRoundID = "probe", s.SessionID, s.DownloadRoundID
	r.Candidates = []Candidate{a}
	if _, err := m.Start(requestJSON(t, r)); err == nil {
		t.Fatal("added candidate via a later batch")
	}
}

func TestV2PipelineIncrementalPauseAndReferenceProof(t *testing.T) {
	ops := fakeOperations()
	base := ops.connectivity
	entered, release := make(chan struct{}), make(chan struct{})
	ops.connectivity = func(ctx context.Context, r Result, o Options, e []byte) (Result, []string) {
		if r.Address == testCandidate(2).Address {
			close(entered)
			<-ctx.Done()
			<-release
		}
		return base(ctx, r, o, e)
	}
	ops.download = func(context.Context, Result, Options, []byte, int64, func(int64, float64)) (Result, []string) {
		t.Error("probe downloaded")
		return Result{}, nil
	}
	m := newManager(ops, time.Now)
	r, prepared := preparedV2(t, m, 2)
	id, err := m.Start(requestJSON(t, r))
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	deadline := time.Now().Add(time.Second)
	var s Snapshot
	for time.Now().Before(deadline) {
		s = getSnapshot(t, m, id)
		if s.Completed == 1 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if s.Completed != 1 || s.WorkerDone || !s.Results[0].ProbeComplete || !s.Results[0].ScoreReady || s.Results[0].TCPAttempts != 10 || s.Results[0].HTTPAttempts != 5 {
		t.Fatal(s)
	}
	m.Cancel(id)
	s = getSnapshot(t, m, id)
	if s.State != "cancelled" || s.WorkerDone {
		t.Fatal(s)
	}
	if _, err = m.Start(requestJSON(t, r)); err == nil {
		t.Fatal("worker still live")
	}
	close(release)
	s = waitDone(t, m, id)
	if !s.Results[0].ProbeComplete || s.Results[1].ProbeComplete || s.Results[1].ScoreReady || s.Results[1].Status != "untested" || s.DownloadBytes != 0 {
		t.Fatal(s)
	}
	// Completed reference cannot be repeated, partial can restart from stage 1.
	r.Candidates = r.Candidates[:1]
	if _, err = m.Start(requestJSON(t, r)); err == nil {
		t.Fatal("completed candidate rescanned")
	}
	m.ops.connectivity = base
	r.Candidates = []Candidate{testCandidate(2)}
	s = startV2(t, m, r)
	if !s.Results[0].ProbeComplete || s.SessionID != prepared.SessionID {
		t.Fatal(s)
	}
	// New target must be explicitly labelled; it never overwrites reference proof.
	r.Options.TargetHost = "other.example.com"
	if _, err = m.Start(requestJSON(t, r)); err == nil {
		t.Fatal("reference target changed")
	}
	r.ProbeRole = "validation"
	s = startV2(t, m, r)
	if s.Results[0].ResultKind != "validation" || s.TargetKey == prepared.TargetKey || s.DownloadBytes != 0 {
		t.Fatal(s)
	}
	r.Operation = "download"
	r.ProbeRole = "reference"
	if _, err = m.Start(requestJSON(t, r)); err == nil {
		t.Fatal("validation used as reference download proof")
	}
}

func TestV2LedgerAcrossTasksScansAndExplicitRound(t *testing.T) {
	ops := fakeOperations()
	var probes atomic.Int32
	base := ops.connectivity
	ops.connectivity = func(ctx context.Context, r Result, o Options, e []byte) (Result, []string) {
		probes.Add(1)
		return base(ctx, r, o, e)
	}
	m := newManager(ops, time.Now)
	r, prep := preparedV2(t, m, 2)
	probe := startV2(t, m, r)
	for i := 1; i <= 2; i++ {
		d := downloadV2(r)
		d.Candidates = []Candidate{r.Candidates[i-1]}
		s := startV2(t, m, d)
		if s.DownloadBytes != maxManualDownloadBytes || s.DownloadRoundUsed != int64(i)*maxManualDownloadBytes || s.DownloadRoundRemaining != int64(2-i)*maxManualDownloadBytes || s.DownloadRoundID != prep.DownloadRoundID || s.Results[0].ProbeComplete || s.Results[0].ScoreReady || s.Results[0].Status != "untested" || s.DownloadReservedBytes != maxManualDownloadBytes {
			t.Fatal(s)
		}
	}
	if probes.Load() != 2 {
		t.Fatal("download reprobed")
	}
	if _, err := m.Start(requestJSON(t, downloadV2(r))); err == nil {
		t.Fatal("budget exceeded")
	}
	// New scan/network inherits exhausted ledger, not a new allowance.
	n := testRequest(1)
	n.Operation = "prepare"
	n.NetworkID = "cell-2"
	n.DownloadRoundID = prep.DownloadRoundID
	s := startV2(t, m, n)
	if s.SessionID == prep.SessionID || s.DownloadRoundUsed != maxTotalDownloadBytes {
		t.Fatal(s)
	}
	if _, err := m.Start(requestJSON(t, r)); err == nil {
		t.Fatal("old session resurrected")
	}
	n.SessionID = s.SessionID
	n.Candidates = nil
	n.NewDownloadRound = true
	s = startV2(t, m, n)
	if s.DownloadRoundID == prep.DownloadRoundID || s.DownloadRoundUsed != 0 || s.DownloadRoundRemaining != maxTotalDownloadBytes {
		t.Fatal(s)
	}
	if probe.Results[0].Score == 0 || !probe.Results[0].ProbeComplete {
		t.Fatal("reference overwritten")
	}
	// Losing either proof or ledger is an error, never an automatic recovery.
	lost := newManager(fakeOperations(), time.Now)
	if _, err := lost.Start(requestJSON(t, n)); err == nil || !strings.Contains(err.Error(), "ledger_lost") {
		t.Fatal(err)
	}
	n.DownloadRoundID = ""
	n.NewDownloadRound = false
	if _, err := m.Start(requestJSON(t, n)); err == nil {
		t.Fatal("missing round ID accepted")
	}
	n.DownloadRoundID = s.DownloadRoundID
	n.SessionID = "unknown"
	if _, err := m.Start(requestJSON(t, n)); err == nil || !strings.Contains(err.Error(), "unknown_session") {
		t.Fatal(err)
	}
}

func TestV2DownloadCancelledSettlementAndNoLateProof(t *testing.T) {
	ops := fakeOperations()
	entered, release := make(chan struct{}), make(chan struct{})
	ops.download = func(ctx context.Context, r Result, _ Options, _ []byte, _ int64, progress func(int64, float64)) (Result, []string) {
		progress(100, 0.1)
		close(entered)
		<-ctx.Done()
		<-release
		r.DownloadBytes, r.DownloadSeconds, r.DownloadState = 200, 0.2, "ok"
		r.ProbeComplete, r.ScoreReady, r.Status = true, true, "valid"
		return r, nil
	}
	m := newManager(ops, time.Now)
	r, _ := preparedV2(t, m, 1)
	startV2(t, m, r)
	id, err := m.Start(requestJSON(t, downloadV2(r)))
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	m.Cancel(id)
	s := getSnapshot(t, m, id)
	if s.WorkerDone || s.DownloadRoundUsed != 100 {
		t.Fatal(s)
	}
	close(release)
	s = waitDone(t, m, id)
	if !s.WorkerDone || s.DownloadRoundUsed != 200 || s.DownloadBytes != 200 || s.Results[0].DownloadState != "failed" || s.Results[0].ProbeComplete || s.Results[0].ScoreReady || s.Results[0].Status != "untested" {
		t.Fatal(s)
	}
	// Refresh/new task does not lose cancellation's final byte settlement.
	r.Operation = "prepare"
	r.Candidates = nil
	s = startV2(t, m, r)
	if s.DownloadRoundUsed != 200 {
		t.Fatal(s)
	}
}

func TestV2ECHCacheTTLRefreshAndIsolation(t *testing.T) {
	ops := fakeOperations()
	var calls atomic.Int32
	now := time.Unix(1730000000, 0)
	ops.prepareTTL = func(context.Context, Options) ([]byte, time.Duration, []string, error) {
		calls.Add(1)
		return []byte{0, 3, 1, 2, 3}, time.Minute, nil, nil
	}
	m := newManager(ops, func() time.Time { return now })
	r := testRequest(2)
	r.Operation = "prepare"
	r.Options.EnableECH = true
	s := startV2(t, m, r)
	if s.ECH.State != "ready" || s.ECH.ExpiresAt != now.Add(time.Minute).UnixMilli() || calls.Load() != 1 {
		t.Fatal(s)
	}
	r.SessionID, r.DownloadRoundID = s.SessionID, s.DownloadRoundID
	r.Operation = "probe"
	startV2(t, m, r)
	startV2(t, m, downloadV2(r))
	if calls.Load() != 1 {
		t.Fatal("ECH queried per batch/download")
	}
	original := append([]Candidate{}, s.Candidates...)
	r.Operation = "prepare"
	r.Candidates = nil
	s = startV2(t, m, r)
	if !s.ECH.CacheHit || calls.Load() != 1 {
		t.Fatal(s)
	}
	now = now.Add(2 * time.Minute)
	d := downloadV2(Request{Operation: "download", NetworkID: r.NetworkID, SessionID: r.SessionID, DownloadRoundID: r.DownloadRoundID, Candidates: original, Options: r.Options})
	if _, err := m.Start(requestJSON(t, d)); err == nil || !strings.Contains(err.Error(), "prepare_required") {
		t.Fatal(err)
	}
	s = startV2(t, m, r)
	if calls.Load() != 2 || s.SessionID != r.SessionID || s.DownloadRoundUsed != maxManualDownloadBytes || !reflect.DeepEqual(original, s.Candidates) {
		t.Fatal(s)
	}
	r.RefreshECH = true
	s = startV2(t, m, r)
	if calls.Load() != 3 || s.DownloadRoundUsed != maxManualDownloadBytes {
		t.Fatal(s)
	}
	r.RefreshECH = false
	r.Options.DNSServer = "https://dns.example.com/query"
	s = startV2(t, m, r)
	if calls.Load() != 4 || s.ECH.Key == echKey(r.NetworkID, defaultOptions()) {
		t.Fatal(s)
	}
	r.NetworkID = "changed"
	if _, err := m.Start(requestJSON(t, r)); err == nil || !strings.Contains(err.Error(), "network_mismatch") {
		t.Fatal(err)
	}
	// Network isolation requires a new frozen session but inherits the ledger.
	r.SessionID = ""
	r.Candidates = original
	s = startV2(t, m, r)
	if calls.Load() != 5 || s.DownloadRoundUsed != maxManualDownloadBytes {
		t.Fatal(s)
	}
}

func TestV2ECHFallbackCancellationAndCacheBounds(t *testing.T) {
	ops := fakeOperations()
	var calls atomic.Int32
	ops.prepare = func(context.Context, Options) ([]byte, []string, error) {
		calls.Add(1)
		return nil, nil, errors.New("unavailable")
	}
	m := newManager(ops, time.Now)
	r := testRequest(1)
	r.Operation = "prepare"
	r.Options.EnableECH = true
	s := startV2(t, m, r)
	r.SessionID, r.DownloadRoundID = s.SessionID, s.DownloadRoundID
	r.Candidates = nil
	for i := 0; i < 3; i++ {
		s = startV2(t, m, r)
	}
	if calls.Load() != 1 || s.ECH.State != "fallback" || s.ECH.ExpiresAt != 0 {
		t.Fatal(s, calls.Load())
	}
	entered := make(chan struct{})
	m.ops.prepare = func(ctx context.Context, _ Options) ([]byte, []string, error) {
		close(entered)
		<-ctx.Done()
		return nil, nil, ctx.Err()
	}
	r.RefreshECH = true
	id, err := m.Start(requestJSON(t, r))
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	m.Cancel(id)
	s = waitDone(t, m, id)
	if !s.WorkerDone || s.State != "cancelled" {
		t.Fatal(s)
	}
	r.RefreshECH = false
	s = startV2(t, m, r)
	if !s.ECH.CacheHit || s.ECH.State != "fallback" {
		t.Fatal("cancelled refresh replaced old cache", s)
	}
	// Internal fixture stresses both independent bounds without network or public bypass.
	cache := &scanSession{ech: map[string]echEntry{}}
	for i := 0; i < 20; i++ {
		storeECH(cache, echEntry{config: make([]byte, 100<<10), info: ECHPreparation{Key: fmt.Sprint(i), State: "ready", PreparedAt: int64(i)}})
	}
	bytes := 0
	for _, e := range cache.ech {
		bytes += len(e.config)
	}
	if len(cache.ech) > 8 || bytes > maxECHCacheBytes {
		t.Fatal(len(cache.ech), bytes)
	}
	cache.ech = map[string]echEntry{}
	for i := 0; i < 20; i++ {
		storeECH(cache, echEntry{info: ECHPreparation{Key: fmt.Sprint(i), State: "fallback"}})
	}
	if len(cache.ech) != 8 {
		t.Fatal(len(cache.ech))
	}
	// Unknown TTL uses five minutes; known long TTL is capped at ten minutes.
	m.ops.prepare = func(context.Context, Options) ([]byte, []string, error) { return []byte{1}, nil, nil }
	r.RefreshECH = true
	s = startV2(t, m, r)
	if s.ECH.ExpiresAt-s.ECH.PreparedAt != int64((5*time.Minute)/time.Millisecond) {
		t.Fatal(s.ECH)
	}
	m.ops.prepareTTL = func(context.Context, Options) ([]byte, time.Duration, []string, error) {
		return []byte{1}, time.Hour, nil, nil
	}
	s = startV2(t, m, r)
	if s.ECH.ExpiresAt-s.ECH.PreparedAt != int64((10*time.Minute)/time.Millisecond) {
		t.Fatal(s.ECH)
	}
}

func TestV2ECHWireTTLIncludingZeroAndCNAME(t *testing.T) {
	query, id, err := echQuestion("cloudflare-ech.com", zeroReader{})
	if err != nil {
		t.Fatal(err)
	}
	config := []byte{0, 3, 1, 2, 3}
	for _, seconds := range []uint32{0, 7, 3600} {
		wire := wireECHResponse(query, config)
		binary.BigEndian.PutUint32(wire[len(query)+6:len(query)+10], seconds)
		_, ttl, err := parseECHAnswerTTL(wire, id, "cloudflare-ech.com")
		if err != nil || ttl != time.Duration(seconds)*time.Second {
			t.Fatal(ttl, err)
		}
	}
	data := wireECHResponse(query, config)[len(query)+12:]
	alias := echWireName("alias.example.com")
	wire := echWireRecords(query, echWireRecord{[]byte{0xc0, 0x0c}, 5, 1, alias}, echWireRecord{alias, 65, 1, data})
	binary.BigEndian.PutUint32(wire[len(query)+6:len(query)+10], 3)
	_, ttl, err := parseECHAnswerTTL(wire, id, "cloudflare-ech.com")
	if err != nil || ttl != 3*time.Second {
		t.Fatal(ttl, err)
	}
}

func TestV2PrepareCancellationNeverPublishesSession(t *testing.T) {
	ops := fakeOperations()
	entered, release := make(chan struct{}), make(chan struct{})
	ops.prepare = func(ctx context.Context, _ Options) ([]byte, []string, error) {
		close(entered)
		<-ctx.Done()
		<-release
		return []byte{1}, nil, nil
	}
	m := newManager(ops, time.Now)
	r := testRequest(1)
	r.Operation = "prepare"
	r.Options.EnableECH = true
	id, err := m.Start(requestJSON(t, r))
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	m.Cancel(id)
	if getSnapshot(t, m, id).WorkerDone {
		t.Fatal("prepare worker not joined")
	}
	close(release)
	s := waitDone(t, m, id)
	if s.SessionID != "" || s.DownloadRoundID != "" || m.session != nil || m.ledger != nil {
		t.Fatal(s)
	}
}

func TestV2FailedPipelineCompletesZeroAndValidationCannotGrantDownload(t *testing.T) {
	ops := fakeOperations()
	var stability atomic.Int32
	ops.connectivity = func(_ context.Context, r Result, o Options, _ []byte) (Result, []string) {
		if o.TargetHost == "node.example.com" {
			r.Status = "failed"
			r.Error = "certificate failed"
		} else {
			r.Status = "valid"
		}
		return r, nil
	}
	base := ops.stability
	ops.stability = func(ctx context.Context, r Result) (Result, []string) { stability.Add(1); return base(ctx, r) }
	m := newManager(ops, time.Now)
	r, _ := preparedV2(t, m, 1)
	s := startV2(t, m, r)
	if stability.Load() != 0 || !s.Results[0].ProbeComplete || !s.Results[0].ScoreReady || s.Results[0].Score != 0 || s.Results[0].NAReason == "" {
		t.Fatal(s)
	}
	other := r
	other.ProbeRole = "validation"
	other.Options.TargetHost = "other.example.com"
	startV2(t, m, other)
	if _, err := m.Start(requestJSON(t, downloadV2(r))); err == nil || !strings.Contains(err.Error(), "reference_required") {
		t.Fatal(err)
	}
	if len(m.session.completed) != 1 || len(m.session.eligible) != 1 {
		t.Fatal("validation matrix retained")
	}
}

func TestV2LostLiveLedgerCannotBeReinitialized(t *testing.T) {
	m := newManager(fakeOperations(), time.Now)
	preparedV2(t, m, 1)
	m.ledger = nil // internal loss fixture; there is no public ledger deletion API
	r := testRequest(1)
	r.Operation = "prepare"
	if _, err := m.Start(requestJSON(t, r)); err == nil || !strings.Contains(err.Error(), "ledger_lost") {
		t.Fatal(err)
	}
}

func TestV2PortAndNetworkMembershipCannotBeSpoofed(t *testing.T) {
	m := newManager(fakeOperations(), time.Now)
	r, _ := preparedV2(t, m, 1)
	bad := r
	bad.Candidates = []Candidate{r.Candidates[0]}
	bad.Candidates[0].Address = "104.16.0.1:8443"
	bad.Candidates[0].Port = 8443
	if _, err := m.Start(requestJSON(t, bad)); err == nil || !strings.Contains(err.Error(), "candidate_mismatch") {
		t.Fatal(err)
	}
	bad = r
	bad.NetworkID = "another-network"
	if _, err := m.Start(requestJSON(t, bad)); err == nil || !strings.Contains(err.Error(), "network_mismatch") {
		t.Fatal(err)
	}
	bad = r
	bad.Candidates = []Candidate{{Address: "127.0.0.1:443", SourceID: "test", SourceKind: "manual"}}
	if _, err := m.Start(requestJSON(t, bad)); err == nil {
		t.Fatal("private-address public API bypass")
	}
}
