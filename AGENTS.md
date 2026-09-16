# X Client (Android)

Android 多协议 VPN 客户端，支持每个 Profile 配置一个代理协议。

基于 [gcm-client](https://github.com/v2up-32mb/gcm-client) 改造，复用其 Profile 管理、VPN 隧道框架、路由绕过、全局设置等功能，扩展为多协议架构。

## 技术栈

- **Android 层**：Java 17 / AGP 8.x / Material / ZXing，包名 `com.x.client.app`，`applicationId` 同名
- **Go 核心库**：`golib/`（`module xclient`，Go 1.23），通过 `gomobile bind` 编译为 `app/libs/xclient.aar`
  - `android.go`（package xclient）是 gomobile 入口 thin wrapper
  - 完整 package：`config / dns / ech / logger / pool / protocol / relay / routing / socks5`
  - 完整保留 ECH / DoH / 连接池 / 流复用 / quality_monitor / relay / warmup 全部能力
- **VPN 隧道**：[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（CI 阶段 `git clone` 到 `app/src/main/jni`）
- **SOCKS5 / HTTP 代理**：由 Go 核心库在本地监听，`hev-socks5-tunnel` 将 VPN Tun 流量转发到该 SOCKS5

## 协议支持

### GCM（当前已实现）

GCM 二进制多路复用协议（2 字节头）：

```
[STREAM_ID:1][TYPE:1][可选 DATA]
TYPE = 0 CONNECT    DATA = ASCII "host:port|"
TYPE = 1 CONNECTED  无 DATA
TYPE = 2 CLOSE       无 DATA
```

WebSocket 连接：`wss://<workerHost>/<userID>?fallbackip=<出口IP列表>`

### x-tunnel（已实现）

多通道 WebSocket 隧道，8 字节头协议（connID + msgType），支持通道选择机制 +
Hot Pair + UDP associate + 独立 HTTP 代理 + 背压控制。协议细节见 `golib/README.md`。

## 构建

**重要约束: 本项目构建任务均使用 GitHub Actions，不允许在本地尝试构建。**

### CI/CD 工作流

- **AAR 预热**: `.github/workflows/build-aar.yml`
  - 推送到 `main`（`golib/**` 变更）或手动触发；固定从 `main` 构建 `xclient.aar`
  - 目的：将 gomobile 交叉编译产物落到默认分支作用域缓存（所有 workflow 可读），tag 发布直接命中

- **Release 构建**: `.github/workflows/release.yml`
  - **tag 触发**（`v*`）：构建 release APK → 签名 → 自动创建 GitHub Release（语义化版本带 `-preview`/`-beta`/`-rc` 后缀自动标记 prerelease）
  - **手动 workflow_dispatch**：仅普通 CI 构建验证（release 构建类型，unsigned，不发版）；版本名 `ci-<shortsha>`
  - 缓存：`gradle.properties` 开启 `org.gradle.caching=true`；`gomobile` AAR 按 `golib/**` 内容哈希缓存

## 配置导出/导入 URI

支持 `gcm://`、`ech://`（兼容）和 `xtunnel://` 导入导出：

```
gcm://<workerHost>?ip=<优选中转IP:端口>&fip=<出口代理IP>&user_id=<用户ID>&dns=<DoH服务器>&domain=<ECH查询域名>&disable_ech=1#<配置名称>
xtunnel://<serverAddr>?token=<Token>&relay_nodes=<节点,逗号分隔>&connections=<连接数>&ech=0|1&domain=<ECH域名>&dns=<DoH服务器>&insecure=1&hotpair=1#<配置名称>
```

Profile 编辑页按协议显示字段：GCM（WorkerHost/PrefIp/UserId/FallbackIp/DisableEch/DisableIpv6Route）与
X-Tunnel（ServerAddr wss:///Token/RelayNodes/Connections/EnableECH/ECHDomain/DNSServer/Insecure/HotPair）。

## Go 入口 API（gomobile AAR）

Android 侧统一通过 `xclient.Xclient` 调用：

- `startSocksProxy(listenAddr, protocol, paramsJSON, verbose)` — 按协议启动代理
  - `protocol`：`"gcm"`（默认，空值向后兼容）或 `"xtunnel"`（阶段 3 实现）
  - `paramsJSON`：协议参数 JSON 对象（`{"key": "value", ...}`）
  - `verbose`：调试日志开关
- `stopSocksProxy()` — 停止当前代理并释放资源
- `reconnect(reason)` / `notifyNetworkChanged()` — 重连与网络切换
- `getRuntimeLogs()` / `appendRuntimeLog(scope, message)` — 运行时日志缓冲
- `validateBypassRules(rules)` — 校验路由绕过规则

GCM 协议参数（TProxyService 组装）：

```
worker_host, ws_conn, relay_ips, user_id, proxy_ip,
ech_domain, ech_dns, enable_ech, disable_ipv6_route,
enable_dns_warmup, bypass_private, bypass_geoip_cn,
bypass_geosite_cn, bypass_rules, enable_dynamic_pool, dynamic_pool_max
```

Profile 的 `Protocol` 字段（默认 `gcm`）决定分发目标；`gcm://` URI 导入的旧 Profile 自动沿用 GCM 协议。

X-Tunnel 协议参数（阶段 3 已实现分发，UI 参数编辑在阶段 5）：

```
server_addr (wss:// 必填), token, connections, client_id,
relay_nodes (逗号分隔), enable_ech, ech_domain, dns_server,
insecure, enable_hot_pair
```

共享 ECH 管理器（xclient/ech + xclient/dns）：DoH 多服务器 fallback 优先，
失败回退 UDP DNS（8.8.8.8:53，移植自 x-tunnel），再回退标准 TLS。
x-tunnel 连接池自带持续重连；SOCKS5/HTTP 无可用通道时返回标准失败应答。

## 开发注意事项

- Go module 名为 `xclient`，gomobile 生成的 AAR 类名前缀为 `xclient.Xclient`
- Android Java 包名 `com.x.client.app`，NDK PKGNAME `com/x/client/app`
- hev-socks5-tunnel 子模块在 CI 阶段 clone 到 `app/src/main/jni`
- 支持 4 种 ABI: armeabi-v7a, arm64-v8a, x86, x86_64
- 模块功能文档：[`golib/README.md`](golib/README.md)（Go 核心）与 [`app/README.md`](app/README.md)（Android）
- 本地开发过程文件（调研报告、历史日志、实施计划 `INTEGRATION_PLAN.md`）仅存于本地
  `docs/` 与仓库根目录，已在 `.gitignore` 声明，不推送远程

---

# MEMORY

- Go 工具链：系统默认 go 1.25.5（/usr/local/go，2026-09-02 替换原 1.20.14），无需再指定 GOROOT/PATH；1.20 时代的模块缓存/构建缓存已清理，protoc-gen-go{,-grpc} 已用 1.25.5 重建。
- 本机出站代理：socks5://127.0.0.1:30001 到 30004 共 4 个 SOCKS5 服务器（用户提供的，需要外网时可用，任取其一；2026-07 已实测可用）。
- HTTP→SOCKS5 桥已做成 pi 全局 skill `http-socks5-bridge`（目录 ~/.pi/agent/skills/http-socks5-bridge/）：用 `scripts/bridge.sh [status|start|stop|restart|test]` 管理；启动后监听 127.0.0.1:18080（HTTP CONNECT），round-robin 转发到上述 4 个 SOCKS5 端口（可用 BRIDGE_SOCKS_PORTS / BRIDGE_LISTEN_PORT 环境变量改）。只接受 http 代理参数的工具（fetch_content / web_search / source_check）proxy 参数填 http://127.0.0.1:18080。日志 /tmp/http2socks-bridge.log。

## Commit Message 规范

- 描述部分优先使用中文；专有名词（库名、组件名、类名、工具名等）用行内代码反引号包裹。
- 示例：feat(app): 引入 `Material 3` 主题与三层 `token`；fix(ci): 对齐 `apksigner` 的 `build-tools` 版本。
- Conventional Commits 的 type/scope 前缀保持英文（`feat`/`fix`/`refactor`/`docs`/`chore`/`ci`/`build`…），勿改：CI 的 release notes 按英文前缀分类。
