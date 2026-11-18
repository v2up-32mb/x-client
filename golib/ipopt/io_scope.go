package ipopt

import (
	"context"
	"net"
	"sync"
)

// HTTP Transport may return a cancelled request while its dial is unwinding.
// Keep those dials and their live sockets task-owned until actual cleanup, not
// merely until RoundTrip returns. Closing the gate prevents late I/O admission.
type ioScopeKey struct{}

type ioScope struct {
	mu     sync.Mutex
	closed bool
	dials  sync.WaitGroup
	conns  map[*trackedConn]struct{}
}

func newIOScope() *ioScope { return &ioScope{conns: make(map[*trackedConn]struct{})} }

func (s *ioScope) begin() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.dials.Add(1)
	return true
}

func (s *ioScope) track(raw net.Conn) (net.Conn, error) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		_ = raw.Close()
		return nil, context.Canceled
	}
	c := &trackedConn{Conn: raw, owner: s}
	s.conns[c] = struct{}{}
	s.mu.Unlock()
	return c, nil
}

// Caller cancels the task first. No lock is held during actual Close or Wait;
// normal socket Close unregisters itself, so tracking is bounded by live I/O.
func (s *ioScope) close() {
	s.mu.Lock()
	s.closed = true
	conns := make([]*trackedConn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
	s.dials.Wait()
}

type trackedConn struct {
	net.Conn
	owner *ioScope
	once  sync.Once
	err   error
}

func (c *trackedConn) Close() error {
	c.once.Do(func() {
		c.err = c.Conn.Close()
		c.owner.mu.Lock()
		delete(c.owner.conns, c)
		c.owner.mu.Unlock()
	})
	return c.err
}
