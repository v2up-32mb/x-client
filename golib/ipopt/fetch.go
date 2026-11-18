package ipopt

import (
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

type fetchOutput struct {
	candidates []Candidate
	prefixes   []Prefix
	errors     []LineError
}

// rt is injectable for offline tests. Production always uses guarded dialing.
func fetchSource(ctx context.Context, s Source, rt http.RoundTripper) (fetchOutput, error) {
	out := fetchOutput{[]Candidate{}, []Prefix{}, []LineError{}}
	if err := validateSource(s); err != nil {
		return out, err
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if rt == nil {
		rt = freshTransport{}
	}
	client := &http.Client{Transport: rt, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if err := safeRedirect(req, via); err != nil {
			return err
		}
		if s.Kind != "list" && (req.URL.Host != "www.cloudflare.com" || req.URL.Scheme != "https" || req.URL.Path != via[0].URL.Path) {
			return fmt.Errorf("官方来源禁止重定向到其他来源")
		}
		return nil
	}}
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.URL, nil)
	if err != nil {
		return out, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "text/plain, text/*, */*")
	req.Header.Set("Accept-Encoding", "identity")
	resp, err := client.Do(req)
	if err != nil {
		return out, fmt.Errorf("来源抓取失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return out, fmt.Errorf("来源返回HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength > maxSourceBytes {
		return out, fmt.Errorf("来源超过2MiB")
	}
	var bodyReader io.Reader = resp.Body
	enc := strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))
	if enc == "gzip" {
		gz, err := gzip.NewReader(resp.Body)
		if err != nil {
			return out, fmt.Errorf("来源 gzip 解压失败: %w", err)
		}
		defer gz.Close()
		bodyReader = gz
	} else if enc != "" && enc != "identity" {
		return out, fmt.Errorf("来源返回了不支持的压缩正文: %s", enc)
	}
	body, err := readLimited(bodyReader, maxSourceBytes)
	if err != nil {
		return out, err
	}
	if isHTML(resp.Header, body) || !utf8.Valid(body) {
		return out, fmt.Errorf("来源不是UTF-8 IP列表（HTML或无效正文）")
	}
	if s.Kind == "list" {
		r, e := parseList(string(body), s.ID, "third_party", true)
		if e != nil {
			return out, e
		}
		// Keep every rejected line visible; never turn a private address into
		// an internet probe simply because it was supplied by a remote list.
		out.candidates, out.errors = r.Candidates, r.Errors
	} else {
		out.prefixes, out.errors = parsePrefixes(string(body), s)
	}
	if len(out.candidates)+len(out.prefixes) == 0 {
		return out, fmt.Errorf("来源未包含有效候选；请检查错误行")
	}
	return out, nil
}
