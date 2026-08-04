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
