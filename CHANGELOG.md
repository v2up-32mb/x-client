# CHANGELOG — X Client (Android)

记录 `x-client`（Android 多协议 VPN 客户端，含 `golib/` Go 核心绑定）的版本变更。
版本历史较长，CHANGELOG 从 v1.3.x 起按阶段记录；更早版本见 git tags。

> 发版铁律见 `AGENTS.md`：任何 tag / Release 发布必须先经人工批准。

---

## Unreleased (main)

- **Added**：新增 `socks5` 核心代理协议支持：`golib/socks5`（RFC 1928 / RFC 1929 TCP 上游中继、可选用户名/密码认证与活跃连接追踪）、Android `Socks5ConfigBinder` 配置表单、`socks5://` / `socks://` URI 导入导出、优选 IP 体系隔离、非空密码日志脱敏与四国语言字符串资源。

---

## v1.7.1 — 2026-11-25

- **Changed**：移除冗余 `trial` 构建变体，统一 `release.yml` 手动 `workflow_dispatch` CI 构建（`com.x.client.app.debug`）。
- **Changed**：优化优选 IP 探测并发流水线并放宽 HTTPS 偶发失败判定。

---

## v1.7.0 — 2026-11-25

- **Added**：实现应用内版本检测与下载更新（`UpdateManager`，支持 GitHub Releases 检测、按 ABI 匹配下载、VPN 路由联动与旧安装包清理）；设置页 GitHub 仓库链接支持调用系统浏览器打开及无浏览器降级。
- **Added**：补充 `Apache 2.0` / `MIT` 双重开源许可证（`LICENSE`）。
- **Changed**：`golib` 升级至 Go 1.27 并更新上游依赖，编译期动态注入 Go 版本（`BuildConfig.GO_VERSION`）到关于信息。
- **Fixed**：重构分应用代理选择页（`AppListActivity`），声明 `<queries>` 包可见性并支持多策略应用枚举，彻底修复列表为空、状态不同步与权限标志异常问题。

---

## v1.6.0 ~ v1.6.3 — 2026-11-23

- **Changed**：Material 3 全页面质感重构（设置页、优选页、运行日志页、扫码/分享页，以及节点编辑页解耦 `ProtocolConfigBinder`）。
- **Added**：适配手机横屏多列网格、异形全面屏占满与自适应安全边距；支持亮暗模式调色板独立存储。
- **Removed**：彻底移除 Profile 列表滑动菜单，统一改为次级弹出菜单。
- **Fixed**：强制 `apksigner` 写入 v1 签名并修正校验参数，修复国产系统与 Android TV 安装兼容性。

---

## v1.5.0 ~ v1.5.3 — 2026-11-19 ~ 2026-11-20

- **Added**：全局优选 IP 正式上线（官方 CIDR / 第三方列表 / 手动导入多渠道来源、真实目标验证、内存测速账本与自适应接管）。
- **Added**：支持 English / 简体中文 / 繁體中文 / Русский 四种语言，并在设置页提供应用内即时切换。
- **Added**：全面适配 Android TV 与遥控器操作（解绑触摸与相机依赖、新增 TV Banner、支持 OK 键一键启停、长按菜单与全流程焦点导航）。

---

## v1.4.0 ~ v1.4.6 — 2026-10-18 ~ 2026-10-21

- **Added**：新增 VPN 代理延迟实时测试与常驻通知展示（折叠态可见，正文显示当前 `Profile` 名称）；新增代理绕过例外列表（`force_proxy_rules`）与延迟测试强制走代理功能。
- **Fixed**：`xtunnel` 升级至 `v0.3.4`、`xshared` 升级至 `v0.1.5`（传入 120s 空闲超时），修复并发探测连接泄漏与测速任务异常。

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