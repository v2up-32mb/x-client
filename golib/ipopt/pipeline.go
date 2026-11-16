package ipopt

import (
	"context"
	"sync"
)

func (m *Manager) runProbe(t *task) error {
	r := t.request
	t.update(func(s *Snapshot) {
		s.Total = len(r.Candidates)
		s.Candidates = append([]Candidate{}, r.Candidates...)
		s.Warnings = append(s.Warnings, "TCP成功率不是ICMP丢包；HTTPS trace成功不代表WebSocket/认证成功；本次probe不下载/不推荐")
		for _, c := range r.Candidates {
			v := newResult(c, r.Options.EnableECH)
			v.TargetKey, v.ResultKind, v.ProbePhase = targetKey(r.Options), r.ProbeRole, "connectivity"
			s.Results = append(s.Results, v)
		}
	})
	if t.ctx.Err() != nil {
		return t.ctx.Err()
	}
	// Admission already limits this batch to N. Each goroutine owns one IP
	// end-to-end; no all-IP connectivity/stability/latency barrier exists.
	var wg sync.WaitGroup
	for i := range r.Candidates {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			v := t.result(i)
			stages := []struct {
				phase string
				fn    func(context.Context, Result) (Result, []string)
			}{
				{"connectivity", func(ctx context.Context, v Result) (Result, []string) {
					return m.ops.connectivity(ctx, v, r.Options, t.ech)
				}},
				{"stability", m.ops.stability},
				{"latency", func(ctx context.Context, v Result) (Result, []string) { return m.ops.latency(ctx, v, r.Options, t.ech) }},
			}
			for _, stage := range stages {
				if t.ctx.Err() != nil {
					return
				}
				v.ProbePhase, v.NAReason = stage.phase, "尚未执行后续阶段"
				t.update(func(s *Snapshot) {
					partial := v
					// Even the legacy status cannot expose early valid in v2.
					partial.Status = "untested"
					s.Results[i] = partial
				})
				var warnings []string
				v, warnings = stage.fn(t.ctx, v)
				if t.ctx.Err() != nil {
					return
				}
				t.update(func(s *Snapshot) { s.Warnings = appendUnique(s.Warnings, warnings...) })
				if v.Status == "failed" {
					v.NAReason = stage.phase + "失败，后续未测项跳过"
					break
				}
			}
			if t.ctx.Err() != nil {
				return
			}
			v.ProbePhase, v.ProbeComplete, v.TestedAt = "done", true, t.now().UnixMilli()
			if v.Status == "valid" {
				v.NAReason = ""
			}
			scoreResult(&v)
			// Completion and Go's reference proof become visible together; the
			// cancellation guard prevents a late worker reviving eligibility.
			t.update(func(s *Snapshot) {
				s.Results[i] = v
				s.Completed++
				if r.ProbeRole == "reference" {
					t.session.completed[v.Address] = true
					t.session.eligible[v.Address] = v.Status == "valid" && v.ScoreReady
				}
			})
		}(i)
	}
	wg.Wait()
	return t.ctx.Err()
}

func (m *Manager) runDownloadOnly(t *task) error {
	o := t.request.Options
	// Never load a reference Result into a download worker. Returning a sample
	// cannot overwrite its certificate, ECH status, timing, score or proof.
	base := newResult(t.request.Candidates[0], o.EnableECH)
	base.ResultKind, base.NAReason = "download", "仅下载；参考成绩与证明未修改"
	t.update(func(s *Snapshot) {
		s.Phase, s.Total = "download", 1
		s.Candidates = append([]Candidate{}, t.request.Candidates...)
		s.Results = []Result{base}
	})
	if t.ctx.Err() != nil {
		return t.ctx.Err()
	}
	t.recordDownload(0, 0, 0)
	sample, warnings := m.ops.download(t.ctx, base, o, t.ech, t.snapshot.DownloadReservedBytes, func(bytes int64, seconds float64) {
		t.recordDownload(0, bytes, seconds)
	})
	base.DownloadBytes, base.DownloadSeconds = sample.DownloadBytes, sample.DownloadSeconds
	base.DownloadMiBS, base.DownloadPeakMiBS, base.DownloadState = sample.DownloadMiBS, sample.DownloadPeakMiBS, sample.DownloadState
	base.DownloadECHStatus = sample.DownloadECHStatus
	t.finishDownload(0, base, warnings)
	return t.ctx.Err()
}
