# CHANGELOG — X Client (Android)

记录 `x-client`（Android 多协议 VPN 客户端，含 `golib/` Go 核心绑定）的版本变更。
版本历史较长，CHANGELOG 从 v1.3.x 起按阶段记录；更早版本见 git tags。

> 发版铁律见 `AGENTS.md`：任何 tag / Release 发布必须先经人工批准。

---

## v1.3.x — 2026-09-26（当前阶段）

**Build / Integrate**

- **依赖 bump**：`golib` 的 `xtunnel v0.2.0 → v0.3.1`（统一核心库 + B 方案
  `MsgHotPairBegin` + 结构化领域事件）。零代码适配（`Client/NewClient/Config/DefaultConfig`
  签名不变，Config 字段齐全）。
- **日志钩子补齐**：golib 此前未注入 `xtunnel.SetLogf`（核心库运行静默、结构化事件无人消费）。
  新增 `golib/xtunnel/log_hook.go`：注入钩子，等级映射 `xshared/logger`，域事件
  （conn/hotpair/pool）渲染结构化摘要。
- **scope 统一**：核心库日志复用既有 `sysLog`（scope=`XTunnel`），与 xtunnel 后端既有日志、
  GCM（`System`）、Android 层（`AndroidVPN`）共享同一 runtimeLogs 缓冲；Android UI
  (`Xclient.getRuntimeLogs()`) 显示无需改动。
- **`golib/xtunnel/backend.go`**：`ParseSocks5Auth/AuthEqual` 改调 `xshared/socks5`（对齐
  xtunnel 纯协议收敛），xshared 升 `v0.1.1`。

**升级 / 验证**

- `go build/vet/test`（`xclient` / `xclient/gcm` / `xclient/xtunnel`）全绿。
- AAR 产物由 CI / Android 侧构建（本仓不 commit AAR）。

---

## v1.3.0（里程碑）

- 多协议架构（GCM + x-tunnel）、Profile 管理、路由绕过、全局设置扩展
  （基于 gcm-client 改造；其余历史变更见 git tags，本文件从该版本起记录）。

---

## 说明

- 完整历史：`git log` / git tags（`v1.1.0` … `v1.3.x`）。
- 依赖铁律：`golib/go.mod` 只 pin 正式 tag（gcm / xtunnel / xshared），不用 pseudo/replace。