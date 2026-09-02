package pool

import (
	"crypto/tls"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"xclient/shared/config"
	"xclient/shared/logger"
)

// fakeEchManager 记录 GetTlsConfig 收到的 useECH 参数与 Refresh 调用次数。
type fakeEchManager struct {
	mu           sync.Mutex
	lastUseEch   *bool
	refreshCalls int32
}

func (f *fakeEchManager) GetTlsConfig(domain string, useEch bool) (*tls.Config, error) {
	f.mu.Lock()
	f.lastUseEch = &useEch
	f.mu.Unlock()
	return &tls.Config{ServerName: domain, MinVersion: tls.VersionTLS13}, nil
}

func (f *fakeEchManager) Refresh(domain string) error {
	atomic.AddInt32(&f.refreshCalls, 1)
	return nil
}

func newTestPoolForECH() (*ConnectionPool, *fakeEchManager) {
	fm := &fakeEchManager{}
	return &ConnectionPool{
		cfg:        &config.Config{EnableECH: true, WorkerHost: "worker.example.com", MaxPoolSize: 2},
		log:        logger.GetLogger("TestPool"),
		echManager: fm,
	}, fm
}

// TestHandleDialErrorECHRejectionTriggersFallback 回归测试：
// Go 1.24+ 的 ECH 拒绝错误（*tls.ECHRejectionError，错误串 "tls: server rejected ECH"）
// 曾不被历史小写字符串匹配命中，导致连续失败计数不增长、降级窗口永不启用，
// 开启 ECH 的 Profile 遇到不接受 ECH 的服务器时永久拨号失败。
// 详见 docs/golib-ech-downgrade-verification.md。
func TestHandleDialErrorECHRejectionTriggersFallback(t *testing.T) {
	p, _ := newTestPoolForECH()
	rejErr := &tls.ECHRejectionError{}

	for i := 1; i <= 3; i++ {
		p.handleDialError(rejErr, nil)
	}

	p.mu.Lock()
	fallback := p.echFallbackEnabled
	until := p.echDisabledUntil
	p.mu.Unlock()

	if !fallback {
		t.Fatal("ECH 连续 3 次 ECHRejectionError 后应启用降级 (echFallbackEnabled=false)")
	}
	if until.Before(time.Now()) {
		t.Fatalf("echDisabledUntil 未设置为未来时间: %v", until)
	}
	if !until.Before(time.Now().Add(6 * time.Minute)) {
		t.Fatalf("echDisabledUntil 应约为 5 分钟后: %v", until)
	}
	if got := atomic.LoadInt32(&p.echFailureCount); got != 3 {
		t.Fatalf("echFailureCount = %d, want 3", got)
	}

	// 降级窗口内 getTLSConfig 必须请求 useECH=false（普通 TLS）
	p.getTLSConfig()
	p.echManager.(*fakeEchManager).mu.Lock()
	useEch := p.echManager.(*fakeEchManager).lastUseEch
	p.echManager.(*fakeEchManager).mu.Unlock()
	if useEch == nil || *useEch {
		t.Fatalf("降级窗口内 getTLSConfig 请求了 useECH=%v, want false", useEch)
	}
}

// TestHandleDialErrorECHRejectionRefreshesConfig 未达阈值时应异步刷新 ECH 配置。
func TestHandleDialErrorECHRejectionRefreshesConfig(t *testing.T) {
	p, fm := newTestPoolForECH()

	p.handleDialError(&tls.ECHRejectionError{}, nil)
	p.handleDialError(&tls.ECHRejectionError{}, nil)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if atomic.LoadInt32(&fm.refreshCalls) >= 2 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if got := atomic.LoadInt32(&fm.refreshCalls); got < 2 {
		t.Fatalf("2 次未达阈值的 ECH 失败应触发 2 次 Refresh, got %d", got)
	}
	p.mu.Lock()
	fallback := p.echFallbackEnabled
	p.mu.Unlock()
	if fallback {
		t.Fatal("未达到 3 次失败阈值时不应启用降级")
	}
}

// TestHandleDialErrorUnrelatedErrorDoesNotCount ECH 无关错误不得计入 ECH 失败。
func TestHandleDialErrorUnrelatedErrorDoesNotCount(t *testing.T) {
	p, _ := newTestPoolForECH()
	timeout := errors.New("dial tcp 1.2.3.4:443: i/o timeout")

	for i := 1; i <= 5; i++ {
		p.handleDialError(timeout, nil)
	}

	p.mu.Lock()
	fallback := p.echFallbackEnabled
	p.mu.Unlock()
	if got := atomic.LoadInt32(&p.echFailureCount); got != 0 {
		t.Fatalf("无关错误不应计入 ECH 失败: echFailureCount=%d", got)
	}
	if fallback {
		t.Fatal("无关错误不应触发 ECH 降级")
	}
}
