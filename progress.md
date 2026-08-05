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
