# golib — X Client Go 核心库

`golib` 是 X Client 的多协议 VPN 核心，以 Go module `xclient` 组织，通过
[`gomobile bind`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) 编译为
`app/libs/xclient.aar`，供 Android 层（`app/`）调用。

- Go 1.25.5（`go.mod` 指令）
- 入口：`android.go`（package `xclient`，gomobile thin wrapper）
- 协议栈与共享能力全部下沉至外部库（commit 伪版本随发版升级为 tag 引用）：
  - [`github.com/v2up-32mb/gcm`](https://github.com/v2up-32mb/gcm) —— GCM 协议（protocol/pool/relay + `NewStreamDialer` 适配器）
  - [`github.com/v2up-32mb/xtunnel`](https://github.com/v2up-32mb/xtunnel) —— X-Tunnel 协议（Client + `ProxyDialer`/`Reconnect`）
  - [`github.com/v2up-32mb/xshared`](https://github.com/v2up-32mb/xshared) —— SOCKS5/HTTP 代理服务器 + config/dns/ech/logger/routing
- 本仓只保留两个薄 backend：`gcm/backend.go`、`xtunnel/backend.go`（paramsJSON 解析 + 库装配）

## 目录结构

```
golib/
├── android.go            # gomobile 入口：API 导出 + 协议分发（newBackend）
├── gcm/
│   └── backend.go        # GCM 后端：paramsJSON → gcm 库装配（SOCKS5 + StreamDialer + bypass Option）
└── xtunnel/
    ├── backend.go        # X-Tunnel 后端：paramsJSON → xtunnel 库装配（SOCKS5 + ProxyDialer + bypass Option）
    └── log.go            # 后端日志器（xshared logger）
```

## 公共 API（Android 侧调用）

Android 侧统一通过 `xclient.Xclient`（gomobile 绑定类）调用，`android.go` 导出：

| API | 说明 |
|---|---|
| `StartSocksProxy(listenAddr, protocol, paramsJSON, verbose)` | 在 `listenAddr` 启动本地代理；`protocol` 取 `"gcm"`（默认，空值向后兼容）或 `"xtunnel"`；`paramsJSON` 为协议参数对象 |
| `StopSocksProxy()` | 停止当前代理并释放资源 |
| `Reconnect(reason)` | 按指定原因重连当前后端（xtunnel 走库 `reconnectCh` 强制重建通道） |
| `NotifyNetworkChanged()` | 通知网络切换（触发后端自适应重连） |
| `SetTimeZone(tz)` | 设置日志时间戳时区（跟随 Android 系统时区） |
| `ValidateBypassRules(rules)` | 校验路由绕过规则（换行分隔，见下） |
| `AppendRuntimeLog(scope, message)` | 写入内存环形日志缓冲 |
| `GetRuntimeLogs()` | 读取本次运行日志全文 |

### 协议参数（paramsJSON 字段）

**GCM**（`TProxyService.buildGCMParams` 组装，17 项）：

```
worker_host, ws_conn, relay_ips, user_id, proxy_ip,
ech_domain, ech_dns, enable_ech, disable_ipv6_route,
enable_dns_warmup, bypass_private, bypass_geoip_cn,
bypass_geosite_cn, bypass_rules, enable_dynamic_pool, dynamic_pool_max,
log_level
```

**X-Tunnel**（基础 15 项 + 高级参数可覆盖）：

```
server_addr (wss:// 必填), token, connections, client_id,
relay_nodes (逗号分隔), enable_ech, ech_domain, dns_server,
insecure, enable_hot_pair, hot_pair_count, log_level,
bypass_private, bypass_geoip_cn, bypass_geosite_cn, bypass_rules
```

高级参数（`XT_ADVANCED_PARAMS` 透传，毫秒/字节整数，0 或缺省用默认值）：

```
backpressure_limit, write_queue_wait_timeout, dial_timeout, handshake_timeout,
read_timeout, write_timeout, ping_interval, reconnect_delay, connect_timeout,
max_socks5_connections, udp_blocked_ports
```

> 键集由 `gcm/backend.go` 与 `xtunnel/backend.go` 的 `Param*` 常量定义，与 Android 侧逐键对齐；
> 变更任一侧必须同步另一侧。

## 协议速查

### GCM（2 字节头）

```
[STREAM_ID:1][TYPE:1][可选 DATA]
TYPE = 0 CONNECT    DATA = ASCII "host:port|"
TYPE = 1 CONNECTED  无 DATA
TYPE = 2 DATA       DATA = 任意二进制
TYPE = 3 CLOSE       无 DATA
```

- WebSocket 连接：`wss://<workerHost>/<userID>?fallbackip=<出口IP列表>`
- 多路复用：256 流/WS，共享连接池；选路 = Relay 评分 + 负载均衡
- 协议无 UDP：SOCKS5 UDP ASSOCIATE 请求由共享服务器回复 `0x07`

### x-tunnel（8 字节头）

- 头部：`connID` + `msgType`（8 字节），每通道独立 connID 空间
- 选路 = 通道竞争 + Hot Pair 预绑定；UDP ASSOCIATE 经 `ProxyDialer()`（端口拦截 +
  IPStrategy 过滤）；Fast Retry、背压控制在库内

## ECH / DoH 回退链（共享）

```
DoH 多服务器 fallback → UDP DNS (8.8.8.8:53) → 标准 TLS 1.3
```

ECH 配置项（`ech_domain` / `ech_dns`）对两种协议共用；配置懒加载（首次拨号按需获取，
不阻塞后端启动）。

## 路由绕过规则语法

`bypass_rules` 以换行分隔，每行一条，支持三种形式（可通过 `validateBypassRules` 校验）：

```
192.168.1.0/24          # CIDR 网段
example.com             # 域名（含子域）
full:api.example.com    # 全匹配域名
```

内置开关：`bypass_private`（本地/局域网）、`bypass_geoip_cn`、`bypass_geosite_cn`。
匹配器由 backend 构建（`routing.NewMatcher`），经 `WithBypassMatcher` 注入共享 SOCKS5 服务器。

## 数据流

```
┌─────────────────────────────────────────────┐
│ Android 应用（main 进程）                     │
│   ProfileListActivity / ProfileEditActivity  │
└──────────────┬──────────────────────────────┘
               │ Intent ACTION_CONNECT / 本地广播 ACTION_STATUS
┌──────────────▼──────────────────────────────┐
│ TProxyService（:vpn 进程，VpnService）        │
│   └─ TUN 接口                                │
└──────────────┬──────────────────────────────┘
               │ hev-socks5-tunnel（NDK）
┌──────────────▼──────────────────────────────┐
│ golib：xshared SOCKS5 服务器                 │
│   └─ newBackend(protocol)                    │
│       ├─ gcm     → gcmlib.NewStreamDialer    │
│       └─ xtunnel → xtlib.ProxyDialer()       │
└─────────────────────────────────────────────┘
```

## 构建与验证

```bash
# AAR 产物（供 Android 层使用）
cd golib
go get golang.org/x/mobile/bind
gomobile bind -target=android -androidapi=24 -o ../app/libs/xclient.aar

# Go 侧单测（本地可验证；Android APK 编译一律走 GitHub Actions）
go test ./... -race
```

> 完整应用构建与发布流程见仓库根目录 `README.md`；模块约定见根目录 `AGENTS.md`。
