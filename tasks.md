# Tasks

## 当前状态

阶段 1 已完成并验证：提交 `d9b0e26` 推送成功，GitHub Actions run `30886749438` 成功，4 ABI APK 全部产出。
阶段 2 代码修改已完成（Go + Java），待提交推送并等 CI 验证。

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

### 阶段 5：Android UI 适配
- [ ] `Preferences.java`：新增 `Protocol` 字段（已完成：`PROTOCOL`/`getProtocol`/`setProtocol`，默认 gcm）
- [ ] `ProfileEditActivity.java`：新增协议选择 Spinner；根据选择显示/隐藏对应参数字段
- [ ] GCM Profile 显示：WorkerHost, UserId, PrefIp, FallbackIp, EchDomain, EchDns, DisableEch
- [ ] x-tunnel Profile 显示：ServerAddr (wss://), Token, RelayNodes, Connections, EnableECH, ECHDomain, DNSServer, Insecure, EnableHotPair
- [ ] `TProxyService.java`：根据 Protocol 字段组装 params 并调用 `Xclient.startSocksProxy(listenAddr, protocol, paramsJSON, verbose)`（已完成，待 CI 验证）
- [ ] URI 导入/导出：`gcm://` 保持兼容，新增 `xtunnel://` 格式
- [ ] 验证：CI 构建通过

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
