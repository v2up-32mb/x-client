package ech

import (
	"crypto/ecdh"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestIsECHRelatedError(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		// 回归核心：Go 1.24+ 的 ECH 拒绝错误串为 "tls: server rejected ECH"，
		// 历史小写 "ech" 匹配对其不生效
		{"ECHRejectionError", &tls.ECHRejectionError{}, true},
		{"ECHRejectionError with retry configs", &tls.ECHRejectionError{RetryConfigList: []byte{0x00, 0x01}}, true},
		{"wrapped in net.OpError", &net.OpError{Op: "dial", Err: &tls.ECHRejectionError{}}, true},
		{"wrapped in fmt.Errorf %w", fmt.Errorf("dial tcp 1.2.3.4:443: %w", &tls.ECHRejectionError{}), true},
		// 兜底：原有字符串匹配行为保持
		{"legacy handshake failure", errors.New("tls: handshake failure"), true},
		{"legacy encrypted_client_hello", errors.New("tls: malformed encrypted_client_hello extension"), true},
		{"legacy lowercase ech in cert error", errors.New("tls: failed to verify certificate: x509: certificate is valid for a.com, not cloudflare-ech.com"), true},
		// 无关错误不得命中
		{"unrelated timeout", errors.New("dial tcp 1.2.3.4:443: i/o timeout"), false},
		{"unrelated refused", errors.New("dial tcp 1.2.3.4:443: connect: connection refused"), false},
		{"unrelated reset", errors.New("read tcp 1.2.3.4:443: connection reset by peer"), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := IsECHRelatedError(tc.err); got != tc.want {
				t.Fatalf("IsECHRelatedError(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

// buildTestECHConfigList 构造最小合法 ECHConfigList（X25519Mlipike + HKDF-SHA256/AES-128-GCM）。
// 服务器端无需持有私钥：客户端对不支持 ECH 的服务器总会判定为拒绝（accept_confirmation 不匹配）。
func buildTestECHConfigList(publicName string) []byte {
	pub, err := func() ([]byte, error) {
		priv, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		return priv.PublicKey().Bytes(), nil
	}()
	if err != nil {
		panic(err)
	}
	inner := []byte{}
	inner = append(inner, 0x01)       // config_id
	inner = append(inner, 0x00, 0x20) // kem_id = X25519Mlipike
	inner = append(inner, byte(len(pub)>>8), byte(len(pub)))
	inner = append(inner, pub...)
	inner = append(inner, 0x00, 0x04)             // cipher_suites 长度
	inner = append(inner, 0x00, 0x01, 0x00, 0x01) // {HKDF-SHA256, AES-128-GCM}
	inner = append(inner, byte(len(publicName)))  // maximum_name_length
	inner = append(inner, byte(len(publicName)))  // public_name 长度
	inner = append(inner, publicName...)
	inner = append(inner, 0x00, 0x00) // extensions 长度

	ec := make([]byte, 0, len(inner)+4)
	ec = append(ec, 0xfe, 0x0d) // version
	ec = append(ec, byte(len(inner)>>8), byte(len(inner)))
	ec = append(ec, inner...)

	list := make([]byte, 0, len(ec)+2)
	list = append(list, byte(len(ec)>>8), byte(len(ec)))
	list = append(list, ec...)
	return list
}

// startTestTLSServer 启动本地 TLS 1.3 服务器（不支持 ECH），证书对 publicName 有效。
// 返回监听器与包含自签根的 CA 池。
func startTestTLSServer(t *testing.T, publicName string) (net.Listener, *x509.CertPool) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: publicName},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:              []string{publicName},
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(leaf)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	tlsCfg := &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS13}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {})
	go http.Serve(tls.NewListener(ln, tlsCfg), mux)
	return ln, roots
}

// TestECHRejectionErrorIsDetected 离线端到端回归：真实握手路径上，
// ECH 客户端对无 ECH 服务器的错误必须能被 IsECHRelatedError 识别。
// 防止未来 Go 调整错误类型/包装方式时匹配再次漂移。
func TestECHRejectionErrorIsDetected(t *testing.T) {
	const publicName = "test-ech.example.com"
	list := buildTestECHConfigList(publicName)

	ln, roots := startTestTLSServer(t, publicName)
	defer ln.Close()

	cfg := &tls.Config{
		ServerName:                     publicName,
		RootCAs:                        roots,
		MinVersion:                     tls.VersionTLS13,
		EncryptedClientHelloConfigList: list,
	}
	conn, err := tls.Dial("tcp", ln.Addr().String(), cfg)
	if err == nil {
		conn.Close()
		t.Fatal("expected ECH rejection error, got successful connection")
	}
	var rejected *tls.ECHRejectionError
	if !errors.As(err, &rejected) {
		t.Fatalf("expected *tls.ECHRejectionError, got %T: %v", err, err)
	}
	if !IsECHRelatedError(err) {
		t.Fatalf("IsECHRelatedError(%v) = false, want true", err)
	}
}
