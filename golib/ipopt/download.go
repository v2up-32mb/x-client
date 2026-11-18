package ipopt

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

func (p *networkProbe) download(ctx context.Context, r Result, o Options, ech []byte, budget int64, progress func(int64, float64)) (result Result, warnings []string) {
	warnings = []string{}
	r.DownloadState = "failed"
	if budget <= 0 {
		return r, []string{r.Address + ": 下载总预算已耗尽"}
	}
	limit := o.DownloadBytesPerIP
	if budget < limit {
		limit = budget
	}
	ctx, cancel := context.WithTimeout(ctx, time.Duration(o.DownloadSeconds)*time.Second)
	defer cancel()
	u, err := url.Parse(o.DownloadURL)
	if err != nil {
		return r, []string{r.Address + ": 无效下载URL"}
	}
	s := p.session(ctx, r.Candidate, u, o, ech)
	defer s.close()
	defer func() { result.DownloadECHStatus = s.echStatus() }()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return r, []string{r.Address + ": " + err.Error()}
	}
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := s.client.Do(req)
	if err != nil {
		return r, []string{r.Address + ": 下载请求失败: " + err.Error()}
	}
	defer resp.Body.Close()
	stop := context.AfterFunc(ctx, func() { _ = resp.Body.Close() })
	defer stop()
	if err = validateDownloadResponse(resp); err != nil {
		return r, []string{r.Address + ": " + err.Error()}
	}
	sample := measureBody(ctx, resp.Body, resp.Header, limit, resp.ContentLength, p.now, progress)
	r.DownloadBytes = sample.bytes
	r.DownloadSeconds = sample.seconds
	r.DownloadMiBS = sample.average
	r.DownloadPeakMiBS = sample.peak
	r.DownloadState = sample.state
	if sample.err != nil {
		warnings = append(warnings, r.Address+": 下载不可用: "+sample.err.Error())
	}
	if sample.state == "short" {
		warnings = append(warnings, r.Address+": 下载采样不足1秒，低置信度，不自动推荐")
	}
	if o.EnableECH && s.echStatus() == "fallback" {
		warnings = append(warnings, r.Address+": 下载站使用标准TLS回退；目标域名ECH状态见结果")
	}
	return r, warnings
}

func validateDownloadResponse(resp *http.Response) error {
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || resp.StatusCode == 204 {
		return fmt.Errorf("HTTPS下载返回状态码 %d", resp.StatusCode)
	}
	if enc := resp.Header.Get("Content-Encoding"); enc != "" && enc != "identity" {
		return fmt.Errorf("下载禁止压缩正文")
	}
	ct := strings.ToLower(strings.TrimSpace(strings.Split(resp.Header.Get("Content-Type"), ";")[0]))
	isBinary := ct == "application/octet-stream" || ct == "application/binary" || ct == "application/x-binary" ||
		ct == "application/zip" || ct == "application/x-download" || ct == "binary/octet-stream" ||
		strings.HasPrefix(ct, "image/") || strings.HasPrefix(ct, "video/") || strings.HasPrefix(ct, "audio/")
	if !isBinary {
		return fmt.Errorf("下载需要二进制正文，拒绝错误页或未知类型: %s", ct)
	}
	if resp.ContentLength == 0 {
		return fmt.Errorf("下载正文为空")
	}
	return nil
}

type bodySample struct {
	bytes   int64
	seconds float64
	average float64
	peak    float64
	state   string
	err     error
}

// Body accounting is exclusive of DNS/TCP/TLS/header time. Reads cannot exceed
// the remaining task body budget. Every consumed byte, including a rejected
// error-page prefix, is charged to that budget; failed samples have no speed.
func measureBody(ctx context.Context, body io.Reader, header http.Header, limit, expected int64, now func() time.Time, progress func(int64, float64)) bodySample {
	s := bodySample{state: "failed"}
	start := now()
	windowStart := start
	var windowBytes int64
	prefix := []byte{}
	buf := make([]byte, 32<<10)
	for s.bytes < limit {
		if ctx.Err() != nil {
			s.err = ctx.Err()
			break
		}
		want := int64(len(buf))
		if left := limit - s.bytes; want > left {
			want = left
		}
		n, err := body.Read(buf[:int(want)])
		if n > 0 {
			s.bytes += int64(n)
			at := now()
			if progress != nil {
				// Report before validation and even if Read returned bytes along
				// with a cancellation/error. This is actual consumed body data.
				progress(s.bytes, at.Sub(start).Seconds())
			}
			windowBytes += int64(n)
			if len(prefix) < 512 {
				take := n
				if take > 512-len(prefix) {
					take = 512 - len(prefix)
				}
				prefix = append(prefix, buf[:take]...)
			}
			if isHTML(header, prefix) || errorBodyPrefix(prefix) {
				s.err = fmt.Errorf("下载正文为HTML/JSON/XML错误页")
				break
			}
			if seconds := at.Sub(windowStart).Seconds(); seconds >= 0.5 {
				speed := float64(windowBytes) / (1 << 20) / seconds
				if speed > s.peak {
					s.peak = speed
				}
				windowStart = at
				windowBytes = 0
			}
		}
		if err != nil {
			if err != io.EOF {
				s.err = err
				if ctx.Err() == context.DeadlineExceeded {
					s.err = context.DeadlineExceeded
				}
			} else if expected >= 0 && s.bytes < expected && s.bytes < limit {
				s.err = io.ErrUnexpectedEOF
			}
			break
		}
		if n == 0 {
			s.err = io.ErrNoProgress
			break
		}
	}
	s.seconds = now().Sub(start).Seconds()
	if progress != nil {
		progress(s.bytes, s.seconds)
	}
	// Deadline is the normal duration cap, cancellation is never success.
	if ctx.Err() == context.Canceled {
		s.err = context.Canceled
	}
	if s.bytes == 0 || s.seconds <= 0 {
		if s.err == nil {
			s.err = fmt.Errorf("没有可计时的下载样本")
		}
		return s
	}
	if s.err != nil && !errors.Is(s.err, context.DeadlineExceeded) {
		s.peak = 0
		return s
	}
	s.err = nil
	s.average = float64(s.bytes) / (1 << 20) / s.seconds
	s.state = "ok"
	if s.seconds < 1 {
		s.state = "short"
	}
	return s
}

func errorBodyPrefix(prefix []byte) bool {
	s := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(string(prefix), "\ufeff")))
	return strings.HasPrefix(s, "{") || strings.HasPrefix(s, "[") || strings.HasPrefix(s, "<") ||
		strings.HasPrefix(s, "error") || strings.HasPrefix(s, "access denied") || strings.HasPrefix(s, "forbidden") || strings.HasPrefix(s, "unauthorized")
}
