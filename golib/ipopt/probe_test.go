package ipopt

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func offlineTLS(t *testing.T, h http.Handler) (*httptest.Server, *networkProbe, *atomic.Int32) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "node.example.com"}, DNSNames: []string{"node.example.com", "speed.example.com", "list.example.com"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	srv := httptest.NewUnstartedServer(h)
	srv.Config.ErrorLog = log.New(io.Discard, "", 0)
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	srv.StartTLS()
	t.Cleanup(srv.Close)
	count := &atomic.Int32{}
	p := &networkProbe{baseTLS: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}, now: time.Now,
		dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			count.Add(1)
			return (&net.Dialer{}).DialContext(ctx, network, srv.Listener.Addr().String())
		},
	}
	return srv, p, count
}

func traceBody(host string) string {
	return "fl=abc\nh=" + host + "\nip=1.2.3.4\nts=1730000000\ncolo=HKG\nhttp=http/1.1\n"
}

func TestRealTLSNonstandardPortSNIHostTenSocketsAndFiveRequests(t *testing.T) {
	var mu sync.Mutex
	var hosts, snis, paths, remotes []string
	_, p, dials := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		hosts = append(hosts, r.Host)
		snis = append(snis, r.TLS.ServerName)
		paths = append(paths, r.URL.RequestURI())
		remotes = append(remotes, r.RemoteAddr)
		mu.Unlock()
		w.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(w, traceBody(r.Host))
	}))
	o := defaultOptions()
	o.TargetHost = "node.example.com"
	o.TargetPort = 8443
	c := testCandidate(1)
	c.Address = "104.16.0.1:2008"
	c.Port = 2008
	baseDial := p.dial
	p.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != c.Address {
			t.Errorf("dial %s want %s", address, c.Address)
		}
		return baseDial(ctx, network, address)
	}
	r, warnings := p.connectivity(context.Background(), newResult(c, false), o, nil)
	if r.Status != "valid" || r.Colo != "HKG" || r.ECHStatus != "disabled" || r.TLSMS <= 0 || len(warnings) != 0 {
		t.Fatalf("%+v %v", r, warnings)
	}
	before := dials.Load()
	r, _ = p.stability(context.Background(), r)
	if r.TCPAttempts != 10 || r.TCPSuccess != 10 || dials.Load()-before != 10 {
		t.Fatalf("%+v dials=%d", r, dials.Load()-before)
	}
	r, warnings = p.latency(context.Background(), r, o, nil)
	if r.HTTPAttempts != 5 || r.HTTPSuccess != 5 || r.HTTPMedianMS <= 0 || len(warnings) != 0 {
		t.Fatalf("%+v %v", r, warnings)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(hosts) != 7 {
		t.Fatalf("one connectivity + warmup + five measured =7, got %d", len(hosts))
	}
	for i := range hosts {
		if hosts[i] != "node.example.com:8443" || snis[i] != "node.example.com" || paths[i] != "/cdn-cgi/trace" {
			t.Fatalf("host/sni/path: %s %s %s", hosts[i], snis[i], paths[i])
		}
		if i > 1 && remotes[i] != remotes[1] {
			t.Fatal("five HTTP requests not same connection")
		}
	}
}

func TestTLSCertificateFailureCannotBeMadeInsecure(t *testing.T) {
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, traceBody(r.Host)) }))
	o := defaultOptions()
	o.TargetHost = "wrong.example.net"
	p.baseTLS.InsecureSkipVerify = true // internal injection must still be overridden
	r, _ := p.connectivity(context.Background(), newResult(testCandidate(1), false), o, nil)
	if r.Status != "failed" || r.Error == "" || r.Recommended {
		t.Fatal(r)
	}
}

func TestTraceHTTPFailuresAndNoCrossOriginRedirect(t *testing.T) {
	for _, mode := range []string{"403", "html", "non-cf", "redirect"} {
		t.Run(mode, func(t *testing.T) {
			_, p, dials := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch mode {
				case "403":
					w.WriteHeader(403)
				case "html":
					w.Header().Set("Content-Type", "text/html")
					_, _ = io.WriteString(w, "<html>error</html>")
				case "non-cf":
					_, _ = io.WriteString(w, "ordinary server")
				case "redirect":
					w.Header().Set("Location", "https://elsewhere.example.com/cdn-cgi/trace")
					w.WriteHeader(302)
				}
			}))
			o := defaultOptions()
			o.TargetHost = "node.example.com"
			r, _ := p.connectivity(context.Background(), newResult(testCandidate(1), false), o, nil)
			if r.Status != "failed" || dials.Load() != 1 {
				t.Fatalf("%+v dials=%d", r, dials.Load())
			}
		})
	}
}

func TestCFTraceRaySupplementAndMissingTrace(t *testing.T) {
	h := http.Header{"Cf-Ray": []string{"abc-NRT"}}
	colo, err := parseTrace(h, []byte("h=node.example.com\nip=1.1.1.1\n"))
	if err != nil || colo != "NRT" {
		t.Fatalf("%s %v", colo, err)
	}
	if _, err = parseTrace(h, []byte("Welcome")); err == nil {
		t.Fatal("CF-RAY alone cannot validate trace")
	}
}

func TestHTTPNoReuseIsExplicitlyWarned(t *testing.T) {
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Connection", "close")
		_, _ = io.WriteString(w, traceBody(r.Host))
	}))
	o := defaultOptions()
	o.TargetHost = "node.example.com"
	r := newResult(testCandidate(1), false)
	r.Status = "valid"
	r, warnings := p.latency(context.Background(), r, o, nil)
	if r.HTTPSuccess != 5 || !strings.Contains(strings.Join(warnings, " "), "总请求延迟") {
		t.Fatalf("%+v %v", r, warnings)
	}
}

func TestProbeCancellationClosesBlockingBody(t *testing.T) {
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
	o := defaultOptions()
	o.TargetHost = "node.example.com"
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan Result, 1)
	go func() { r, _ := p.connectivity(ctx, newResult(testCandidate(1), false), o, nil); done <- r }()
	<-started
	cancel()
	select {
	case r := <-done:
		if r.Status != "failed" {
			t.Fatal(r)
		}
	case <-time.After(time.Second):
		t.Fatal("probe still blocked")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("body connection not closed")
	}
}

func TestFailedHTTPRecheckIsRetainedAndCannotBeRecommended(t *testing.T) {
	var requests atomic.Int32
	_, p, _ := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requests.Add(1) == 3 {
			w.WriteHeader(403)
			return
		}
		_, _ = io.WriteString(w, traceBody(r.Host))
	}))
	o := defaultOptions()
	o.TargetHost = "node.example.com"
	r := newResult(testCandidate(1), false)
	r.Status = "valid"
	r.TCPAttempts, r.TCPSuccess = 10, 10
	r, _ = p.latency(context.Background(), r, o, nil)
	if r.Status != "failed" || r.HTTPAttempts != 5 || r.HTTPSuccess != 4 || r.Error == "" || fullStable(r) {
		t.Fatal(r)
	}
}
