package ipopt

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// Return a public A plus a forbidden site-local AAAA, exercising the actual
// resolver wire parser and the all-addresses guard (not just literal URLs).
func siteLocalAnswer(query []byte, truncate bool) ([]byte, error) {
	var q dnsmessage.Message
	if err := q.Unpack(query); err != nil {
		return nil, err
	}
	r := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true, RecursionAvailable: true, Truncated: truncate}, Questions: q.Questions}
	if !truncate {
		for _, question := range q.Questions {
			h := dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 60, Type: question.Type}
			switch question.Type {
			case dnsmessage.TypeA:
				r.Answers = append(r.Answers, dnsmessage.Resource{Header: h, Body: &dnsmessage.AResource{A: [4]byte{1, 1, 1, 1}}})
			case dnsmessage.TypeAAAA:
				r.Answers = append(r.Answers, dnsmessage.Resource{Header: h, Body: &dnsmessage.AAAAResource{AAAA: netip.MustParseAddr("fec0::1").As16()}})
			}
		}
	}
	return r.Pack()
}

func TestPrivateResolverBootstrapFramingAndSiteLocalAnswer(t *testing.T) {
	for _, tcpFallback := range []bool{false, true} {
		t.Run(map[bool]string{false: "udp", true: "truncated-udp-tcp"}[tcpFallback], func(t *testing.T) {
			udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			tcp, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				udp.Close()
				t.Fatal(err)
			}
			var servers sync.WaitGroup
			servers.Add(2)
			defer func() { udp.Close(); tcp.Close(); servers.Wait() }()
			go func() {
				defer servers.Done()
				buf := make([]byte, 4096)
				for {
					n, addr, err := udp.ReadFrom(buf)
					if err != nil {
						return
					}
					answer, err := siteLocalAnswer(buf[:n], tcpFallback)
					if err != nil {
						t.Error(err)
						continue
					}
					_, _ = udp.WriteTo(answer, addr)
				}
			}()
			go func() {
				defer servers.Done()
				for {
					c, err := tcp.Accept()
					if err != nil {
						return
					}
					func() {
						defer c.Close()
						_ = c.SetDeadline(time.Now().Add(time.Second))
						var size [2]byte
						if _, err := io.ReadFull(c, size[:]); err != nil {
							t.Error(err)
							return
						}
						query := make([]byte, binary.BigEndian.Uint16(size[:]))
						if _, err := io.ReadFull(c, query); err != nil {
							t.Error(err)
							return
						}
						answer, err := siteLocalAnswer(query, false)
						if err != nil {
							t.Error(err)
							return
						}
						binary.BigEndian.PutUint16(size[:], uint16(len(answer)))
						_, _ = c.Write(append(size[:], answer...))
					}()
				}
			}()
			var tcpCalls atomic.Int32
			lookup := func(ctx context.Context, host string) ([]netip.Addr, error) {
				return lookupIPsWithDial(ctx, host, func(ctx context.Context, network, address string) (net.Conn, error) {
					if address != dnsBootstrap {
						t.Errorf("nonliteral/nonpublic bootstrap: %s", address)
					}
					if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > probeTimeout {
						t.Error("DNS dial lacks bounded deadline")
					}
					addr := udp.LocalAddr().String()
					if network == "tcp" {
						tcpCalls.Add(1)
						addr = tcp.Addr().String()
					}
					return (&net.Dialer{}).DialContext(ctx, network, addr)
				})
			}
			// This is the domain path used for fetch and DoH URLs. There must
			// be no destination dial, even though the A answer is public.
			c, err := guardedDialUsing(context.Background(), "tcp", "list.example.com:443", lookup, func(context.Context, string, string) (net.Conn, error) {
				t.Error("destination dial attempted for mixed site-local answers")
				return nil, errors.New("offline test forbids destination dial")
			})
			if c != nil {
				c.Close()
			}
			if err == nil || !strings.Contains(err.Error(), "fec0::1") {
				t.Fatalf("mixed site-local DNS answer accepted: %v", err)
			}
			if tcpFallback != (tcpCalls.Load() > 0) {
				t.Fatalf("TCP fallback calls=%d", tcpCalls.Load())
			}
		})
	}
}

type slowCloseConn struct {
	net.Conn
	closing chan<- struct{}
	release <-chan struct{}
	closes  atomic.Int32
	mu      sync.Mutex
	limit   time.Time
}

func (c *slowCloseConn) SetDeadline(at time.Time) error {
	c.mu.Lock()
	c.limit = at
	c.mu.Unlock()
	return c.Conn.SetDeadline(at)
}

func (c *slowCloseConn) Close() error {
	c.closes.Add(1)
	c.closing <- struct{}{}
	<-c.release
	return c.Conn.Close()
}

func TestResolverCancelJoinsSocketCloseAndTaskSlot(t *testing.T) {
	// No goroutine wraps LookupHost in production. This transport-like caller
	// deliberately returns on ctx cancellation before its dial has returned;
	// Manager must still join the real DNS exchange/Close before admitting I/O.
	closing := make(chan struct{}, 8)
	release := make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unblock()
	entered := make(chan struct{}, 8)
	var peers sync.WaitGroup
	var mu sync.Mutex
	var conns []*slowCloseConn
	dial := func(ctx context.Context, _, address string) (net.Conn, error) {
		if address != dnsBootstrap {
			t.Errorf("unexpected DNS destination %s", address)
		}
		a, b := net.Pipe()
		c := &slowCloseConn{Conn: a, closing: closing, release: release}
		mu.Lock()
		conns = append(conns, c)
		mu.Unlock()
		peers.Add(1)
		go func() {
			defer peers.Done()
			defer b.Close()
			buf := make([]byte, 4096)
			if _, err := b.Read(buf); err == nil {
				entered <- struct{}{}
			}
			_, _ = io.Copy(io.Discard, b)
		}()
		return c, nil
	}
	dialDone := make(chan error, 1)
	ops := fakeOperations()
	ops.fetch = func(ctx context.Context, _ Source) (fetchOutput, error) {
		go func() {
			_, err := bindDial(ctx, func(ctx context.Context, network, address string) (net.Conn, error) {
				return guardedDialUsing(ctx, network, address, func(ctx context.Context, host string) ([]netip.Addr, error) {
					return lookupIPsWithDial(ctx, host, dial)
				}, func(context.Context, string, string) (net.Conn, error) {
					t.Error("destination dial attempted after DNS cancellation")
					return nil, errors.New("offline test forbids destination dial")
				})
			}, context.Background(), "tcp", "list.example.com:443")
			dialDone <- err
		}()
		<-ctx.Done()
		return fetchOutput{}, ctx.Err()
	}
	m := newManager(ops, time.Now)
	request := `{"operation":"fetch","network_id":"net","source":{"id":"list","url":"https://list.example.com/","kind":"list"}}`
	id, err := m.Start(request)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Cancel(id)
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("DNS exchange never started")
	}
	m.Cancel(id)
	select {
	case <-closing:
	case <-time.After(time.Second):
		t.Fatal("task cancellation did not close DNS socket")
	}
	if _, err := m.Start(request); err == nil || !strings.Contains(err.Error(), "busy") {
		t.Fatalf("slot released before DNS socket close: %v", err)
	}
	select {
	case err := <-dialDone:
		t.Fatalf("lookup returned while socket close blocked: %v", err)
	default:
	}
	unblock()
	if s := waitDone(t, m, id); s.State != "cancelled" {
		t.Fatal(s)
	}
	select {
	case err := <-dialDone:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("lookup cancel: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("DNS lookup did not finish")
	}
	peers.Wait()
	mu.Lock()
	defer mu.Unlock()
	for _, c := range conns {
		c.mu.Lock()
		deadline := c.limit
		c.mu.Unlock()
		if c.closes.Load() != 1 || deadline.IsZero() {
			t.Fatalf("DNS closes=%d deadline=%v", c.closes.Load(), deadline)
		}
	}
	worker, _ := m.find(id)
	worker.io.mu.Lock()
	defer worker.io.mu.Unlock()
	if len(worker.io.conns) != 0 || !worker.io.closed {
		t.Fatal("task I/O tracking was not released")
	}
}

func TestOwnedConnectionCloseStopsCallbackAndUntracks(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	a, b := net.Pipe()
	defer b.Close()
	release := make(chan struct{})
	close(release)
	raw := &slowCloseConn{Conn: a, closing: make(chan struct{}, 8), release: release}
	scope := newIOScope()
	tracked, err := scope.track(raw)
	if err != nil {
		t.Fatal(err)
	}
	c := ownConn(ctx, tracked)
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	c.Close()
	cancel()
	scope.close()
	if raw.closes.Load() != 1 || len(scope.conns) != 0 {
		t.Fatalf("close count=%d tracked=%d", raw.closes.Load(), len(scope.conns))
	}
	if scope.begin() {
		t.Fatal("closed task admitted another dial")
	}
}

func TestResolverDeadlineClosesDNSExchange(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	close(release)
	var peers sync.WaitGroup
	var mu sync.Mutex
	var conns []*slowCloseConn
	_, err := lookupIPsWithDial(ctx, "deadline.example.com", func(ctx context.Context, _, _ string) (net.Conn, error) {
		a, b := net.Pipe()
		c := &slowCloseConn{Conn: a, closing: make(chan struct{}, 8), release: release}
		mu.Lock()
		conns = append(conns, c)
		mu.Unlock()
		peers.Add(1)
		go func() {
			defer peers.Done()
			defer b.Close()
			_, _ = io.Copy(io.Discard, b) // accepts the query, never responds
		}()
		return c, nil
	})
	var timeout net.Error
	if !errors.Is(err, context.DeadlineExceeded) && !(errors.As(err, &timeout) && timeout.Timeout()) {
		t.Fatalf("lookup deadline: %v", err)
	}
	peers.Wait()
	mu.Lock()
	defer mu.Unlock()
	for _, c := range conns {
		if c.closes.Load() != 1 {
			t.Fatalf("deadline leaked/reclosed DNS socket: %d", c.closes.Load())
		}
	}
}
