package ipopt

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	shareddns "github.com/v2up-32mb/xshared/dns"
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

// Build real DNS wire messages without the dependency's typed HTTPS builder:
// that builder does not exist in x/net v0.17.0. Keep wireECHResponse and its
// original success/fallback fixtures unchanged.
type echWireRecord struct {
	name       []byte
	typ, class uint16
	data       []byte
}

func echWireName(name string) []byte {
	var wire []byte
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		wire = append(wire, byte(len(label)))
		wire = append(wire, label...)
	}
	return append(wire, 0)
}

func echWireRecords(query []byte, records ...echWireRecord) []byte {
	wire := append([]byte{}, query...)
	binary.BigEndian.PutUint16(wire[2:4], 0x8180)
	binary.BigEndian.PutUint16(wire[6:8], uint16(len(records)))
	for _, r := range records {
		wire = append(wire, r.name...)
		wire = binary.BigEndian.AppendUint16(wire, r.typ)
		wire = binary.BigEndian.AppendUint16(wire, r.class)
		wire = append(wire, 0, 0, 0, 60) // TTL
		wire = binary.BigEndian.AppendUint16(wire, uint16(len(r.data)))
		wire = append(wire, r.data...)
	}
	return wire
}

func TestECHWireMixedAnswersAndOwnerBinding(t *testing.T) {
	query, id, err := echQuestion("cloudflare-ech.com", zeroReader{})
	if err != nil {
		t.Fatal(err)
	}
	config := []byte{0, 3, 1, 2, 3}
	// The existing response has a compressed owner (2 bytes), then 10 bytes
	// of fixed RR header. Exercise precisely the same RDATA under both x/net versions.
	data := wireECHResponse(query, config)[len(query)+12:]
	owner := []byte{0xc0, 0x0c}
	good := echWireRecord{owner, 65, 1, data}
	others := []echWireRecord{
		{owner, 1, 1, []byte{192, 0, 2, 1}},
		{owner, 28, 1, make([]byte, 16)},
		{owner, 16, 1, []byte{3, 't', 'x', 't'}},
		{owner, 65280, 1, data}, // An unknown type with HTTPS-like bytes is not HTTPS.
		{owner, 64, 1, data},    // Nor is SVCB a type65 answer.
		{owner, 65, 3, data},    // Wrong DNS class.
		{echWireName("unrelated.example."), 65, 1, data},
	}
	if got, err := parseECHAnswer(echWireRecords(query, others...), id, "cloudflare-ech.com"); err == nil || len(got) != 0 {
		t.Fatalf("unrelated records supplied ECH: %x %v", got, err)
	}
	for _, first := range []bool{false, true} {
		t.Run(fmt.Sprintf("valid-first=%t", first), func(t *testing.T) {
			records := append([]echWireRecord{}, others...)
			if first {
				records = append([]echWireRecord{good}, records...)
			} else {
				records = append(records, good)
			}
			got, err := parseECHAnswer(echWireRecords(query, records...), id, "CLOUDFLARE-ECH.COM.")
			if err != nil || !bytes.Equal(got, config) {
				t.Fatalf("mixed wire response: %x %v", got, err)
			}
		})
	}
}

func TestECHWireRejectsMalformedRDATA(t *testing.T) {
	query, id, err := echQuestion("cloudflare-ech.com", zeroReader{})
	if err != nil {
		t.Fatal(err)
	}
	config := []byte{0, 3, 1, 2, 3}
	data := wireECHResponse(query, config)[len(query)+12:]
	owner := []byte{0xc0, 0x0c}
	for name, bad := range map[string][]byte{
		"empty":                 {},
		"priority-only":         {0, 1},
		"alias-root-with-ech":   append([]byte{0, 0, 0}, data[3:]...),
		"compressed-target":     append([]byte{0, 1, 0xc0, 0x0c}, data[3:]...),
		"reserved-target-label": append([]byte{0, 1, 0x40}, data[3:]...),
		"truncated-target":      {0, 1, 3, 'a'},
		"unterminated-target":   {0, 1, 1, 'a'},
		"no-ech-key":            {0, 1, 0, 0, 4, 0, 4, 192, 0, 2, 1},
		"short-param-header":    append(append([]byte{}, data...), 0, 6, 0),
		"short-param-value":     append(append([]byte{}, data...), 0, 6, 0, 2, 1),
		"duplicate-key":         append(append([]byte{}, data...), data[3:]...),
		"descending-key":        append(append([]byte{}, data...), 0, 4, 0, 0),
		"empty-ech-list":        {0, 1, 0, 0, 5, 0, 2, 0, 0},
		"wrong-ech-list-length": {0, 1, 0, 0, 5, 0, 5, 0, 4, 1, 2, 3},
	} {
		t.Run(name, func(t *testing.T) {
			// A valid but unrelated RR also follows: it must not supply ECH or
			// provide missing bytes to a parser reading past the first RDLENGTH.
			wire := echWireRecords(query,
				echWireRecord{owner, 65, 1, bad},
				echWireRecord{echWireName("unrelated.example."), 65, 1, data})
			if got, err := parseECHAnswer(wire, id, "cloudflare-ech.com"); err == nil || len(got) != 0 {
				t.Fatalf("malformed RDATA supplied ECH: %x %v", got, err)
			}
		})
	}
}

func TestECHWireRejectsMalformedEnvelopesAndTrailingAnswers(t *testing.T) {
	query, id, err := echQuestion("cloudflare-ech.com", zeroReader{})
	if err != nil {
		t.Fatal(err)
	}
	wire := wireECHResponse(query, []byte{0, 3, 1, 2, 3})
	for name, mutate := range map[string]func([]byte) []byte{
		"opcode":                        func(b []byte) []byte { b[2] |= 0x08; return b },
		"question-count":                func(b []byte) []byte { b[5] = 2; return b },
		"no-question":                   func(b []byte) []byte { b[5] = 0; return b },
		"authority-is-not-answer":       func(b []byte) []byte { b[7] = 0; b[9] = 1; return b },
		"additional-is-not-answer":      func(b []byte) []byte { b[7] = 0; b[11] = 1; return b },
		"question-type":                 func(b []byte) []byte { b[len(query)-3] = 1; return b },
		"question-class":                func(b []byte) []byte { b[len(query)-1] = 3; return b },
		"owner-pointer-outside-message": func(b []byte) []byte { b[len(query)+1] = 0xff; return b },
		"owner-pointer-loop":            func(b []byte) []byte { b[len(query)+1] = byte(len(query)); return b },
		"short-rdlength":                func(b []byte) []byte { b[len(query)+11]--; return b },
		"long-rdlength":                 func(b []byte) []byte { b[len(query)+11]++; return b },
		"missing-next-answer":           func(b []byte) []byte { b[7]++; return b },
	} {
		t.Run(name, func(t *testing.T) {
			if got, err := parseECHAnswer(mutate(append([]byte{}, wire...)), id, "cloudflare-ech.com"); err == nil || len(got) != 0 {
				t.Fatalf("malformed envelope supplied ECH: %x %v", got, err)
			}
		})
	}
	for n := 0; n < len(wire); n++ {
		if got, err := parseECHAnswer(wire[:n], id, "cloudflare-ech.com"); err == nil || len(got) != 0 {
			t.Fatalf("truncated message (%d bytes) supplied ECH: %x %v", n, got, err)
		}
	}
	data := wire[len(query)+12:]
	for name, bad := range map[string]echWireRecord{
		"short-A":       {[]byte{0xc0, 0x0c}, 1, 1, []byte{192, 0, 2}},
		"short-TXT":     {[]byte{0xc0, 0x0c}, 16, 1, []byte{4, 'x'}},
		"bad-CNAME":     {[]byte{0xc0, 0x0c}, 5, 1, []byte{0xc0, 0xff}},
		"short-unknown": {[]byte{0xc0, 0x0c}, 65280, 1, data},
		"short-HTTPS":   {[]byte{0xc0, 0x0c}, 65, 1, data},
	} {
		t.Run("trailing-"+name, func(t *testing.T) {
			wire := echWireRecords(query, echWireRecord{[]byte{0xc0, 0x0c}, 65, 1, data}, bad)
			if name == "short-unknown" || name == "short-HTTPS" {
				wire = wire[:len(wire)-1] // RDLENGTH still declares the original length.
			}
			if got, err := parseECHAnswer(wire, id, "cloudflare-ech.com"); err == nil || len(got) != 0 {
				t.Fatalf("malformed trailing answer supplied ECH: %x %v", got, err)
			}
		})
	}
}

func TestECHWireCNAMEAndHTTPSAliasBoundaries(t *testing.T) {
	query, id, err := echQuestion("cloudflare-ech.com", zeroReader{})
	if err != nil {
		t.Fatal(err)
	}
	config := []byte{0, 3, 1, 2, 3}
	data := wireECHResponse(query, config)[len(query)+12:]
	owner := []byte{0xc0, 0x0c}
	alias := echWireName("alias.example.")
	for _, tc := range []struct {
		name    string
		records []echWireRecord
		want    bool
	}{
		{"CNAME-after-HTTPS", []echWireRecord{{alias, 65, 1, data}, {owner, 5, 1, alias}}, true},
		{"case-insensitive-CNAME", []echWireRecord{{owner, 5, 1, echWireName("ALIAS.EXAMPLE.")}, {alias, 65, 1, data}}, true},
		{"compressed-CNAME", []echWireRecord{{alias, 65, 1, data}, {owner, 5, 1, binary.BigEndian.AppendUint16(nil, 0xc000|uint16(len(query)))}}, true},
		{"wrong-class-CNAME", []echWireRecord{{owner, 5, 3, alias}, {alias, 65, 1, data}}, false},
		{"unrelated-CNAME", []echWireRecord{{echWireName("other.example."), 5, 1, alias}, {alias, 65, 1, data}}, false},
		{"CNAME-cycle", []echWireRecord{{owner, 5, 1, alias}, {alias, 5, 1, owner}}, false},
		{"CNAME-self-loop", []echWireRecord{{owner, 5, 1, owner}}, false},
		{"HTTPS-alias-not-followed", []echWireRecord{{owner, 65, 1, append([]byte{0, 0}, alias...)}, {alias, 65, 1, data}}, false},
		{"HTTPS-alias-with-ech", []echWireRecord{{owner, 65, 1, append(append([]byte{0, 0}, alias...), data[3:]...)}, {alias, 65, 1, data}}, false},
		{"HTTPS-service-target-not-owner", []echWireRecord{{owner, 65, 1, append([]byte{0, 1}, alias...)}, {alias, 65, 1, data}}, false},
		{"HTTPS-service-named-target", []echWireRecord{{owner, 65, 1, append(append([]byte{0, 1}, alias...), data[3:]...)}}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseECHAnswer(echWireRecords(query, tc.records...), id, "cloudflare-ech.com")
			if tc.want {
				if err != nil || !bytes.Equal(got, config) {
					t.Fatalf("CNAME/service-mode answer: %x %v", got, err)
				}
			} else if err == nil || len(got) != 0 {
				t.Fatalf("unrelated or alias-only answer supplied ECH: %x %v", got, err)
			}
		})
	}
	for _, hops := range []int{8, 9} {
		for _, reverse := range []bool{false, true} {
			t.Run(fmt.Sprintf("hops=%d/reverse=%t", hops, reverse), func(t *testing.T) {
				var records []echWireRecord
				from := owner
				for i := 0; i < hops; i++ {
					to := echWireName(fmt.Sprintf("alias%d.example.", i))
					records = append(records, echWireRecord{from, 5, 1, to})
					from = to
				}
				records = append(records, echWireRecord{from, 65, 1, data})
				if reverse {
					for i, j := 0, len(records)-1; i < j; i, j = i+1, j-1 {
						records[i], records[j] = records[j], records[i]
					}
				}
				got, err := parseECHAnswer(echWireRecords(query, records...), id, "cloudflare-ech.com")
				if hops == 8 {
					if err != nil || !bytes.Equal(got, config) {
						t.Fatalf("bounded CNAME chain rejected: %x %v", got, err)
					}
				} else if err == nil || len(got) != 0 {
					t.Fatalf("CNAME hop limit bypassed: %x %v", got, err)
				}
			})
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

func TestECHEmptyDNSUsesContextBoundBuiltinList(t *testing.T) {
	o := defaultOptions()
	o.EnableECH = true
	o.DNSServer = ""
	want := append([]string{}, shareddns.DefaultDoHServers...)
	if len(want) == 0 {
		t.Fatal("shared defaults missing")
	}
	calls := 0
	config := []byte{0, 3, 1, 2, 3}
	rt := roundTripFunc(func(req *http.Request) (*http.Response, error) {
		i := calls
		calls++
		if i >= len(want) || req.URL.String() != want[i] {
			t.Fatalf("default DoH order: %d %s; want %v", i, req.URL, want)
		}
		if deadline, ok := req.Context().Deadline(); !ok || time.Until(deadline) > 4*time.Second {
			t.Fatal("built-in DoH request is not context bound")
		}
		if i < len(want)-1 {
			return nil, errors.New("offline injected failure")
		}
		q, err := io.ReadAll(req.Body)
		if err != nil {
			return nil, err
		}
		return response(req, 200, "application/dns-message", string(wireECHResponse(q, config))), nil
	})
	got, warnings, err := prepareECH(context.Background(), o, rt, func(context.Context, string, string) (net.Conn, error) {
		t.Error("unexpected UDP after successful default DoH")
		return nil, errors.New("unexpected UDP")
	})
	if err != nil || calls != len(want) || len(warnings) != len(want)-1 || !bytes.Equal(got, config) {
		t.Fatalf("got=%x warnings=%v err=%v calls=%d", got, warnings, err, calls)
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
	var requests atomic.Int32
	_, p, dials := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.TLS == nil || r.TLS.ServerName != "node.example.com" || r.Host != "node.example.com:8443" || r.URL.RequestURI() != "/cdn-cgi/trace" {
			t.Errorf("fallback lost HTTPS/SNI/Host/path: %+v", r)
		}
		_, _ = io.WriteString(w, traceBody(r.Host))
	}))
	o := defaultOptions()
	o.TargetHost, o.TargetPort = "node.example.com", 8443
	o.EnableECH = true
	c := testCandidate(1)
	c.Address, c.Port = "104.16.0.1:2008", 2008
	baseDial := p.dial
	p.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != c.Address {
			t.Errorf("ECH/fallback dial %s want %s", address, c.Address)
		}
		return baseDial(ctx, network, address)
	}
	config := rejectionConfig(t, "node.example.com")
	r, warnings := p.connectivity(context.Background(), newResult(c, true), o, config)
	if r.Status != "valid" || r.Colo != "HKG" || r.ECHStatus != "fallback" || dials.Load() != 2 || !strings.Contains(strings.Join(warnings, " "), "回退") {
		t.Fatalf("%+v %v dials=%d", r, warnings, dials.Load())
	}
	r, warnings = p.latency(context.Background(), r, o, config)
	if r.Status != "valid" || r.Colo != "HKG" || r.HTTPAttempts != 3 || r.HTTPSuccess != 3 || r.ECHStatus != "fallback" || dials.Load() != 4 || requests.Load() != 5 || len(warnings) == 0 {
		t.Fatalf("HTTPS latency after ECH rejection: %+v %v dials=%d requests=%d", r, warnings, dials.Load(), requests.Load())
	}
	// Missing config must never silently report a successful ECH verification.
	r, warnings = p.connectivity(context.Background(), newResult(c, true), o, nil)
	if r.Status != "valid" || r.ECHStatus != "fallback" || len(warnings) == 0 {
		t.Fatalf("%+v %v", r, warnings)
	}
	// The outer ECH public name has a trusted certificate, but the fresh
	// fallback must still reject a mismatched target certificate before HTTP.
	o.TargetHost = "wrong.example.net"
	p.baseTLS.InsecureSkipVerify = true
	before := dials.Load()
	r, _ = p.connectivity(context.Background(), newResult(c, true), o, config)
	if r.Status != "failed" || r.Error == "" || r.ECHStatus != "fallback" || dials.Load()-before != 2 || requests.Load() != 6 {
		t.Fatalf("unverified ECH fallback accepted: %+v dials=%d requests=%d", r, dials.Load()-before, requests.Load())
	}
}

func TestProbeOneECHFallbackSkipsRetryOnReconnect(t *testing.T) {
	var requests atomic.Int32
	_, p, dials := offlineTLS(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.Header().Set("Connection", "close")
		_, _ = io.WriteString(w, traceBody(r.Host))
	}))
	o := defaultOptions()
	o.TargetHost = "node.example.com"
	o.EnableECH = true
	config := rejectionConfig(t, "node.example.com")
	var preStabilityDials int32
	r, _ := p.probeOne(context.Background(), newResult(testCandidate(1), true), o, config, func(phase string) {
		if phase == "stability" {
			preStabilityDials = dials.Load()
		}
	})
	// connectivity: 2 dials (ECH rejection + fallback); 3 latency reconnects: 1 dial each (no ECH retry) = 5 dials before stability.
	if r.Status != "valid" || r.ECHStatus != "fallback" || r.HTTPSuccess != httpProbeCount || r.TCPSuccess != tcpProbeCount || requests.Load() != 1+httpProbeCount || preStabilityDials != 2+httpProbeCount || dials.Load() != 2+httpProbeCount+tcpProbeCount {
		t.Fatalf("%+v preStability=%d dials=%d requests=%d", r, preStabilityDials, dials.Load(), requests.Load())
	}
}
