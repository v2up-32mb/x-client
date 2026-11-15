package ipopt

import (
	"encoding/json"
	"fmt"
	"io"
	"net/netip"
	"net/url"
	"strings"
)

func decodeRequest(text string) (Request, error) {
	r := Request{Options: defaultOptions()}
	if len(text) > 4<<20 || !strings.HasPrefix(strings.TrimSpace(text), "{") {
		return r, fmt.Errorf("请求必须为JSON对象且不超过4MiB")
	}
	d := json.NewDecoder(strings.NewReader(text))
	d.DisallowUnknownFields()
	if err := d.Decode(&r); err != nil {
		return r, fmt.Errorf("无效的任务JSON: %w", err)
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return r, fmt.Errorf("请求只允许一个JSON对象")
	}
	if strings.TrimSpace(r.NetworkID) == "" || len(r.NetworkID) > 256 {
		return r, fmt.Errorf("需要有效的network_id")
	}
	switch r.Operation {
	case "fetch":
		if err := validateSource(r.Source); err != nil {
			return r, err
		}
	case "test":
		if err := validateOptions(&r.Options); err != nil {
			return r, err
		}
		if len(r.Candidates) > maxCandidates || len(r.Prefixes) > maxCandidates {
			return r, fmt.Errorf("候选或地址段超过4096个限制")
		}
		if len(r.Candidates)+len(r.Prefixes) == 0 {
			return r, fmt.Errorf("没有候选IP或地址段")
		}
		cs, seen := []Candidate{}, map[string]int{}
		for _, c := range r.Candidates {
			n, err := normalizeCandidate(c)
			if err != nil {
				return r, err
			}
			if !publicIP(netip.MustParseAddr(n.IP)) {
				return r, fmt.Errorf("禁止探测非公网地址: %s", n.IP)
			}
			mergeCandidate(&cs, seen, n)
		}
		r.Candidates = cs
		for i, p := range r.Prefixes {
			n, err := netip.ParsePrefix(p.CIDR)
			if err != nil || n.Addr().Is4In6() || p.Port < 1 || p.Port > 65535 || !validKind(p.SourceKind) || p.SourceID == "" || len(p.SourceID) > 256 {
				return r, fmt.Errorf("无效的地址段: %s", p.CIDR)
			}
			// Reject wholly non-public prefixes early; individual sampled hosts
			// are checked again, including broad prefixes crossing reserved space.
			if !publicIP(n.Masked().Addr()) {
				return r, fmt.Errorf("禁止非公网地址段: %s", p.CIDR)
			}
			r.Prefixes[i].CIDR = n.Masked().String()
		}
	default:
		return r, fmt.Errorf("operation必须为fetch或test")
	}
	return r, nil
}

func validHost(host string) bool {
	if len(host) == 0 || len(host) > 253 || strings.ContainsAny(host, "/:?#@\\ \t\r\n") {
		return false
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		return publicIP(ip)
	}
	host = strings.TrimSuffix(host, ".")
	if !strings.Contains(host, ".") {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	return true
}

func validateOptions(o *Options) error {
	if !validHost(o.TargetHost) {
		return fmt.Errorf("target_host必须为不带协议、路径或端口的公网目标域名")
	}
	o.TargetHost = strings.ToLower(strings.TrimSuffix(o.TargetHost, "."))
	if o.TargetPort < 1 || o.TargetPort > 65535 {
		return fmt.Errorf("target_port必须为1..65535")
	}
	if o.Protocol != "gcm" && o.Protocol != "xtunnel" {
		return fmt.Errorf("protocol必须为gcm或xtunnel")
	}
	if o.SampleLimit < 1 || o.SampleLimit > 4096 || o.ProbeConcurrency < 1 || o.ProbeConcurrency > 32 {
		return fmt.Errorf("sample_limit为1..4096，probe_concurrency为1..32")
	}
	if o.RecommendCount < 0 || o.RecommendCount > 4096 || o.DownloadTop < 0 || o.DownloadTop > 5 {
		return fmt.Errorf("recommend_count为0..4096，download_top为0..5（0表示关闭）")
	}
	if o.DownloadBytesPerIP < 1 || o.DownloadBytesPerIP > maxDownloadBytes || o.DownloadSeconds < 1 || o.DownloadSeconds > 5 {
		return fmt.Errorf("每IP下载上限为1..20MiB和1..5秒")
	}
	if _, err := safeURL(o.DownloadURL); err != nil {
		return fmt.Errorf("download_url: %w", err)
	}
	o.ECHDomain = strings.TrimSpace(o.ECHDomain)
	if o.ECHDomain == "" {
		o.ECHDomain = defaultOptions().ECHDomain
	}
	o.DNSServer = strings.TrimSpace(o.DNSServer)
	if o.DNSServer == "" && o.Protocol == "xtunnel" {
		// Pinned xtunnel v0.3.4 default; GCM empty selects xshared's built-in list.
		o.DNSServer = "https://v.recipes/dns-query"
	}
	if o.EnableECH {
		if !validHost(o.ECHDomain) {
			return fmt.Errorf("无效的ech_domain")
		}
		if o.DNSServer != "" {
			u, err := safeURL(o.DNSServer)
			if err != nil || u.Scheme != "https" {
				return fmt.Errorf("dns_server必须为HTTPS DoH URL（GCM空值使用内置列表）")
			}
		}
	}
	return nil
}

func safeURL(raw string) (*url.URL, error) {
	if len(raw) > 4096 {
		return nil, fmt.Errorf("URL过长")
	}
	u, err := url.Parse(raw)
	if err != nil || u == nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.User != nil || u.Fragment != "" || u.Opaque != "" {
		return nil, fmt.Errorf("需要不含用户信息/片段的HTTP(S) URL")
	}
	h := u.Hostname()
	if ip, e := netip.ParseAddr(h); e == nil {
		if !publicIP(ip) {
			return nil, fmt.Errorf("URL不允许非公网地址")
		}
	} else if !validHost(h) {
		return nil, fmt.Errorf("无效的URL域名")
	}
	if u.Port() != "" {
		ap, e := netip.ParseAddrPort("1.1.1.1:" + u.Port())
		if e != nil || ap.Port() == 0 {
			return nil, fmt.Errorf("无效的URL端口")
		}
	}
	return u, nil
}

func validateSource(s Source) error {
	if s.ID == "" || len(s.ID) > 256 {
		return fmt.Errorf("无效的来源ID")
	}
	if _, err := safeURL(s.URL); err != nil {
		return err
	}
	switch s.Kind {
	case "list":
		return nil
	case "cidr4":
		if s.URL == "https://www.cloudflare.com/ips-v4" {
			return nil
		}
	case "cidr6":
		if s.URL == "https://www.cloudflare.com/ips-v6" {
			return nil
		}
	default:
		return fmt.Errorf("来源kind必须为cidr4/cidr6/list")
	}
	return fmt.Errorf("官方CIDR来源只能使用Cloudflare对应的官方URL")
}
