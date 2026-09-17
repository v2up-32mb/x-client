# LIB_VERSIONS — 共享库版本矩阵

> 上游追踪守护表：记录 x-client 及各 CLI 仓引用的共享库版本。
> **规则**：库间依赖一律用 tag 版本引用（无 replace/submodule/vendoring）；
> 升级任一库时同步更新所有消费方与本表；`x-client/golib/go.mod` 是 AAR 构建的事实源。

## 当前版本（2026-09-17）

| 库 | 版本 | commit | 说明 |
|---|---|---|---|
| `github.com/v2up-32mb/xshared` | **v0.1.0** | `6cd4e97` | SOCKS5/HTTP 代理服务器 + config/dns/ech/logger/routing（含 IPv6 目标构造修复） |
| `github.com/v2up-32mb/gcm` | **v0.1.0** | `c6781b1` | GCM 协议库（protocol/pool/relay + StreamDialer 适配器 + IPv6 回归） |
| `github.com/v2up-32mb/xtunnel` | **v0.1.0** | `c67594b` | X-Tunnel 协议库（Client/ProxyDialer + Reconnect 下沉） |

## 消费方矩阵

| 消费方 | 引用的库 | go.mod 位置 |
|---|---|---|
| **x-client**（Android AAR） | xshared v0.1.0 · gcm v0.1.0 · xtunnel v0.1.0 | `golib/go.mod` |
| **gcm-cli** | xshared v0.1.0 · gcm v0.1.0 | `go.mod` |
| **xtunnel-cli** | xshared v0.1.0 · xtunnel v0.1.0 | `go.mod` |

## 库间依赖

```
xshared（无内部依赖）
  ↑
  ├── gcm     （config/dns/ech/logger/routing/socks5）
  └── xtunnel （ech_bridge: ech/dns/config + dialer 接口）
```

## 发布物对照

| 仓库 | tag | 发布物 |
|---|---|---|
| xshared | v0.1.0 | Go 库（被引用；无二进制发布） |
| gcm | v0.1.0 | Go 库 + **Worker 服务端**（`worker/worker.js` + `DEPLOY.md`，随 release） |
| xtunnel | v0.1.0 | Go 库（被引用） |
| gcm-cli | v1.0.0 | `gcm` 二进制 ×5 平台 |
| xtunnel-cli | v1.3.0 | `x-tunnel-client`/`x-tunnel-server` ×5 平台 |
| x-client | v1.3.0 | 签名 APK ×5（`release.yml` tag 触发） |

## 升级流程

1. 上游/库更新 → 库仓 `main` 推进 + CI 绿 → 打新 tag（语义化：破坏性 ↑ major，功能 ↑ minor，修复 ↑ patch）
2. 各消费方 `go mod edit -require=<lib>@<newtag>` + `go mod tidy` + `go test ./... -race` 绿
3. 更新本表；x-client 推送后 AAR 缓存自动失效重编（`build-aar.yml` 键含 golib 内容哈希）
4. CLI 仓发版走各自 `release.yml`（tag 触发；旧 tag 可 dispatch 补发）

## 注意事项

- **paramsJSON 键兼容**：`x-client/golib/{gcm,xtunnel}/backend.go` 的 `Param*` 常量与
  Android 侧 `TProxyService.buildGCMParams/buildXtunnelParams` 逐键对齐——升级库时若库侧
  参数面变化，先核对键集（终审曾发现库侧 IPv6 阻断缺陷，键集差集审计必须重跑）
- xshared 的 SOCKS5 服务器是行为基线：UDP ASSOCIATE（`WithBlockedPorts`，默认 `[443]`）、
  软等待 100ms（`WithMaxConns`）、IPv6 目标双侧剥括号（B1 修复）
