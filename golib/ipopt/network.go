package ipopt

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

var excluded = func() []netip.Prefix {
	out := []netip.Prefix{}
	for _, s := range []string{
		"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
		"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4",
		"::/96", "::ffff:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48", "100::/64", "2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20",
	} {
		out = append(out, netip.MustParsePrefix(s))
	}
	return out
}()

func publicIP(ip netip.Addr) bool {
	if !ip.IsValid() || ip.Zone() != "" {
		return false
	}
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, p := range excluded {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

// guardedDial checks every resolved address before dialing a literal. It avoids
// proxy environment variables, DNS rebinding between validation and connect,
// and mixed public/private DNS answers. Resolver operations inherit ctx.
func guardedDial(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	var ips []netip.Addr
	if ip, e := netip.ParseAddr(host); e == nil {
		ips = []netip.Addr{ip}
	} else {
		ips, err = net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, err
		}
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("DNS未返回地址")
	}
	for _, ip := range ips {
		if !publicIP(ip) {
			return nil, fmt.Errorf("阻止连接非公网地址: %s", ip)
		}
	}
	d := net.Dialer{Timeout: probeTimeout}
	var last error
	for _, ip := range ips {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		conn, e := d.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if e == nil {
			return conn, nil
		}
		last = e
	}
	return nil, last
}

// bindDial also binds Transport's potentially detached dial context to the
// actual task. The returned socket remains task-owned through TLS/body reads.
func bindDial(parent context.Context, dial dialFunc, ctx context.Context, network, address string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	stop := context.AfterFunc(parent, cancel)
	defer stop()
	defer cancel()
	if parent.Err() != nil {
		return nil, parent.Err()
	}
	conn, err := dial(ctx, network, address)
	if err != nil {
		return nil, err
	}
	owned := &contextConn{Conn: conn}
	owned.stop = context.AfterFunc(parent, func() { _ = conn.Close() })
	return owned, nil
}

type contextConn struct {
	net.Conn
	stop func() bool
}

func (c *contextConn) Close() error {
	c.stop()
	return c.Conn.Close()
}

func internetTransport(ctx context.Context) *http.Transport {
	return &http.Transport{Proxy: nil, DialContext: func(dctx context.Context, network, address string) (net.Conn, error) {
		return bindDial(ctx, guardedDial, dctx, network, address)
	},
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout: probeTimeout, ResponseHeaderTimeout: probeTimeout,
		DisableCompression: true, MaxIdleConns: 2, MaxIdleConnsPerHost: 1,
		IdleConnTimeout: time.Second, MaxResponseHeaderBytes: 32 << 10}
}

// freshTransport binds each auxiliary request (including each DoH fallback and
// fetch redirect) to its own deadline, rather than only the enclosing task.
// These requests do not need a shared keep-alive pool.
type freshTransport struct{}

func (freshTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	tr := internetTransport(req.Context())
	resp, err := tr.RoundTrip(req)
	if err != nil {
		tr.CloseIdleConnections()
		return nil, err
	}
	resp.Body = &transportBody{ReadCloser: resp.Body, closeIdle: tr.CloseIdleConnections}
	return resp, nil
}

type transportBody struct {
	io.ReadCloser
	closeIdle func()
}

func (b *transportBody) Close() error {
	err := b.ReadCloser.Close()
	b.closeIdle()
	return err
}

func safeRedirect(req *http.Request, via []*http.Request) error {
	if len(via) > 3 {
		return fmt.Errorf("重定向超过3次")
	}
	if _, err := safeURL(req.URL.String()); err != nil {
		return err
	}
	if via[len(via)-1].URL.Scheme == "https" && req.URL.Scheme != "https" {
		return fmt.Errorf("禁止HTTPS降级重定向")
	}
	return nil
}

func isHTML(header http.Header, body []byte) bool {
	ct := strings.ToLower(header.Get("Content-Type"))
	text := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(string(body), "\ufeff")))
	return strings.Contains(ct, "html") || strings.HasPrefix(text, "<!doctype html") || strings.HasPrefix(text, "<html") || strings.HasPrefix(text, "<head") || strings.HasPrefix(text, "<body")
}

func readLimited(body io.Reader, limit int64) ([]byte, error) {
	b, err := io.ReadAll(io.LimitReader(body, limit+1))
	if err != nil {
		return b, err
	}
	if int64(len(b)) > limit {
		return nil, fmt.Errorf("响应正文超过%d字节限制", limit)
	}
	return b, nil
}
