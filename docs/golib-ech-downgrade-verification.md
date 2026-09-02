# GCM ECH 降级 bug 验证报告（main 分支 / Go 1.25.5 实测）

- **日期**：2026-09-02
- **分支**：`main`（0242f48，v1.1.9）
- **Go 版本**：go1.25.5（与 `golib/go.mod` 的 `go 1.25.5` 一致，`/opt/tools/go`）
- **来源**：`feat/java-native-rewrite` 研究分支 `research/java-native/08-risk-register.md` 风险项 X3（"GCM ECH 降级靠错误串匹配，Go 1.25.5 实际错误串对不上"）要求切回 main 复核。

## TL;DR（结论）

| # | 论断 | 结果 |
|---|------|------|
| 1 | GCM 的 ECH 降级逻辑（失败计数→3 次→禁用 ECH 5 分钟）在 Go 1.25.5 下**永远不会被真实的 ECH 拒绝错误触发** | ✅ **证实，bug 真实存在** |
| 2 | xtunnel 侧的 ECH 错误重试（`dialer.go:95`）不受影响 | ✅ 证实（匹配大写 `"ECH"` 可命中） |
| 3 | （新发现）Go 在 ECH 拒绝路径会**无条件校验服务器证书**（绕过 `InsecureSkipVerify`），校验对象是 ECH 配置的 `public_name` | ✅ 证实（1.24/1.25 行为一致），见 §5 |

**实际后果**：GCM Profile 开启 ECH 后，若对端（CDN 边缘）不接受 ECH，则每次拨号都失败于
`tls: server rejected ECH`，而该错误串**不命中 GCM 的任何匹配条件**，`echFailureCount` 永不增长、
`echDisabledUntil` 永不设置（全代码库仅 `connection.go:499` 一处赋值，不可达）→ Profile **永久不可用**，
直到用户手动关闭 ECH 或更换 ECH 域名。设计的"3 连败后明文 TLS 兜底 5 分钟"自愈路径实际从未生效。

## 1. 验证环境与方法

- 真实 ECH 配置：DoH 查询 `cloudflare-ech.com` HTTPS(65) 记录取得 71 字节 ECHConfigList
  （config_id=0x4b, kem=X25519Mlipike, public_name=`cloudflare-ech.com`）。
- 本地 TLS 1.3 服务器（127.0.0.1，**不支持 ECH**，证书对 `public_name` 有效，客户端用自签根 `RootCAs` 校验）——
  等价于生产场景中"边缘节点未配置/不接受 ECH"的最常见情形。
- 三条拨号路径：① 裸 `tls.Dial`；② `InsecureSkipVerify=true` 的 `tls.Dial`；③ `gorilla/websocket.Dialer`
  （v1.5.3，与 GCM `connection.go:588` 完全相同的用法）；外加模拟 relay `NetDial` 的 OpError 前缀包装。
- 匹配判定直接复制 main 分支源码的条件（逐字节一致）。
- 复现程序与完整输出见附录。

## 2. 实测结果（Go 1.25.5）

ECH 客户端 → 无 ECH 服务器，三条路径的错误**完全一致**：

```
错误类型: *tls.ECHRejectionError
错误串  : "tls: server rejected ECH"      （relay 包装后: "dial tcp 1.2.3.4:443: tls: server rejected ECH"）
```

用 main 分支真实匹配条件逐条判定：

```
GCM (connection.go L486-488):
  strings.Contains(errStr, "ech")                    → false  （"rejected ECH" 无小写 "ech"）
  strings.Contains(errStr, "encrypted_client_hello") → false
  strings.Contains(errStr, "tls: handshake failure") → false
  ⇒ 三个条件全部未命中，echFailureCount 不增长，降级永不启用 ✘

xtunnel (dialer.go L95):
  strings.Contains(err.Error(), "ECH")               → true
  ⇒ 命中，Refresh(ECHDomain) + 1s 重试逻辑正常触发 ✔
```

## 3. 源码证据

**Go 1.25.5 标准库**（`/opt/tools/go/src/crypto/tls/`）：

- `ech.go:510-512`：`func (e *ECHRejectionError) Error() string { return "tls: server rejected ECH" }`
- `handshake_client_tls13.go:153-156`：拒绝判定（`echRejected`）后**先走完 outer 握手**，
  `sendAlert(alertECHRequired)` 再返回 `&ECHRejectionError{retryConfigs}`。
- 交叉核对 Go 1.24 release 分支源码：`ECHRejectionError.Error()` 同为 `"tls: server rejected ECH"`，
  即该错误串自 ECH 客户端引入（Go 1.24.0）起从未变化——GCM 的匹配串从一开始就对不上。

**x-client main 分支**：

- `golib/gcm/pool/connection.go:484-488`（`handleDialError`）：
  ```go
  if strings.Contains(errStr, "ech") ||
      strings.Contains(errStr, "encrypted_client_hello") ||
      strings.Contains(errStr, "tls: handshake failure") {
  ```
  三条件均大小写敏感。`"tls: server rejected ECH"` 逐一比对均不命中。
- `golib/xtunnel/dialer.go:95`：
  ```go
  if p.config.EnableECH && (strings.Contains(err.Error(), "ECH") || strings.Contains(err.Error(), "ech")) && i < maxRetries {
  ```
  大写 `"ECH"` 直接命中。
- `golib/gcm/backend.go:249`：GCM `EnableECH` 默认 **false**（仅用户显式开启 ECH 的 Profile 受影响）；
  `golib/xtunnel/config.go:94`：xtunnel `EnableECH` 默认 **true**（其匹配逻辑正确，风险项不成立）。
- 全代码库检索：`echDisabledUntil` 仅 `connection.go:499` 一处赋值；无任何其他路径能关闭 GCM 的 ECH。

## 4. 影响面分析

- **触发条件**（三者同时满足）：① GCM Profile 开启 ECH；② 客户端成功取到 ECH 配置
  （`GetTlsConfig` 失败会走另一条路——直接返回普通 TLS 配置，不受此 bug 影响）；③ 服务器对 ECH
  "拒绝"——注意 Go 的判定是 accept_confirmation 不匹配即算拒绝，**包括最常见的"服务器完全不支持 ECH"**。
- **失败模式**：`handleDialError` 落进"3. 其他错误，仅记录日志"→ 拨号循环不断重试，日志持续
  `拨号失败，等待重试: tls: server rejected ECH`，连接池 0 可用连接。无自愈、无降级、无告警区分。
- **GCM 三个匹配条件各自的实际语义**：
  - `"ech"`（小写）：真实 ECH 拒绝错误不命中。只会意外命中**错误串里恰好含 "ech" 的其他错误**——
    例如 §5 的证书校验失败且 `public_name` 含 "ech"（如 `cloudflare-ech.com`）时。
  - `"encrypted_client_hello"`：Go 1.25.5 客户端路径不会产出含该串的错误（服务端侧 malformed 提示
    `tls: malformed encrypted_client_hello extension` 是服务端错误，客户端收不到）。实际是死条件。
  - `"tls: handshake failure"`：仅在服务器**主动发送 TLS alert 中断握手**时出现（如 TLS 版本协商失败、
    中间设备干扰），与 ECH 拒绝是不同失败模式。

## 5. 新发现：ECH 拒绝路径的证书校验语义（1.24/1.25 一致）

`handshake_client.go:1130-1160`（1.24 与 1.25.5 相同逻辑）：

```go
echRejected := c.config.EncryptedClientHelloConfigList != nil && !c.echAccepted
if echRejected {
    // 无条件走完整证书校验，DNSName = c.serverName（= ECH 配置 public_name）
    // —— InsecureSkipVerify 在此分支被绕过（实测确认）；
    //    除非设置了 EncryptedClientHelloRejectionVerify（仓库未使用）
} else if !c.config.InsecureSkipVerify {
    ...
}
```

含义：

1. **`public_name` 必须被服务器证书覆盖**是硬性要求。若 ECH 配置域名与 Worker 边缘证书不匹配，
   客户端根本走不到 ECH 拒绝判定，而是先失败于
   `*tls.CertificateVerificationError`（`tls: failed to verify certificate: x509: certificate is valid for …, not <public_name>`）。
   该错误串是否命中 GCM 的 `"ech"` 条件，取决于 `public_name` 字面是否含小写 "ech"——纯靠运气。
2. 生产上（Cloudflare 边缘、证书覆盖 public_name）则按预期得到 `ECHRejectionError`，回到 §2 的 bug 路径。
3. 对 Android 端无额外风险（系统根 CA 校验 Cloudflare 证书正常），但**配置约束**应写进文档：
   ECH 域名的 public_name 必须与 Worker 边缘证书覆盖范围一致。

## 6. 修复建议（如需在 Go 侧修，属一行级改动）

不要用字符串匹配，改用错误类型判定（`golib/gcm/pool/connection.go` `handleDialError`）：

```go
var rejErr *tls.ECHRejectionError
if p.cfg.EnableECH && p.echManager != nil && errors.As(err, &rejErr) {
    // 原有计数/刷新/降级逻辑
}
```

保留原字符串条件作为兜底亦可（`errors.As` 优先）。xtunnel 侧建议同样改为 `errors.As`，
避免未来 Go 调整错误串时再次漂移。是否修复由用户决定（本报告仅为验证，未改动任何产品代码）。

## 附录：复现程序输出（go1.25.5 linux/arm64）

```
=== 步骤 1: 获取真实 ECHConfigList (cloudflare-ech.com) ===
  [doh] 从 https://dns.google/resolve?name=cloudflare-ech.com&type=HTTPS 取到 ECHConfigList (71 字节)
  首个 ECHConfig 的 public_name (outer SNI) = cloudflare-ech.com

=== 步骤 2: 启动本地 TLS 1.3 服务器（不支持 ECH，证书对 public_name 有效）===
  服务器监听 127.0.0.1:8671

=== 步骤 3: ECH 客户端 → 无 ECH 服务器（Go 1.25.5）===
--- 3a. 裸 tls.Dial，正常证书校验（RootCAs）---
  [tls.Dial] 错误类型: *tls.ECHRejectionError
  [tls.Dial] 错误串  : "tls: server rejected ECH"

--- 3b. 裸 tls.Dial，InsecureSkipVerify=true（看是否被 ECH 拒绝路径绕过）---
  [tls.Dial skipVerify] 错误类型: *tls.ECHRejectionError
  [tls.Dial skipVerify] 错误串  : "tls: server rejected ECH"

--- 3c. gorilla/websocket.Dialer（GCM 实际拨号路径）---
  [websocket.Dial] 错误类型: *tls.ECHRejectionError
  [websocket.Dial] 错误串  : "tls: server rejected ECH"

=== 步骤 4: 用 GCM / xtunnel 的匹配条件逐一测试 ===
  [3a tls.Dial] ✘ GCM 三个条件全部未命中 (含"ech":false, 含"encrypted_client_hello":false, 含"tls: handshake failure":false)
  [3a tls.Dial] ✔ xtunnel 命中 (含"ECH":true, 含"ech":false)

  [3b tls.Dial skipVerify] ✘ GCM 三个条件全部未命中 (含"ech":false, 含"encrypted_client_hello":false, 含"tls: handshake failure":false)
  [3b tls.Dial skipVerify] ✔ xtunnel 命中 (含"ECH":true, 含"ech":false)

  [3c websocket.Dial] ✘ GCM 三个条件全部未命中 (含"ech":false, 含"encrypted_client_hello":false, 含"tls: handshake failure":false)
  [3c websocket.Dial] ✔ xtunnel 命中 (含"ECH":true, 含"ech":false)

--- 附加: 模拟 relay NetDial 包装后的错误串 ---
  [wrapped] 错误串  : "dial tcp 1.2.3.4:443: tls: server rejected ECH"
  [wrapped] ✘ GCM 三个条件全部未命中 (含"ech":false, 含"encrypted_client_hello":false, 含"tls: handshake failure":false)
  [wrapped] ✔ xtunnel 命中 (含"ECH":true, 含"ech":false)
```

复现程序：`golib/echverify/main.go`（验证完成后已删除，如需复现可按下述要点重写：
DoH 取 ECHConfigList → 本地 TLS1.3 无 ECH 服务器（证书覆盖 public_name）→ 三路径拨号 → 复制 main 分支匹配条件判定）。
