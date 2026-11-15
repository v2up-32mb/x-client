package ipopt

import (
	"context"
	"net"
	"net/netip"
	"strings"
)

// Bootstrap is a literal public DNS endpoint: never consult Android's libc
// resolver, the local network's DNS servers, or guardedDial recursively.
const dnsBootstrap = "1.1.1.1:53"

func lookupIPs(ctx context.Context, host string) ([]netip.Addr, error) {
	return lookupIPsWithDial(ctx, host, (&net.Dialer{Timeout: probeTimeout}).DialContext)
}

func lookupIPsWithDial(ctx context.Context, host string, dial dialFunc) ([]netip.Addr, error) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	r := &net.Resolver{PreferGo: true, StrictErrors: true}
	r.Dial = func(dctx context.Context, network, _ string) (net.Conn, error) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		raw, err := dial(dctx, network, dnsBootstrap)
		if err != nil {
			return nil, err
		}
		c := ownConn(dctx, raw)
		deadline, _ := dctx.Deadline()
		if err := c.SetDeadline(deadline); err != nil {
			_ = c.Close()
			return nil, err
		}
		// The Go resolver chooses UDP framing by this interface, not network.
		// Hiding PacketConn behind net.Conn would incorrectly send TCP frames.
		if pc, ok := raw.(net.PacketConn); ok {
			return &dnsPacketConn{contextConn: c, packet: pc}, nil
		}
		return c, nil
	}
	// On Go 1.25 Unix/Android, LookupHost directly runs the Go lookup and
	// joins its A/AAAA exchanges. Unlike LookupNetIP/LookupIPAddr it does NOT
	// use the detached singleflight lookup that may return before cleanup.
	// Every DNS exchange closes its owned socket before this call returns.
	// Absolute names also prevent local search suffix queries.
	hosts, err := r.LookupHost(ctx, strings.TrimSuffix(host, ".")+".")
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		return nil, err
	}
	ips := make([]netip.Addr, 0, len(hosts))
	for _, host := range hosts {
		ip, err := netip.ParseAddr(host)
		if err != nil {
			return nil, err
		}
		ips = append(ips, ip)
	}
	return ips, nil
}

type dnsPacketConn struct {
	*contextConn
	packet net.PacketConn
}

func (c *dnsPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	return c.packet.ReadFrom(p)
}

func (c *dnsPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	return c.packet.WriteTo(p, addr)
}
