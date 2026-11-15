package ipopt

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func response(req *http.Request, status int, ct, body string) *http.Response {
	return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": []string{ct}}, Body: io.NopCloser(strings.NewReader(body)), ContentLength: int64(len(body)), Request: req}
}

func TestFetchOfficialCIDRAndThirdParty(t *testing.T) {
	source := Source{"cf-v4", "https://www.cloudflare.com/ips-v4", "cidr4"}
	rt := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		if _, ok := req.Context().Deadline(); !ok {
			t.Error("fetch has no deadline")
		}
		if req.Header.Get("Accept-Encoding") != "identity" {
			t.Error("compression enabled")
		}
		return response(req, 200, "text/plain", "\ufeff104.16.0.0/13\r\n104.16.1.2/13\nnot-cidr\n2606:4700::/32"), nil
	})
	out, err := fetchSource(context.Background(), source, rt)
	if err != nil || len(out.prefixes) != 1 || out.prefixes[0].CIDR != "104.16.0.0/13" || out.prefixes[0].SourceKind != "official" || len(out.errors) != 2 {
		t.Fatalf("%+v %v", out, err)
	}
	source = Source{"optional", "https://776771.xyz/all.txt", "list"}
	out, err = fetchSource(context.Background(), source, roundTripFunc(func(req *http.Request) (*http.Response, error) {
		return response(req, 200, "text/plain", "1.1.1.1:2008#third\n127.0.0.1:443\nnope"), nil
	}))
	if err != nil || len(out.candidates) != 1 || out.candidates[0].SourceKind != "third_party" || len(out.errors) != 2 {
		t.Fatalf("%+v %v", out, err)
	}
}

func TestFetchRejectsErrorsOversizeAndRedirects(t *testing.T) {
	s := Source{"x", "https://list.example.com/all.txt", "list"}
	for name, rt := range map[string]roundTripFunc{
		"403": func(req *http.Request) (*http.Response, error) {
			return response(req, 403, "text/plain", "1.1.1.1:443"), nil
		},
		"html": func(req *http.Request) (*http.Response, error) {
			return response(req, 200, "text/html", "1.1.1.1:443"), nil
		},
		"sniffhtml": func(req *http.Request) (*http.Response, error) {
			return response(req, 200, "text/plain", "<!doctype html>bad"), nil
		},
		"oversize": func(req *http.Request) (*http.Response, error) {
			r := response(req, 200, "text/plain", "")
			r.ContentLength = maxSourceBytes + 1
			return r, nil
		},
		"streamoversize": func(req *http.Request) (*http.Response, error) {
			r := response(req, 200, "text/plain", strings.Repeat("x", maxSourceBytes+1))
			r.ContentLength = -1
			return r, nil
		},
		"gzip": func(req *http.Request) (*http.Response, error) {
			r := response(req, 200, "text/plain", "1.1.1.1:443")
			r.Header.Set("Content-Encoding", "gzip")
			return r, nil
		},
		"downgrade": func(req *http.Request) (*http.Response, error) {
			r := response(req, 302, "text/plain", "")
			r.Header.Set("Location", "http://list.example.com/all.txt")
			return r, nil
		},
		"private-redirect": func(req *http.Request) (*http.Response, error) {
			r := response(req, 302, "text/plain", "")
			r.Header.Set("Location", "https://127.0.0.1/all.txt")
			return r, nil
		},
		"redirect-loop": func(req *http.Request) (*http.Response, error) {
			r := response(req, 302, "text/plain", "")
			r.Header.Set("Location", "https://list.example.com/loop")
			return r, nil
		},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := fetchSource(context.Background(), s, rt); err == nil {
				t.Fatal("invalid fetch accepted")
			}
		})
	}
}

func TestFetchCancellationBeforeResponseHeaders(t *testing.T) {
	// The injected transport checks cancellation before any response headers.
	entered := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := fetchSource(ctx, Source{"x", "https://list.example.com/", "list"}, roundTripFunc(func(req *http.Request) (*http.Response, error) {
			close(entered)
			<-req.Context().Done()
			return nil, req.Context().Err()
		}))
		done <- err
	}()
	<-entered
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancel succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("fetch did not cancel")
	}
}

func TestGuardedDialRejectsLiteralPrivateBeforeNetworking(t *testing.T) {
	for _, addr := range []string{"127.0.0.1:80", "[::1]:80", "192.168.1.1:443"} {
		if conn, err := guardedDial(context.Background(), "tcp", addr); err == nil {
			conn.Close()
			t.Fatal("private accepted")
		}
	}
}

func TestTaskBoundDialCancelsDetachedTransportDialAndSocket(t *testing.T) {
	parent, cancel := context.WithCancel(context.Background())
	entered := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		_, err := bindDial(parent, func(ctx context.Context, _, _ string) (net.Conn, error) {
			close(entered)
			<-ctx.Done()
			return nil, ctx.Err()
		}, context.Background(), "tcp", "1.1.1.1:443")
		done <- err
	}()
	<-entered
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("detached dial outlived task")
	}
	parent, cancel = context.WithCancel(context.Background())
	a, b := net.Pipe()
	defer b.Close()
	conn, err := bindDial(parent, func(context.Context, string, string) (net.Conn, error) { return a, nil }, context.Background(), "tcp", "1.1.1.1:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	cancel()
	_ = b.SetReadDeadline(time.Now().Add(time.Second))
	if _, err = b.Read(make([]byte, 1)); err == nil {
		t.Fatal("task socket remained open")
	}
}

// clock is thread-safe so race-enabled manager tests may inject it too.
type fakeClock struct {
	mu sync.Mutex
	at time.Time
}

func (c *fakeClock) now() time.Time          { c.mu.Lock(); defer c.mu.Unlock(); return c.at }
func (c *fakeClock) advance(d time.Duration) { c.mu.Lock(); c.at = c.at.Add(d); c.mu.Unlock() }

func TestFetchCancellationAfterHeadersClosesActualTLSBody(t *testing.T) {
	started := make(chan struct{})
	closed := make(chan struct{})
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
		close(closed)
	}))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	tr := &http.Transport{TLSClientConfig: p.baseTLS.Clone(), DialContext: p.dial}
	defer tr.CloseIdleConnections()
	done := make(chan error, 1)
	go func() {
		_, err := fetchSource(ctx, Source{"x", "https://list.example.com/list", "list"}, tr)
		done <- err
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("fetch did not reach response body")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled body accepted")
		}
	case <-time.After(time.Second):
		t.Fatal("fetch body read outlived cancellation")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("fetch socket not closed")
	}
}
