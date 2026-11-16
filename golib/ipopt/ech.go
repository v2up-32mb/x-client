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

	"golang.org/x/net/dns/dnsmessage"
)

// ECH preparation deliberately does not call xshared's background-context
// manager. This RFC8484/type65 path is owned by one task, has no cache refresh
// goroutines, and binds DoH, UDP and body reads to its cancellation/deadlines.
func prepareECH(ctx context.Context, o Options, rt http.RoundTripper, udpDial func(context.Context, string, string) (net.Conn, error)) ([]byte, []string, error) {
	data, _, warnings, err := prepareECHTTL(ctx, o, rt, udpDial)
	return data, warnings, err
}

func prepareECHTTL(ctx context.Context, o Options, rt http.RoundTripper, udpDial func(context.Context, string, string) (net.Conn, error)) ([]byte, time.Duration, []string, error) {
	if !o.EnableECH {
		return nil, 0, []string{}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if rt == nil {
		rt = freshTransport{}
	}
	query, id, err := echQuestion(o.ECHDomain, rand.Reader)
	if err != nil {
		return nil, 0, nil, err
	}
	warnings := []string{}
	servers := echServers(o)
	client := &http.Client{Transport: rt, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return fmt.Errorf("DoH禁止重定向") }}
	defer client.CloseIdleConnections()
	for _, server := range servers {
		if err := ctx.Err(); err != nil {
			break
		}
		data, ttl, e := queryECHDoHTTL(ctx, client, server, query, id, o.ECHDomain)
		if e == nil {
			return data, ttl, warnings, nil
		}
		warnings = append(warnings, "ECH DoH失败: "+server+": "+e.Error())
	}
	if ctx.Err() == nil {
		data, ttl, e := queryECHUDPTTL(ctx, udpDial, query, id, o.ECHDomain)
		if e == nil {
			return data, ttl, warnings, nil
		}
		warnings = append(warnings, "ECH UDP回退失败: "+e.Error())
	}
	if err := ctx.Err(); err != nil {
		return nil, 0, warnings, err
	}
	return nil, 0, warnings, fmt.Errorf("未获取ECH配置，将使用验证证书的标准TLS")
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
	data, _, err := queryECHDoHTTL(ctx, client, server, query, id, domain)
	return data, err
}

func queryECHDoHTTL(ctx context.Context, client *http.Client, server string, query []byte, id uint16, domain string) ([]byte, time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server, bytes.NewReader(query))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 || !strings.HasPrefix(strings.ToLower(resp.Header.Get("Content-Type")), "application/dns-message") {
		return nil, 0, fmt.Errorf("DoH响应不是200 DNS wire message")
	}
	body, err := readLimited(resp.Body, 65535)
	if err != nil {
		return nil, 0, err
	}
	return parseECHAnswerTTL(body, id, domain)
}

func queryECHUDP(ctx context.Context, dial func(context.Context, string, string) (net.Conn, error), query []byte, id uint16, domain string) ([]byte, error) {
	data, _, err := queryECHUDPTTL(ctx, dial, query, id, domain)
	return data, err
}

func queryECHUDPTTL(ctx context.Context, dial func(context.Context, string, string) (net.Conn, error), query []byte, id uint16, domain string) ([]byte, time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	conn, err := dial(ctx, "udp", "8.8.8.8:53")
	if err != nil {
		return nil, 0, err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	deadline, _ := ctx.Deadline()
	if err = conn.SetDeadline(deadline); err != nil {
		return nil, 0, err
	}
	if _, err = conn.Write(query); err != nil {
		return nil, 0, err
	}
	buf := make([]byte, 65535)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, 0, err
	}
	return parseECHAnswerTTL(buf[:n], id, domain)
}

func parseECHAnswer(buf []byte, id uint16, domain string) ([]byte, error) {
	data, _, err := parseECHAnswerTTL(buf, id, domain)
	return data, err
}

func parseECHAnswerTTL(buf []byte, id uint16, domain string) ([]byte, time.Duration, error) {
	var p dnsmessage.Parser
	h, err := p.Start(buf)
	if err != nil {
		return nil, 0, err
	}
	if h.ID != id || !h.Response || h.OpCode != 0 || h.Truncated || h.RCode != dnsmessage.RCodeSuccess {
		return nil, 0, fmt.Errorf("DNS响应ID/标志/状态不匹配或被截断")
	}
	qs, err := p.AllQuestions()
	if err != nil || len(qs) != 1 || qs[0].Type != dnsmessage.Type(65) || qs[0].Class != dnsmessage.ClassINET || !strings.EqualFold(qs[0].Name.String(), strings.TrimSuffix(domain, ".")+".") {
		return nil, 0, fmt.Errorf("DNS问题不匹配")
	}
	// Read type65 as wire RDATA explicitly: AllAnswers returns UnknownResource
	// in x/net v0.17.0 but HTTPSResource in v0.61.0. UnknownResource after a
	// header works for known types too, in both versions, and leaves our strict
	// TargetName/SvcParam validation independent of the dependency's decoder.
	var answers []dnsmessage.Resource
	for {
		header, e := p.AnswerHeader()
		if e == dnsmessage.ErrSectionDone {
			break
		}
		if e != nil {
			return nil, 0, e
		}
		var r dnsmessage.Resource
		if header.Type == dnsmessage.Type(65) {
			body, e := p.UnknownResource()
			if e != nil {
				return nil, 0, e
			}
			r = dnsmessage.Resource{Header: header, Body: &body}
		} else {
			// Answer reuses the pending header. Do not merely skip other types:
			// their malformed bodies must still fail, and CNAMEs need decoding.
			r, e = p.Answer()
			if e != nil {
				return nil, 0, e
			}
		}
		answers = append(answers, r)
	}
	// Read every answer before selecting ECH, including records following
	// an otherwise usable one. Permit up to eight CNAME hops within this reply;
	// HTTPS alias-mode targets still need a new query and are not followed.
	allowed := map[string]bool{strings.ToLower(qs[0].Name.String()): true}
	for round := 0; round < 8; round++ {
		next := map[string]bool{}
		for _, r := range answers {
			if c, ok := r.Body.(*dnsmessage.CNAMEResource); ok && r.Header.Class == dnsmessage.ClassINET && allowed[strings.ToLower(r.Header.Name.String())] {
				next[strings.ToLower(c.CNAME.String())] = true
			}
		}
		// Merge after the round so wire order cannot bypass the hop limit.
		for name := range next {
			allowed[name] = true
		}
	}
	for _, r := range answers {
		if r.Header.Type != dnsmessage.Type(65) || r.Header.Class != dnsmessage.ClassINET || !allowed[strings.ToLower(r.Header.Name.String())] {
			continue
		}
		if unknown, ok := r.Body.(*dnsmessage.UnknownResource); ok {
			if config, e := parseHTTPSParams(unknown.Data); e == nil {
				ttl := r.Header.TTL
				for _, alias := range answers {
					if _, ok := alias.Body.(*dnsmessage.CNAMEResource); ok && alias.Header.Class == dnsmessage.ClassINET && allowed[strings.ToLower(alias.Header.Name.String())] && alias.Header.TTL < ttl {
						ttl = alias.Header.TTL
					}
				}
				return config, time.Duration(ttl) * time.Second, nil
			}
		}
	}
	return nil, 0, fmt.Errorf("DNS HTTPS记录中没有可用ECH配置")
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
