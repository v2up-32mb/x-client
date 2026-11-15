package ipopt

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

func wireECHResponse(query, config []byte) []byte {
	buf := append([]byte{}, query...)
	binary.BigEndian.PutUint16(buf[2:4], 0x8180)
	binary.BigEndian.PutUint16(buf[6:8], 1)
	rdata := []byte{0, 1, 0, 0, 5}
	rdata = binary.BigEndian.AppendUint16(rdata, uint16(len(config)))
	rdata = append(rdata, config...)
	buf = append(buf, 0xc0, 0x0c, 0, 65, 0, 1, 0, 0, 0, 60)
	buf = binary.BigEndian.AppendUint16(buf, uint16(len(rdata)))
	return append(buf, rdata...)
}

func TestECHWireParsingAndMalformedReplies(t *testing.T) {
	query, id, err := echQuestion("cloudflare-ech.com", zeroReader{})
	if err != nil {
		t.Fatal(err)
	}
	config := []byte{0, 3, 1, 2, 3}
	wire := wireECHResponse(query, config)
	got, err := parseECHAnswer(wire, id, "cloudflare-ech.com")
	if err != nil || !bytes.Equal(got, config) {
		t.Fatalf("%x %v", got, err)
	}
	if _, err = parseECHAnswer(wire, id+1, "cloudflare-ech.com"); err == nil {
		t.Fatal("wrong ID")
	}
	if _, err = parseECHAnswer(wire, id, "different.example.com"); err == nil {
		t.Fatal("wrong question")
	}
	for name, mutate := range map[string]func([]byte) []byte{
		"truncated":          func(b []byte) []byte { b[2] |= 2; return b },
		"query-not-response": func(b []byte) []byte { b[2] &= 0x7f; return b },
		"NXDOMAIN":           func(b []byte) []byte { b[3] = 0x83; return b },
		"short":              func(b []byte) []byte { return b[:len(b)-1] },
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parseECHAnswer(mutate(append([]byte{}, wire...)), id, "cloudflare-ech.com"); err == nil {
				t.Fatal("invalid DNS accepted")
			}
		})
	}
	for _, data := range [][]byte{{}, {0, 0, 0}, {0, 1, 0xc0, 0}, {0, 1, 0, 0, 5, 0, 5, 0, 4, 1, 2, 3}, {0, 1, 0, 0, 5, 0, 5, 0, 3, 1, 2, 3, 0, 5, 0, 5, 0, 3, 1, 2, 3}} {
		if _, err := parseHTTPSParams(data); err == nil {
			t.Fatalf("invalid HTTPS data accepted %x", data)
		}
	}
}

func TestECHDoHIsRFC8484AndContextBound(t *testing.T) {
	o := defaultOptions()
	o.EnableECH = true
	calls := 0
	config := []byte{0, 3, 1, 2, 3}
	rt := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		calls++
		if req.Method != "POST" || req.Header.Get("Content-Type") != "application/dns-message" || req.URL.String() != o.DNSServer {
			t.Errorf("not RFC8484 request: %+v", req)
		}
		if deadline, ok := req.Context().Deadline(); !ok || time.Until(deadline) > 4*time.Second {
			t.Error("DoH missing bounded deadline")
		}
		q, err := io.ReadAll(req.Body)
		if err != nil {
			return nil, err
		}
		b := wireECHResponse(q, config)
		return response(req, 200, "application/dns-message", string(b)), nil
	})
	got, warnings, err := prepareECH(context.Background(), o, rt, func(context.Context, string, string) (net.Conn, error) {
		t.Error("unexpected UDP")
		return nil, errors.New("no UDP")
	})
	if err != nil || calls != 1 || len(warnings) != 0 || !bytes.Equal(got, config) {
		t.Fatalf("%x %v %v calls=%d", got, warnings, err, calls)
	}
}

func TestECHUDPRealOfflineFallback(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	config := []byte{0, 3, 1, 2, 3}
	done := make(chan error, 1)
	go func() {
		buf := make([]byte, 2048)
		n, peer, err := pc.ReadFrom(buf)
		if err == nil {
			_, err = pc.WriteTo(wireECHResponse(buf[:n], config), peer)
		}
		done <- err
	}()
	o := defaultOptions()
	o.EnableECH = true
	calls := 0
	rt := roundTripFunc(func(*http.Request) (*http.Response, error) {
		calls++
		return nil, errors.New("offline DoH unavailable")
	})
	got, warnings, err := prepareECH(context.Background(), o, rt, func(ctx context.Context, network, address string) (net.Conn, error) {
		if network != "udp" || address != "8.8.8.8:53" {
			t.Errorf("unexpected fallback %s %s", network, address)
		}
		return (&net.Dialer{}).DialContext(ctx, network, pc.LocalAddr().String())
	})
	if err != nil || !bytes.Equal(got, config) || calls != 3 || len(warnings) != 3 {
		t.Fatalf("%x %v %v calls=%d", got, warnings, err, calls)
	}
	if err = <-done; err != nil {
		t.Fatal(err)
	}
}

func TestECHUDPCancelClosesBlockingRead(t *testing.T) {
	q, id, _ := echQuestion("cloudflare-ech.com", zeroReader{})
	a, b := net.Pipe()
	defer b.Close()
	readQuery := make(chan struct{})
	go func() { buf := make([]byte, len(q)); _, _ = io.ReadFull(b, buf); close(readQuery) }()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := queryECHUDP(ctx, func(context.Context, string, string) (net.Conn, error) { return a, nil }, q, id, "cloudflare-ech.com")
		done <- err
	}()
	<-readQuery
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("UDP cancel succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("UDP blocked beyond cancellation")
	}
}

func TestECHPreparationCancellationStopsFallbackSequence(t *testing.T) {
	o := defaultOptions()
	o.EnableECH = true
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		_, _, err := prepareECH(ctx, o, roundTripFunc(func(req *http.Request) (*http.Response, error) {
			close(entered)
			<-req.Context().Done()
			return nil, req.Context().Err()
		}), func(context.Context, string, string) (net.Conn, error) {
			t.Error("UDP after cancellation")
			return nil, errors.New("unexpected")
		})
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
		t.Fatal("ECH preparation still running")
	}
}

func rejectionConfig(t *testing.T, publicName string) []byte {
	t.Helper()
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pub := key.PublicKey().Bytes()
	inner := []byte{1, 0, 0x20}
	inner = binary.BigEndian.AppendUint16(inner, uint16(len(pub)))
	inner = append(inner, pub...)
	inner = append(inner, 0, 4, 0, 1, 0, 1, byte(len(publicName)), byte(len(publicName)))
	inner = append(inner, publicName...)
	inner = append(inner, 0, 0)
	config := []byte{0xfe, 0x0d}
	config = binary.BigEndian.AppendUint16(config, uint16(len(inner)))
	config = append(config, inner...)
	out := binary.BigEndian.AppendUint16(nil, uint16(len(config)))
	return append(out, config...)
}

func TestECHRejectionFallsBackToVerifiedTLSAndIsNotCalledUsed(t *testing.T) {
	_, p, dials := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, traceBody(r.Host)) }))
	o := defaultOptions()
	o.TargetHost = "node.example.com"
	o.EnableECH = true
	r, warnings := p.connectivity(context.Background(), newResult(testCandidate(1), true), o, rejectionConfig(t, "node.example.com"))
	if r.Status != "valid" || r.ECHStatus != "fallback" || dials.Load() != 2 || !strings.Contains(strings.Join(warnings, " "), "回退") {
		t.Fatalf("%+v %v dials=%d", r, warnings, dials.Load())
	}
	// Missing config must never silently report a successful ECH verification.
	r, warnings = p.connectivity(context.Background(), newResult(testCandidate(1), true), o, nil)
	if r.Status != "valid" || r.ECHStatus != "fallback" || len(warnings) == 0 {
		t.Fatalf("%+v %v", r, warnings)
	}
}
