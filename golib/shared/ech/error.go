package ech

import (
	"crypto/tls"
	"errors"
	"strings"
)

// IsECHRelatedError 判断拨号错误是否为 ECH 相关失败。
//
// Go 1.24+ 客户端在服务器拒绝 ECH 时返回 *tls.ECHRejectionError，
// 错误串固定为 "tls: server rejected ECH"，与历史的小写 "ech" /
// "tls: handshake failure" 等字符串匹配条件大小写不一致，导致 GCM 的
// ECH 降级逻辑从未被触发（详见 docs/golib-ech-downgrade-verification.md）。
// 因此优先按错误类型判定（errors.As，可穿透 net.OpError 等包装），
// 字符串匹配仅作为兜底，保留 GCM/xtunnel 原有的匹配行为。
func IsECHRelatedError(err error) bool {
	if err == nil {
		return false
	}
	var rejected *tls.ECHRejectionError
	if errors.As(err, &rejected) {
		return true
	}
	s := err.Error()
	return strings.Contains(s, "ECH") ||
		strings.Contains(s, "ech") ||
		strings.Contains(s, "encrypted_client_hello") ||
		strings.Contains(s, "tls: handshake failure")
}
