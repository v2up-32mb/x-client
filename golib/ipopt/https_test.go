package ipopt

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestTargetTraceURLAlwaysUsesHTTPS(t *testing.T) {
	for _, port := range []int{80, 443, 8443} {
		o := defaultOptions()
		o.TargetHost, o.TargetPort = "node.example.com", port
		u := targetURL(o)
		wantHost := o.TargetHost
		if port != 443 {
			wantHost = net.JoinHostPort(o.TargetHost, portString(port))
		}
		if u.Scheme != "https" || u.Host != wantHost || u.RequestURI() != "/cdn-cgi/trace" {
			t.Fatalf("target_port=%d: %s", port, u)
		}
	}
}

func TestCleartextDownloadRejectedBeforeNetwork(t *testing.T) {
	for _, port := range []int{80, 443, 2008} {
		t.Run(portString(port), func(t *testing.T) {
			r := testRequest(1)
			r.Options.DownloadURL = fmt.Sprintf("http://speed.example.com:%d/data?bytes=4096", port)
			for _, top := range []int{0, 5} {
				r.Options.DownloadTop = top
				if _, err := decodeRequest(requestJSON(t, r)); err == nil || !strings.Contains(err.Error(), "download_url必须为HTTPS") {
					t.Fatalf("cleartext input accepted: top=%d err=%v", top, err)
				}
			}
			// Even an internal caller bypassing JSON validation cannot dial HTTP.
			p := newNetworkProbe()
			var dials, progress atomic.Int32
			p.dial = func(context.Context, string, string) (net.Conn, error) {
				dials.Add(1)
				return nil, fmt.Errorf("unexpected dial")
			}
			got, warnings := p.download(context.Background(), newResult(r.Candidates[0], false), r.Options, nil, 4096, func(int64, float64) { progress.Add(1) })
			if dials.Load() != 0 || progress.Load() != 0 || got.DownloadState != "failed" || got.DownloadBytes != 0 || !strings.Contains(strings.Join(warnings, " "), "禁止明文HTTP请求") {
				t.Fatalf("%+v warnings=%v dials=%d progress=%d", got, warnings, dials.Load(), progress.Load())
			}
			// HTTPS is selected by scheme, including port 80; keep the URL intact.
			r.Options.DownloadURL = strings.Replace(r.Options.DownloadURL, "http://", "https://", 1)
			if got, err := decodeRequest(requestJSON(t, r)); err != nil || got.Options.DownloadURL != r.Options.DownloadURL {
				t.Fatalf("HTTPS nonstandard URL changed/rejected: %+v %v", got.Options, err)
			}
		})
	}
}

func TestMeasuredHTTPSRequestsRejectRedirects(t *testing.T) {
	for _, location := range []string{"http://node.example.com/cdn-cgi/trace", "https://node.example.com/other", "https://elsewhere.example.com/cdn-cgi/trace"} {
		t.Run(location, func(t *testing.T) {
			var requests atomic.Int32
			_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.TLS == nil || r.TLS.ServerName != "node.example.com" || r.Host != "node.example.com" || r.URL.RequestURI() != "/cdn-cgi/trace" {
					t.Errorf("lost HTTPS/SNI/Host/path: %+v", r)
				}
				if requests.Add(1) == 1 { // Valid warmup; reject redirects in all three measured requests.
					_, _ = io.WriteString(w, traceBody(r.Host))
					return
				}
				w.Header().Set("Location", location)
				w.WriteHeader(http.StatusFound)
			}))
			o := defaultOptions()
			o.TargetHost = "node.example.com"
			r := newResult(testCandidate(1), false)
			r.Status, r.TCPAttempts, r.TCPSuccess = "valid", 4, 4
			r, _ = p.latency(context.Background(), r, o, nil)
			if r.Status != "failed" || r.HTTPAttempts != 3 || r.HTTPSuccess != 0 || requests.Load() != 4 || !strings.Contains(r.Error, "测速禁止重定向") || fullStable(r) {
				t.Fatalf("redirect followed or accepted: %+v requests=%d", r, requests.Load())
			}
		})
	}
}

func TestHTTPSDownloadCertificateFailureSendsNoRequest(t *testing.T) {
	var requests atomic.Int32
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = io.WriteString(w, strings.Repeat("x", 4096))
	}))
	p.baseTLS.InsecureSkipVerify = true // Must also be overridden for downloads.
	o := defaultOptions()
	o.DownloadURL = "https://wrong.example.net:8443/data"
	r, warnings := p.download(context.Background(), newResult(testCandidate(1), false), o, nil, 4096, nil)
	if r.DownloadState != "failed" || r.DownloadBytes != 0 || requests.Load() != 0 || len(warnings) == 0 {
		t.Fatalf("unverified request/body: %+v warnings=%v requests=%d", r, warnings, requests.Load())
	}
}
