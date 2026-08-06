# X Client

[![Release](https://img.shields.io/github/v/release/v2up-32mb/x-client)](https://github.com/v2up-32mb/x-client/releases)

X Client 是一个 Android 多协议 VPN 客户端。每个 Profile（节点）可独立选择代理协议，
共享同一套 VPN 框架、节点管理、路由绕过与全局设置。

- 基于 [gcm-client](https://github.com/v2up-32mb/gcm-client) 改造，扩展为多协议架构
- 协议核心由 Go 编写（`golib/`），通过 `gomobile bind` 编译为 `xclient.aar`
- VPN 通道基于 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  将 TUN 流量转发到核心库的本地 SOCKS5 代理

## 功能特性

- **多协议**：GCM / X-Tunnel，每个 Profile 独立选择
- **ECH + DoH**：DoH 多服务器 fallback → UDP DNS（8.8.8.8:53）→ 标准 TLS 1.3 三级回退
- **连接池**：WebSocket 多路复用、动态扩容、Relay 评分加权负载均衡、质量监控
- **路由绕过**：本地/局域网、GeoIP:CN、GeoSite:CN、手动规则（GCM 与 X-Tunnel 均支持）
- **节点管理**：多 Profile、命名、导入/导出、二维码扫描
- **全局设置**：SOCKS5 端口、DoH 服务器、ECH 域名、DNS 预热、日志等级
- **运行日志**：内存环形缓冲，日志等级可调，时间戳跟随 Android 系统时区
- **主题**：跟随系统 / 亮色 / 暗色
- **X-Tunnel 独有**：UDP associate（SOCKS5 UDP 代理）、Hot Pair 热通道预绑定、
  Fast Retry、通道竞争选路、背压控制、HTTP 代理

## 支持的协议

| | GCM | X-Tunnel |
|---|---|---|
| 协议头 | 2 字节 `[STREAM_ID:1][TYPE:1]` | 8 字节（connID + msgType） |
| 多路复用 | 256 流/WS，共享连接池 | 每通道独立 connID 空间 |
| 选路 | Relay 评分 + 负载均衡 | 通道竞争 + Hot Pair 预绑定 |
| UDP | 不支持 | 完整 UDP associate |
| HTTP 代理 | 流内传输 | 独立监听器 |
| 路由绕过 | ✅ | ✅（阶段 9 起，SOCKS5/HTTP 直连分流；UDP 暂不参与） |
| ECH/DoH | ✅ 共享模块 | ✅ 共享模块 |

## 下载

从 [GitHub Releases](https://github.com/v2up-32mb/x-client/releases) 下载签名 APK，
按设备架构选择：

| 文件 | 适用设备 |
|---|---|
| `x-client-arm64-v8a-release-signed.apk` | 64 位 ARM（大多数现代设备） |
| `x-client-armeabi-v7a-release-signed.apk` | 32 位 ARM |
| `x-client-x86-release-signed.apk` | 32 位 x86（模拟器） |
| `x-client-x86_64-release-signed.apk` | 64 位 x86（模拟器） |
| `x-client-universal-release-signed.apk` | 全架构（体积最大） |

## 构建

> 项目构建验证以 GitHub Actions 为准；本地只需验证 Go 侧（`golib/`）。

### 前置条件

- JDK 17、Android SDK（compileSdk 34 / minSdk 24 / targetSdk 34）、NDK `26.3.11579264`
- Go 1.25+、[gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)
- hev-socks5-tunnel 需在构建前 clone 到 `app/src/main/jni`（CI 会自行完成）

### 本地构建

```bash
# 1. 克隆 hev-socks5-tunnel（TUN 转发层）
git clone --recursive https://github.com/heiher/hev-socks5-tunnel app/src/main/jni

# 2. 编译 Go 核心库为 AAR
cd golib
go get golang.org/x/mobile/bind
gomobile bind -target=android -androidapi=24 -o ../app/libs/xclient.aar

# 3. 构建 APK（版本号由 CI 从 tag 注入）
cd ..
./gradlew assembleDebug
```

### CI 工作流

| 工作流 | 触发方式 | 产物 |
|---|---|---|
| `build-debug.yml` | `workflow_dispatch` 手动 | 4 ABI debug APK |
| `release.yml` | 推送 `v*` 标签 | 签名 APK + GitHub Release |
| `check-keystore.yml` | `workflow_dispatch` 手动 | 验证签名密钥 secrets |

Release 流程：打附注标签（如 `v1.1.2`）并推送，`release.yml` 自动构建、签名
（secrets：`SIGNING_KEY` / `ALIAS` / `KEY_STORE_PASSWORD` / `KEY_PASSWORD`）并创建 Release，
`VERSION_NAME` 取标签去掉 `v` 前缀，`VERSION_CODE` 取提交计数。

## 使用

### 配置导入 / 导出 URI

```
gcm://<workerHost>?ip=<优选中转IP:端口>&fip=<出口代理IP>&user_id=<用户ID>&dns=<DoH服务器>&domain=<ECH查询域名>&disable_ech=1#<配置名称>
xtunnel://<serverAddr>?token=<Token>&relay_nodes=<节点,逗号分隔>&connections=<连接数>&ech=0|1&domain=<ECH域名>&dns=<DoH服务器>&insecure=1&hotpair=<对数 1..8>#<配置名称>
```

- `hotpair=1`（或 `true`/`yes`）兼容旧格式（启用 1 对）；`hotpair=2..8` 表示启用 N 对
- Profile 编辑页按协议显示字段；列表页显示协议徽标与服务器地址
- 全局设置（DoH 服务器、ECH 域名、DNS 预热）对两种协议共用

### 运行日志

- 主界面 / 设置页可查看「本次运行日志」
- 日志等级（调试 / 信息 / 警告 / 错误）在「全局设置」中调整，下次启动 VPN 生效
- 日志时间戳与 Android 系统时区一致（VPN 运行中修改系统时区也会即时生效）

## 架构

```
x-client/
├── app/                  # Android 应用（Java，VpnService + 主界面）
│   ├── src/main/java/com/x/client/app/   # UI / 服务 / 偏好设置
│   └── libs/xclient.aar  # gomobile 编译产物（CI 生成，不入库）
├── golib/                # Go 核心库（module xclient，Go 1.25.5）
│   ├── gcm/              # GCM 协议后端（backend/pool/relay/protocol）
│   ├── xtunnel/          # X-Tunnel 协议后端（client/relay/protocol）
│   └── shared/           # 共享模块（config/dns/ech/logger/routing/socks5）
├── .github/workflows/    # CI：Debug 构建 / Release 发布 / 密钥检查
└── ... / CLAUDE.md、INTEGRATION_PLAN.md、tasks.md、progress.md
```

数据流：

```
VPN TUN ──► hev-socks5-tunnel ──► 本地 SOCKS5 ──► Go 协议后端 ──► WebSocket / ECH ──► 出口
```

### Go 入口 API（gomobile AAR，Java 侧 `Xclient` 调用）

| 方法 | 说明 |
|---|---|
| `startSocksProxy(listenAddr, protocol, paramsJSON, verbose)` | 按协议启动代理 |
| `stopSocksProxy()` | 停止代理并释放资源 |
| `reconnect(reason)` / `notifyNetworkChanged()` | 重连 / 网络切换 |
| `getRuntimeLogs()` / `appendRuntimeLog(scope, message)` | 运行时日志 |
| `validateBypassRules(rules)` | 校验路由绕过规则 |
| `setTimeZone(tz)` | 与 Android 系统时区对齐日志时间戳 |

`protocol` 为 `gcm`（默认，空值向后兼容）或 `xtunnel`；`paramsJSON` 为 `{"key": "value", ...}`。

**GCM 参数键**：`worker_host`、`ws_conn`、`relay_ips`、`user_id`、`proxy_ip`、
`ech_domain`、`ech_dns`、`enable_ech`、`disable_ipv6_route`、`enable_dns_warmup`、
`bypass_private`、`bypass_geoip_cn`、`bypass_geosite_cn`、`bypass_rules`、
`enable_dynamic_pool`、`dynamic_pool_max`、`log_level`

**X-Tunnel 参数键**：`server_addr`（wss:// 必填）、`token`、`connections`、
`relay_nodes`、`enable_ech`、`ech_domain`、`dns_server`、`insecure`、
`enable_hot_pair`、`log_level`、`bypass_private`、`bypass_geoip_cn`、
`bypass_geosite_cn`、`bypass_rules`（`client_id` 在 Go 侧保留支持）

## 开发注意事项

- Go 模块名为 `xclient`；gomobile 生成的 AAR 类名前缀为 `xclient.Xclient`
- 网络直连 GitHub 可能被 SNI 阻断，可配置 git 仓库级 `http.proxy`
- `golib/go.mod` 中 `golang.org/x/mobile/bind` 由 CI 自行 `go get`，不提交依赖改动
- 集成设计与两项目架构交叉分析详见 `INTEGRATION_PLAN.md`
