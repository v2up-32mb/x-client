package ipopt

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/netip"
	"net/url"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type dialFunc func(context.Context, string, string) (net.Conn, error)

// Dependencies are internal and injectable by package tests. The public JSON
// API never exposes a private-address bypass, custom trust roots, or fake probe.
type networkProbe struct {
	dial    dialFunc
	now     func() time.Time
	baseTLS *tls.Config
}

func newNetworkProbe() *networkProbe {
	return &networkProbe{dial: guardedDial, now: time.Now, baseTLS: &tls.Config{MinVersion: tls.VersionTLS12}}
}

type pinnedSession struct {
	client   *http.Client
	cancel   context.CancelFunc
	mu       sync.Mutex
	tlsMS    float64
	used     bool
	fallback bool
	enabled  bool
}

func (s *pinnedSession) echStatus() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.enabled {
		return "disabled"
	}
	if s.fallback || !s.used {
		return "fallback"
	}
	return "used"
}

func (s *pinnedSession) close() { s.cancel(); s.client.CloseIdleConnections() }

func (p *networkProbe) session(parent context.Context, c Candidate, u *url.URL, o Options, ech []byte) *pinnedSession {
	owner, cancel := context.WithCancel(parent)
	s := &pinnedSession{cancel: cancel, enabled: o.EnableECH, fallback: o.EnableECH && len(ech) == 0}
	tr := &http.Transport{Proxy: nil, DisableCompression: true, MaxIdleConns: 1, MaxIdleConnsPerHost: 1, MaxConnsPerHost: 1,
		IdleConnTimeout: time.Second, ResponseHeaderTimeout: probeTimeout, MaxResponseHeaderBytes: 32 << 10,
		// Keep HTTP/1.1 explicit so request timing and connection reuse have a
		// consistent interpretation, rather than implicitly multiplexing h2.
		ForceAttemptHTTP2: false, TLSNextProto: map[string]func(string, *tls.Conn) http.RoundTripper{},
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return bindDial(owner, p.dial, ctx, network, c.Address)
		},
	}
	tr.DialTLSContext = func(ctx context.Context, network, _ string) (net.Conn, error) {
		ctx, done := context.WithTimeout(ctx, probeTimeout)
		stop := context.AfterFunc(owner, done)
		defer stop()
		defer done()
		dialTLS := func(config []byte) (*tls.Conn, error) {
			raw, err := bindDial(owner, p.dial, ctx, network, c.Address)
			if err != nil {
				return nil, err
			}
			cfg := p.baseTLS.Clone()
			cfg.InsecureSkipVerify = false
			cfg.ServerName = u.Hostname()
			cfg.NextProtos = []string{"http/1.1"}
			cfg.EncryptedClientHelloConfigList = config
			if len(config) > 0 {
				cfg.MinVersion = tls.VersionTLS13
			}
			conn := tls.Client(raw, cfg)
			start := p.now()
			if err = conn.HandshakeContext(ctx); err != nil {
				_ = raw.Close()
				return nil, err
			}
			s.mu.Lock()
			s.tlsMS = milliseconds(p.now().Sub(start))
			if conn.ConnectionState().ECHAccepted {
				s.used = true
			}
			s.mu.Unlock()
			return conn, nil
		}
		conn, err := dialTLS(ech)
		if err != nil && len(ech) > 0 && ctx.Err() == nil {
			var reject *tls.ECHRejectionError
			if errors.As(err, &reject) || strings.Contains(strings.ToLower(err.Error()), "ech") {
				s.mu.Lock()
				s.fallback = true
				s.mu.Unlock()
				// A fresh verified handshake, never InsecureSkipVerify. No
				// goroutine is left waiting on an uncancellable shared manager.
				return dialTLS(nil)
			}
		}
		return conn, err
	}
	s.client = &http.Client{Transport: tr, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		// Even same-origin redirects can change the measured resource; first
		// version rejects all redirects (stricter than cross-origin minimum).
		return fmt.Errorf("测速禁止重定向")
	}}
	return s
}

func targetURL(o Options) *url.URL {
	host := o.TargetHost
	if o.TargetPort != 443 {
		host = net.JoinHostPort(host, portString(o.TargetPort))
	}
	return &url.URL{Scheme: "https", Host: host, Path: "/cdn-cgi/trace"}
}

func milliseconds(d time.Duration) float64 { return float64(d) / float64(time.Millisecond) }

func requestTrace(ctx context.Context, s *pinnedSession, u *url.URL, now func() time.Time) (string, float64, bool, error) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	var reused atomic.Bool
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return "", 0, false, err
	}
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Cache-Control", "no-cache")
	// Callbacks execute before Do returns; do not time only a TCP handshake.
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), &httptrace.ClientTrace{GotConn: func(info httptrace.GotConnInfo) { reused.Store(info.Reused) }}))
	start := now()
	resp, err := s.client.Do(req)
	if err != nil {
		return "", 0, reused.Load(), err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", 0, reused.Load(), fmt.Errorf("目标trace返回HTTP %d（不代表WS/认证成功）", resp.StatusCode)
	}
	if enc := resp.Header.Get("Content-Encoding"); enc != "" && enc != "identity" {
		return "", 0, reused.Load(), fmt.Errorf("trace返回压缩正文")
	}
	body, err := readLimited(resp.Body, 16<<10)
	if err != nil {
		return "", 0, reused.Load(), err
	}
	elapsed := milliseconds(now().Sub(start))
	colo, err := parseTrace(resp.Header, body)
	return colo, elapsed, reused.Load(), err
}

func validColo(s string) bool {
	if len(s) != 3 {
		return false
	}
	for _, c := range s {
		if c < 'A' || c > 'Z' {
			return false
		}
	}
	return true
}

func parseTrace(header http.Header, body []byte) (string, error) {
	if isHTML(header, body) {
		return "", fmt.Errorf("目标不支持Cloudflare trace（HTML），待验证/不适用")
	}
	fields := map[string]string{}
	for _, line := range strings.Split(string(body), "\n") {
		key, val, ok := strings.Cut(strings.TrimSpace(line), "=")
		if ok {
			fields[key] = val
		}
	}
	_, ipErr := netip.ParseAddr(fields["ip"])
	if ipErr != nil || fields["h"] == "" {
		return "", fmt.Errorf("目标不支持Cloudflare trace，待验证/不适用")
	}
	colo := fields["colo"]
	if !validColo(colo) {
		parts := strings.Split(header.Get("CF-RAY"), "-")
		if len(parts) > 1 {
			colo = strings.ToUpper(parts[len(parts)-1])
		}
	}
	if !validColo(colo) {
		return "", fmt.Errorf("trace缺少有效colo/CF-RAY机房代码，待验证")
	}
	return colo, nil
}

func (p *networkProbe) connectivity(ctx context.Context, r Result, o Options, ech []byte) (Result, []string) {
	u := targetURL(o)
	s := p.session(ctx, r.Candidate, u, o, ech)
	defer s.close()
	colo, _, _, err := requestTrace(ctx, s, u, p.now)
	r.TestedAt = p.now().UnixMilli()
	r.ECHStatus = s.echStatus()
	s.mu.Lock()
	r.TLSMS = s.tlsMS
	s.mu.Unlock()
	if err != nil {
		r.Status = "failed"
		r.Error = "TCP/TLS/CF trace检查失败: " + err.Error()
	} else {
		r.Status = "valid"
		r.Colo = colo
		r.Error = ""
	}
	warnings := []string{}
	if o.EnableECH && r.ECHStatus == "fallback" {
		warnings = append(warnings, r.Address+": ECH未使用或被拒绝，回退标准TLS（证书仍校验）")
	}
	return r, warnings
}

func (p *networkProbe) stability(ctx context.Context, r Result) (Result, []string) {
	times := []float64{}
	for i := 0; i < 10; i++ {
		if ctx.Err() != nil {
			break
		}
		attempt, cancel := context.WithTimeout(ctx, probeTimeout)
		start := p.now()
		r.TCPAttempts++
		conn, err := p.dial(attempt, "tcp", r.Address)
		if err == nil {
			times = append(times, milliseconds(p.now().Sub(start)))
			r.TCPSuccess++
			_ = conn.Close()
		}
		cancel()
	}
	if len(times) > 0 {
		sort.Float64s(times)
		r.TCPMinMS, r.TCPMaxMS = times[0], times[len(times)-1]
		for _, v := range times {
			r.TCPAvgMS += v
		}
		r.TCPAvgMS /= float64(len(times))
	} else if ctx.Err() == nil {
		r.Status = "failed"
		r.Error = "10次独立TCP连接全部失败"
	}
	return r, []string{}
}

func (p *networkProbe) latency(ctx context.Context, r Result, o Options, ech []byte) (Result, []string) {
	u := targetURL(o)
	s := p.session(ctx, r.Candidate, u, o, ech)
	defer s.close()
	warnings := []string{}
	// Warm the connection with a validated trace; the following five requests
	// are measured independently. If reuse fails we explicitly report total
	// request latency instead of pretending it is pure established-stream RTT.
	if _, _, _, err := requestTrace(ctx, s, u, p.now); err != nil {
		r.Status = "failed"
		r.Error = "HTTP复查预热失败: " + err.Error()
		r.TestedAt = p.now().UnixMilli()
		r.ECHStatus = s.echStatus()
		return r, warnings
	}
	times := []float64{}
	reuseAll := true
	for i := 0; i < 5; i++ {
		if ctx.Err() != nil {
			break
		}
		r.HTTPAttempts++
		colo, ms, reused, err := requestTrace(ctx, s, u, p.now)
		if !reused {
			reuseAll = false
		}
		if err != nil {
			r.Status = "failed"
			r.Error = "HTTP复查失败: " + err.Error()
			continue
		}
		r.Colo = colo
		r.HTTPSuccess++
		times = append(times, ms)
	}
	if !reuseAll {
		warnings = append(warnings, r.Address+": HTTP连接未全程复用，http_median_ms为含重连的总请求延迟，不是纯RTT")
	}
	if r.HTTPSuccess == 0 && ctx.Err() == nil {
		r.Status = "failed"
	}
	if len(times) > 0 {
		sort.Float64s(times)
		n := len(times)
		r.HTTPMedianMS = times[n/2]
		if n%2 == 0 {
			r.HTTPMedianMS = (times[n/2-1] + times[n/2]) / 2
		}
		// Jitter is max-min across successful complete HTTP request durations.
		r.HTTPJitterMS = times[n-1] - times[0]
	}
	if s.echStatus() == "fallback" || r.ECHStatus == "fallback" {
		r.ECHStatus = "fallback"
	} else {
		r.ECHStatus = s.echStatus()
	}
	if o.EnableECH && r.ECHStatus == "fallback" {
		warnings = append(warnings, r.Address+": HTTP复查未全程使用ECH，已记录fallback")
	}
	r.TestedAt = p.now().UnixMilli()
	return r, warnings
}
