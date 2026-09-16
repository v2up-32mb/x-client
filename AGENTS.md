# MEMORY

- Go 工具链：系统默认 go 1.25.5（/usr/local/go，2026-09-02 替换原 1.20.14），无需再指定 GOROOT/PATH；1.20 时代的模块缓存/构建缓存已清理，protoc-gen-go{,-grpc} 已用 1.25.5 重建。
- 本机出站代理：socks5://127.0.0.1:30001 到 30004 共 4 个 SOCKS5 服务器（用户提供的，需要外网时可用，任取其一；2026-07 已实测可用）。
- HTTP→SOCKS5 桥已做成 pi 全局 skill `http-socks5-bridge`（目录 ~/.pi/agent/skills/http-socks5-bridge/）：用 `scripts/bridge.sh [status|start|stop|restart|test]` 管理；启动后监听 127.0.0.1:18080（HTTP CONNECT），round-robin 转发到上述 4 个 SOCKS5 端口（可用 BRIDGE_SOCKS_PORTS / BRIDGE_LISTEN_PORT 环境变量改）。只接受 http 代理参数的工具（fetch_content / web_search / source_check）proxy 参数填 http://127.0.0.1:18080。日志 /tmp/http2socks-bridge.log。

## Commit Message 规范

- 描述部分优先使用中文；专有名词（库名、组件名、类名、工具名等）用行内代码反引号包裹。
- 示例：feat(app): 引入 `Material 3` 主题与三层 `token`；fix(ci): 对齐 `apksigner` 的 `build-tools` 版本。
- Conventional Commits 的 type/scope 前缀保持英文（`feat`/`fix`/`refactor`/`docs`/`chore`/`ci`/`build`…），勿改：CI 的 release notes 按英文前缀分类。
