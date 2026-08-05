package logger

import (
	"testing"

	"xclient/shared/config"
)

func TestInitGlobalLoggerPropagatesToExistingLoggers(t *testing.T) {
	// 模拟 xtunnel 包级 sysLog：在 InitGlobalLogger 之前就创建了 Logger。
	l := GetLogger("PropagateTest")
	if l.level != config.INFO {
		t.Fatalf("fresh logger level = %v, want INFO", l.level)
	}

	InitGlobalLogger(&config.Config{LogLevel: config.DEBUG})
	defer func() {
		// 恢复默认级别，避免影响同包其他测试
		InitGlobalLogger(&config.Config{LogLevel: config.INFO})
	}()

	if globalLevel != config.DEBUG {
		t.Fatalf("globalLevel = %v, want DEBUG", globalLevel)
	}
	if l.level != config.DEBUG {
		t.Fatalf("existing logger level = %v, want DEBUG (must be propagated)", l.level)
	}

	// 级别下调同样生效
	InitGlobalLogger(&config.Config{LogLevel: config.ERROR})
	if l.level != config.ERROR {
		t.Fatalf("existing logger level after downgrade = %v, want ERROR", l.level)
	}
}
