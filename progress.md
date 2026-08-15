# Progress

## 2026-08-04 阶段 0：项目骨架搭建

- 合并 gcm-client `feat/global-network-settings` 到 `main` 并推送 (`1ece217`)
- 从 gcm-client main (`1ece217`) 全量 fork 94 个文件到 `/root/projects/x-client`
- 创建 GitHub 仓库 `v2up-32mb/x-client`（私有），初始提交 `c1e76de` 已推送
- 编写 `INTEGRATION_PLAN.md`（249 行），包含两项目架构对比、共用功能交叉分析、目标架构、Backend 接口设计、7 阶段实施计划、风险注意事项
- 将旧的 x-client（ECH 单文件隧道原型）重命名为 `x-client.legacy` 存档

## 2026-08-04 阶段 1：Go module 重命名和包名迁移（代码修改已完成，未提交）

### Go 侧
- `golib/go.mod`: `module gcm` → `module xclient`
- `golib/android.go` + `android_test.go`: `package gcm` → `package xclient`
- 全部 15 个 Go 文件的 import 路径从 `"gcm/xxx"` → `"xclient/xxx"`（config/dns/ech/logger/pool/protocol/relay/routing/socks5）
- `gofmt -w` 格式化 5 个继承自 gcm-client 的格式偏差文件
- 验证：`go build ./...` 通过，`go vet ./...` 通过，`go test ./... -count=3` 全部 9 个包通过，`git diff --check` 通过

### Android 侧
- Java 包名 `com.gcm.client.app` → `com.x.client.app`（14 个 Java 文件物理移到新目录）
- `import gcm.Gcm` → `import xclient.Xclient`，所有 `Gcm.` 调用 → `Xclient.`（TProxyService + SettingsActivity）
- TProxyService 广播 ACTION 常量 `com.gcm.client.app.*` → `com.x.client.app.*`
- `GcmApplication.java` → `XclientApplication.java`（AndroidManifest 同步更新）
- `app/build.gradle`: `namespace`/`applicationId`/`PKGNAME` 改为 `com.x.client.app` / `com/x/client/app`，AAR 改为 `xclient.aar`
- `settings.gradle`: `gcmclient` → `xclient`
- XML 布局文件：3 处自定义 View 引用 `com.gcm.client.app.*` → `com.x.client.app.*`
- `strings.xml`（中文 + 俄语）：`GCM 代理` → `X 代理`

### CI/CD
- `build-debug.yml`：AAR 名 `gcm.aar` → `xclient.aar`，step ID `check-gcm-aar` → `check-xclient-aar`，输出变量同步；push 触发分支新增 `main` 和 `develop`
- `release.yml`：AAR 名同步修改；Release APK 重命名前缀 `gcm-client-` → `x-client-`；APP_NAME `GCM Client` → `X Client`

### CLAUDE.md
- 重写为 x-client 多协议项目描述，更新 module 名、包名、AAR 名、协议支持说明、URI 格式、构建约束

### 未提交状态
- 41 个文件已修改（git add -A 已暂存），未提交未推送

## 2026-08-04 阶段 1 完成：提交推送 + CI 验证通过

- 提交 `d9b0e26`（43 文件：Go module 重命名、Android 包名迁移、CI 适配、progress/tasks 文档），推送 origin/main
- GitHub Actions Build Debug run `30886749438` 成功（6m33s），4 ABI APK 产出：
  `app-arm64-v8a-debug` / `app-armeabi-v7a-debug` / `app-x86-debug` / `app-x86_64-debug`
- 网络说明：本机直连 github.com:443 被 SNI 阻断，git 已配置 `http.proxy http://192.168.4.1:7890`（仓库级）

## 2026-08-04 阶段 2 进行中：ProxyBackend 接口 + 协议分发

### Go 侧（已完成，未提交）
- `golib/gcm/backend.go`（新包 `xclient/gcm`）：GCM 生命周期封装为 `Backend`，实现 `Start/Stop/Reconnect/NotifyNetworkChanged`；参数解析收敛为 `buildConfig(listenAddr, params, verbose)`，16 个参数字典化（worker_host/ws_conn/relay_ips/user_id/proxy_ip/ech_domain/ech_dns/enable_ech/disable_ipv6_route/enable_dns_warmup/bypass_*/enable_dynamic_pool/dynamic_pool_max）
- `golib/android.go`：定义 `ProxyBackend` 接口 + `StartSocksProxy(listenAddr, protocol, paramsJSON, verbose)` 分发入口；空 protocol 默认 GCM（向后兼容）；`xtunnel` 返回"未实现"错误；Stop/Reconnect/NotifyNetworkChanged 转发到 activeBackend
- 测试：`golib/gcm/backend_test.go`（参数全量/默认/错误路径/池设置/Worker 归一化）+ `android_test.go` 重写（JSON 解析/协议分发/幂等停止），全部通过

- 阶段 2 提交 `a1bf62f` 推送成功，GitHub Actions Build Debug run `30887891590` 成功，4 ABI APK 全部产出（arm64-v8a 9.0MB / armeabi-v7a 9.2MB / x86 9.5MB / x86_64 9.3MB）

## 2026-08-04 阶段 3：集成 x-tunnel 协议后端（代码完成，待提交）

- Go 版本按要求保持 1.25.5：`golib/go.mod` 从 `go 1.23` 升级为 `go 1.25.5`，新增 `google/uuid v1.6.0`，websocket 升到 v1.5.3
- 拷贝 x-tunnel main (9ee779a) `client/pkg` → `golib/xtunnel/`、`common` → `golib/xtunnel/protocol/`、relay 提取到 `golib/xtunnel/relay/`（约 6000 行含测试）
- 适配：包名/import 重写、`log` → `xclient/logger`（XTunnel/XTunnelRelay scope）
- ECH 融合：`dns.ResolveHTTPSUDP`（UDP DNS type 65 查询），共享 ech 管理器 DoH 多服务器 → UDP 8.8.8.8:53 → 标准 TLS 三级回退；x-tunnel 原生 ECHManager 删除
- `xtunnel/backend.go` 实现 ProxyBackend；android.go 接通 `xtunnel` 分发；参数键值化（10 个键）
- relay 融合 gcm 负载均衡：`candidateWeight = 评分 × 负载因子`，Acquire/Release 活跃计数，SelectNodeExcluding 加权随机
- 修复上游缺陷（x-tunnel main 上同样失败）：SOCKS5/HTTP 无通道时标准失败应答；Shutdown 关闭 SOCKS5/HTTP 监听器释放端口
- 测试：移植 10 个 x-tunnel 测试文件 + 新增 backend/params/生命周期（端口释放）/ech UDP fallback/UDP DNS 测试；全量 `go test ./... -count=3`、vet、gofmt、diff --check 通过；gobind 验证绑定面不变

## 2026-08-04 阶段 4：共享模块优化（代码完成，待提交 + CI 验证）

- golib 重组为三层结构：`shared/`（config/dns/ech/logger/routing/socks5）、`gcm/`（backend/pool/protocol/relay）、`xtunnel/`（不变）；全量 import 重写（git mv 保留历史）
- 14 个包 `go test ./... -count=3`、vet、gofmt、diff --check、gobind 全部通过
- 决策：`shared/socks5` 的 UDP associate 融合延后——`gcm/protocol` 无 UDP 消息类型，需 GCM 服务端协议扩展（客户端不可独立完成）；x-tunnel 的 UDP 能力保留在 xtunnel 内部

## 2026-08-04 阶段 5：Android UI 适配（代码完成，待提交 + CI 验证）

- ProfileEditActivity：协议 Spinner（GCM/X-Tunnel）切换显示两组字段；按协议校验必填；两套参数均持久化（切换不丢）
- Preferences：9 个 X-Tunnel per-profile 参数存取；列表页地址显示回退到 xtunnel server_addr
- TProxyService：按 Protocol 分支组装 paramsJSON（键与 Go 侧一致：server_addr/token/connections/relay_nodes/enable_ech/ech_domain/dns_server/insecure/enable_hot_pair）
- URI：`xtunnel://` 导入导出（token/relay_nodes/connections/ech/domain/dns/insecure/hotpair），`gcm://`/`ech://` 兼容
- strings.xml（中/俄）新增协议与 X-Tunnel 字段文案

## 2026-08-04 真机反馈修复（stage 5 APK 实测）

- **问题 1**：`invalid params JSON: json: cannot unmarshal number into Go value of type string`——Android JSONObject.put 输出无引号数字/布尔。修复：`golib/android.go` `parseParamsJSON` 改为解析 `map[string]interface{}`，标量统一转字符串（float64→FormatFloat、bool→FormatBool、nil→空），数组/对象报错；新增 `TestParseParamsJSONScalarTypes`
- **问题 2**：x-tunnel 编辑页删除「ECH 查询域名」「DoH 服务器」设置项，TProxyService 组装参数时复用全局 `getEchDomain()/getEchDns()`（与 GCM 一致）；Preferences 删除 XT_ECH_DOMAIN/XT_DNS_SERVER
- **问题 3**：x-tunnel ECH 文案统一为 GCM 的「禁用 ECH（标准 TLS 1.3）」（@string/disable_ech），checkbox 默认未选中 = 默认启用 ECH；存储语义改为 `XtDisableEch`（默认 false），导出 URI `ech=0` 表示禁用、导入 `ech=0/false/no` 置禁用；旧 URI 的 domain/dns 参数忽略（兼容）

## 2026-08-04 真机反馈第二轮修复（6 项）

1. VPN 运行时禁止在列表页切换配置（onProfileClick 增加 getEnable() 守卫 + Toast）
2. xtunnel 分享 URI 服务器地址为空：export 分支改用 `getXtServerAddr()`（worker_host 对 xtunnel 为空）
3. 配置文件列表右下角显示协议标签（ProfileInfo 增加 protocol 字段，item_profile_swipe 增加 text_protocol）
4. 启动/停止按钮下移 5dp（activity_profile_list btn_start marginBottom 24→19dp）
5. 全局设置页标题「GCM 全局网络设置」→「全局网络设置」
6. WebSocket 连接数 / 启用连接池动态扩容 / 动态扩容连接上限：从全局设置移入 GCM 协议配置页（Preferences 改 per-profile 存储 + 旧全局值回退；ProfileEditActivity 新增三字段与校验；保存仅在 GCM 协议分支写入避免覆写）

## 2026-08-04 真机反馈第三轮修复 + Release 签名准备

- GCM 分享链接补充 3 个连接池参数（ws_conn / enable_dynamic_pool / dynamic_pool_max）：导出始终携带（无损分享），ProfileListActivity 导入建配置与 ProfileEditActivity 导入填字段同步支持
- Release 签名：为 x-client 仓库生成全新 PKCS12 keystore 并配置 4 个 Actions secrets（SIGNING_KEY / ALIAS=xclient / KEY_STORE_PASSWORD / KEY_PASSWORD）
  - 本地备份：/root/secrets/x-client/（xclient-release.p12、CREDENTIALS.env、SIGNING_KEY.b64），权限 600，**必须离线备份**，丢失后无法再签后续版本
  - 验证：check-keystore.yml workflow 端到端校验

## 2026-08-04 v1.1 Release 发布成功

- Release workflow run `30927171092` 成功，GitHub Release「X Client - v1.1」已发布（非 draft/prerelease）
- 5 个签名 APK：arm64-v8a / armeabi-v7a / x86 / x86_64 / universal（15-46MB）
- 修复过程：① `secrets.GITHUB_TOKEN` → `github.token`（原引用不存在的 secret 导致 Create Release 403）；② 仓库 Actions 权限改为 write（`/actions/permissions/workflow`）；③ 新增 `workflow_dispatch` 便于重试；④ v1.1 tag 移至 af50487（含修复）
- Secrets：SIGNING_KEY / ALIAS=xclient / KEY_STORE_PASSWORD / KEY_PASSWORD（check-keystore workflow 验证通过）
- 签名密钥备份：`/root/secrets/x-client/`（xclient-release.p12 + CREDENTIALS.env，600 权限）——必须离线备份，丢失后无法签署后续版本

## 2026-08-05 真机日志定位并修复：ECH 缓存击穿 + 断线重连后通道失效

日志证据（xtunnel 配置，21 通道）：启动时 20+ 次重复 DoH 查询（ECH 缓存被并发击穿）；00:30:36 全部通道 close 1006 断开后重连，随后大量 `ping发送失败: use of closed network connection`，00:38 起 SOCKS5 全部超时（TX - RX -）。

- **ECH 缓存击穿**：shared/ech 增加 singleflight（inflight map），同一域名并发取配置只执行一次网络查询，其余等待在途结果；新增并发测试（8 个 goroutine 只触发 1 次查询）
- **断线重连失效**：根因是写队列 `writeQueues[idx]` 跨会话共享——旧连接遗留的 writeWorker 存活期间会与新连接的 writeWorker 抢队列数据包，把请求写进已关闭连接（日志中的 ping 失败即旧 worker），导致新通道收不到请求（SOCKS5 超时）。修复：**会话级写队列**（每次连接创建独立 queue 并原子替换），旧 worker 只能消费旧队列，很快自行退出
- **网络切换响应慢**：xtunnel Backend.Reconnect/NotifyNetworkChanged 原为 no-op，需等 TCP 死链检测（日志中约 7 秒+）。修复：pool 增加 reconnectLoop + Reconnect()，收到信号立即关闭全部当前 WebSocket 强制在新网络重建；Client.Reconnect 接线到 Backend
- dialer ECH 失败重试的 Refresh 改用 `p.config.ECHDomain`（原误用目标服务器名，与缓存键不一致）
- 新增 TestPoolReconnectClosesCurrentChannels；全量 14 包测试/vet/gofmt/diff --check/gobind 通过

## 2026-08-05 真机反馈第五轮修复：xtunnel 分享导入 token 丢失 + 无 host 链接

- 导出 bug：xtunnel 分支读取 XtServerAddr 发生在「恢复当前配置」之后（分享非当前 profile 时读到旧 profile 的值）→ 移到恢复前读取
- 导入 bug：xtunnel:// 链接的 token 参数落入 GCM user_id 变量（xtToken 恒空）→ 按协议分支解析（列表页 + 编辑页）
- 健壮性：导入拒绝无 host 链接（`链接缺少服务器地址，无法导入`）；Go 侧 buildConfig 校验 ServerAddr 必须含 host，启动即报清晰错误（不再出现 TLS `ServerName` 谜之报错）
- 说明：旧格式分享链接（第一轮反馈的 `xtunnel://?token=...`）本身不含服务器地址，无法导入；请用新版本重新分享后再导入

## 2026-08-05 真机反馈第六轮：xtunnel TLS ServerName 报错排查（进行中）

- 用户澄清：导入链接带服务器地址、编辑页显示正确、token 为空，但启动仍报 `tls: either ServerName or InsecureSkipVerify must be specified`
- 排查结论：Go 侧全链路实测正常（buildConfig → url.Parse → Hostname）；Java 侧 Preferences per-profile 读写、TProxyService 组装、gomobile 绑定均无断裂；ServerAddr 仅一处赋值；app/libs 无提交（AAR 每次 CI 重建）
- 已加防御与诊断：① Go 侧 buildConfig 校验 ServerAddr 必须含 host（dfe43d3，启动即报清晰错误）；② TProxyService 启动时把实际 paramsJSON 写入运行日志（App 内「运行日志」可见）
- 待真机复测：若 `启动参数(xtunnel)` 中 server_addr 正确仍报 TLS 错，则需完整启动日志进一步定位

## 2026-08-05 v1.1.1 Release 发布成功

- 修复：xtunnel 分享链接导入 host 解析——`"xtunnel://"` 为 10 字符，导入误用 `substring(9)` 导致服务器地址解析成 `wss:///ech-us.ics.de5.net:443`（多一个 `/`）；列表/编辑两处导入均改为 `substring(10)`，并加前导斜杠容错（兼容旧链接）
- CI 策略变更：build-debug.yml 仅 `workflow_dispatch` 手动触发（push 不再自动构建）；release.yml 保留 tag 触发
- v1.1.1 发布成功（run 30983082578）：5 个签名 APK（arm64-v8a/armeabi-v7a/x86/x86_64/universal）

## 2026-08-05 阶段 8：全局日志等级 + 日志时区对齐

### 需求
1. 全局设置页新增「日志等级」下拉框，控制代理协议（GCM / X-Tunnel）输出到运行日志的详细程度
2. 代理日志时间戳时区与 Android 系统时区一致（此前显示 UTC）

### Go 侧
- `shared/logger`：`InitGlobalLogger` 改为统一走 `SetGlobalLevel`，把级别传播到已创建的 Logger——修复 xtunnel 包级 `sysLog` 在包 init 阶段创建、此前不随 verbose/log_level 生效的隐藏问题
- `gcm/backend.go` + `xtunnel/backend.go`：新增 `log_level` 参数（DEBUG/INFO/WARN/ERROR）；显式参数优先，`verbose` 布尔保留向后兼容；xtunnel 侧收敛为 `logLevelFromParams` 助手
- `android.go`：导出 `SetTimeZone(tz)` + 嵌入 `time/tzdata`（Android 无 zoneinfo 时 LoadLocation 可用）；支持 IANA 名称（Asia/Shanghai）与 Android 固定偏移 ID（GMT+08:00 / GMT-05:30 / UTC+8）
- 测试：`TestBuildConfigLogLevelPrecedence`、`TestLogLevelFromParams`、`TestInitGlobalLoggerPropagatesToExistingLoggers`、`TestSetTimeZone` 全部通过

### Android 侧
- `Preferences.java`：全局 `LogLevel` 键（默认 INFO，未知值回退 INFO），`getLogLevel/setLogLevel`
- `activity_settings.xml` + `SettingsActivity.java`：日志等级 Spinner（调试/信息/警告/错误），VPN 运行中禁用，保存写入偏好
- `TProxyService.java`：GCM/X-Tunnel paramsJSON 均增加 `log_level`；`onCreate` 同步系统时区并注册 `ACTION_TIMEZONE_CHANGED` 接收器（VPN 运行中改时区即时生效），`onDestroy` 注销
- `XclientApplication.java`：应用启动时同步系统时区（主进程）
- strings 中文 + 俄语补齐文案

### 验证
- `go test ./... -count=3`：14 包全部通过；`go vet`、`gofmt -l`、`git diff --check` 干净
- XML 三个资源文件 well-formed 校验通过
- gobind 完整验证：`go get golang.org/x/mobile/bind` 后 `gobind -lang=java` 成功（exit 0），`Xclient.setTimeZone(String)` 导出确认；go.mod/go.sum 改动已还原不提交

## 2026-08-05 阶段 9：X-Tunnel 启用路由绕过

### 需求
全局设置的路由绕过（本地/局域网、GeoIP:CN、GeoSite:CN、手动规则）在 X-Tunnel 协议下同样生效。
此前该能力只在 GCM 后端接线（gcm/backend.go 构建 routing.Matcher + shared/socks5.SetBypassMatcher），
xtunnel 的 SOCKS5/HTTP 是独立实现，既不解析 bypass_* 参数也无法分流。

### Go 侧
- `xtunnel/config.go`：Config 新增 `BypassPrivate/BypassGeoIPCN/BypassGeoSiteCN/BypassRules` 4 字段
- `xtunnel/backend.go`：新增 4 个参数常量（键与 GCM 一致）；buildConfig 解析并校验布尔类型；Start 中构建
  `routing.NewMatcher`（非法规则在启动网络前报 `invalid bypass rules`）；`Client.SetBypassMatcher` 注入
- `xtunnel/pool.go`：`clientPool.bypassMatcher` + `shouldBypass`（域名/IP/CIDR/private/geosite 直接生效；
  GEOIP 类规则仅对 IP 目标生效——xtunnel 不做客户端 DNS 解析，域名由服务端解析）+ `dialBypassTarget`
  （复用 connectTimeout）+ `relayBypassConnections`（TCP 半关闭语义，支持 bufio 包装）+ `asTCP`
- `xtunnel/socks5.go`：`handleSOCKS5Connect` 命中绕过 → `handleSOCKS5Direct` 直连（失败发标准 SOCKS5 失败应答）；
  UDP ASSOCIATE 不参与绕过（与 GCM 一致，GCM wire 协议无 UDP）
- `xtunnel/http_proxy.go`：CONNECT 命中 → 直连 + 200；普通请求命中 → 重建上游请求 + 缓冲字节直连转发
  （`bufioProxyConn` 保证 bufio 已缓冲字节不被跳过）

### Android 侧
- `TProxyService.buildXtunnelParams`：增加 `bypass_private/bypass_geoip_cn/bypass_geosite_cn/bypass_rules`
  （与 GCM 共用全局设置偏好）

### 测试（golib/xtunnel/bypass_test.go）
- `TestShouldBypassRules`：14 组规则表（private/geoip/geosite/manual domain+suffix/full/IP/CIDR/IPv6）
- `TestBuildConfigBypassParams`：参数解析 + 布尔类型错误在启动前报错
- `TestBackendStartInvalidBypassRules`：非法规则报 `invalid bypass rules`
- `TestBackendSOCKS5BypassDirect`：隧道不可达（wss://127.0.0.1:1）时直连仍完成 SOCKS5 握手 + echo 往返
- `TestHTTPProxyBypassDirect`：HTTP CONNECT 与普通 GET 均直连成功

### 验证
- `go test ./... -count=3`：14 包全部通过；`go vet`、`gofmt -l`、`git diff --check` 干净
- gobind 导出面不变（7 个 API，含 setTimeZone）
- README 同步：X-Tunnel 路由绕过标 ✅，参数键表补 4 键

## 2026-08-06 阶段 10：日志等级跨进程覆盖修复 + Hot-Pair 数量可配

### BUG：日志等级改回 INFO 不生效（v1.1.3 真机反馈）
现象：WARN 保存生效；改回 INFO 保存后启动 VPN 仍无 INFO 日志，设置页仍显示 WARN。

根因：**多进程 SharedPreferences 陈旧缓存全量写回**。TProxyService 运行在 `:vpn` 独立进程，
与主进程同时写 SocksPrefs.xml（service 5 处 `setEnable`）。Android 7+ 忽略 MODE_MULTI_PROCESS，
`:vpn` 进程复用上次会话缓存（LogLevel=WARN）时，任意 `commit` 会把整个文件全量写回，
覆盖主进程刚保存的 LogLevel=INFO（磁盘与主进程缓存均被污染 → 日志仍 WARN、设置页显示 WARN）。

修复（:vpn 进程变为纯只读）：
- TProxyService 删除全部 setEnable 写入；Enable 状态完全由主进程维护
  （ProfileListActivity 的 STATUS receiver 已写 STARTED/ERROR/STOPPED；ServiceReceiver
  manifest 新增 ACTION_STATUS 过滤作为主界面不在前台时的兜底）
- TProxyService 正常停止路径 onDestroy 末尾 `killProcess(myPid())`：保证下一次 VPN
  会话以全新进程从磁盘加载 prefs（logRequestOnly 日志请求路径除外）
- Preferences.migrateGlobalNetworkSettings 仅主进程执行（/proc/self/cmdline 判断进程名），
  杜绝 :vpn 进程在构造 Preferences 时因迁移 commit 全量写回

### 改进：X-Tunnel Hot-Pair 数量可配
- Preferences：`XtHotPairCount`（per-profile，默认 1，上限 8）
- ProfileEditActivity：启用热通道对开关下新增「热通道对数（1-8）」输入框，开关联动禁用；
  保存校验数字与范围（仅 xtunnel + 启用时生效）
- TProxyService：paramsJSON 增加 `hot_pair_count`
- Go xtunnel/backend.go：解析 `hot_pair_count` → cfg.HotPairCount（上限 8，超限/非法启动前报错）
  ——x-tunnel 主体连接池本就支持多 Pair（PairWarmer.PairCount = cfg.HotPairCount）

### 验证
- go test ./... -count=3（14 包）、vet、gofmt、diff --check、XML well-formed、gobind 导出面不变（8 API）
- 新增 TestBuildConfigHotPairCount（默认 1 / 显式 4 / 非数字 / 9 超限）
- 发布 v1.1.4（Release 自动触发）

## 2026-08-06 阶段 14：v1.1.6 真机反馈 3 项修复（代码完成）

### 1. hot-pair ID 稳定为槽位 ID（不再递增）
- 现象：Pair 20/21/22 持续递增；期望设置 2 对时 ID 恒为 01/02。
- 修复：删除 generatePairID（全局递增）+ pairIDCounter；新增 assignPairSlot 分配 1..8 最小未占用槽位（%02d）。
  BuildPair 不再预分配 ID（「准备构建时不急于设置 ID」）；tryBuildPairs 构建成功后分配。
  周期刷新替换路径：候选与最老 Pair 通道一致 → 放弃候选（不分配 ID），旧 Pair 保留原 ID 继续服务；
  通道不同 → 候选继承旧 Pair 槽位 ID，旧 Pair 正常 Draining；底层 prebind connID 仍为 UUID。

### 2. SOCKS5 最大连接数：确认并发语义 + 突发软等待 + 文案修正
- 根因排查：信号量获取/释放逻辑正确（defer 释放），新增测试证明顺序 10 次短连接无泄漏、
  3 并发占满后第 4 个被拒 → 语义本来就是并发限制，非累计 bug。
- 真机误拒来自应用突发短连接尖峰（10 个连接几乎同时打开）与误导性文案。
- 修复：acquireProxySlot 助手（socks5/http 共用），拒绝前等待 100ms 软窗口吸收突发；
  文案改为「SOCKS5 并发连接数已达上限 (N)，拒绝新连接，请稍后重试」（HTTP 代理同改），级别 Warn。

### 3. 两协议日志重新分级
- xtunnel：84 处 Info → 52 处 Info / 32 处 Warn / 1 处 Error / 3 处 Debug。
  升级类：通道连接失败/断开重连/重试超限/无健康节点/写队列满/背压拥堵/ping-pong 失败/
  读取消息失败/Hot Pair 上行发送失败回退广播/无可用通道/构建 Pair 失败/通道失效重建/
  可用通道不足/SOCKS5 连接失败与超时/发送数据失败/中转节点测速与标记失败/后端未运行收到重连请求等；
  Error：PairWarmer 启动超时且无就绪通道放弃启动。
- gcm：核查后分级已完善（Warn/Error 已有：ECH 预取失败、预热失败/panic、拨号失败、节点连续失败、连接满载等），未改动。

### 验证
- go test ./...（14 包全过）；新增 TestPairWarmerAssignPairSlot、TestSOCKS5SoftLimitWaitWindowAbsorbsBurst
- x-tunnel 主体同步（main + win7-compat）：pair 槽位 ID + socks5/HTTP 并发软等待与文案，待提交推送
- x-tunnel main 仅剩 3 个改动前即存在的 flaky 失败（超时类测试，stash 验证无关）；未打标签

## 2026-08-08 阶段 15：滑动菜单复制配置 + VPN 状态启动校验（代码完成，待构建/真机验证）

### 1. 配置文件「复制」功能
- 滑动菜单按钮顺序：分享 → 复制 → 修改 → 删除；复制为橙色 ic_action_copy（自建 content_copy 矢量，不依赖系统图标）
- 点击复制：AlertDialog 名称输入框（预填原名称）→ 确定后 `UUID.randomUUID()` 新 id，
  `Preferences.copyProfile` 遍历 getAll() 复制所有 `_<源id>` 后缀键（String/Int/Long/Bool/Float/StringSet 类型保留，
  跳过 ProfileName_*）→ addProfile 注册新 id；取消则不创建
- 名称允许重复：移除 edit 保存与导入两处 `profileNameExists` 拦截（连同方法一并删除），
  底层始终以 profile id（UUID）区分，无冲突
- removeProfile 顺带修复：原硬编码键列表漏删 XT_*、AdvancedParams 等；改为遍历 getAll() 删除全部 `_<id>` 后缀键，
  避免复制-删除循环后残留垃圾键
- 复制为只读操作，VPN 运行时也可用（与分享一致）

### 2. BUG：APP 被意外终止后按钮仍显示「停止」
- 根因：Enable 为持久化标记，进程被杀时 TProxyService(:vpn) 与 VPN 隧道随进程消亡但无广播，标记残留
- 修复（ProfileListActivity.reconcileVpnState，onCreate + onResume 调用）：
  视为「自身 VPN 运行中」需同时满足 ①Enable=true ②系统存在活跃 TRANSPORT_VPN 网络
  ③getRunningServices 中本应用 TProxyService 存活（Android 8+ 该 API 只返回本应用服务，
  因此其他 VPN 程序建立的 VPN 网络不会被误判为自身的）；任一不满足 → setEnable(false)，按钮显示「启动」

### 验证
- 27 个 res XML well-formed、git diff --check、golib go test/vet 全过（本轮无 Go 改动）
- 无本地 Java/Android SDK（Java 未装，gradlew 无法运行），Android 构建验证在用户要求时经 Actions；
  未打标签（用户未要求），未触发 debug 构建

## 2026-08-08 发布 v1.1.7

- 附注标签 v1.1.7（tag x-client v1.1.7: copy profile from swipe menu + VPN running-state validation on startup）推送 origin
- Release APK 工作流自动触发（run 31232831398）completed/success，非 debug 手动构建
- Release "X Client - v1.1.7"（非 draft/非 prerelease）：5 个签名 APK 齐全
  （arm64-v8a 15.7MB / armeabi-v7a 15.5MB / universal 47.9MB / x86 15.7MB / x86_64 16.4MB）

## 2026-08-08 阶段 16：滑动菜单动画宽度修复（v1.1.7 反馈）

- 现象：菜单从 3 键变 4 键后，展开动画显示完第 3 个按钮就"直接展开完毕"，第 4 个按钮瞬间弹出
- 根因：`SwipeRevealLayout.actionWidth` 硬编码 168dp = 56dp*3，未随按钮数同步；
  `drawChild` 裁剪条件 `currentRevealWidth < actionWidth` 在 168dp 处失效，剩余 56dp 直接整块绘制
- 修复：onLayout 后按 actionButtons 实际宽度（224dp）动态更新 actionWidth；
  初始 GONE 状态下强制 measure 获取真实宽度；setTranslationOffset 兜底同步；
  动画时长不变（250ms），展开/收拢/裁剪/透明度/吸附阈值全程覆盖全部 4 个按钮

## 2026-08-14 阶段 17：背压调参第一步 + speedtest 上传断连排查（代码调参完成已推送，待真机验证）

### 分支与发布状态
- main 打 v1.1.8 标签并推送（README/发布说明：滑动菜单动画宽度修复），Release 自动触发（x-client 仓库）
- x-client 与 x-tunnel 两仓库创建 feat/backpressure-tuning 分支（基于各自 main）；x-tunnel 分支工作区
  有未跟踪的构建产物 client/x-tunnel-client 与 docs/communication-protocol.md，勿提交前者

### 根因分析（关键）
上传断连链路：speedtest 上行 → 本地 SOCKS5 → 客户端 asyncWriteDirect（全局队列 8MB + 单通道写队列 4096 条）
→ WS → 服务端 handleTCPData → 目标 socket。断连最可能在客户端写队列满：WriteQueueWaitTimeout=100ms 超时后
asyncWriteDirect 返回"写队列超限/缓冲区拥堵" → SendDataDirect 错误 → socks5.go handleSOCKS5Connect 直接 return
（defer c.Close()）→ speedtest 报网络错误，且可能偶发（取决于瞬时流量）。
服务端背压（默认 1MB）只广播下行状态，且 broadcastBackpressure 会拖慢上传，需对齐放大。
对比 GCM 协议：GCM 用连接池+多路复用流，无此全局队列瓶颈，故只有 xtunnel 上传失败。

### 已改（x-client golib/xtunnel，提交 be96773 并推送）
- pool.go：writeQueueSize 4096→16384；waitForBackpressure 加 3s 超时，超时降级 Pause→SlowDown 继续发送
- config.go：DefaultBackpressureLimitBytes 8MB→16MB；WriteQueueWaitTimeout 默认 100ms→500ms；注释同步
- pool.go 注释 8MB→16MB
- config_test.go 断言同步更新（500ms/16MB）；golib go test ./... -count=3 全绿、go vet ./... 通过

### 补充调整（2026-08-16）
- 修复严重 BUG：服务端 ch_id 占用检查原本全局生效，多客户端时后到客户端若与已连客户端 ch_id 相同会被拒绝
  （日志：拒绝客户端 ... ch_id N 已被占用）。根因：服务端按全局 chID 维护连接（chConns），未按客户端隔离。
- 修复方案（x-tunnel 5002b7d）：通道索引改为 clientID -> chID -> wsConn（clientChConns）：
  - handleWebSocket 占用检查/自动分配均限定在客户端自身编号空间；wsConns 改纯 append
  - 消息路由基于来源连接定位客户端（handleMessage 传来源 clientID；selectDownlink/prebind/UDP connect 查本客户端空间）
  - cleanupChannel 按 clientID+chID 清理，且只清理属于该客户端的连接状态，不误关其他客户端同 ch_id 连接
  - 新增回归测试：多客户端同 ch_id 同时在线且互不干扰（TestMultipleClientsCanShareSameChID）、
    清理隔离（TestCleanupChannelDoesNotCloseOtherClientSameChID）；修复了测试与 ws 升级握手的注册竞态（轮询就绪）
  - 协议与客户端零改动（消息帧无 client_id 字段，chID 语义保持：客户端各自 1..N）
- 背压数值最终调整：客户端 16MB->8MB（x-client 4443c69 / x-tunnel 5b10fc1），
  服务端 1MB->32MB（server/pkg DefaultConfig + newServerPool fallback + CLI -backpressure-limit 默认值 1MB->32MB 三处，x-tunnel 5b10fc1）；
  客户端写队列 16384/等待 500ms/Pause 3s 降级保持不变
- 验证：x-tunnel server/pkg go test 3 轮全绿、go test ./... 仅 3 个既有 flaky；x-client golib go test -count=3 全绿 + vet 通过；
  race 检测器环境不支持（TSan unsupported VMA range）
- 服务端启动命令建议（32MB 生效默认）：x-tunnel-server -l :10000 -token v2up-ech

### v1.1.9 发布（2026-08-16）
- 用户要求 main 快进到 feat/backpressure-tuning 顶（b40ad91）并打标签发布
- main：8afcb2b -> b40ad91（fast-forward），附注标签 v1.1.9（message: 背压调参第一步 + 多客户端 ch_id 独立编号空间修复），
  均推送 GitHub 并远程验证（main=b40ad91，tag 指向同一 commit）
- 推送时 192.168.4.1:7890 代理故障（GnuTLS handshake failed，curl 同失败），按用户指示直连 push 成功（未改仓库代理配置）
- Release APK workflow 自动触发：run 31896030985（v1.1.9，push 事件），未手动触发 debug 构建
- 内容：客户端背压 8MB/写队列 16384/等待 500ms/Pause 3s 降级；服务端 32MB + CLI flag 默认值修复；多客户端 ch_id 隔离（服务端侧）

### 待办（真机验证）
- [x] 更新 config_test.go 断言 → golib 全量 go test -count=3/vet 通过（x-client be96773）
- [x] x-tunnel client/pkg + server/pkg 同步改动（CLI 客户端 8MB→16MB/100ms→500ms/写队列 16384/Pause 3s；
  服务端 BackpressureLimitBytes 1MB→16MB）（x-tunnel 35c52fd；go test 仅 3 个既有 flaky 失败，HEAD 复现确认非本次引入）
- [x] 两仓库分别测试、提交、推送 feat/backpressure-tuning（x-client→GitHub、x-tunnel→gitea）
- [ ] 用户真机验证 speedtest 上传；结果决定"调参落地"还是"smux 完整重构"
