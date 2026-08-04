# X Client (Android)

Android 多协议 VPN 客户端，支持每个 Profile 配置一个代理协议。

基于 [gcm-client](https://github.com/v2up-32mb/gcm-client) 改造，复用其 Profile 管理、VPN 隧道框架、路由绕过、全局设置等功能，扩展为多协议架构。

## 技术栈

- **Android 层**：Java 17 / AGP 8.x / Material / ZXing，包名 `com.x.client.app`，`applicationId` 同名
- **Go 核心库**：`golib/`（`module xclient`，Go 1.23），通过 `gomobile bind` 编译为 `app/libs/xclient.aar`
  - `android.go`（package xclient）是 gomobile 入口 thin wrapper
  - 完整 package：`config / dns / ech / logger / pool / protocol / relay / routing / socks5`
  - 完整保留 ECH / DoH / 连接池 / 流复用 / quality_monitor / relay / warmup 全部能力
- **VPN 隧道**：[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（CI 阶段 `git clone` 到 `app/src/main/jni`）
- **SOCKS5 / HTTP 代理**：由 Go 核心库在本地监听，`hev-socks5-tunnel` 将 VPN Tun 流量转发到该 SOCKS5

## 协议支持

### GCM（当前已实现）

GCM 二进制多路复用协议（2 字节头）：

```
[STREAM_ID:1][TYPE:1][可选 DATA]
TYPE = 0 CONNECT    DATA = ASCII "host:port|"
TYPE = 1 CONNECTED  无 DATA
TYPE = 2 DATA       DATA = 任意二进制
TYPE = 3 CLOSE       无 DATA
```

WebSocket 连接：`wss://<workerHost>/<userID>?fallbackip=<出口IP列表>`

### x-tunnel（计划集成）

多通道 WebSocket 隧道，8 字节头协议，支持通道选择机制 + Hot Pair + UDP associate。
详见 `INTEGRATION_PLAN.md`。

## 构建

**重要约束: 本项目构建任务均使用 GitHub Actions，不允许在本地尝试构建。**

### CI/CD 工作流

- **Debug 构建**: `.github/workflows/build-debug.yml`
  - 推送到 `main`、`develop`、`feat/*`、`fix/*`、`repair/*` 分支触发
  - 也可通过手动 workflow_dispatch 触发
  - 构建产物: 4 个 ABI 的独立 APK

- **Release 构建**: `.github/workflows/release.yml`
  - 推送 `v*` tag 触发 (如 `v1.0.0`)
  - 自动签名并创建 GitHub Release

## 配置导出/导入 URI

支持 `gcm://` 和 `ech://`（兼容）导入导出：

```
gcm://<workerHost>?ip=<优选中转IP:端口>&fip=<出口代理IP>&user_id=<用户ID>&dns=<DoH服务器>&domain=<ECH查询域名>&disable_ech=1#<配置名称>
```

## 开发注意事项

- Go module 名为 `xclient`，gomobile 生成的 AAR 类名前缀为 `xclient.Xclient`
- Android Java 包名 `com.x.client.app`，NDK PKGNAME `com/x/client/app`
- hev-socks5-tunnel 子模块在 CI 阶段 clone 到 `app/src/main/jni`
- 支持 4 种 ABI: armeabi-v7a, arm64-v8a, x86, x86_64
- 集成计划详见 `INTEGRATION_PLAN.md`
