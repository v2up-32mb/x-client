package ipopt

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func fakeOperations() operations {
	return operations{
		fetch: func(context.Context, Source) (fetchOutput, error) {
			return fetchOutput{[]Candidate{testCandidate(1)}, []Prefix{}, []LineError{}}, nil
		},
		prepare: func(context.Context, Options) ([]byte, []string, error) { return nil, []string{}, nil },
		connectivity: func(_ context.Context, r Result, _ Options, _ []byte) (Result, []string) {
			r.Status = "valid"
			r.Colo = "HKG"
			r.TestedAt = 1730000000000
			return r, nil
		},
		stability: func(_ context.Context, r Result) (Result, []string) {
			r.TCPMeasured = true
			r.TCPSuccess = 10
			r.TCPAttempts = 10
			r.TCPMinMS = 20
			r.TCPMaxMS = 30
			r.TCPAvgMS = 25
			return r, nil
		},
		latency: func(_ context.Context, r Result, _ Options, _ []byte) (Result, []string) {
			r.HTTPMeasured = true
			r.HTTPSuccess = 5
			r.HTTPAttempts = 5
			r.HTTPMedianMS = 30
			r.HTTPJitterMS = 5
			return r, nil
		},
		download: func(_ context.Context, r Result, o Options, _ []byte, budget int64, progress func(int64, float64)) (Result, []string) {
			r.DownloadState = "ok"
			r.DownloadBytes = o.DownloadBytesPerIP
			if r.DownloadBytes > budget {
				r.DownloadBytes = budget
			}
			r.DownloadSeconds = 2
			r.DownloadMiBS = float64(r.DownloadBytes) / (1 << 20) / 2
			if progress != nil {
				progress(r.DownloadBytes/2, 1)
				progress(r.DownloadBytes, r.DownloadSeconds)
			}
			return r, nil
		},
	}
}

func getSnapshot(t *testing.T, m *Manager, id string) Snapshot {
	t.Helper()
	text, err := m.Get(id)
	if err != nil {
		t.Fatal(err)
	}
	var s Snapshot
	if err = json.Unmarshal([]byte(text), &s); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"warnings", "candidates", "prefixes", "errors", "results"} {
		if strings.Contains(text, `"`+key+`":null`) {
			t.Fatal("null array", text)
		}
	}
	return s
}

func waitDone(t *testing.T, m *Manager, id string) Snapshot {
	t.Helper()
	task, err := m.find(id)
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-task.done:
	case <-time.After(3 * time.Second):
		t.Fatal("task did not finish")
	}
	return getSnapshot(t, m, id)
}

func TestTaskPipelineSerialDownloadsBudgetAndJSON(t *testing.T) {
	ops := fakeOperations()
	var active, peak, calls atomic.Int32
	var mu sync.Mutex
	budgets := []int64{}
	base := ops.download
	ops.download = func(ctx context.Context, r Result, o Options, ech []byte, budget int64, progress func(int64, float64)) (Result, []string) {
		n := active.Add(1)
		if n > peak.Load() {
			peak.Store(n)
		}
		defer active.Add(-1)
		calls.Add(1)
		mu.Lock()
		budgets = append(budgets, budget)
		mu.Unlock()
		return base(ctx, r, o, ech, budget, progress)
	}
	m := newManager(ops, time.Now)
	req := testRequest(8)
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "completed" || s.Phase != "done" || s.NetworkID != req.NetworkID || len(s.Results) != 8 || s.DownloadBytes != 100<<20 || s.Total != 5 || s.Completed != 5 || peak.Load() != 1 || calls.Load() != 5 {
		t.Fatalf("%+v peak=%d calls=%d", s, peak.Load(), calls.Load())
	}
	recommended := 0
	for _, r := range s.Results {
		if r.Recommended {
			recommended++
			if !fullStable(r) || r.DownloadState != "ok" {
				t.Fatal(r)
			}
		}
	}
	if recommended != 3 {
		t.Fatalf("recommended %d", recommended)
	}
	mu.Lock()
	defer mu.Unlock()
	for i, b := range budgets {
		if b != int64(100-i*20)<<20 {
			t.Fatal(budgets)
		}
	}
}

func TestTaskExplicitZeroDownloadAndRecommend(t *testing.T) {
	ops := fakeOperations()
	ops.download = func(context.Context, Result, Options, []byte, int64, func(int64, float64)) (Result, []string) {
		t.Error("download called with download_top=0")
		return Result{}, nil
	}
	m := newManager(ops, time.Now)
	r := testRequest(3)
	r.Options.DownloadTop = 0
	r.Options.RecommendCount = 0
	id, err := m.Start(requestJSON(t, r))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "completed" || s.DownloadBytes != 0 {
		t.Fatal(s)
	}
	for _, result := range s.Results {
		if result.Recommended || result.DownloadState != "untested" || result.HTTPAttempts != 5 || result.TCPAttempts != 10 {
			t.Fatal(result)
		}
	}
}

func TestTaskCancelBusyAndNoLateResultOverwrite(t *testing.T) {
	ops := fakeOperations()
	entered := make(chan struct{})
	release := make(chan struct{})
	ops.connectivity = func(ctx context.Context, r Result, _ Options, _ []byte) (Result, []string) {
		close(entered)
		<-ctx.Done()
		<-release
		r.Status = "valid"
		r.Recommended = true
		return r, []string{"late"}
	}
	m := newManager(ops, time.Now)
	req := testRequest(1)
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	m.Cancel(id)
	m.Cancel(id)
	m.Cancel("unknown")
	before, err := m.Get(id)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 10; j++ {
				if _, err := m.Get(id); err != nil {
					t.Error(err)
				}
				m.Cancel(id)
			}
		}()
	}
	if _, err = m.Start(requestJSON(t, req)); err == nil || !strings.Contains(err.Error(), "busy") {
		t.Fatal("cancelled worker slot prematurely released", err)
	}
	wg.Wait()
	close(release)
	s := waitDone(t, m, id)
	after, _ := m.Get(id)
	if s.State != "cancelled" || s.NetworkID != req.NetworkID || s.Results[0].Recommended || s.Results[0].Status != "untested" || strings.Replace(before, `"worker_done":false`, `"worker_done":true`, 1) != after || !s.WorkerDone {
		t.Fatalf("cancel overwritten: %s -> %s", before, after)
	}
}

func TestTaskProbeConcurrencyBound(t *testing.T) {
	ops := fakeOperations()
	entered := make(chan struct{}, 3)
	release := make(chan struct{})
	var active, peak atomic.Int32
	ops.connectivity = func(ctx context.Context, r Result, _ Options, _ []byte) (Result, []string) {
		n := active.Add(1)
		for old := peak.Load(); n > old && !peak.CompareAndSwap(old, n); old = peak.Load() {
		}
		select {
		case entered <- struct{}{}:
		default:
		}
		select {
		case <-release:
		case <-ctx.Done():
		}
		active.Add(-1)
		r.Status = "valid"
		return r, nil
	}
	m := newManager(ops, time.Now)
	req := testRequest(10)
	req.Options.ProbeConcurrency = 3
	req.Options.DownloadTop = 0
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("workers not dispatched")
		}
	}
	s := getSnapshot(t, m, id)
	if s.Phase != "connectivity" || s.Completed != 0 || s.Total != 10 {
		t.Fatal(s)
	}
	close(release)
	s = waitDone(t, m, id)
	if s.State != "completed" || peak.Load() != 3 {
		t.Fatalf("state %s peak=%d", s.State, peak.Load())
	}
}

func TestTaskHistoryBoundAndNetworkIsolation(t *testing.T) {
	m := newManager(fakeOperations(), time.Now)
	ids := []string{}
	for i := 0; i < 5; i++ {
		r := testRequest(1)
		r.NetworkID = fmt.Sprintf("network-%d", i)
		r.Options.DownloadTop = 0
		id, err := m.Start(requestJSON(t, r))
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, id)
		s := waitDone(t, m, id)
		if s.NetworkID != r.NetworkID || s.ID != id {
			t.Fatal(s)
		}
	}
	for _, id := range ids[:2] {
		if _, err := m.Get(id); err == nil {
			t.Fatal("history not evicted")
		}
	}
	for _, id := range ids[2:] {
		if _, err := m.Get(id); err != nil {
			t.Fatal(err)
		}
	}
}

func TestFailedCandidatesRemainAndRecommendationDoesNotPad(t *testing.T) {
	ops := fakeOperations()
	base := ops.connectivity
	ops.connectivity = func(ctx context.Context, r Result, o Options, e []byte) (Result, []string) {
		if r.IP == testCandidate(1).IP {
			r.Status = "failed"
			r.Error = "certificate mismatch"
			return r, nil
		}
		return base(ctx, r, o, e)
	}
	m := newManager(ops, time.Now)
	req := testRequest(3)
	req.Options.DownloadTop = 0
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if len(s.Results) != 3 || s.Results[2].Status != "failed" || s.Results[2].Recommended || !s.Results[0].Recommended || !s.Results[1].Recommended || !strings.Contains(strings.Join(s.Warnings, " "), "只有2个") {
		t.Fatal(s)
	}
}

func TestFetchFailureIsNotSuccessAndRetainsLineErrors(t *testing.T) {
	ops := fakeOperations()
	ops.fetch = func(context.Context, Source) (fetchOutput, error) {
		return fetchOutput{errors: []LineError{{1, "invalid", "bad"}}}, errors.New("source unavailable")
	}
	m := newManager(ops, time.Now)
	id, err := m.Start(`{"operation":"fetch","network_id":"net","source":{"id":"cf","url":"https://www.cloudflare.com/ips-v4","kind":"cidr4"}}`)
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "failed" || s.Completed != 0 || s.Error == "" || len(s.Errors) != 1 {
		t.Fatal(s)
	}
}

func TestFinalCandidateLimitIsErrorBeforeAnyProbe(t *testing.T) {
	ops := fakeOperations()
	ops.connectivity = func(context.Context, Result, Options, []byte) (Result, []string) {
		t.Error("probed oversized final request")
		return Result{}, nil
	}
	m := newManager(ops, time.Now)
	req := testRequest(4096)
	req.Prefixes = []Prefix{{"1.1.1.1/32", 443, "cf", "official"}}
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "failed" || !strings.Contains(s.Error, "4096") {
		t.Fatal(s)
	}
}

func TestTaskECHFailureExplicitFallbackAndCancellation(t *testing.T) {
	ops := fakeOperations()
	ops.prepare = func(context.Context, Options) ([]byte, []string, error) {
		return nil, []string{"DoH and UDP unavailable"}, errors.New("no config")
	}
	m := newManager(ops, time.Now)
	req := testRequest(1)
	req.Options.EnableECH = true
	req.Options.DownloadTop = 0
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	s := waitDone(t, m, id)
	if s.State != "completed" || s.Results[0].ECHStatus != "fallback" || !strings.Contains(strings.Join(s.Warnings, " "), "ECH准备失败") {
		t.Fatal(s)
	}
	entered := make(chan struct{})
	ops.prepare = func(ctx context.Context, _ Options) ([]byte, []string, error) {
		close(entered)
		<-ctx.Done()
		return nil, nil, ctx.Err()
	}
	ops.connectivity = func(context.Context, Result, Options, []byte) (Result, []string) {
		t.Error("probe after ECH cancellation")
		return Result{}, nil
	}
	m = newManager(ops, time.Now)
	id, err = m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	m.Cancel(id)
	s = waitDone(t, m, id)
	if s.State != "cancelled" {
		t.Fatal(s)
	}
}

func TestTaskCancelRetainsLiveDownloadAccountingWithoutRevival(t *testing.T) {
	serverClosed := make(chan struct{})
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write([]byte(strings.Repeat("x", 1024)))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(serverClosed)
	}))
	read := make(chan struct{})
	cleaned := make(chan struct{})
	release := make(chan struct{})
	var readOnce, releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unblock()
	ops := fakeOperations()
	ops.download = func(ctx context.Context, r Result, o Options, ech []byte, budget int64, progress func(int64, float64)) (Result, []string) {
		r, warnings := p.download(ctx, r, o, ech, budget, func(bytes int64, seconds float64) {
			progress(bytes, seconds)
			if bytes == 1024 {
				readOnce.Do(func() { close(read) })
			}
		})
		close(cleaned)
		<-release
		// Even a stale success result must not overwrite a cancelled snapshot.
		r.Recommended = true
		r.Status = "failed"
		r.DownloadState = "ok"
		r.DownloadMiBS, r.DownloadPeakMiBS = 99, 99
		return r, warnings
	}
	m := newManager(ops, time.Now)
	req := testRequest(2)
	req.Options.DownloadURL = "https://speed.example.com/data"
	id, err := m.Start(requestJSON(t, req))
	if err != nil {
		t.Fatal(err)
	}
	defer m.Cancel(id)
	select {
	case <-read:
	case <-time.After(time.Second):
		t.Fatal("download did not report body consumption")
	}
	live := getSnapshot(t, m, id)
	if live.State != "running" || live.Phase != "download" || live.DownloadBytes != 1024 || live.Results[0].DownloadBytes != 1024 || live.Results[0].DownloadState == "untested" {
		t.Fatalf("body consumption absent from live snapshot: %+v", live)
	}
	m.Cancel(id)
	select {
	case <-cleaned:
	case <-time.After(time.Second):
		t.Fatal("download did not return after cancellation")
	}
	select {
	case <-serverClosed:
	case <-time.After(time.Second):
		t.Fatal("actual download socket remained open")
	}
	partial := getSnapshot(t, m, id)
	if partial.State != "cancelled" || partial.DownloadBytes != 1024 || partial.Results[0].DownloadSeconds <= 0 || partial.Results[0].DownloadState != "failed" {
		t.Fatalf("cancelled accounting lost: %+v", partial)
	}
	if _, err := m.Start(requestJSON(t, req)); err == nil || !strings.Contains(err.Error(), "busy") {
		t.Fatalf("download slot released before cleanup: %v", err)
	}
	unblock()
	s := waitDone(t, m, id)
	r := s.Results[0]
	if s.State != "cancelled" || s.Phase != "done" || s.Completed != 0 || s.DownloadBytes != 1024 || r.DownloadBytes != 1024 || r.DownloadSeconds < partial.Results[0].DownloadSeconds || r.DownloadState != "failed" || r.Status != "valid" || r.Recommended || r.DownloadMiBS != 0 || r.DownloadPeakMiBS != 0 {
		t.Fatalf("late result revived/double charged cancellation: %+v", s)
	}
	if s.Results[1].DownloadState != "untested" || s.Results[1].DownloadBytes != 0 || s.Results[1].Recommended {
		t.Fatal("another download was started after cancel", s.Results[1])
	}
}

func TestCancelledDownloadSettlementOnlyMergesActualCounters(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	previous := newResult(testCandidate(1), false)
	previous.DownloadBytes, previous.DownloadState = 20<<20, "ok"
	current := newResult(testCandidate(2), false)
	current.Status = "valid"
	current.DownloadBytes, current.DownloadSeconds = 100, 0.5
	worker := &task{ctx: ctx, now: time.Now, snapshot: Snapshot{
		State: "cancelled", Phase: "done", Completed: 1,
		Results: []Result{previous, current}, DownloadBytes: (20 << 20) + 100,
	}}
	// A Read may return additional bytes with its cancellation error; then
	// cleanup settles elapsed time. Neither should be discarded or doubled.
	worker.recordDownload(1, 150, 0.75)
	late := current
	late.DownloadBytes, late.DownloadSeconds = 200, 1
	late.Status, late.DownloadState, late.Recommended = "failed", "ok", true
	late.DownloadMiBS, late.DownloadPeakMiBS = 99, 99
	worker.finishDownload(1, late, []string{"late warning"})
	worker.finishDownload(1, late, nil) // cumulative, idempotent settlement
	s := worker.snapshot
	r := s.Results[1]
	if s.State != "cancelled" || s.Phase != "done" || s.Completed != 1 || s.DownloadBytes != (20<<20)+200 || len(s.Warnings) != 0 || r.DownloadBytes != 200 || r.DownloadSeconds != 1 || r.DownloadState != "failed" || r.Status != "valid" || r.Recommended || r.DownloadMiBS != 0 || r.DownloadPeakMiBS != 0 || s.Results[0].DownloadState != "ok" {
		t.Fatalf("late accounting changed non-accounting fields: %+v", s)
	}
}
