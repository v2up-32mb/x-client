# golib — X Client Go 核心库

`golib` 是 X Client 的多协议 VPN 核心，以 Go module `xclient` 组织，通过
[`gomobile bind`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) 编译为
`app/libs/xclient.aar`，供 Android 层（`app/`）调用。

- Go 1.25.5（`go.mod` 指令）
- 入口：`android.go`（package `xclient`，gomobile thin wrapper）
- 协议后端：`gcm/` 与 `xtunnel/`，共享能力收敛于 `shared/`

## 目录结构

```
golib/
├── android.go            # gomobile 入口：API 导出 + 协议分发（newBackend）
├── gcm/                  # GCM 协议后端
│   ├── pool/             #   WebSocket 连接池（多路复用、动态扩容）
│   ├── relay/            #   Relay 评分与负载均衡
│   └── protocol/         #   GCM 二进制协议（2 字节头）
├── xtunnel/              # X-Tunnel 协议后端
│   ├── relay/            #   中转链路
│   ├── protocol/         #   x-tunnel 协议（8 字节头）与 IP 策略
│   ├── client.go         #   WebSocket 客户端
│   ├── pool.go           #   多通道连接池（通道竞争选路）
│   ├── pair_warmer.go    #   Hot Pair 热通道预绑定
│   ├── backpressure*     #   写队列背压控制
│   ├── fast_retry*       #   快速重试
│   ├── http_proxy.go     #   独立 HTTP 代理监听器
│   ├── socks5.go         #   SOCKS5（含 UDP associate）
│   └── bypass*           #   直连分流
└── shared/               # 共享模块（两种协议公用）
    ├── config/           #   配置结构
    ├── dns/              #   DoH / UDP DNS
    ├── ech/              #   ECH（Encrypted Client Hello）
    ├── logger/           #   分级日志
    ├── routing/          #   路由绕过规则
    └── socks5/           #   SOCKS5 协议实现
```

## 公共 API（Android 侧调用）

Android 侧统一通过 `xclient.Xclient`（gomobile 绑定类）调用，`android.go` 导出：

| API | 说明 |
|---|---|
| `StartSocksProxy(listenAddr, protocol, paramsJSON, verbose)` | 在 `listenAddr` 启动本地代理；`protocol` 取 `"gcm"`（默认，空值向后兼容）或 `"xtunnel"`；`paramsJSON` 为协议参数对象 |
| `StopSocksProxy()` | 停止当前代理并释放资源 |
| `Reconnect(reason)` | 按指定原因重连当前后端 |
| `NotifyNetworkChanged()` | 通知网络切换（触发后端自适应重连） |
| `SetTimeZone(tz)` | 设置日志时间戳时区（跟随 Android 系统时区） |
| `ValidateBypassRules(rules)` | 校验路由绕过规则（换行分隔，见下） |
| `AppendRuntimeLog(scope, message)` | 写入内存环形日志缓冲 |
| `GetRuntimeLogs()` | 读取本次运行日志全文 |

### 协议参数（paramsJSON 字段）

**GCM**（`TProxyService` 组装，16 项）：

```
worker_host, ws_conn, relay_ips, user_id, proxy_ip,
ech_domain, ech_dns, enable_ech, disable_ipv6_route,
enable_dns_warmup, bypass_private, bypass_geoip_cn,
bypass_geosite_cn, bypass_rules, enable_dynamic_pool, dynamic_pool_max
```

**X-Tunnel**（10 项）：

```
server_addr (wss:// 必填), token, connections, client_id,
relay_nodes (逗号分隔), enable_ech, ech_domain, dns_server,
insecure, enable_hot_pair
```

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

### x-tunnel（8 字节头）

- 头部：`connID` + `msgType`（8 字节），每通道独立 connID 空间
- 选路 = 通道竞争 + Hot Pair 预绑定；支持完整 UDP associate、独立 HTTP 代理监听、
  Fast Retry、背压控制

## ECH / DoH 回退链（共享）

```
DoH 多服务器 fallback → UDP DNS (8.8.8.8:53) → 标准 TLS 1.3
```

ECH 配置项（`ech_domain` / `ech_dns`）对两种协议共用；`enable_ech=1` 表示禁用
ECH 回落标准 TLS。

## 路由绕过规则语法

`bypass_rules` 以换行分隔，每行一条，支持三种形式（可通过 `validateBypassRules` 校验）：

```
192.168.1.0/24          # CIDR 网段
example.com             # 域名（含子域）
full:api.example.com    # 全匹配域名
```

内置开关：`bypass_private`（本地/局域网）、`bypass_geoip_cn`、`bypass_geosite_cn`。

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
│ golib：本地 SOCKS5 / HTTP 代理               │
│   └─ newBackend(protocol)                    │
│       ├─ gcm     → wss://<worker>/<user>     │
│       └─ xtunnel → wss://<server>            │
└─────────────────────────────────────────────┘
```

## 构建与验证

```bash
# AAR 产物（供 Android 层使用）
cd golib
go get golang.org/x/mobile/bind
gomobile bind -target=android -androidapi=24 -o ../app/libs/xclient.aar

# Go 侧单测（本地可验证；Android APK 编译一律走 GitHub Actions）
go test ./...
```

> 完整应用构建与发布流程见仓库根目录 `README.md`；模块约定见根目录 `AGENTS.md`。
