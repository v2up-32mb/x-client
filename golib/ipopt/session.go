package ipopt

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	shareddns "github.com/v2up-32mb/xshared/dns"
)

const maxECHEntries = 8
const maxECHCacheBytes = 512 << 10

type scanSession struct {
	id, network, reference string
	candidates             []Candidate
	members                map[string]Candidate
	// Only reference completion/eligibility is retained. Other-target proofs
	// exist solely in the three bounded task snapshots (at most 32 IP each).
	completed map[string]bool
	eligible  map[string]bool
	ech       map[string]echEntry
}

type echEntry struct {
	config []byte
	info   ECHPreparation
}

type downloadLedger struct {
	mu   sync.Mutex
	id   string
	used int64
}

func (l *downloadLedger) fill(s *Snapshot) { l.mu.Lock(); defer l.mu.Unlock(); l.fillLocked(s) }
func (l *downloadLedger) fillLocked(s *Snapshot) {
	s.DownloadRoundID, s.DownloadRoundLimit, s.DownloadRoundUsed = l.id, 0, l.used
	s.DownloadRoundRemaining = 0
}

func opaqueID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

func digest(v any) string {
	b, _ := json.Marshal(v)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func echServers(o Options) []string {
	servers := []string{o.DNSServer}
	if o.DNSServer == "" {
		servers = append([]string{}, shareddns.DefaultDoHServers...)
	}
	return appendUnique(servers, "https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query")
}

func echKey(network string, o Options) string {
	return digest([]any{network, o.EnableECH, o.ECHDomain, echServers(o)})
}
func targetKey(o Options) string {
	return digest([]any{o.TargetHost, o.TargetPort, o.Protocol, o.EnableECH, o.ECHDomain, echServers(o)})
}

// Called under m.mu, before admitting a worker. No public path can manufacture
// eligibility, inject private test transports, or silently recreate a ledger.
func (m *Manager) validateSessionRequest(r *Request) error {
	if r.Operation != "prepare" && r.Operation != "probe" && r.Operation != "download" {
		return nil
	}
	if m.ledger == nil {
		if m.ledgerInitialized || r.DownloadRoundID != "" {
			return fmt.Errorf("ledger_lost: 下载账本已失效，禁止自动恢复额度")
		}
		if r.Operation != "prepare" || r.SessionID != "" {
			return fmt.Errorf("unknown_session: 会话或账本不存在")
		}
	} else if r.DownloadRoundID != m.ledger.id {
		return fmt.Errorf("ledger_mismatch: 必须提供当前download_round_id，不能自动清账")
	}
	if r.SessionID == "" {
		if r.Operation != "prepare" {
			return fmt.Errorf("unknown_session: 缺少session_id")
		}
		return nil
	}
	s := m.session
	if s == nil || s.id != r.SessionID {
		return fmt.Errorf("unknown_session: 未知或已替换的session_id")
	}
	if s.network != r.NetworkID {
		return fmt.Errorf("network_mismatch: 会话网络已改变，需要显式新扫描（保留下载账本）")
	}
	if r.Operation == "prepare" {
		return nil
	}
	key := targetKey(r.Options)
	if (r.ProbeRole == "reference" || r.Operation == "download") && key != s.reference {
		return fmt.Errorf("target_mismatch: reference参数与冻结会话不符")
	}
	if r.ProbeRole == "validation" && key == s.reference {
		return fmt.Errorf("target_mismatch: 同参考目标应复用reference证明")
	}
	for i, c := range r.Candidates {
		stored, ok := s.members[c.Address]
		if !ok {
			return fmt.Errorf("candidate_mismatch: 候选IP/port不属于冻结会话: %s", c.Address)
		}
		r.Candidates[i] = stored
		if r.Operation == "probe" && r.ProbeRole == "reference" && s.completed[c.Address] {
			return fmt.Errorf("already_complete: 不能重扫已完成reference候选: %s", c.Address)
		}
		if r.Operation == "download" && !s.eligible[c.Address] {
			return fmt.Errorf("reference_required: 下载需要本会话完整合格reference检查")
		}
	}
	if r.Options.EnableECH {
		e, ok := s.ech[echKey(r.NetworkID, r.Options)]
		if !ok || (e.info.ExpiresAt != 0 && m.now().UnixMilli() >= e.info.ExpiresAt) {
			return fmt.Errorf("prepare_required: ECH配置缺失或过期，请统一prepare（不清队列/账本）")
		}
	}
	return nil
}

func (m *Manager) prepareEntry(ctx context.Context, r Request, s *scanSession) (echEntry, []string, error) {
	key := echKey(r.NetworkID, r.Options)
	if !r.Options.EnableECH {
		return echEntry{info: ECHPreparation{Key: key, State: "disabled"}}, []string{}, nil
	}
	if e, ok := s.ech[key]; ok && !r.RefreshECH && (e.info.ExpiresAt == 0 || m.now().UnixMilli() < e.info.ExpiresAt) {
		e.info.CacheHit = true
		return e, []string{}, nil
	}
	var data []byte
	var warnings []string
	var err error
	ttl := 5 * time.Minute // injected/unknown TTL only; production returns DNS TTL
	if m.ops.prepareTTL != nil {
		data, ttl, warnings, err = m.ops.prepareTTL(ctx, r.Options)
	} else {
		data, warnings, err = m.ops.prepare(ctx, r.Options)
	}
	if ctx.Err() != nil {
		return echEntry{}, nil, ctx.Err()
	}
	if errors.Is(err, context.Canceled) {
		return echEntry{}, nil, err
	}
	now := m.now()
	e := echEntry{info: ECHPreparation{Key: key, State: "fallback", PreparedAt: now.UnixMilli()}}
	if err == nil && len(data) > 0 {
		if len(data) > maxECHCacheBytes {
			return echEntry{}, nil, fmt.Errorf("ECH配置超过512KiB限制")
		}
		if ttl > 10*time.Minute {
			ttl = 10 * time.Minute
		}
		if ttl < 0 {
			ttl = 5 * time.Minute
		}
		e.config = append([]byte{}, data...)
		e.info.State, e.info.ExpiresAt = "ready", now.Add(ttl).UnixMilli()
	} else {
		e.info.Error = "未获取ECH配置，使用验证证书的标准TLS"
		if err != nil {
			e.info.Error = err.Error()
		}
		warnings = appendUnique(warnings, "ECH准备失败，明确回退标准TLS: "+e.info.Error)
	}
	return e, warnings, nil
}

func storeECH(s *scanSession, e echEntry) {
	if e.info.State == "disabled" {
		return
	}
	e.info.CacheHit = false
	delete(s.ech, e.info.Key)
	for {
		bytes := len(e.config)
		var oldest string
		for k, v := range s.ech {
			bytes += len(v.config)
			if oldest == "" || v.info.PreparedAt < s.ech[oldest].info.PreparedAt || (v.info.PreparedAt == s.ech[oldest].info.PreparedAt && k < oldest) {
				oldest = k
			}
		}
		if len(s.ech) < maxECHEntries && bytes <= maxECHCacheBytes {
			break
		}
		delete(s.ech, oldest)
	}
	s.ech[e.info.Key] = e
}

func (m *Manager) runPrepare(t *task) error {
	r := t.request
	s := t.session
	if r.SessionID == "" {
		cs, err := materialize(r)
		if err != nil {
			return err
		}
		id, err := opaqueID()
		if err != nil {
			return err
		}
		s = &scanSession{id: id, network: r.NetworkID, reference: targetKey(r.Options), candidates: cs,
			members: map[string]Candidate{}, completed: map[string]bool{}, eligible: map[string]bool{}, ech: map[string]echEntry{}}
		for _, c := range cs {
			s.members[c.Address] = c
		}
	}
	e, warnings, err := m.prepareEntry(t.ctx, r, s)
	if err != nil {
		return err
	}
	ledger := t.ledger
	if ledger == nil || r.NewDownloadRound {
		id, err := opaqueID()
		if err != nil {
			return err
		}
		ledger = &downloadLedger{id: id}
	}
	// Commit the entire preparation atomically against cancellation. Failed or
	// cancelled prepare leaves the previous session/cache/ledger untouched.
	m.mu.Lock()
	defer m.mu.Unlock()
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.ctx.Err() != nil || terminal(t.snapshot.State) {
		return context.Canceled
	}
	storeECH(s, e)
	m.session, m.ledger, t.session, t.ledger = s, ledger, s, ledger
	m.ledgerInitialized = true
	t.snapshot.SessionID = s.id
	t.snapshot.Candidates = s.candidates
	t.snapshot.ECH = e.info
	t.snapshot.Total, t.snapshot.Completed = len(s.candidates), len(s.candidates)
	t.snapshot.Warnings = appendUnique(t.snapshot.Warnings, warnings...)
	if len(r.Prefixes) > r.Options.SampleLimit {
		t.snapshot.Warnings = appendUnique(t.snapshot.Warnings, "地址段多于抽样上限，本轮只覆盖前部分地址段")
	}
	ledger.fill(&t.snapshot)
	return nil
}
