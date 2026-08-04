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
TYPE = 2 DATA       DATA = 任意二进制
TYPE = 3 CLOSE       无 DATA
```

WebSocket 连接：`wss://<workerHost>/<userID>?fallbackip=<出口IP列表>`

### x-tunnel（计划集成）

多通道 WebSocket 隧道，8 字节头协议，支持通道选择机制 + Hot Pair + UDP associate。
详见 `INTEGRATION_PLAN.md`。

## 构建

**重要约束: 本项目构建任务均使用 GitHub Actions，不允许在本地尝试构建。**

### CI/CD 工作流

- **Debug 构建**: `.github/workflows/build-debug.yml`
  - 推送到 `main`、`develop`、`feat/*`、`fix/*`、`repair/*` 分支触发
  - 也可通过手动 workflow_dispatch 触发
  - 构建产物: 4 个 ABI 的独立 APK

- **Release 构建**: `.github/workflows/release.yml`
  - 推送 `v*` tag 触发 (如 `v1.0.0`)
  - 自动签名并创建 GitHub Release

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
- 集成计划详见 `INTEGRATION_PLAN.md`
