# Tasks

## 当前状态

阶段 0-5 全部完成并验证（7 次 CI 全绿，4 ABI APK）。
真机反馈多轮问题已修复（第一~六轮 + v1.1.1 发布，详见 progress.md；第六轮：xtunnel:// 前缀长度 substring(9)→(10) 修复、CI 改手动触发）。
1. paramsJSON 数字/布尔解析失败 → Go `parseParamsJSON` 支持标量类型（GCM 的 ws_conn 等数字参数同样受益）
2. x-tunnel 页面删除 ECH 查询域名/DoH 服务器设置项，复用全局设置（SettingsActivity）
3. ECH 文案统一为「禁用 ECH（标准 TLS 1.3）」，checkbox 默认未选中（= 默认启用 ECH），x-tunnel 存储改为 XtDisableEch 语义

## 下一步

### 立即执行
- [x] 提交阶段 1 的全部改动并推送 `origin/main`，触发 GitHub Actions Debug 构建验证
- [x] 检查 Actions 构建结果，确认 4 ABI APK 产出正常（run 30886749438，4 个 artifact）
- [x] 提交阶段 2 改动并推送（a1bf62f），CI 构建通过（run 30887891590）
- [x] 确认阶段 2 CI 的 4 ABI APK 产出

### 阶段 2：定义 Backend 接口和协议分发（代码已完成）
- [x] 在 `golib/android.go` 中定义 `ProxyBackend` 接口
- [x] 现有 GCM 代码封装为 `gcm/backend.go`（实现 ProxyBackend）
- [x] `StartSocksProxy` 签名改为 `(listenAddr, protocol, paramsJSON, verbose)` 形式
- [x] 内部根据 protocol 选择对应 Backend 实例
- [x] 保持向后兼容：空 protocol / 旧 `gcm://` Profile 默认使用 GCM 协议
- [x] Android 侧 `TProxyService.java` 改为从 Preferences 读取 Protocol 字段并组装 paramsJSON
- [x] 验证：Go 测试通过（已完成），Android 能编译（CI run 30887891590 通过）

### 阶段 3：集成 x-tunnel 协议后端（代码已完成，待提交 + CI 验证）
- [x] 从 x-tunnel main (`9ee779a`) 复制 `client/pkg/` → `golib/xtunnel/`（Go 1.25.5，不降级）
- [x] 从 x-tunnel 复制 `common/` → `golib/xtunnel/protocol/`
- [x] 将 x-tunnel 的 `log` 包替换为 `xclient/logger`（XTunnel / XTunnelRelay scope）
- [x] 将 x-tunnel 的 ECH 管理器替换为共享 `xclient/ech`：`dns.ResolveHTTPSUDP` 新增 UDP DNS 查询（type 65），ech 管理器 DoH→UDP→标准 TLS 三级回退（含测试）
- [x] 编写 `xtunnel/backend.go` 实现 `ProxyBackend`；`StartSocksProxy` 的 `xtunnel` 分发已接通（android.go）
- [x] 适配 x-tunnel 的 `Config` 为 params key-value 形式（server_addr/token/connections/client_id/relay_nodes/enable_ech/ech_domain/dns_server/insecure/enable_hot_pair）
- [x] 将 RelayNodeManager 提取到 `xtunnel/relay/`，融合 gcm 加权负载均衡（candidateWeight = 评分 × 负载因子，Acquire/Release 活跃计数，SelectNodeExcluding 加权随机）
- [x] 修复 x-tunnel 上游缺陷：SOCKS5/HTTP 无可用通道时返回标准失败应答（上游两个对应测试同样失败）；监听器在 Shutdown 时关闭释放端口（Android 重启必需）
- [x] 验证：xtunnel/protocol/relay 全量测试通过（含移植测试）；`go test ./... -count=3`、vet、gofmt、diff --check 全过
- [ ] 提交推送后确认 CI 构建与 4 ABI APK

### 阶段 4：共享模块优化（代码已完成，待提交 + CI 验证）
- [x] golib 三层结构重组：`shared/`（config/dns/ech/logger/routing/socks5）+ `gcm/`（backend/pool/protocol/relay）+ `xtunnel/`，全量 import 重写
- [x] `shared/ech`：融合 gcm 的 DoH 多服务器 fallback + x-tunnel 的 UDP DNS fallback（阶段 3 已完成，随目录迁移）
- [x] `shared/routing`：gcm 版本直接迁移（x-tunnel 无此功能）
- [x] `shared/dns`：gcm 的 `DoHClient` + `DNSCache` 直接迁移（含 UDP HTTPS 查询）
- [x] `shared/logger`：gcm 的 `logger.Logger` 直接迁移
- [x] `shared/config`：直接迁移；`Protocol` 字段在 Android Preferences 侧管理（Go 分发入口按 protocol 选择后端，无需 Go 侧 Profile 存储）
- [x] 验证：全套 Go 测试通过（14 包，-count=3）、vet、gofmt、diff --check、gobind
- [ ] `shared/socks5` UDP associate：**延后**——GCM wire 协议（gcm/protocol）无 UDP 消息类型，移植 UDP 需 GCM 服务端协议扩展（超出客户端范围）；x-tunnel 的 SOCKS5+UDP 已在 xtunnel 内部集成

### 阶段 5：Android UI 适配（代码已完成，待提交 + CI 验证）
- [x] `Preferences.java`：`Protocol` 字段 + 9 个 X-Tunnel per-profile 参数（ServerAddr/Token/RelayNodes/Connections/EnableECH/ECHDomain/DNSServer/Insecure/HotPair）；列表页地址回退显示
- [x] `ProfileEditActivity.java`：协议选择 Spinner（GCM/X-Tunnel）按协议显示/隐藏字段组；按协议校验必填（gcm→worker_host，xtunnel→wss:// server_addr）；保存保留两套字段
- [x] GCM Profile 显示：WorkerHost, PrefIp, UserId, FallbackIp, DisableEch, DisableIpv6Route
- [x] x-tunnel Profile 显示：ServerAddr (wss://), Token, RelayNodes, Connections, EnableECH, ECHDomain, DNSServer, Insecure, EnableHotPair
- [x] `TProxyService.java`：按 Protocol 分支组装 paramsJSON（GCM 键 / xtunnel 键与 Go 侧一致）
- [x] URI 导入/导出：`gcm://`/`ech://` 保持兼容，新增 `xtunnel://`（token/relay_nodes/connections/ech/domain/dns/insecure/hotpair）
- [ ] 验证：CI 构建通过（Java 编译只能靠 Actions）

### 阶段 6：CI/CD 适配
- [ ] `build-debug.yml`：gomobile bind 输出名适配（已在阶段 1 完成，run 30886749438 已验证）
- [ ] AAR 产物改为 `xclient.aar`（已在阶段 1 完成，已验证）
- [ ] `release.yml`：适配新 applicationId 和 release note（已在阶段 1 完成，需验证）

### 阶段 7：端到端验证
- [ ] Go 全量测试通过（`go test ./... -count=3`）
- [ ] `go vet ./...` 通过
- [ ] GitHub Actions Debug 构建成功，4 ABI APK 产出
- [ ] GCM 协议 Profile：设备测试 VPN 连接正常
- [ ] x-tunnel 协议 Profile：设备测试 VPN 连接正常
- [ ] 协议切换：切换 Profile 后正确加载对应协议
- [ ] 全局设置（DoH/ECH/主题/路由绕过）在两种协议下都正常

## 注意事项

- 构建验证只能通过 GitHub Actions（无本地 Android 环境）
- GitHub 直连被 SNI 阻断，git 使用 `http.proxy http://192.168.4.1:7890`（仓库级配置）
- Go module 版本：当前 `go 1.23`，CI 使用 `go-version: '1.25'`，gomobile bind 需确认兼容
- x-tunnel 原 module 为 `go 1.25.5`，集成时需降级适配
- 旧的 x-client 项目已存档为 `/root/projects/x-client.legacy`

### 阶段 8：全局日志等级 + 日志时区对齐（完成，CI 已验证）

提交 `adbb4af` 推送成功，CI run `31019772769` 通过，4 ABI APK 产出
（arm64-v8a 9.2MB / armeabi-v7a 9.4MB / x86 9.7MB / x86_64 9.5MB，
体积增量来自嵌入 `time/tzdata`）。

需求：全局设置页新增「日志等级」下拉框，控制代理协议输出到运行日志的详细程度；
代理日志时间戳时区与 Android 系统时区一致（当前为 UTC）。

- [x] Go `shared/logger`：`InitGlobalLogger` 将新级别传播到已创建的 Logger（修复 xtunnel 包级 sysLog 在 init 时创建、不随 verbose/log_level 生效的问题）
- [x] Go `gcm/backend.go` + `xtunnel/backend.go`：新增 `log_level` 参数（DEBUG/INFO/WARN/ERROR，显式参数优先，`verbose` 布尔保留向后兼容）
- [x] Go `android.go`：导出 `SetTimeZone(tz)`（`time/tzdata` 嵌入 IANA 数据库 + 固定偏移 GMT±HH:MM 解析），Android 侧传入系统时区
- [x] Go 测试：log_level 优先级、logger 级别传播、SetTimeZone 命名时区/固定偏移/未知值
- [x] Android `Preferences.java`：全局 `LogLevel` 键 + 校验 getter/setter（默认 INFO）
- [x] Android `activity_settings.xml` + `SettingsActivity.java`：日志等级 Spinner（调试/信息/警告/错误），VPN 运行中禁用，保存写入偏好
- [x] Android `TProxyService.java`：两协议 paramsJSON 增加 `log_level`；onCreate 同步时区 + `ACTION_TIMEZONE_CHANGED` 广播更新
- [x] Android `XclientApplication.java`：应用启动时同步系统时区
- [x] strings（中文 + 俄语）补齐日志等级文案
- [x] 验证：`go test ./... -count=3`（14 包通过）、vet、gofmt、diff --check、gobind（`setTimeZone` 导出验证通过）→ 提交推送 → CI 构建 4 ABI APK

### 阶段 9：X-Tunnel 启用路由绕过（完成，待发布验证）

需求：全局设置的路由绕过（本地/局域网、GeoIP:CN、GeoSite:CN、手动规则）在 X-Tunnel
协议下同样生效（此前仅 GCM 接线，xtunnel 不解析 bypass_* 参数）。

- [x] Go `xtunnel`：解析 `bypass_private/bypass_geoip_cn/bypass_geosite_cn/bypass_rules` 参数，构建 `routing.Matcher`（Config 新增 4 字段，Start 中构建 matcher，非法规则启动前报错）
- [x] Go `xtunnel`：SOCKS5 与 HTTP 代理入口接入 bypass——命中规则的目标走直连（`shouldBypass`/`dialBypassTarget`/`relayBypassConnections`），不经过隧道；UDP ASSOCIATE 不参与（与 GCM 一致）
- [x] Android `TProxyService.java`：`buildXtunnelParams` 增加 4 个 bypass 键（与 GCM 共用全局偏好）
- [x] Go 测试：`TestShouldBypassRules`（14 组规则）、`TestBuildConfigBypassParams`、`TestBackendStartInvalidBypassRules`、`TestBackendSOCKS5BypassDirect`（隧道不可达时直连往返）、`TestHTTPProxyBypassDirect`（CONNECT + GET 直连）
- [x] 验证：`go test ./... -count=3`（14 包）、vet、gofmt、diff --check、gobind 导出面不变 → 提交推送 + v1.1.3 标签（Release 自动构建，不手动触发 debug 构建）

### 阶段 10：日志等级跨进程覆盖 BUG 修复 + Hot-Pair 数量可配（完成，待发布验证）

需求：
1. BUG：日志等级从 WARN 改回 INFO 保存后不生效，设置页仍显示 WARN。
   根因：TProxyService（:vpn 进程）与主进程同时写 SocksPrefs.xml——:vpn 进程复用
   陈旧缓存时 setEnable commit 全量写回，覆盖主进程保存的 LogLevel=INFO。
2. 改进：X-Tunnel Hot-Pair 支持配置启用对数（当前固定 1 对）。

- [x] `TProxyService.java`：删除全部 5 处 prefs.setEnable 写入（Enable 由主进程 ProfileListActivity/ServiceReceiver 维护）；monitorNativeTunnel 改用 runtimeRunning
- [x] `TProxyService.java`：正常停止路径 onDestroy 末尾 killProcess（保证下次会话全新进程加载 prefs，logRequestOnly 除外）
- [x] `ServiceReceiver.java` + manifest：ACTION_STATUS 过滤，兜底更新 Enable 状态
- [x] `Preferences.java`：迁移写盘仅主进程执行（isMainProcess 按 /proc/self/cmdline 判断，:vpn 进程纯只读）
- [x] `Preferences.java` + `ProfileEditActivity` + `activity_profile_edit.xml`：Hot Pair 数量输入框（默认 1，上限 8，开关联动禁用）
- [x] `TProxyService.buildXtunnelParams`：传 hot_pair_count
- [x] Go `xtunnel/backend.go`：解析 hot_pair_count → cfg.HotPairCount（1..8 校验）+ 测试（默认/显式/非法/超限）
- [x] 验证：go test/vet/gofmt/diff/XML/gobind → 提交推送 → v1.1.4 标签
### 阶段 11：v1.1.4 真机反馈 5 项修复（完成，待发布验证）

- [x] URI 导出 `hotpair=<对数>`（启用时写 prefs.getXtHotPairCount()）；导入解析 `hotpair=2..8` 为数量、`hotpair=1/true/yes` 兼容旧格式（启用 1 对），列表页与编辑页两处导入均设置 XtHotPairCount
- [x] `Preferences.getLogLevel/setLogLevel` 白名单补上 LOG_LEVEL_INFO（此前 INFO 保存被静默拒绝，DEBUG/WARN/ERROR 均可存）
- [x] hot-pair 创建日志计数 `(readyCount+1)/PairCount`，从 (1/N) 起；x-tunnel 主体 main/win7-compat 一并修
- [x] Pair ID 由 UUID 改为两位十六进制（generatePairID 原子计数器 + 活动 Pair 去重；预绑定内部 connID 仍用 UUID）；pool.go 日志去掉 [:16] 截断；主体一并修
- [x] 多 Pair 周期刷新先构建候选：候选通道与最老 Pair 完全一致时放弃候选、旧 Pair 继续服务；构建失败/通道不足时保留现有 Pair；主体一并修（x-tunnel main 8fc3ed6 / win7-compat c8d76d6 已推送）
- [x] Go 测试：pairChannelsEqual / generatePairID 格式与去重 / discardCandidatePair（refs=0 立即移除、refs>0 转 Draining）
- [x] README：URI 示例 hotpair=1 → hotpair=<对数 1..8> + 兼容说明
验证：go test -count=3（14 包）、vet、gofmt、diff --check、XML、gobind 导出面不变 → 提交推送 → v1.1.5 标签（Release 自动构建，不手动触发 debug 构建）
