# GCM Client (Android)

Android VPN 客户端，基于 GCM (Go Cloud Multiplexer) 2 字节头二进制流复用协议。
从 [ech-client](https://github.com/v2up-32mb/ech-client) 改造而来，复用其 Profile 管理与 VPN 隧道框架，替换核心 Go 库与协议层。

## 技术栈

- **Android 层**：Java 17 / AGP 8.x / Material / ZXing，包名 `com.gcm.client.app`，`applicationId` 同名
- **Go 核心库**：`golib/`（`module gcm`，Go 1.23，复用 GCM 主分支源码），通过 `gomobile bind` 编译为 `app/libs/gcm.aar`
  - 内含完整 package：`config / dns / ech / logger / pool / protocol / relay / socks5`
  - `android.go`（package gcm）是 gomobile 入口 thin wrapper，镜像 `main.go` 的初始化链
  - 完整保留 ECH / DoH / 连接池 / 流复用 / quality_monitor / relay / warmup 全部能力
- **VPN 隧道**：[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（CI 阶段 `git clone` 到 `app/src/main/jni`）
- **SOCKS5 / HTTP 代理**：由 Go 核心库在本地监听，`hev-socks5-tunnel` 将 VPN Tun 流量转发到该 SOCKS5

## 核心协议

GCM 二进制多路复用协议（2 字节头）：

```
[STREAM_ID:1][TYPE:1][可选 DATA]
TYPE = 0 CONNECT    DATA = ASCII "host:port|"
TYPE = 1 CONNECTED  无 DATA
TYPE = 2 DATA       DATA = 任意二进制
TYPE = 3 CLOSE       无 DATA
```

WebSocket 连接：`wss://<workerHost>/<userID>?fallbackip=<出口IP列表>`

- 多条 WS 连接共享同一个 `ConnectionPool`（GCM 主分支的 `pool.ConnectionPool`）
- `STREAM_ID` 为 1 字节，最多 256 个流，由本地分配
- 乐观响应：发送 CONNECT 后立即回 SOCKS5 成功，不等 CONNECTED 返回
- ECH：`enableECH=true` 时通过 DoH 查询 ECH 公钥（`ech.EchManager`，缓存+自动刷新），失败回落标准 TLS 1.3
- DoH：`dohURL` 非空则使用该 DoH 服务器，否则默认 `https://doh.pub/dns-query`
- DNS 预热默认关闭（`EnableDNSWarmup=false`），避免 Android 冷启动冲突；UI checkbox 可开启

## 字段映射（用户配置 → GCM 语义）

| Android pref key | 含义 | Go 参数对应 | Worker 协议 |
|---|---|---|---|
| `WorkerHost` | Worker 域名（如 `gcm.ics.de5.net`） | `workerHost` | TLS SNI + Host 头 |
| `UserId` | 用户路径标识（如 `v2up`） | `userID` | WS URL 路径 |
| `PrefIp`（优选中转节点） | 逗号分隔多个 `IP:端口`，TCP 中继点 | `relayIPs` | TLS SNI 保持 WorkerHost |
| `FallbackIp`（出口代理 IP） | 逗号分隔多个，透传给 Worker | `proxyIP` | `?fallbackip=` 查询参数 |
| `EchDomain` | ECH 查询域名（如 `cloudflare-ech.com`） | `echDomain` | DoH HTTPS RR 查询 |
| `EchDns` | DoH 服务器（默认 `https://doh.pub/dns-query`） | `dohURL` | `cfg.DoHUrl`，空则 Go 端默认 `https://doh.pub/dns-query` |
| `DisableEch` | 禁用 ECH（true=标准 TLS 1.3） | `enableECH`（取反） | — |
| `EnableDnsWarmup` | DNS 预热开关（默认 false，UI 可开关） | `enableDNSWarmup` | `--enable-dns-warmup` |
| `WsConn` | WebSocket 连接数（同时设 Min/MaxPoolSize） | `wsConn` | — |
| `DisableIpv6Route` | 保留参数（Go 库不直接使用，由 VPN 层负责） | `disableIPv6Route` | — |

### 与 GCM CLI 的对应

- `PREF_IP` ↔ GCM CLI `--relay/-r`（`RelayIPs`）：TCP 中转入口
- `FALLBACK_IP` ↔ GCM CLI `--proxy-ip/-p`（`ProxyIP`）：出口端代理 IP
- `WORKER_HOST` ↔ GCM CLI `--worker/-w`（`WorkerHost`）
- `ECH_DOMAIN` ↔ GCM CLI `--ech-domain`（`ECHDomain`）
- `ECH_DNS` ↔ GCM CLI `--doh-url`（`DoHUrl`，若 EchDns 留空则用 GCM 内置列表）
- `DISABLE_ECH` ↔ GCM CLI `--enable-ech`（取反）
- `ENABLE_DNS_WARMUP` ↔ GCM CLI `--enable-dns-warmup`
- `USER_ID` ↔ GCM CLI WS URL 路径

## 配置导出/导入 URI

格式（`gcm://`，兼容 `ech://` 导入）：

```
gcm://<workerHost>?ip=<优选中转IP:端口>&fip=<出口代理IP>&user_id=<用户ID>&dns=<DoH服务器>&domain=<ECH查询域名>&disable_ech=1#<配置名称>
```

- `ip` 对应 `PREF_IP`（可选，逗号分隔多个）
- `fip` 对应 `FALLBACK_IP`（可选，逗号分隔多个）
- `user_id` 对应用户路径标识（`token` 作为旧别名也兼容）
- `dns` 对应 `ECH_DNS`（可选）
- `domain` 对应 `ECH_DOMAIN`（可选）
- `disable_ech` 为 1/true/yes 时禁用 ECH（仅 true 才导出，默认不导出）
- 支持 URL fragment 编码配置名称

## 构建

### 本地构建

需要 JDK 17、Android SDK / NDK、Go 1.24+：

```bash
cd golib
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android -androidapi=24 -o ../app/libs/gcm.aar
cd ..
./gradlew assembleDebug
```

### CI 构建（GitHub Actions）

- `.github/workflows/build-debug.yml`：push 到 `feat/*` / `fix/*` 或 `workflow_dispatch` 时触发
  - 自动 `git clone` `hev-socks5-tunnel` 到 `app/src/main/jni`
  - 条件编译 `gcm.aar`（`gomobile bind`）并跳过 if 已存在
  - 编译并按 ABI 上传 APK artifacts
- `.github/workflows/release.yml`：push `v*` tag 时触发，对 release APK 签名并发布 GitHub Release
  - 需配置仓库 Secrets：`SIGNING_KEY` / `ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`

## 目录结构

```
golib/
  android.go    gomobile 入口（package gcm，StartSocksProxy/StopSocksProxy）
  config/      配置（DefaultConfig + 函数参数注入）
  dns/         DoH 客户端 + DNS 缓存（含 warmup_list）
  ech/         ECH 管理器（公钥查询+缓存+自动刷新）
  logger/      日志
  pool/        连接池 + 流复用 + 质量监控 + 流量计数
  protocol/    2 字节头二进制协议编解码
  relay/       中转节点管理器
  socks5/      SOCKS5 服务器
  go.mod       module gcm，来自 GCM 主分支
app/
  libs/gcm.aar  CI 编译产物（不入库）
  src/main/jni/ hev-socks5-tunnel（CI 克隆入库）
  src/main/java/com/gcm/client/app/  Android UI/Service
  src/main/res/                      布局/字符串/图标
.github/workflows/  CI 工作流
```

## Go 核心 API

`golib/android.go`（package gcm）暴露的 gomobile 入口：

```go
func StartSocksProxy(
    listenAddr, workerHost string,
    wsConn int,
    relayIPs, userID, proxyIP, echDomain, dohURL string,
    enableECH, disableIPv6Route, enableDNSWarmup bool,
    bypassPrivate, bypassGeoIPCN, bypassGeoSiteCN bool,
    bypassRules string,
    verbose bool,
) error

func ValidateBypassRules(rules string) error
func StopSocksProxy()
```

`StartSocksProxy` 启动本地 SOCKS5 代理（内部镜像 GCM `main.go` 的初始化链）：
- `listenAddr`：本地监听地址（如 `127.0.0.1:1080`）
- `workerHost`：Worker 域名（自动去除 `wss://`/`https://` 前缀和末尾 `/`）
- `wsConn`：WebSocket 连接数（<=0 时默认 3；同时设置 `Min/MaxPoolSize` 作为定长池）
- `relayIPs`：优选中转节点（逗号分隔多个 `IP:端口`；空则直连 Worker）
- `userID`：WS URL 路径标识
- `proxyIP`：出口代理 IP（逗号分隔多个，通过 `?fallbackip=` 透传给 Worker）
- `echDomain`：ECH 查询域名（空则默认 `cloudflare-ech.com`；`enableECH=false` 时忽略）
- `dohURL`：DoH 服务器（空则默认 `https://doh.pub/dns-query`，可在 UI 文本框中修改）
- `enableECH`：是否启用 ECH（true=DoH 查询 ECH 公钥+ECH TLS；false=标准 TLS 1.3）
- `disableIPv6Route`：保留参数，本层不使用（VPN 由 Android 层负责）
- `enableDNSWarmup`：DNS 预热开关（默认 false，避免冷启动冲突；UI checkbox 可开关）
- `bypassPrivate`：本地、局域网和链路本地地址直连
- `bypassGeoIPCN`：命中内置 `GEOIP:CN` CIDR 的目标直连
- `bypassGeoSiteCN`：命中内置 `GEOSITE:CN` 域名规则的目标直连
- `bypassRules`：换行分隔的手动 IP、CIDR、域名后缀和 `full:` 域名规则
- `verbose`：日志级别（true=DEBUG，false=INFO）

`ValidateBypassRules` 在保存全局设置前验证手动规则。`StopSocksProxy` 同步逆序释放资源：`echManager.StopAutoRefresh` → `socks5Server.Close` → `connPool.Close` → `relayManager.Close` → `dnsCache.Close` → `logger.Close`，并加 `sync.Mutex` 防重复。
