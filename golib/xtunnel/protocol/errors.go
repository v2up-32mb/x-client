// Package common 提供 client 和 server 共享的协议定义和工具函数
package protocol

import (
	"errors"
	"io"
	"net"

	"github.com/gorilla/websocket"
)

// shortID 返回短格式的连接 ID（用于日志）
func ShortID(id string) string {
	if len(id) >= 8 {
		return id[:8]
	}
	return id
}

// IsNormalCloseError 判断是否为正常的关闭错误
func IsNormalCloseError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) {
		return true
	}
	var ce *websocket.CloseError
	if errors.As(err, &ce) {
		switch ce.Code {
		case websocket.CloseNormalClosure, websocket.CloseGoingAway, websocket.CloseNoStatusReceived:
			return true
		}
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return true
	}
	// 检查错误消息（TLS 连接关闭等情况）
	errStr := err.Error()
	return ContainsString(errStr, "tls: bad record MAC") ||
		ContainsString(errStr, "use of closed network connection") ||
		ContainsString(errStr, "connection reset by peer") ||
		ContainsString(errStr, "broken pipe") ||
		ContainsString(errStr, "websocket: close sent")
}

// ContainsString 简单的字符串包含检查（避免导入 strings）
func ContainsString(s, substr string) bool {
	return len(s) >= len(substr) && FindSubstring(s, substr)
}

// FindSubstring 查找子串
func FindSubstring(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
