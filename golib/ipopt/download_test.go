package ipopt

import (
	"bytes"
	"context"
	"errors"
	"io"
	"math"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

type timedReader struct {
	io.Reader
	clock *fakeClock
	step  time.Duration
}

func (r timedReader) Read(b []byte) (int, error) {
	n, err := r.Reader.Read(b)
	r.clock.advance(r.step)
	return n, err
}

func TestDownloadBodyBudgetMeanPeakAndShortSamples(t *testing.T) {
	clock := &fakeClock{at: time.Unix(1730000000, 0)}
	r := timedReader{bytes.NewReader(bytes.Repeat([]byte{'x'}, 2<<20)), clock, time.Second / 16}
	s := measureBody(context.Background(), r, http.Header{}, 1<<20, 2<<20, clock.now, nil)
	if s.state != "ok" || s.bytes != 1<<20 || s.seconds != 2 || math.Abs(s.average-0.5) > 0.0001 || math.Abs(s.peak-0.5) > 0.0001 {
		t.Fatalf("%+v", s)
	}
	clock = &fakeClock{at: time.Unix(1730000000, 0)}
	s = measureBody(context.Background(), timedReader{strings.NewReader("abcdefghij"), clock, 100 * time.Millisecond}, http.Header{}, 10, 10, clock.now, nil)
	if s.state != "short" || s.bytes != 10 || s.seconds != 0.1 || s.average <= 0 {
		t.Fatalf("%+v", s)
	}
}

func TestDownloadRejectsWrongBodyAndUnexpectedEOF(t *testing.T) {
	for _, body := range []string{"<html>error</html>", "<div>forbidden</div>", "Forbidden", `{"error":"blocked"}`, "<?xml version=\"1\"?>", "access denied", "error: rate limited"} {
		clock := &fakeClock{at: time.Now()}
		s := measureBody(context.Background(), timedReader{strings.NewReader(body), clock, time.Second}, http.Header{}, 1000, int64(len(body)), clock.now, nil)
		if s.state != "failed" || s.average != 0 || s.bytes != int64(len(body)) || s.err == nil {
			t.Fatalf("%+v", s)
		}
	}
	clock := &fakeClock{at: time.Now()}
	s := measureBody(context.Background(), timedReader{strings.NewReader("binary"), clock, time.Second}, http.Header{}, 100, 100, clock.now, nil)
	if s.state != "failed" || !errors.Is(s.err, io.ErrUnexpectedEOF) || s.average != 0 {
		t.Fatalf("%+v", s)
	}
}

func TestDownloadResponseValidation(t *testing.T) {
	for _, r := range []*http.Response{
		{StatusCode: 403, Header: http.Header{}, ContentLength: 12},
		{StatusCode: 200, Header: http.Header{"Content-Type": []string{"text/html"}}, ContentLength: 12},
		{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/json"}}, ContentLength: 12},
		{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/octet-stream"}, "Content-Encoding": []string{"gzip"}}, ContentLength: 12},
		{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/octet-stream"}}, ContentLength: 0},
	} {
		if err := validateDownloadResponse(r); err == nil {
			t.Fatal("error response accepted", r)
		}
	}
}

func TestDownloadHTTPSPathQueryHostAndCandidatePort(t *testing.T) {
	seen := make(chan *http.Request, 1)
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen <- r
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = io.WriteString(w, strings.Repeat("x", 4096))
	}))
	c := testCandidate(1)
	c.Address = "104.16.0.1:443"
	baseDial := p.dial
	p.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != c.Address {
			t.Errorf("dial address %s", address)
		}
		return baseDial(ctx, network, address)
	}
	o := defaultOptions()
	o.DownloadURL = "https://speed.example.com:2008/data?bytes=4096&keep=yes"
	o.DownloadBytesPerIP = 4096
	r, warnings := p.download(context.Background(), newResult(c, false), o, nil, 2048, nil)
	if r.DownloadBytes != 2048 || r.DownloadState != "short" || !strings.Contains(strings.Join(warnings, " "), "不足1秒") {
		t.Fatalf("%+v %v", r, warnings)
	}
	req := <-seen
	if req.Host != "speed.example.com:2008" || req.URL.RequestURI() != "/data?bytes=4096&keep=yes" || req.TLS == nil || req.TLS.ServerName != "speed.example.com" || req.Header.Get("Accept-Encoding") != "identity" {
		t.Fatal(req)
	}
}

func TestDownloadHTTPSOnNonstandardPortAndRejectsRedirect(t *testing.T) {
	for _, location := range []string{"", "https://elsewhere.example.com/data", "https://speed.example.com:8443/other", "http://speed.example.com:8443/data"} {
		t.Run(location, func(t *testing.T) {
			redirect := location != ""
			_, p, dials := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.TLS.ServerName != "speed.example.com" || r.URL.RawQuery != "bytes=4096" {
					t.Errorf("lost SNI/query")
				}
				if redirect {
					w.Header().Set("Location", location)
					w.WriteHeader(302)
					return
				}
				w.Header().Set("Content-Type", "application/octet-stream")
				_, _ = io.WriteString(w, strings.Repeat("x", 4096))
			}))
			o := defaultOptions()
			o.DownloadURL = "https://speed.example.com:8443/data?bytes=4096"
			o.DownloadBytesPerIP = 4096
			c := testCandidate(1)
			c.Address = "104.16.0.1:2008"
			c.Port = 2008
			r, warnings := p.download(context.Background(), newResult(c, false), o, nil, 4096, nil)
			if redirect && !strings.Contains(strings.Join(warnings, " "), "测速禁止重定向") {
				t.Fatalf("redirect not explicitly rejected: %v", warnings)
			}
			if dials.Load() != 1 || (redirect && r.DownloadState != "failed") || (!redirect && r.DownloadBytes != 4096) {
				t.Fatalf("%+v dials=%d", r, dials.Load())
			}
		})
	}
}

func TestDownloadDeadlineClosesBlockingRead(t *testing.T) {
	closed := make(chan struct{})
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(bytes.Repeat([]byte{'x'}, 1024))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(closed)
	}))
	o := defaultOptions()
	o.DownloadURL = "https://speed.example.com/data"
	o.DownloadSeconds = 1
	start := time.Now()
	r, _ := p.download(context.Background(), newResult(testCandidate(1), false), o, nil, maxDownloadBytes, nil)
	if time.Since(start) > 2*time.Second || r.DownloadBytes != 1024 || (r.DownloadState != "short" && r.DownloadState != "ok") {
		t.Fatalf("deadline sample %+v duration=%v", r, time.Since(start))
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("download body still open")
	}
}

type cancelRead struct {
	cancel context.CancelFunc
	clock  *fakeClock
}

func (r cancelRead) Read(p []byte) (int, error) {
	r.clock.advance(250 * time.Millisecond)
	r.cancel()
	return copy(p, "partial-body"), context.Canceled
}

func TestBodyProgressChargesBytesReturnedWithCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	clock := &fakeClock{at: time.Now()}
	var reports int
	var gotBytes int64
	var gotSeconds float64
	s := measureBody(ctx, cancelRead{cancel, clock}, http.Header{}, 100, -1, clock.now, func(bytes int64, seconds float64) {
		reports++
		gotBytes, gotSeconds = bytes, seconds
	})
	if reports < 2 || gotBytes != int64(len("partial-body")) || gotSeconds != 0.25 || s.bytes != gotBytes || s.seconds != gotSeconds || s.state != "failed" || s.average != 0 || !errors.Is(s.err, context.Canceled) {
		t.Fatalf("sample=%+v reports=%d bytes=%d seconds=%f", s, reports, gotBytes, gotSeconds)
	}
}
