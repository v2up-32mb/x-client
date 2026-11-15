package ipopt

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	shareddns "github.com/v2up-32mb/xshared/dns"
	"golang.org/x/net/dns/dnsmessage"
)

// ECH preparation deliberately does not call xshared's background-context
// manager. This RFC8484/type65 path is owned by one task, has no cache refresh
// goroutines, and binds DoH, UDP and body reads to its cancellation/deadlines.
func prepareECH(ctx context.Context, o Options, rt http.RoundTripper, udpDial func(context.Context, string, string) (net.Conn, error)) ([]byte, []string, error) {
	if !o.EnableECH {
		return nil, []string{}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if rt == nil {
		rt = freshTransport{}
	}
	query, id, err := echQuestion(o.ECHDomain, rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	warnings := []string{}
	servers := []string{o.DNSServer}
	if o.DNSServer == "" {
		// Reuse only the pinned defaults, never the uncancellable shared client/manager.
		servers = append([]string{}, shareddns.DefaultDoHServers...)
	}
	servers = appendUnique(servers, "https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query")
	client := &http.Client{Transport: rt, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return fmt.Errorf("DoH禁止重定向") }}
	defer client.CloseIdleConnections()
	for _, server := range servers {
		if err := ctx.Err(); err != nil {
			break
		}
		data, e := queryECHDoH(ctx, client, server, query, id, o.ECHDomain)
		if e == nil {
			return data, warnings, nil
		}
		warnings = append(warnings, "ECH DoH失败: "+server+": "+e.Error())
	}
	if ctx.Err() == nil {
		data, e := queryECHUDP(ctx, udpDial, query, id, o.ECHDomain)
		if e == nil {
			return data, warnings, nil
		}
		warnings = append(warnings, "ECH UDP回退失败: "+e.Error())
	}
	if err := ctx.Err(); err != nil {
		return nil, warnings, err
	}
	return nil, warnings, fmt.Errorf("未获取ECH配置，将使用验证证书的标准TLS")
}

func echQuestion(domain string, entropy io.Reader) ([]byte, uint16, error) {
	var idBytes [2]byte
	if _, err := io.ReadFull(entropy, idBytes[:]); err != nil {
		return nil, 0, err
	}
	id := binary.BigEndian.Uint16(idBytes[:])
	name, err := dnsmessage.NewName(strings.TrimSuffix(domain, ".") + ".")
	if err != nil {
		return nil, 0, err
	}
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id, RecursionDesired: true})
	if err = b.StartQuestions(); err != nil {
		return nil, 0, err
	}
	if err = b.Question(dnsmessage.Question{Name: name, Type: dnsmessage.Type(65), Class: dnsmessage.ClassINET}); err != nil {
		return nil, 0, err
	}
	buf, err := b.Finish()
	return buf, id, err
}

func queryECHDoH(ctx context.Context, client *http.Client, server string, query []byte, id uint16, domain string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server, bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 || !strings.HasPrefix(strings.ToLower(resp.Header.Get("Content-Type")), "application/dns-message") {
		return nil, fmt.Errorf("DoH响应不是200 DNS wire message")
	}
	body, err := readLimited(resp.Body, 65535)
	if err != nil {
		return nil, err
	}
	return parseECHAnswer(body, id, domain)
}

func queryECHUDP(ctx context.Context, dial func(context.Context, string, string) (net.Conn, error), query []byte, id uint16, domain string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	conn, err := dial(ctx, "udp", "8.8.8.8:53")
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	deadline, _ := ctx.Deadline()
	if err = conn.SetDeadline(deadline); err != nil {
		return nil, err
	}
	if _, err = conn.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	return parseECHAnswer(buf[:n], id, domain)
}

func parseECHAnswer(buf []byte, id uint16, domain string) ([]byte, error) {
	var p dnsmessage.Parser
	h, err := p.Start(buf)
	if err != nil {
		return nil, err
	}
	if h.ID != id || !h.Response || h.OpCode != 0 || h.Truncated || h.RCode != dnsmessage.RCodeSuccess {
		return nil, fmt.Errorf("DNS响应ID/标志/状态不匹配或被截断")
	}
	qs, err := p.AllQuestions()
	if err != nil || len(qs) != 1 || qs[0].Type != dnsmessage.Type(65) || qs[0].Class != dnsmessage.ClassINET || !strings.EqualFold(qs[0].Name.String(), strings.TrimSuffix(domain, ".")+".") {
		return nil, fmt.Errorf("DNS问题不匹配")
	}
	// Permit a bounded CNAME chain within this response, but not unrelated
	// answer records. Alias-mode HTTPS records need a new query and fall back.
	allowed := map[string]bool{strings.ToLower(qs[0].Name.String()): true}
	answers, err := p.AllAnswers()
	if err != nil {
		return nil, err
	}
	for round := 0; round < 8; round++ {
		for _, r := range answers {
			if c, ok := r.Body.(*dnsmessage.CNAMEResource); ok && allowed[strings.ToLower(r.Header.Name.String())] {
				allowed[strings.ToLower(c.CNAME.String())] = true
			}
		}
	}
	for _, r := range answers {
		if r.Header.Type != dnsmessage.Type(65) || r.Header.Class != dnsmessage.ClassINET || !allowed[strings.ToLower(r.Header.Name.String())] {
			continue
		}
		if unknown, ok := r.Body.(*dnsmessage.UnknownResource); ok {
			if config, e := parseHTTPSParams(unknown.Data); e == nil {
				return config, nil
			}
		}
	}
	return nil, fmt.Errorf("DNS HTTPS记录中没有可用ECH配置")
}

func parseHTTPSParams(data []byte) ([]byte, error) {
	if len(data) < 3 || binary.BigEndian.Uint16(data[:2]) == 0 {
		return nil, fmt.Errorf("无效或alias-mode HTTPS记录")
	}
	pos := 2
	// RFC9460 TargetName MUST NOT use compression.
	for {
		if pos >= len(data) {
			return nil, fmt.Errorf("截断的HTTPS target")
		}
		n := int(data[pos])
		pos++
		if n == 0 {
			break
		}
		if n > 63 || pos+n > len(data) {
			return nil, fmt.Errorf("无效的HTTPS target")
		}
		pos += n
	}
	last := -1
	var config []byte
	for pos < len(data) {
		if pos+4 > len(data) {
			return nil, fmt.Errorf("截断的SvcParam")
		}
		key := int(binary.BigEndian.Uint16(data[pos : pos+2]))
		n := int(binary.BigEndian.Uint16(data[pos+2 : pos+4]))
		pos += 4
		if key <= last || pos+n > len(data) {
			return nil, fmt.Errorf("无序或截断的SvcParam")
		}
		last = key
		if key == 5 {
			v := data[pos : pos+n]
			if n < 3 || int(binary.BigEndian.Uint16(v[:2])) != n-2 {
				return nil, fmt.Errorf("无效的ECHConfigList长度")
			}
			config = append([]byte{}, v...)
		}
		pos += n
	}
	if len(config) == 0 {
		return nil, fmt.Errorf("HTTPS记录缺少ECH key5")
	}
	return config, nil
}
