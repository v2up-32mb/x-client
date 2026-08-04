package xtunnel

import "xclient/shared/logger"

// sysLog 是 xtunnel 后端的统一日志器（与 GCM 后端共享运行时日志缓冲）。
var sysLog = logger.GetLogger("XTunnel")
