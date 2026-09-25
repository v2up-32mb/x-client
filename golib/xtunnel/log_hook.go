package xtunnel

import (
	"fmt"

	"github.com/v2up-32mb/xshared/logger"
	xtlib "github.com/v2up-32mb/xtunnel"
)

// installCoreLogHook 注入 xtunnel 核心库日志钩子（方向 1 + 方向 2 适配）。
//
// 核心库默认静默，由本壳（golib）接管：按等级映射 xshared/logger 输出
// `[模块] 消息`，结构化领域事件（LogEvent.Domain）附加摘要字段，
// Android 侧可检索/按模块路由/按事件统计，无需解析字符串。
// 钩子并发安全（logger 内部加锁，核心库多 goroutine 同时触发无竞争）。
func installCoreLogHook() {
	xtlib.SetLogf(func(ev xtlib.LogEvent) {
		l := logger.GetLogger("Xtunnel")
		msg := fmt.Sprintf(ev.Format, ev.Args...)
		if suf := domainSuffix(ev.Domain); suf != "" {
			msg += suf
		}
		switch ev.Level {
		case xtlib.LevelDebug:
			l.Debug("[%s] %s", ev.Module, msg)
		case xtlib.LevelWarn:
			l.Warn("[%s] %s", ev.Module, msg)
		case xtlib.LevelError:
			l.Error("[%s] %s", ev.Module, msg)
		default: // LevelInfo
			l.Info("[%s] %s", ev.Module, msg)
		}
	})
}

// restoreCoreLogSilent 恢复核心库静默（Stop 时调用）。
func restoreCoreLogSilent() {
	xtlib.SetLogf(nil)
}

// domainSuffix 将结构化领域事件渲染为日志摘要后缀；无 Domain 时返回空串。
func domainSuffix(d *xtlib.DomainEvent) string {
	if d == nil {
		return ""
	}
	var s string
	switch p := d.Payload.(type) {
	case xtlib.ConnEvent:
		s = fmt.Sprintf("conn=%s client=%s target=%s TX=%d RX=%d", p.Event, p.Client, p.Target, p.UplinkCh, p.DownlinkCh)
	case xtlib.HotPairEvent:
		s = fmt.Sprintf("hotpair=%s key=%s ChA=%d ChB=%d", p.Event, p.Key, p.ChA, p.ChB)
	case xtlib.PoolEvent:
		s = fmt.Sprintf("pool=%s client=%s ch=%d detail=%s", p.Event, p.ClientID, p.ChID, p.Detail)
	default:
		s = fmt.Sprintf("%s=%v", d.Type, d.Payload)
	}
	return " | " + s
}