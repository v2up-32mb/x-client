// Package ipopt implements bounded, foreground preferred-IP tasks. It is
// independent of the VPN backend; callers must stop VPNs before starting work.
package ipopt

import "time"

const (
	maxSourceBytes          = 2 << 20
	maxListLines            = 10000
	maxCandidates           = 4096
	maxDownloadBytes        = 20 << 20
	maxManualDownloadBytes  = 50 << 20
	manualDownloadURL       = "https://speed.cloudflare.com/__down?bytes=52428800"
	maxTotalDownloadBytes   = 100 << 20
	defaultDownloadURL      = "https://speed.cloudflare.com/__down?bytes=20971520"
	probeTimeout            = 5 * time.Second
	tcpDialTimeout          = 2 * time.Second
	httpProbeTimeout        = 3 * time.Second
	tcpProbeCount           = 4
	httpProbeCount          = 3
	defaultProbeConcurrency = 16
	maxProbeConcurrency     = 64
)

type Candidate struct {
	Address     string   `json:"address"`
	IP          string   `json:"ip"`
	Port        int      `json:"port"`
	Comment     string   `json:"comment"`
	SourceID    string   `json:"source_id"`
	SourceKind  string   `json:"source_kind"`
	SourceIDs   []string `json:"source_ids"`
	SourceKinds []string `json:"source_kinds"`
}

type Prefix struct {
	CIDR       string `json:"cidr"`
	Port       int    `json:"port"`
	SourceID   string `json:"source_id"`
	SourceKind string `json:"source_kind"`
}

type LineError struct {
	Line    int    `json:"line"`
	Message string `json:"message"`
	Text    string `json:"text"`
}

type ParseResponse struct {
	Candidates []Candidate `json:"candidates"`
	Errors     []LineError `json:"errors"`
}

type Source struct {
	ID   string `json:"id"`
	URL  string `json:"url"`
	Kind string `json:"kind"`
}

type Options struct {
	TargetHost         string `json:"target_host"`
	TargetPort         int    `json:"target_port"`
	Protocol           string `json:"protocol"`
	EnableECH          bool   `json:"enable_ech"`
	ECHDomain          string `json:"ech_domain"`
	DNSServer          string `json:"dns_server"`
	SampleLimit        int    `json:"sample_limit"`
	ProbeConcurrency   int    `json:"probe_concurrency"`
	RecommendCount     int    `json:"recommend_count"`
	DownloadTop        int    `json:"download_top"`
	DownloadBytesPerIP int64  `json:"download_bytes_per_ip"`
	DownloadSeconds    int    `json:"download_seconds"`
	DownloadURL        string `json:"download_url"`
}

func defaultOptions() Options {
	return Options{TargetPort: 443, Protocol: "gcm", ECHDomain: "cloudflare-ech.com",
		DNSServer: "https://doh.pub/dns-query", SampleLimit: 256, ProbeConcurrency: defaultProbeConcurrency,
		RecommendCount: 3, DownloadTop: 5, DownloadBytesPerIP: maxDownloadBytes,
		DownloadSeconds: 5, DownloadURL: defaultDownloadURL}
}

type Request struct {
	Operation        string      `json:"operation"`
	SessionID        string      `json:"session_id"`
	RefreshECH       bool        `json:"refresh_ech"`
	NewDownloadRound bool        `json:"new_download_round"`
	DownloadRoundID  string      `json:"download_round_id"`
	ProbeRole        string      `json:"probe_role"`
	NetworkID        string      `json:"network_id"`
	Source           Source      `json:"source"`
	Candidates       []Candidate `json:"candidates"`
	Prefixes         []Prefix    `json:"prefixes"`
	Options          Options     `json:"options"`
}

// Result keeps legacy http_* JSON keys for HTTPS trace measurements. Callers
// should label those counters and timings HTTPS, not cleartext HTTP.
type Result struct {
	Candidate
	ProbeComplete     bool        `json:"probe_complete"`
	ScoreReady        bool        `json:"score_ready"`
	ScoreVersion      int         `json:"score_version"`
	Score             float64     `json:"score"`
	ScoreBreakdown    []ScorePart `json:"score_breakdown"`
	ProbePhase        string      `json:"probe_phase"`
	NAReason          string      `json:"na_reason"`
	ResultKind        string      `json:"result_kind"`
	TargetKey         string      `json:"target_key"`
	DownloadECHStatus string      `json:"download_ech_status"`
	Status            string      `json:"status"`
	Error             string      `json:"error"`
	Colo              string      `json:"colo"`
	TCPMeasured       bool        `json:"tcp_measured"`
	HTTPMeasured      bool        `json:"http_measured"`
	TCPSuccess        int         `json:"tcp_success"`
	TCPAttempts       int         `json:"tcp_attempts"`
	TCPMinMS          float64     `json:"tcp_min_ms"`
	TCPMaxMS          float64     `json:"tcp_max_ms"`
	TCPAvgMS          float64     `json:"tcp_avg_ms"`
	TLSMS             float64     `json:"tls_ms"`
	HTTPSuccess       int         `json:"http_success"`
	HTTPAttempts      int         `json:"http_attempts"`
	HTTPMedianMS      float64     `json:"http_median_ms"`
	HTTPJitterMS      float64     `json:"http_jitter_ms"`
	DownloadMiBS      float64     `json:"download_mib_s"`
	DownloadPeakMiBS  float64     `json:"download_peak_mib_s"`
	DownloadBytes     int64       `json:"download_bytes"`
	DownloadSeconds   float64     `json:"download_seconds"`
	DownloadState     string      `json:"download_state"`
	ECHStatus         string      `json:"ech_status"`
	Recommended       bool        `json:"recommended"`
	TestedAt          int64       `json:"tested_at"`
}

func newResult(c Candidate, ech bool) Result {
	s := "disabled"
	if ech {
		s = "fallback"
	}
	return Result{Candidate: c, Status: "untested", DownloadState: "untested", ECHStatus: s, DownloadECHStatus: s, ScoreBreakdown: scoreParts(), NAReason: "尚未执行"}
}

type ScorePart struct {
	Key       string  `json:"key"`
	Points    float64 `json:"points"`
	MaxPoints float64 `json:"max_points"`
}

// No ECHConfig bytes cross the JSON/gomobile boundary.
type ECHPreparation struct {
	Key        string `json:"key"`
	State      string `json:"state"` // disabled, ready, fallback
	PreparedAt int64  `json:"prepared_at"`
	ExpiresAt  int64  `json:"expires_at"` // 0: fallback is sticky until explicit refresh
	CacheHit   bool   `json:"cache_hit"`
	Error      string `json:"error"`
}

type Snapshot struct {
	WorkerDone             bool           `json:"worker_done"`
	SessionID              string         `json:"session_id"`
	TargetKey              string         `json:"target_key"`
	ProbeRole              string         `json:"probe_role"`
	ECH                    ECHPreparation `json:"ech"`
	DownloadRoundID        string         `json:"download_round_id"`
	DownloadRoundLimit     int64          `json:"download_round_limit"`
	DownloadRoundUsed      int64          `json:"download_round_used"`
	DownloadRoundRemaining int64          `json:"download_round_remaining"`
	DownloadReservedBytes  int64          `json:"download_reserved_bytes"`

	ID            string      `json:"id"`
	Operation     string      `json:"operation"`
	State         string      `json:"state"`
	Phase         string      `json:"phase"`
	Completed     int         `json:"completed"`
	Total         int         `json:"total"`
	NetworkID     string      `json:"network_id"`
	StartedAt     int64       `json:"started_at"`
	UpdatedAt     int64       `json:"updated_at"`
	DownloadBytes int64       `json:"download_bytes"`
	Warnings      []string    `json:"warnings"`
	Error         string      `json:"error"`
	Candidates    []Candidate `json:"candidates"`
	Prefixes      []Prefix    `json:"prefixes"`
	Errors        []LineError `json:"errors"`
	Results       []Result    `json:"results"`
}
