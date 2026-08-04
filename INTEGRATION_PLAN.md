# x-client 多协议 VPN 客户端 — 集成计划

> 创建日期：2026-08-04
> 基础代码：gcm-client main (1ece217) 全量 fork
> 集成目标：x-tunnel main (9ee779a) 协议层

## 一、项目定位

**x-client** 是一个 Android 多协议 VPN 客户端。

- **前身**：`x-client.legacy`（ECH 单文件隧道）和 `gcm-client`（GCM 多路复用隧道）
- **核心理念**：每个 Profile 选择一个代理协议，共享 Android VPN 框架、Profile 管理、路由绕过、全局设置等通用功能
- **首批协议**：
  1. **GCM** — 2 字节头二进制流复用，WebSocket over TLS 1.3 + ECH（来自 gcm-client，已验证）
  2. **x-tunnel** — 多通道 WebSocket 隧道，通道选择机制 + Hot Pair + UDP + HTTP 代理（来自 x-tunnel 项目）

## 二、两项目架构对比

| 维度 | gcm-client (已 fork) | x-tunnel (待集成) |
|---|---|---|
| Go 模块 | `module gcm` | `module x-tunnel` |
| 协议头 | 2 字节 `[STREAM_ID:1][TYPE:1]` | 8 字节头，含 connID + msgType |
| 多路复用 | 1 字节 StreamID，最多 256 流/WS | 每通道独立 connID 空间 |
| 通道选择 | 无（所有流共享连接池） | 上行/下行竞争选择 + Hot Pair 预绑定 |
| ECH | `ech.EchManager`，DoH 查询，缓存+自动刷新 | `ECHManager`，DoH/UDP 双路径，重试+回退 |
| DoH | `dns.DoHClient`（完整实现，内置备用服务器列表） | 直接 DoH 查询（内联在 ECHManager 中） |
| Relay | `relay.RelayManager`，含评分/负载均衡/健康检测 | `RelayNodeManager`，含评分/健康分数/动态测速间隔 |
| SOCKS5 | `socks5.Server`，含 bypass routing | 内联在 `clientPool`，含 UDP associate |
| HTTP 代理 | `pool.ProxyTransport`（HTTP over WS 流） | `http_proxy.go`（监听器形式） |
| UDP | 不支持 | 完整 UDP associate 支持 |
| 路由绕过 | `routing.Matcher`（GeoIP CN / GeoSite CN / 手动规则） | 无 |
| 配置 | `config.Config`（YAML/JSON/CLI flags 复杂体系） | `client.Config`（结构体 + JSON 配置文件） |
| 日志 | `logger.Logger`（运行时日志缓冲，Android 可读） | 标准 `log` 包 |
| Android 入口 | `gcm.android.go` → `StartSocksProxy()` | 无（需要新建） |
| 测试覆盖 | 28 Go 文件，~2930 行测试 | 15 Go 测试文件，~1100 行测试 |

## 三、共用功能交叉分析

### 3.1 双方都有、gcm-client 实现更好的（直接保留）

| 模块 | gcm-client 优势 | x-tunnel 对应 | 决策 |
|---|---|---|---|
| **ECH 管理器** | 独立 `ech` 包，DoH client 可复用，缓存接口清晰 | 内联在 client/pkg，查询逻辑耦合 | ✅ 保留 gcm 版本 |
| **DoH 客户端** | `dns.DoHClient` 完整实现，多服务器 fallback | 内联简单查询 | ✅ 保留 gcm 版本 |
| **Relay 管理** | `relay.RelayManager` 评分+负载均衡，已修死锁 | `RelayNodeManager` 评分+健康分数 | ⚠️ 两版各有优势，见 3.3 |
| **日志** | `logger.Logger` 运行时缓冲，Android UI 可读 | 标准 log | ✅ 保留 gcm 版本 |
| **路由绕过** | `routing.Matcher` GeoIP/GeoSite CN | 无 | ✅ 保留 gcm 版本 |
| **SOCKS5** | `socks5.Server` 独立服务，含 bypass | 内联在 pool | ⚠️ 保留 gcm 框架，吸收 x-tunnel UDP 支持 |
| **Android 入口** | `android.go` gomobile 入口 | 无 | ✅ 保留 gcm 版本，扩展为多协议 |

### 3.2 x-tunnel 独有、gcm-client 没有的（需引入）

| 特性 | 位置 | 说明 |
|---|---|---|
| **UDP associate** | `socks5.go` + `pool.go` UDP 广播/收发 | SOCKS5 UDP 代理支持 |
| **Hot Channel Pair** | `pair_warmer.go` | 预绑定通道降低首帧延迟 |
| **Fast Retry** | `pool.go` fast retry 状态机 | 快速重连，窗口内激进重试 |
| **通道选择机制** | `pool.go` 广播+上行/下行竞争 | 多通道间竞选最优路径 |
| **背压控制** | `pool.go` + `common/protocol.go` | 全局队列背压阈值 |
| **HTTP Proxy 监听** | `http_proxy.go` | 独立 HTTP 代理监听器 |
| **IP 策略** | `common/ip_strategy.go` | IPv4/IPv6 优先策略 |
| **x-tunnel 协议** | `common/protocol.go` (8字节头) | 完整的消息编解码 |
| **配置文件** | `config_file.go` JSON 配置 | 按 Profile 保存协议参数 |

### 3.3 双方都有、需要挑选优化的

| 模块 | gcm-client | x-tunnel | 决策 |
|---|---|---|---|
| **Relay 评分** | `Score = 延迟 + 失败惩罚`；简单但有效 | `Score = (1-延迟归一化)*0.7 + 成功率*0.3`；含 healthScore 动态测速间隔 | ⚠️ x-tunnel 的动态测速间隔更优；gcm 的负载均衡更成熟。取 x-tunnel 的 healthScore + gcm 的负载均衡 |
| **ECH 查询** | 仅 DoH 路径 | DoH + UDP DNS fallback | ⚠️ x-tunnel 的 UDP fallback 耐用性更好；但 gcm 的 DoH 实现更完整（多服务器 fallback）。保留 gcm 的 DoH，移植 x-tunnel 的 UDP fallback |
| **SOCKS5** | 有 bypass routing | 有 UDP associate | ⚠️ 以 gcm 的 `socks5.Server` 为框架，移植 x-tunnel 的 UDP associate 支持 |
| **连接池** | `pool.ConnectionPool` 多流复用 | `clientPool` 多通道竞争 | ⚠️ 两种根本不同的模式，不合并。作为独立协议后端实现 |
| **WebSocket 拨号** | `pool/connection.go` 含 ECH | `dialer.go` 含中转+ECH | ⚠️ 两版各保留，不合并 |

## 四、目标架构

```
x-client/
├── app/                            # Android 应用
│   ├── build.gradle                # applicationId 改为 com.x.client.app
│   └── src/main/java/com/x/client/app/
│       ├── Preferences.java        # 新增 Protocol 字段（GCM / X_TUNNEL）
│       ├── ProfileEditActivity.java # 新增协议选择 UI + 协议参数编辑
│       ├── TProxyService.java       # 根据 Protocol 调用对应 Go 入口
│       ├── SettingsActivity.java    # 全局设置（保持）
│       └── ...                      # 其他 Activity 不变
├── golib/                          # Go 核心库
│   ├── go.mod                      # module xclient
│   ├── android.go                  # 统一 gomobile 入口（协议分发 + 共享 API）
│   ├── android_test.go
│   ├── shared/                     # 共享模块（从 gcm-client 提取）
│   │   ├── config/                 # 通用配置框架（Profile + 全局设置）
│   │   ├── dns/                    # DoH 客户端（gcm 版本）
│   │   ├── ech/                    # ECH 管理器（gcm 版本 + x-tunnel UDP fallback）
│   │   ├── logger/                 # 日志（gcm 版本，运行时缓冲）
│   │   ├── routing/                # 路由绕过（gcm 版本）
│   │   └── socks5/                 # SOCKS5 框架（gcm 版本 + x-tunnel UDP 支持）
│   ├── gcm/                        # GCM 协议后端
│   │   ├── protocol/               # 2 字节头协议
│   │   ├── pool/                   # ConnectionPool 多流复用
│   │   ├── relay/                  # RelayManager
│   │   └── backend.go              # GCM 后端实现（实现 Backend 接口）
│   └── xtunnel/                    # x-tunnel 协议后端
│       ├── protocol/               # 8 字节头协议
│       ├── pool/                   # clientPool 多通道竞争
│       ├── relay/                  # RelayNodeManager + healthScore
│       ├── pair_warmer.go          # Hot Channel Pair
│       ├── http_proxy.go           # HTTP 代理监听
│       └── backend.go              # x-tunnel 后端实现（实现 Backend 接口）
├── .github/workflows/              # CI（适配新包名）
├── build.gradle
├── CLAUDE.md
└── INTEGRATION_PLAN.md             # 本文件
```

## 五、Backend 接口设计

```go
// golib/android.go 中定义
package xclient

// ProxyBackend 代理后端接口，每种协议实现此接口
type ProxyBackend interface {
    // Start 启动代理后端
    // listenAddr: SOCKS5 本地监听地址
    // params: 协议特定参数（key-value 对）
    Start(listenAddr string, params map[string]string, verbose bool) error
    
    // Stop 停止代理后端
    Stop() error
    
    // Reconnect 触发重连
    Reconnect(reason string)
    
    // NotifyNetworkChanged 通知网络变更
    NotifyNetworkChanged()
}

// 统一入口（Android 调用）
func StartSocksProxy(listenAddr string, protocol string, paramsJSON string, verbose bool) error
func StopSocksProxy()
func Reconnect(reason string)
func NotifyNetworkChanged()
func GetRuntimeLogs() string
func AppendRuntimeLog(scope, message string)
func ValidateBypassRules(rules string) error
```

## 六、分阶段实施计划

### 阶段 0：项目骨架搭建（当前已完成）
- [x] 合并 gcm-client `feat/global-network-settings` 到 main 并推送
- [x] 创建 `/root/projects/x-client` 目录
- [x] 从 gcm-client main (`1ece217`) fork 全部代码到 x-client
- [x] 本集成计划写入 `INTEGRATION_PLAN.md`

### 阶段 1：Go module 重命名和包名迁移
**目标**：将 `module gcm` 重命名为 `module xclient`，所有 import 路径更新

- [ ] `golib/go.mod`: `module gcm` → `module xclient`
- [ ] 全局替换 import 路径：`gcm/config` → `xclient/shared/config` 等
- [ ] 重构 golib 目录为 `shared/` + `gcm/` + `xtunnel/` 三层结构
- [ ] `app/build.gradle`: `applicationId` 改为 `com.x.client.app`，AAR 改为 `xclient.aar`
- [ ] NDK PKGNAME 改为 `com/x/client/app`
- [ ] Android Java 包名从 `com.gcm.client.app` 改为 `com.x.client.app`
- [ ] `gcm.Gcm` 引用改为 `xclient.Xclient`
- [ ] 验证：`go build ./...` + `go test ./...` 通过

### 阶段 2：定义 Backend 接口和协议分发
**目标**：设计 ProxyBackend 接口，android.go 改为多协议分发

- [ ] 在 `golib/android.go` 中定义 `ProxyBackend` 接口
- [ ] 现有 GCM 代码封装为 `gcm/backend.go`（实现 ProxyBackend）
- [ ] `StartSocksProxy` 签名改为 `(listenAddr, protocol, paramsJSON, verbose)` 形式
- [ ] 内部根据 protocol 选择对应 Backend 实例
- [ ] 保持向后兼容：旧版 `gcm://` URI 的 Profile 默认使用 GCM 协议
- [ ] Android 侧 `TProxyService.java` 改为从 Preferences 读取 Protocol 字段
- [ ] 验证：Go 测试通过，Android 能编译（CI 验证）

### 阶段 3：集成 x-tunnel 协议后端
**目标**：将 x-tunnel client 包移植为 xclient/xtunnel 后端

- [ ] 从 x-tunnel main 复制 `client/pkg/` → `golib/xtunnel/`
- [ ] 从 x-tunnel 复制 `common/` → `golib/xtunnel/protocol/`
- [ ] 将 x-tunnel 的 `log` 包替换为 `xclient/shared/logger`
- [ ] 将 x-tunnel 的 ECH 管理器替换为共享的 `shared/ech`（补充 UDP DNS fallback）
- [ ] 编写 `xtunnel/backend.go` 实现 `ProxyBackend` 接口
- [ ] 适配 x-tunnel 的 `Config` 为 params key-value 形式
- [ ] 将 x-tunnel 的 RelayNodeManager 提取到 `xtunnel/relay/`，融合 gcm 的负载均衡能力
- [ ] 验证：x-tunnel 后端独立 Go 测试通过

### 阶段 4：共享模块优化
**目标**：提取共享代码到 `shared/`，消除重复，选择更优实现

- [ ] `shared/ech`：融合 gcm 的 DoH 多服务器 fallback + x-tunnel 的 UDP DNS fallback
- [ ] `shared/socks5`：以 gcm 的 `socks5.Server` 为框架，移植 x-tunnel 的 UDP associate
- [ ] `shared/routing`：gcm 版本直接迁移（x-tunnel 无此功能）
- [ ] `shared/dns`：gcm 的 `DoHClient` + `DNSCache` 直接迁移
- [ ] `shared/logger`：gcm 的 `logger.Logger` 直接迁移
- [ ] `shared/config`：扩展 Profile 配置，新增 `Protocol` 字段和 per-protocol 参数
- [ ] 验证：全套 Go 测试通过

### 阶段 5：Android UI 适配
**目标**：Profile 编辑新增协议选择，每种协议显示对应参数

- [ ] `Preferences.java`：新增 `Protocol` 字段（枚举：GCM / X_TUNNEL）
- [ ] `ProfileEditActivity.java`：新增协议选择 Spinner；根据选择显示/隐藏对应参数字段
- [ ] GCM Profile 显示：WorkerHost, UserId, PrefIp, FallbackIp, EchDomain, EchDns, DisableEch
- [ ] x-tunnel Profile 显示：ServerAddr (wss://), Token, RelayNodes, Connections, EnableECH, ECHDomain, DNSServer, Insecure, EnableHotPair
- [ ] `TProxyService.java`：根据 Protocol 字段组装 params 并调用 `Xclient.startSocksProxy(listenAddr, protocol, paramsJSON, verbose)`
- [ ] URI 导入/导出：`gcm://` 保持兼容，新增 `xtunnel://` 格式
- [ ] 验证：CI 构建通过

### 阶段 6：CI/CD 适配
**目标**：GitHub Actions 构建适配新包名和新 Go module

- [ ] `build-debug.yml`：触发分支改为 `main` + `develop` + `feat/*` + `repair/*`
- [ ] gomobile bind 模块名改为 `xclient`
- [ ] AAR 产物改为 `xclient.aar`
- [ ] `release.yml`：适配新 applicationId
- [ ] NDK 构建适配新 PKGNAME

### 阶段 7：端到端验证
**目标**：两种协议都能在 Android 上正常工作

- [ ] Go 全量测试通过（`go test ./... -count=3`）
- [ ] `go vet ./...` 通过
- [ ] GitHub Actions Debug 构建成功，4 ABI APK 产出
- [ ] GCM 协议 Profile：设备测试 VPN 连接正常
- [ ] x-tunnel 协议 Profile：设备测试 VPN 连接正常
- [ ] 协议切换：切换 Profile 后正确加载对应协议
- [ ] 全局设置（DoH/ECH/主题/路由绕过）在两种协议下都正常

## 七、风险和注意事项

1. **Go module 版本**：gcm 用 Go 1.23，x-tunnel 用 Go 1.25.5。gomobile bind 需确认兼容的 Go 版本。建议统一使用 Go 1.23（gcm 的版本），因为 gomobile 绑定已验证通过。
2. **ech 패키지 충돌**：x-tunnel 的 ECHManager 和 gcm 的 EchManager 接口不同。共享层需要统一接口，可能需要适配层。
3. **SOCKS5 UDP 移植**：gcm 的 socks5.Server 是独立服务，x-tunnel 的 UDP 逻辑内联在 pool 中。移植时需要重构为独立的 UDP handler。
4. **协议参数 JSON 化**：现 gcm 的 `StartSocksProxy` 有大量位置参数。改为 paramsJSON 后，Android 侧需要正确构建 JSON。
5. **向后兼容**：现有 `gcm://` URI 导入的 Profile 应默认使用 GCM 协议，无需用户手动设置。
6. **x-tunnel 无 Android 入口**：x-tunnel 原本只有 CLI，需要全新编写 gomobile 入口（`xtunnel/backend.go`）。
7. **共享代码 3**евого уровня**：`shared/` 改变 import 路径会影响 gcm 后端代码，需批量更新。

## 八、不在本次范围内的

- x-tunnel 服务端集成（x-client 只做客户端）
- 新协议集成（Shadowsocks/V2Ray/Trojan 等，本次只集成 GCM + x-tunnel）
- iOS 支持
- Win7 兼容（gcm-client 和 x-tunnel 的 Win7 分支独立维护）
