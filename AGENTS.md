# MEMORY

- Go 工具链：系统默认 go 1.25.5（/usr/local/go，2026-09-02 替换原 1.20.14），无需再指定 GOROOT/PATH；1.20 时代的模块缓存/构建缓存已清理，protoc-gen-go{,-grpc} 已用 1.25.5 重建。
- 本机出站代理：socks5://127.0.0.1:30001 到 30004 共 4 个 SOCKS5 服务器（用户提供的，需要外网时可用，任取其一；2026-07 已实测可用）。
- HTTP→SOCKS5 桥已做成 pi 全局 skill `http-socks5-bridge`（目录 ~/.pi/agent/skills/http-socks5-bridge/）：用 `scripts/bridge.sh [status|start|stop|restart|test]` 管理；启动后监听 127.0.0.1:18080（HTTP CONNECT），round-robin 转发到上述 4 个 SOCKS5 端口（可用 BRIDGE_SOCKS_PORTS / BRIDGE_LISTEN_PORT 环境变量改）。只接受 http 代理参数的工具（fetch_content / web_search / source_check）proxy 参数填 http://127.0.0.1:18080。日志 /tmp/http2socks-bridge.log。
