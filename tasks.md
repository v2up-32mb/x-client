# Tasks

## 当前状态

阶段 1 代码修改已全部完成并暂存（`git add -A`），但**尚未提交和推送**。
Go 测试/vet/gofmt 全部通过，GitHub Actions 构建尚未触发验证。

## 下一步

### 立即执行
- [ ] 提交阶段 1 的全部改动并推送 `origin/main`，触发 GitHub Actions Debug 构建验证
- [ ] 检查 Actions 构建结果，确认 4 ABI APK 产出正常

### 阶段 2：定义 Backend 接口和协议分发
- [ ] 在 `golib/android.go` 中定义 `ProxyBackend` 接口
- [ ] 现有 GCM 代码封装为 `gcm/backend.go`（实现 ProxyBackend）
- [ ] `StartSocksProxy` 签名改为 `(listenAddr, protocol, paramsJSON, verbose)` 形式
- [ ] 内部根据 protocol 选择对应 Backend 实例
- [ ] 保持向后兼容：旧版 `gcm://` URI 的 Profile 默认使用 GCM 协议
- [ ] Android 侧 `TProxyService.java` 改为从 Preferences 读取 Protocol 字段
- [ ] 验证：Go 测试通过，Android 能编译（CI 验证）

### 阶段 3：集成 x-tunnel 协议后端
- [ ] 从 x-tunnel main (`9ee779a`) 复制 `client/pkg/` → `golib/xtunnel/`
- [ ] 从 x-tunnel 复制 `common/` → `golib/xtunnel/protocol/`
- [ ] 将 x-tunnel 的 `log` 包替换为 `xclient/logger`
- [ ] 将 x-tunnel 的 ECH 管理器替换为共享的 `shared/ech`（补充 UDP DNS fallback）
- [ ] 编写 `xtunnel/backend.go` 实现 `ProxyBackend` 接口
- [ ] 适配 x-tunnel 的 `Config` 为 params key-value 形式
- [ ] 将 x-tunnel 的 RelayNodeManager 提取到 `xtunnel/relay/`，融合 gcm 的负载均衡能力
- [ ] 验证：x-tunnel 后端独立 Go 测试通过

### 阶段 4：共享模块优化
- [ ] `shared/ech`：融合 gcm 的 DoH 多服务器 fallback + x-tunnel 的 UDP DNS fallback
- [ ] `shared/socks5`：以 gcm 的 `socks5.Server` 为框架，移植 x-tunnel 的 UDP associate
- [ ] `shared/routing`：gcm 版本直接迁移（x-tunnel 无此功能）
- [ ] `shared/dns`：gcm 的 `DoHClient` + `DNSCache` 直接迁移
- [ ] `shared/logger`：gcm 的 `logger.Logger` 直接迁移
- [ ] `shared/config`：扩展 Profile 配置，新增 `Protocol` 字段和 per-protocol 参数
- [ ] 验证：全套 Go 测试通过

### 阶段 5：Android UI 适配
- [ ] `Preferences.java`：新增 `Protocol` 字段（枚举：GCM / X_TUNNEL）
- [ ] `ProfileEditActivity.java`：新增协议选择 Spinner；根据选择显示/隐藏对应参数字段
- [ ] GCM Profile 显示：WorkerHost, UserId, PrefIp, FallbackIp, EchDomain, EchDns, DisableEch
- [ ] x-tunnel Profile 显示：ServerAddr (wss://), Token, RelayNodes, Connections, EnableECH, ECHDomain, DNSServer, Insecure, EnableHotPair
- [ ] `TProxyService.java`：根据 Protocol 字段组装 params 并调用 `Xclient.startSocksProxy(listenAddr, protocol, paramsJSON, verbose)`
- [ ] URI 导入/导出：`gcm://` 保持兼容，新增 `xtunnel://` 格式
- [ ] 验证：CI 构建通过

### 阶段 6：CI/CD 适配
- [ ] `build-debug.yml`：gomobile bind 输出名适配（已在阶段 1 完成，需验证）
- [ ] AAR 产物改为 `xclient.aar`（已在阶段 1 完成，需验证）
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
- Go module 版本：当前 `go 1.23`，CI 使用 `go-version: '1.25'`，gomobile bind 需确认兼容
- x-tunnel 原 module 为 `go 1.25.5`，集成时需降级适配
- 旧的 x-client 项目已存档为 `/root/projects/x-client.legacy`
