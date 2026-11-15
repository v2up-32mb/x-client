package ipopt

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/netip"
	"sync"
	"time"
)

type operations struct {
	fetch        func(context.Context, Source) (fetchOutput, error)
	prepare      func(context.Context, Options) ([]byte, []string, error)
	connectivity func(context.Context, Result, Options, []byte) (Result, []string)
	stability    func(context.Context, Result) (Result, []string)
	latency      func(context.Context, Result, Options, []byte) (Result, []string)
	download     func(context.Context, Result, Options, []byte, int64, func(int64, float64)) (Result, []string)
}

func productionOperations() operations {
	p := newNetworkProbe()
	return operations{
		fetch: func(ctx context.Context, s Source) (fetchOutput, error) {
			return fetchSource(ctx, s, nil)
		},
		prepare: func(ctx context.Context, o Options) ([]byte, []string, error) {
			return prepareECH(ctx, o, nil, guardedDial)
		},
		connectivity: p.connectivity, stability: p.stability, latency: p.latency, download: p.download,
	}
}

// Manager owns at most one live worker task and three terminal snapshots.
// A cancelled task still occupies the worker slot until all its I/O/workers
// have actually returned; cancellation never merely hides background work.
type Manager struct {
	mu      sync.Mutex
	tasks   map[string]*task
	current string
	history []string
	ops     operations
	now     func() time.Time
}

type task struct {
	mu       sync.Mutex
	snapshot Snapshot
	request  Request
	ctx      context.Context
	cancel   context.CancelFunc
	done     chan struct{}
	io       *ioScope
	now      func() time.Time
}

func NewManager() *Manager { return newManager(productionOperations(), time.Now) }

func newManager(ops operations, now func() time.Time) *Manager {
	return &Manager{tasks: map[string]*task{}, history: []string{}, ops: ops, now: now}
}

func (m *Manager) Start(requestJSON string) (string, error) {
	r, err := decodeRequest(requestJSON)
	if err != nil {
		return "", err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.current != "" {
		return "", fmt.Errorf("busy: 已有优选任务运行或正在取消，请稍后重试")
	}
	var bytes [16]byte
	if _, err = rand.Read(bytes[:]); err != nil {
		return "", err
	}
	id := hex.EncodeToString(bytes[:])
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	scope := newIOScope()
	ctx = context.WithValue(ctx, ioScopeKey{}, scope)
	now := m.now().UnixMilli()
	phase := "connectivity"
	if r.Operation == "fetch" {
		phase = "fetch"
	}
	t := &task{request: r, ctx: ctx, cancel: cancel, done: make(chan struct{}), io: scope, now: m.now,
		snapshot: Snapshot{ID: id, Operation: r.Operation, NetworkID: r.NetworkID, State: "queued", Phase: phase,
			StartedAt: now, UpdatedAt: now, Warnings: []string{}, Candidates: []Candidate{}, Prefixes: []Prefix{}, Errors: []LineError{}, Results: []Result{}},
	}
	m.tasks[id] = t
	m.current = id
	go m.run(t)
	return id, nil
}

func (m *Manager) find(id string) (*task, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tasks[id]
	if !ok {
		return nil, fmt.Errorf("未知或已过期的任务ID")
	}
	return t, nil
}

func (m *Manager) Get(id string) (string, error) {
	t, err := m.find(id)
	if err != nil {
		return "", err
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	b, err := json.Marshal(t.snapshot)
	return string(b), err
}

func terminal(state string) bool {
	return state == "completed" || state == "cancelled" || state == "failed"
}

func (m *Manager) Cancel(id string) {
	t, err := m.find(id)
	if err != nil {
		return
	}
	t.mu.Lock()
	if !terminal(t.snapshot.State) {
		t.snapshot.State = "cancelled"
		t.snapshot.Phase = "done"
		t.snapshot.UpdatedAt = t.now().UnixMilli()
		t.snapshot.Warnings = appendUnique(t.snapshot.Warnings, "任务已取消；部分结果不自动推荐，网络变化时请标记为旧网络")
		for i := range t.snapshot.Results {
			t.snapshot.Results[i].Recommended = false
		}
		t.cancel()
	}
	t.mu.Unlock()
}

func (t *task) update(fn func(*Snapshot)) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if terminal(t.snapshot.State) || t.ctx.Err() != nil {
		return
	}
	fn(&t.snapshot)
	t.snapshot.UpdatedAt = t.now().UnixMilli()
}

// Download accounting is the only update allowed after explicit cancellation.
// It is cumulative (not additive per report), so final settlement cannot double
// charge the body budget. No late status, speed, recommendation or phase leaks
// through this path. The worker slot remains occupied until download returns.
func (t *task) recordDownload(i int, bytes int64, seconds float64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.recordDownloadLocked(i, bytes, seconds)
}

func (t *task) recordDownloadLocked(i int, bytes int64, seconds float64) {
	s := &t.snapshot
	if terminal(s.State) && s.State != "cancelled" {
		return
	}
	r := &s.Results[i]
	if bytes > r.DownloadBytes {
		s.DownloadBytes += bytes - r.DownloadBytes
		r.DownloadBytes = bytes
	}
	if seconds > r.DownloadSeconds {
		r.DownloadSeconds = seconds
	}
	// The fixed schema has no downloading/cancelled sample state. Until a
	// non-cancelled completion validates it, an attempted sample is failed.
	r.DownloadState = "failed"
	r.DownloadMiBS, r.DownloadPeakMiBS = 0, 0
	r.Recommended = false
	s.UpdatedAt = t.now().UnixMilli()
}

func (t *task) finishDownload(i int, r Result, warnings []string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.recordDownloadLocked(i, r.DownloadBytes, r.DownloadSeconds)
	if terminal(t.snapshot.State) || t.ctx.Err() != nil {
		return
	}
	t.snapshot.Results[i] = r
	t.snapshot.Completed++
	t.snapshot.Warnings = appendUnique(t.snapshot.Warnings, warnings...)
}

func (t *task) result(i int) Result {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.snapshot.Results[i]
}

func (m *Manager) run(t *task) {
	defer t.cancel()
	t.update(func(s *Snapshot) { s.State = "running" })
	var err error
	if t.ctx.Err() != nil {
		err = t.ctx.Err()
	} else if t.request.Operation == "fetch" {
		err = m.runFetch(t)
	} else {
		err = m.runTest(t)
	}
	t.mu.Lock()
	if !terminal(t.snapshot.State) {
		s := &t.snapshot
		s.Phase = "done"
		s.UpdatedAt = t.now().UnixMilli()
		if t.ctx.Err() != nil {
			s.State = "failed"
			s.Error = "任务超过总期限或被中止: " + t.ctx.Err().Error()
		} else if err != nil {
			s.State = "failed"
			s.Error = err.Error()
		} else {
			s.State = "completed"
			if t.request.Operation == "test" {
				n := recommend(s.Results, t.request.Options)
				if n < t.request.Options.RecommendCount {
					s.Warnings = appendUnique(s.Warnings, fmt.Sprintf("只有%d个完整合格推荐，未凑足%d个", n, t.request.Options.RecommendCount))
				}
			}
		}
	}
	t.mu.Unlock()
	t.cancel()
	t.io.close() // join detached transport dials before releasing the worker slot
	m.mu.Lock()
	m.current = ""
	m.history = append(m.history, t.snapshot.ID)
	for len(m.history) > 3 {
		delete(m.tasks, m.history[0])
		m.history = m.history[1:]
	}
	close(t.done)
	m.mu.Unlock()
}

func (m *Manager) runFetch(t *task) error {
	t.update(func(s *Snapshot) { s.Total = 1 })
	out, err := m.ops.fetch(t.ctx, t.request.Source)
	t.update(func(s *Snapshot) {
		s.Candidates = out.candidates
		s.Prefixes = out.prefixes
		s.Errors = out.errors
		// Defensive normalization also keeps injected/error outputs [] in JSON.
		if s.Candidates == nil {
			s.Candidates = []Candidate{}
		}
		if s.Prefixes == nil {
			s.Prefixes = []Prefix{}
		}
		if s.Errors == nil {
			s.Errors = []LineError{}
		}
		if err == nil {
			s.Completed = 1
		}
		if len(s.Errors) > 0 {
			s.Warnings = appendUnique(s.Warnings, "来源包含错误行，请明确确认后只导入有效行")
		}
	})
	return err
}

func (m *Manager) runTest(t *task) error {
	r := t.request
	cs, seen := []Candidate{}, map[string]int{}
	for _, c := range r.Candidates {
		mergeCandidate(&cs, seen, c)
	}
	sampled, err := samplePrefixes(r.Prefixes, r.Options.SampleLimit, rand.Reader)
	if err != nil {
		return err
	}
	for _, c := range sampled {
		mergeCandidate(&cs, seen, c)
	}
	if len(cs) > maxCandidates {
		return fmt.Errorf("最终候选%d个，超过4096限制；请减少候选或抽样数", len(cs))
	}
	if len(cs) == 0 {
		return fmt.Errorf("抽样后没有候选")
	}
	t.update(func(s *Snapshot) {
		s.Candidates = cs
		s.Prefixes = append([]Prefix{}, r.Prefixes...)
		for _, c := range cs {
			result := newResult(c, r.Options.EnableECH)
			if !publicIP(netip.MustParseAddr(c.IP)) {
				result.Status = "failed"
				result.Error = "抽样得到非公网地址，已阻止探测"
			}
			s.Results = append(s.Results, result)
		}
		s.Warnings = append(s.Warnings, "TCP成功率不是ICMP丢包；HTTPS trace成功不代表WebSocket/认证成功",
			"HTTPS统计为完整小请求中位数与最大最小差；下载均速仅表示测速站正文吞吐，另有探测/协议开销")
		if len(r.Prefixes) > r.Options.SampleLimit {
			s.Warnings = append(s.Warnings, "地址段多于抽样上限，本轮只覆盖前部分地址段")
		}
	})
	if t.ctx.Err() != nil {
		return t.ctx.Err()
	}
	var ech []byte
	if r.Options.EnableECH {
		var warnings []string
		ech, warnings, err = m.ops.prepare(t.ctx, r.Options)
		if t.ctx.Err() != nil {
			return t.ctx.Err()
		}
		t.update(func(s *Snapshot) {
			s.Warnings = appendUnique(s.Warnings, warnings...)
			if err != nil {
				s.Warnings = appendUnique(s.Warnings, "ECH准备失败，明确回退标准TLS: "+err.Error())
			}
		})
	}
	stages := []struct {
		name string
		fn   func(context.Context, Result) (Result, []string)
	}{
		{"connectivity", func(ctx context.Context, r Result) (Result, []string) {
			return m.ops.connectivity(ctx, r, t.request.Options, ech)
		}},
		{"stability", m.ops.stability},
		{"latency", func(ctx context.Context, r Result) (Result, []string) {
			return m.ops.latency(ctx, r, t.request.Options, ech)
		}},
	}
	for _, stage := range stages {
		if t.ctx.Err() != nil {
			return t.ctx.Err()
		}
		indices := []int{}
		t.mu.Lock()
		for i, v := range t.snapshot.Results {
			if v.Status != "failed" {
				indices = append(indices, i)
			}
		}
		t.mu.Unlock()
		m.runStage(t, stage.name, indices, stage.fn)
	}
	if t.ctx.Err() != nil {
		return t.ctx.Err()
	}
	t.update(func(s *Snapshot) { rank(s.Results) })
	indices := []int{}
	t.mu.Lock()
	for i, v := range t.snapshot.Results {
		if len(indices) >= r.Options.DownloadTop {
			break
		}
		if v.Status == "valid" && v.HTTPSuccess > 0 {
			indices = append(indices, i)
		}
	}
	t.mu.Unlock()
	if len(indices) > 0 {
		t.update(func(s *Snapshot) { s.Phase = "download"; s.Completed = 0; s.Total = len(indices) })
		budget := int64(maxTotalDownloadBytes)
		for _, i := range indices {
			if t.ctx.Err() != nil {
				return t.ctx.Err()
			}
			t.recordDownload(i, 0, 0) // attempted, not an untested zero on cancel
			result, warnings := m.ops.download(t.ctx, t.result(i), r.Options, ech, budget, func(bytes int64, seconds float64) {
				t.recordDownload(i, bytes, seconds)
			})
			budget -= result.DownloadBytes
			t.finishDownload(i, result, warnings)
		}
	}
	return t.ctx.Err()
}

func (m *Manager) runStage(t *task, phase string, indices []int, fn func(context.Context, Result) (Result, []string)) {
	t.update(func(s *Snapshot) { s.Phase = phase; s.Completed = 0; s.Total = len(indices) })
	jobs := make(chan int)
	var wg sync.WaitGroup
	n := t.request.Options.ProbeConcurrency
	if n > len(indices) {
		n = len(indices)
	}
	for w := 0; w < n; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				if t.ctx.Err() != nil {
					return
				}
				r, warnings := fn(t.ctx, t.result(i))
				t.update(func(s *Snapshot) { s.Results[i] = r; s.Completed++; s.Warnings = appendUnique(s.Warnings, warnings...) })
			}
		}()
	}
dispatch:
	for _, i := range indices {
		select {
		case jobs <- i:
		case <-t.ctx.Done():
			break dispatch
		}
	}
	close(jobs)
	wg.Wait()
}
