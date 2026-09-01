# 04. ECH + DoH + DNS 的 Java 化可行性调研报告

> 调研子代理：ech-java-feasibility（Phase 1）
> 范围：确定 ECH / DoH / DNS 功能在 Java 17 + Android minSdk 24 下的技术路线；本报告只调研，不写实现代码。
> 日期：2026-09（按仓库 working copy 时间）

---

## 0. 结论摘要（先给结论）

**总判断：ECH + DoH + DNS 是本次全量 Java 化迁移中技术风险最大的部分，但必须拆成两个能力面看——DoH/DNS/SVCB/缓存/预热完全无风险（纯 Java + 平台 API 即可，约 800–1400 LOC）；ECH 才是真正的风险面，且有三级可选路线。**

关键结论（均有下文证据）：

1. **Go 的 ECH 失败语义不是"静默回退明文"，而是"报错 + 应用层重试 + 连败降级"**。`tls.Config.EncryptedClientHelloConfigList` 一旦设置，服务器拒绝/不支持 ECH 时 Go 会走完 outer ClientHello 的握手流程然后返回 `ECHRejectionError`（`Error() == "tls: server rejected ECH"`）。x-client 的 Go 代码正是依赖"错误字符串匹配 → 刷新 ECH 配置 → 重试 → 连续 3 次失败 → 禁用 ECH 5 分钟"这套应用层逻辑实现最终明文可用（见 §1.3、§1.5）。**Java 复刻必须复刻这套错误-重试-降级语义，否则行为不一致。**

2. **ECHConfig 字节流格式全链路一致，这是最大的"白送红利"**：DNS HTTPS 记录（RFC 9460 type 65）的 `ech` SvcParam（key=5）原始字节 = ECHConfigList（RFC 9848），与 Go 的 `tls.Config.EncryptedClientHelloConfigList`、BoringSSL 的 `SSL_set1_ech_config_list` 是**同一种字节格式**。Java 端 DoH/UDP 查到的 ECH 配置字节可以原样喂给任何 ECH 能力方（GuardianProject Conscrypt fork 或自研栈）。

3. **Java 生态存在一个可用的 ECH TLS 库：GuardianProject 的 Conscrypt fork**（Maven Central 有构件 `info.guardianproject.conscrypt:conscrypt-android`），API 是 `Conscrypt.setEchConfigList(SSLSocket/SSLEngine, byte[])`，底层走 BoringSSL 原生 ECH，行为语义与 Go 同族（拒绝→报错→应用层重试）。**但它基于 2022 年初的旧版 Conscrypt、订阅方只有 F-Droid 系项目，上游 Conscrypt 官方至今（2.7.0，2026-08-31 发布）没有公开 ECH Java API**（native 层 PR 已合入但未暴露 Java 层；Java 层 PR #1406 仍 open）。

4. **纯 Java 自研 ECH 路线在密码学原语上"零障碍"**：BouncyCastle `bcprov-jdk18on 1.80`（已实测 jar 内容）自带 RFC 9180 HPKE 完整实现（`org.bouncycastle.crypto.hpke.*`）、X25519（`X25519Agreement` 等）、HKDF（`HKDFBytesGenerator`）、AES-GCM（JCA 或 `GCMBlockCipher`）、ChaCha20-Poly1305、GCMSIV（RFC 8452，虽然 ECH 用不到）。自研的障碍只在 **TLS 1.3 客户端栈本身**（约 3000–5000 LOC）与 **ECH 双层 ClientHello/accept_confirmation 逻辑**（约 800–1500 LOC），规格以 RFC 9849 + RFC 9180 为准，与 Go 实现逐条对得上。

5. **DoH/JSON → RFC8484 双格式、UDP 回退、内置 3 服务器轮转、DNS 缓存 5min TTL、预热 40 域名，全部是"可直抄"的纯 Java 逻辑**，唯一手写部分是 DNS wire 报文编解码与 SVCB/HTTPS 记录解析（约 450–700 LOC）。

6. **分阶段建议**：第一阶段（不含 ECH）可 100% 交付且对服务端完全兼容（服务端只在收到 ECH 扩展时才进入 ECH 流程，客户端不发 = 普通 TLS 1.3）；第二阶段视拍板选择路线 A（自研 TLS13+ECH，风险 H，2–3 人月）、路线 B（Conscrypt fork 库，风险 M，集成工作量极小但依赖陈旧）或路线 E（fork BouncyCastle bctls 加 ECH 补丁，风险 M+）。

7. **工具说明**：本环境中 `web_search` / `fetch_content` 工具不在可用函数列表内，已改用 `curl` 直连 GitHub API / Maven Central / rfc-editor / chromestatus 等 HTTPS 源完成调研，所有网络结论均附下述 URL；后续可复查。

---

## 1. 要复刻的 Go 行为清单（复刻对象 + 证据）

### 1.1 `golib/shared/ech/manager.go` —— ECH 配置管理器（303 行）

| 行为 | 证据 |
|---|---|
| UDP 回退服务器硬编码 `8.8.8.8:53` | L14 `const defaultUDPDNSServer = "8.8.8.8:53"` |
| 缓存 TTL 默认 24h、定时刷新默认 12h | L50-60 `NewEchManager`：`if cacheTTL == 0 { cacheTTL = 24 * time.Hour }`、`refreshInterval` 同 |
| 查询链路"DoH（内部多服务器 fallback）→ UDP DNS 回退" | L79-83 dohFunc/udpFunc 装配：先 `dohClient.GetECHConfig(domain)`，失败再 `ResolveHTTPSUDP(domain, defaultUDPDNSServer)` |
| `GetTlsConfig(domain, useEch)`：**ECH 配置获取失败 → 返回纯标准 TLS 配置（不报错）** | L90-117：min `VersionTLS13`、`ServerName = domain`（调用方传入 WorkerHost）；`getECHConfig` 出错仅 Warn 日志并返回 `tlsConfig`（明文回退） |
| 缓存 + singleflight | L118-153 `getECHConfig`：`em.cache[cacheKey]`（cacheKey = `echDomain`）；`inflight map[string]*inflightFetch`（`done chan`）实现同域名并发查配置只打一次网络 |
| 强制刷新 / 清缓存 / 统计 / 过期清理 | L155-182 `Refresh`（先删缓存再查）、L201-210 `ClearCache`、L211-227 `GetCacheStats`、L228-249 `CleanupExpired` |
| 定时自动刷新后台循环 | L251-272 `StartAutoRefresh` + `autoRefreshLoop`（ticker 12h，`stopChan` 停止）；L274-286 `refreshAllCached` |
| 冷启动预注入配置（避免循环依赖） | L293-303 `CacheConfig(domain, echConfig)` 直接写缓存 |

### 1.2 `golib/shared/dns/doh.go` —— DoH 客户端 + HTTPS 记录解析（757 行）

**内置服务器列表（逐条）**，L41-46：

```go
var DefaultDoHServers = []string{
    "https://v.recipes/dns-query",
    "https://doh.090227.xyz/CMLiussss",
    "https://doh.pub/dns-query",
}
```

- 用户配置 `DoHUrl` 非空则只用该服务器（L83-89 `NewDoHClient`）；`EnableDoH` 关闭时 `Resolve` 直接报错（L109-127 开始的 `Resolve`）。
- **服务器轮转策略**：`lastServerIdx int32` 记录上次成功的服务器下标（原子），每次从该下标起循环尝试（L109-144）；**每个服务器独立超时**（`context.WithTimeout(ctx, d.client.Timeout)`），避免一个慢服务器耗尽总预算。
- **每服务器重试**：`resolveWithServer` maxRetries=2，错误串含 `"TLS"/"connection"/"EOF"` 才重试，否则换下一服务器（L145-183）。
- **双格式**：`resolveAttempt` 优先 RFC 8484（POST `application/dns-message`，DNS wire 查询，L200-320 `resolveRFC8484`）；失败且非 ctx 取消则回退 JSON API（L465-556 `resolveJSON`，GET，`Accept: application/dns-json`，q 参数 `name`/`type`；**Google DoH 特判**：host 含 `dns.google`/`google.com` 且 path 为 `/dns-query` 时换 `/resolve`；响应结构 `DoHResponse{Answer: []DoHAnswerEntry{Name,Type,Data}}` 兼容 object/array）。
- **HTTPS 记录（type 65）在 RFC8484 中按 UnknownResource 取原始字节 → 转 `"\# <len> <hex>"` RFC 3597 格式文本**（L319-331），再交给 `parseHTTPSRecordWire`。
- **UDP DNS 回退**：`ResolveHTTPSUDP(domain, "8.8.8.8:53")`（L342-396）：`net.DialTimeout("udp", dnsServer, 5s)`，5s deadline，4096 字节缓冲，构造 HTTPS(65) 查询，解析响应取第一条 type 65 的 `UnknownResource` 原始字节 → `parseHTTPSRecordWire` → `record.ECH`。**注意：UDP 路径只取 ECH 配置字节，不返回完整 HTTPSRecord。**
- `GetECHConfig(domain)`（L450-464）= `ResolveHTTPS` → `parseHTTPSRecord` → 取 `record.ECH`，空则报错。
- **`parseHTTPSRecordWire`（RFC 3597 wire 解析，L646-709）**：`priority(2B BE) + target(域名 label 序列, parseDomainName L710-733) + 若干参数`，参数 = `key(2B BE) + valLen(2B BE) + val`；**`case 5: ech` → `ech = val`（原始字节拷贝，即 ECHConfigList）；`case 1: alpn` → hex 存 `alpn_hex`；其余 → `key%d` hex**（L690-699）。文本格式解析 `parseHTTPSRecord`（L592-645）把 `ech=` 值做 `base64.StdEncoding`→`RawStdEncoding` 两级解码失败才放弃。
- 逐条已核对：**SVCB key=5 是 ech，key=1 是 alpn，与 RFC 9460/9848 一致**。

### 1.3 ECH 注入 TLS 握手（`golib/gcm/pool/connection.go` + `golib/xtunnel/dialer.go`）

**GCM（connection.go）**：
- `getTLSConfig()`（L451-475）：
  - L454 `useECH := p.cfg.EnableECH`；L454 `time.Now().Before(p.echDisabledUntil)` 时强制 `useECH=false`（降级窗口内走明文）；
  - 调 `p.echManager.GetTlsConfig(p.cfg.WorkerHost, useECH)`；`echManager == nil` 或出错时返回 `&tls.Config{MinVersion: tls.VersionTLS13, ServerName: p.cfg.WorkerHost}`。
- 三个拨号点（L585-605、L726-744、L888-905）全部走 `websocket.Dialer{TLSClientConfig: getTLSConfig(), …}`；URL 为 `wss://<WorkerHost>/<UserID>?fallbackip=<ProxyIP>`（L434-441 `buildWSSURL`）；HTTP Headers 带 `Host`、`User-Agent`（Chrome 109/Edg 109 伪装，L580-583）。**gorilla/websocket 的 Dialer 不设置 NextProtos，因此 Go 侧不发送 ALPN 扩展**（本项目没有 ALPN 语义，Java 侧无需对齐，但可以给 `http/1.1` 无害）。
- `handleDialError`（L477-528）——**ECH 失败降级语义**：
  - 错误串含 `"ech"` / `"encrypted_client_hello"` / `"tls: handshake failure"` → `echFailureCount++`；
  - 连续失败 ≥ 3 → `echFallbackEnabled = true`、`echDisabledUntil = now + 5min`（L505-512）；
  - 未达阈值 → 异步 `echManager.Refresh(p.cfg.ECHDomain)` 刷配置（L500-508）；
  - 成功建连 → 计数清零（L601-604）。
- 结构字段 L258-260：`echFailureCount int32 / echDisabledUntil time.Time / echFallbackEnabled bool`。

**X-Tunnel（dialer.go，110 行）**：
- `dialWebSocket`（L33-109）：`serverName := u.Hostname()`（**WS host 做 SNI**，不是 echDomain）；`tlsCfg, err := p.echManager.GetTlsConfig(serverName, p.config.EnableECH)`；
  - ECH 使能时若取配置失败/握手失败，`Refresh(p.config.ECHDomain)` 后延时 1s 重试，最多 3 次（L44-73）；
  - `tlsCfg.InsecureSkipVerify = p.config.InsecureSkipVerify`（**仅 xtunnel 有该开关**，L62）；
  - Token → `dialer.Subprotocols = []string{Token}`（WebSocket 子协议做认证，L66-68）；
  - relay 节点用 `Dialer.NetDial` 替换目标地址（L70-80）。

**要点：SNI（= WorkerHost/服务器 host）与 ECH 查询域名（= echDomain，如 `cloudflare-ech.com`）是两个独立字段**；xtunnel 默认 `EnableECH=true, ECHDomain="cloudflare-ech.com"`（`golib/xtunnel/config.go` L94-96），shared config 默认 `EnableECH=false`（`golib/shared/config/config.go` L467）。

### 1.4 `golib/shared/dns/cache.go` + `warmup_list.go`

- `DNSCache`：`map[string]*CacheEntry`，**key = `domain:type`**（L61-63）；`CacheEntry{IP, HTTPSRecord, ExpiresAt}`（L24-33）；TTL 默认 5min、cleanupInterval 默认 1min（config.go L460-461 周边）；后台 `cleanupLoop` 每 1min 清理过期（L261-304）。
- `ResolveCached`（L109-127）：缓存命中直接返回；miss → DoH `Resolve` → `Set` 写缓存。
- `SetHTTPS/GetHTTPS`（L138-183）把 HTTPSRecord 也缓存；`GetECHConfig`（L204-226）从缓存的 HTTPSRecord 取 ECH 字节。
- `Warmup`（L327-384）：合并 `DefaultWarmupDomains`（约 40 个域名：Google/YouTube/Twitter/Facebook/GitHub/Wikipedia/Reddit/OpenAI/Cloudflare/Netflix/Spotify/AWS/Microsoft/Apple/Telegram 等，warmup_list.go L3-69）与自定义列表；**总超时 15s，并发 8（sem 信号量），每个域名查 A + AAAA**。
- 另有 `ResolveAny`（L227-260）、`LookupIPs`（L423-451）等辅助。

### 1.5 Go crypto/tls 的 ECH 语义 —— **兼容基准线（重要修正）**

调研 Go master 源码（golang/go）确认：

- API：`tls.Config.EncryptedClientHelloConfigList []byte`（serialized ECHConfigList），`src/crypto/tls/common.go` L841-861。文档明言：*"If EncryptedClientHelloConfigList is set, MinVersion, if set, must be VersionTLS13"*、*"the handshake will only succeed if ECH is successfully negotiated. If the server rejects ECH, an ECHRejectionError error will be returned"*。
- 构造：`makeClientHello`（`handshake_client.go` L174-207）：`parseECHConfigList` → `pickECHConfig`（取第一个有效配置）→ `hpke.NewSender(echPK, kdf, aead, info)`，**`info = "tls ech\x00" + config.raw`**；outer CH SNI = ECHConfig 的 `public_name`；inner CH = 真实 hello（含真实 SNI），`legacy_session_id` 等 TLS1.2 字段置 nil；ECH 扩展 `hello.encryptedClientHello = {1}` 标识 inner。
- 判定：`handshake_client_tls13.go` L88-115：客户端计算
  `accept_confirmation = HKDF-Expand-Label(HKDF-Extract(0, innerHello.random), "ech accept confirmation", transcript(innerCH ‖ 修改后 ServerHello), 8)`，
  与 ServerHello.random 末 8 字节匹配 → 接受（此后用 inner CH 转录哈希、`c.serverName = config.ServerName` 做证书校验）；不匹配 → `echRejected = true`。
- 拒绝的结果：**仍把 outer CH 当真实 hello 走完整个握手（含证书校验、client Finished），最后** `sendAlert(alertECHRequired)` **并返回 `&ECHRejectionError{retryConfigs}`**（L153-157）。`ECHRejectionError.Error() == "tls: server rejected ECH"`（`ech.go` L488-494）；HRR 场景用 `"hrr ech accept confirmation"` 标签（hs13.go L274）。
- **由此修正一个常见误判：Go 的 ECH 失败不会"悄悄退回明文连接"**；x-client 的"明文可用"靠的是 §1.3 的应用层错误匹配 + 重试 + 3 连败降级 5min。Java 复刻必须同样提供"错误类型/错误串 → Refresh → 降级窗口"机制。另外注意项目错误串匹配有大小写差异（GCM 匹配 `"ech"` 小写与 `"encrypted_client_hello"`，xtunnel 匹配 `"ECH"` 大写；Go 现版本错误串是 `"tls: server rejected ECH"`，只有 xtunnel 的 `"ECH"` 能直接命中，GCM 大概率落在 `"tls: handshake failure"` 之外需要核实——这属于移植时需对齐的细节，已列入 open questions）。

### 1.6 ECH 协议本体（RFC 9849，2026-03，取代 draft-ietf-tls-esni）

- ECHConfig：`{version(0xfe0d), length, contents}`；contents = `{key_config{config_id, kem_id, public_key, cipher_suites}, maximum_name_length, public_name, extensions}`（RFC 9849 §4）。
- 加密：HPKE（RFC 9180）base mode；**`info = "tls ech" || 0x00 || ECHConfig`**（RFC 9849 L663 区域）。
- **inner CH 加密用 ECHConfig.cipher_suites 里选的 HPKE AEAD（默认 AES-128-GCM, aead_id 0x0001；可选 0x0002 AES-256-GCM / 0x0003 ChaCha20-Poly1305），不是 AES-GCM-SIV**——全文检索 `GCM-SIV`/`SIV` 无任何使用场景（仅"padding/防裁剪"等上下文）。**nonce**：RFC 9180 §5.2 AEAD nonce = `base_nonce XOR seq`（第一次 seal 时 seq=0）；**AAD** = `ClientHelloOuterAAD` = outer ClientHello 的序列化（不含 4 字节 Handshake 头），其中 `encrypted_client_hello` 扩展的 payload 整体置为等长全零（RFC 9849 L600-610）；`final_payload = context.Seal(ClientHelloOuterAAD, EncodedClientHelloInner)`（L748-751）；`EncodedClientHelloInner = {ClientHelloInner(legacy_session_id 置空), zeros padding}`（§5.1，padding 策略 §6.1.3）。
- accept_confirmation = `HKDF-Expand-Label(HKDF-Extract(0, ClientHelloInner.random), "ech accept confirmation", transcript_ech_conf, 8)`；transcript_ech_conf 基于"random 末 8 字节清零后的 ServerHello"转录哈希（§7.2）。
- 服务器拒绝语义：不回 ECH 相关响应、用 outer CH 正常完成握手；客户端判定拒绝后**该连接不可用于应用数据**，发 `ech_required` alert，用服务器给的 retry_configs（HRR 的 ECH 扩展）重试（§6.1.4、§6.1.6）。
- SVCB/HTTPS 发布：RFC 9848 —— `ech` SvcParam 的 wire 值 = **ECHConfigList（含冗余长度前缀）**；与 Go 端 `parseHTTPSRecordWire` 取出的原始字节一致。

---

## 2. 生态调研（全部附来源）

> 方法论提示：此环境未提供 `web_search`/`fetch_content` 工具，以下结论均通过 `curl` 抓取 GitHub REST API、Maven Central（repo1）、rfc-editor.org、chromestatus API 等公开 HTTPS 端点获得（2026-09 抓取；各来源 URL 见 §8）。

### 2.1 BouncyCastle —— **TLS 1.3 客户端存在，但无 ECH；密码学原语全覆盖（实测 jar）**

- 构件与版本：`org.bouncycastle:bcprov-jdk18on:1.80`（2025-01-14 发布）及 `bctls-jdk18on:1.80` 在 Maven Central 可下载（已验证 HTTP 200）。
- **HPKE（RFC 9180）**：实测 `bcprov-jdk18on-1.80.jar` 内含 `org/bouncycastle/crypto/hpke/HPKE.class`、`HPKEContext.class`、`DHKEM.class`、`KEM.class`、`AEAD.class`、`HKDF.class`，API 含 `setupBaseR/setupBaseS/seal/open`；即 **BC 自带完整纯 Java HPKE 实现，无需自研**（HPKE 自 BC 1.7x 起加入，此处以 1.80 实测为准）。
- **TLS 1.3 客户端**：实测 `bctls-jdk18on-1.80.jar` 含 `org/bouncycastle/tls/TlsClientProtocol`（29 KB）、`Tls13Verifier`、JSSE provider（`org.bouncycastle.jsse.provider.ProvTlsClientProtocol`）等约 980 个类——**BC 有成熟、纯 Java、无 native 的 TLS 1.3 客户端实现**；但 grep 该 jar 无任何 ECH 类（唯一 "ech" 命中是 `JceChaCha20Poly1305` 的子串）。→ 结论：**BC 没有 ECH，也不打算很快有**。
- **X25519**：`X25519Agreement`、`X25519KeyPairGenerator`、`X25519PrivateKeyParameters/PublicKeyParameters` 均在 bcprov（lightweight API，Android 通用）；JCA 侧还有 `KeyAgreementSpi$X25519`（`META-INF/versions/11/...`，Java 11+ 模块，Android 上走 lightweight API 更稳）。
- **HKDF**：`HKDFBytesGenerator` + `HKDFParameters`。
- **AEAD**：`GCMBlockCipher`（AES-GCM）、`ChaCha20Poly1305`、**`GCMSIVBlockCipher`（RFC 8452 AES-GCM-SIV 也有，虽然 ECH 不需要）**。
- **许可**：BouncyCastle License（Apache/MIT 近似宽松许可，允许闭源商用与再分发；仓库内 LICENSE 文件可见）。Android 兼容性：jdk18on 系列 base classes 为 Java 8 字节码（multi-release jar 的 versions/ 目录在 ART 上默认被忽略、退化为 base class），minSdk 24 OK；是 BC 官方长期推荐的 Android 集成方式（详见 §8 的 BC 站点/文档链接，本次访问 bouncycastle.org 偶发 TLS 握手失败，建议 CI 阶段复验）。

### 2.2 Conscrypt —— **upstream 无公开 ECH Java API；GuardianProject fork 有（且发了 Maven 构件）**

- **upstream（google/conscrypt）状态**（GitHub API，2026-09 抓取）：
  - Issue #730 *"Encrypted ClientHello (ECH)"*（2019-10 开启，22 条评论，**至今 open**）；评论中有 davidben（BoringSSL 负责人）参与，多次强调 multi-CDN 场景下"IP 必须与 ECH 流程关联"等设计约束。
  - PR #1044（eighthave / Guardian Project，*Basic TLS Encrypted ClientHello (ECH) support*）：**open**，最近更新 2025-06-11；改动含 `Conscrypt.java +61`、`SSLParametersImpl +30`、`NativeCrypto +15`、JNI `native_crypto.cc +150` 及 BoringSSL 测试资源配置。
  - PR #1374（*native layer*）：**已合并 2025-09-29**；PR #1402、#1405 也于 2025-10 合入（JNI ECH 相关）。
  - PR #1406（*java layer*）：**open**（状态未知，未见合入）；PR #1340 亦 open。
  - 实测 upstream `main` 分支整棵树**无任何含 "ech" 的路径** → **合入的只是 native/JNI 测试层，没有暴露给应用层的 Java API**。
  - 最新 release `2.7.0`（2026-08-31 发布）——Android 构件 `org.conscrypt:conscrypt-android` 要求 **API 21+（Lollipop）**，仍无 ECH API。
- **GuardianProject fork（实测权威）**：`github.com/guardianproject/conscrypt`（fork，最后推送 2022-03-19），即 PR #1044 的载体；**Maven Central 存在 3 个发布版本**（repo1 实测目录）：
  - `info.guardianproject.conscrypt:conscrypt-android:2.6.alpha1638179154.job1828169525`
  - `...:2.6.alpha1646248177.job2155710579`
  - `...:2.6.alpha1647601986.job2220801545`
  - 其 API（从 PR #1044 diff 实测摘取）：`Conscrypt.setEchConfigList(SSLSocket, byte[])` / `(SSLEngine, byte[])` / 对应 `getEchConfigList`；native 走 `SSL_set1_ech_config_list(ssl, holder, echConfig)`（BoringSSL API）；**入参即 ECHConfigList 字节 = 与 Go 端 `EncryptedClientHelloConfigList` 完全同格式**。
  - 语义：BoringSSL 的客户端 ECH 在服务器拒绝时同样以错误结束握手（`SSL_R_ECH_REJECTED` 类），需要应用层重试/降级——与 Go 的 ECHRejectionError 语义同族，适配成本低。
  - **风险**：2022 年初的底座（旧版 Conscrypt/BoringSSL，含当时已知 TLS 漏洞修补滞后问题）；无官方维护承诺；引入第三方安全敏感组件需安全评审；与大 ABI 集（armeabi-v7a/arm64-v8a/x86/x86_64）需验证是否都有预编译 so。
- **结论（确凿）**：Conscrypt 官方**没有**可用 ECH API（含 2.7.0）；**唯一公开可用的 Conscrypt 系 ECH 是 GuardianProject fork**。Chrome for Android **不是**通过 Android 平台 Conscrypt 实现的 ECH——Chromium 自带 BoringSSL 网络栈（davidben 在 #730 评论亦证实方向），Chromium ECH 特性（chromestatus feature 6196703843581952）**desktop=117 / android=117，默认启用（2023-09 起）**。

### 2.3 Android 平台 SSLSocket：无任何 ECH 钩子

- Android 平台 TLS 由系统 Conscrypt 提供，Java 暴露面只有标准 JSSE（`SSLSocket/SSLEngine/SSLParameters`），**没有 ECH 相关字段或 extras**（Conscrypt #730 从 2019 年至今的讨论明确：ECH 需要从 DNS 取配置并注入握手的整套机制，平台从未合入；Android 16 侧亦无平台 ECH API 公开迹象，相关 search 无命中）。
- 平台能力底线（供自研路线参考）：`AES/GCM/NoPadding`（JCA）自 API 21+ 可用；`KeyAgreement "X25519"` 需要 **API 31+**（Android 12 才对外开放 XDH JCA），因此 **minSdk 24 下 X25519 必须走 BouncyCastle lightweight API**（或自实现 ~150 行）；`HKDF` JCA 也要求 API 31+，minSdk 24 用 BC `HKDFBytesGenerator` 或自实现 ~50 行；`SHA-256` JCA 原生 ✓。

### 2.4 HPKE 纯 Java 库盘点（GitHub 搜索）

- `hpke language:java` 仅 0-star 教学项目（vinodjavvadi/hpke-backend-java、BoraArseven/hpkeimplementation、hwildwood/hpke-poc 等）；`rfc9180` 检索无 Java 实现（Rust/Python/TS 才有：hpke-rs 55★、hpke-py、hpke-js 125★）。
- 值得一提：`a-sit-plus/signum`（Kotlin Multiplatform 密码库，195★，topics 含 hpke/ecdh/ed25519）提供跨平台 HPKE，但引入 KMP 依赖链较沉。
- **结论：纯 Java 生态最可靠的 HPKE 就是 BouncyCastle（无第三方库依赖的第二个选择：按 RFC 9180 自研 ~400-600 LOC，含 X25519 自实现则另 +150）。**

### 2.5 纯 Java/Kotlin 的 ECH 客户端先例：**不存在**

- GitHub 检索 `"encrypted client hello" language:java`、`ech client hello java/kotlin`：无任何成熟纯 Java/Kotlin ECH 客户端项目。命中的全部是 Go（`c2FmZQ/ech`、`salrashid123/go_ech`、`OmarTariq612/goech`、`EchOS` 等）或 C/Rust。
- v2rayNG、sing-box、Clash.Meta 均为 Go 内核（不符合本迁移的"原生 Java"前提）；Amnezia 是 C++/OpenSSL；Outline 是 C++/Go 混合；Shadowsocks-Android（纯 Java）走平台 JSSE/OkHttp，**没有 ECH 能力**（GitHub issue 检索无 ech 命中的实现）。
- **GuardianProject Conscrypt fork（§2.2）是唯一的"Java 可调用"的 ECH 实现，且其本质是绕过 Java 直接调 BoringSSL C++。** 因此"纯 Java 自研 ECH"无任何可抄的近亲，只能以 RFC 9849 + Go 源码为蓝本。

### 2.6 OpenSSL / 其他 C 侧

- **OpenSSL master 已实现 RFC 9849 ECH**（`CHANGES.md` L1227 *"Implemented [RFC 9849], adding support for Encrypted Client Hello (ECH)"*，PR 25193 等；`ssl/ech` 目录存在）。但实测 `openssl-3.5`、`openssl-3.6` 分支均无 `ssl/ech` → **ECH 尚未进入任何已发布 OpenSSL 版本**，第三方预编译 OpenSSL+AAR 均无 ECH。若走 C shim 路线，需自编 OpenSSL master 或 BoringSSL（见 §3 路线 d）。

### 2.7 OkHttp（DoH 与 WS 的载体）

- okhttp 官方 README：**"OkHttp works on Android 5.0+ (API level 21+) and Java 8+"**；最新 5.5.0（Maven Central `com.squareup.okhttp3:okhttp`）。→ minSdk 24 ✓。WebSocket 客户端内置；DoH 也可走 OkHttp（HTTP/2 + 连接池比 HttpURLConnection 更贴近 Go `http.Client` 行为，但 HttpURLConnection 零依赖也可）。

---

## 3. "如何把 ECH 注入 ClientHello"的候选路径（a–e）

### (a) 纯 Java 最小 TLS 1.3 客户端栈 + ECH（自研路线）—— 可行，风险 H

**内容**：自己实现 record 层（TLS 1.3 AEAD record 保护 AAD 规则）、握手消息编解码（ClientHello/ServerHello/HRR/EncryptedExtensions/Certificate/CertificateVerify/Finished）、key schedule（RFC 8446 §7 HKDF-Expand-Label + transcript hash）、密钥交换（X25519 + 平台/BC ECDHE secp256r1）、证书链验证（复用平台 `CertPathValidator`）、以及 ECH 增量：outer/inner 双层 CH 构造、ECH 扩展（config_id/cipher_suite/enc/密文）、accept_confirmation 判定、拒绝→报错→重试。

**估算 LOC**（对照 OpenJDK `sun.security.ssl` 与 BC `TlsClientProtocol`（单类 29KB）规模）：
- 基础 TLS 1.3 客户端（仅 1.3、单向认证、无恢复、无客户端证书、无需 ALPN）：**3000–5000 LOC**；
- ECH 增量（outer/inner CH + HPKE 装配 + accept/HRR 确认 + retry_configs 处理）：**800–1500 LOC**；
- 合计 **4000–6500 LOC**，其中约 1/3 是协议编解码与常量表。
- HPKE/X25519/HKDF/AES-GCM 全部由 BC 提供（0 LOC），心脏骤停点从"密码学"转移到"TLS 协议状态机"。

**风险清单**：
- 证书验证正确性（x509 链、SAN、时间、hostname）——建议复用平台 `CertPathValidator` + `HttpsURLConnection` 的验证策略，风险可控；
- TLS 1.3 互操作面宽（各大 CDN/worker 端）——必须以真实服务端 + 抓包对拍 Go 版调试；会话恢复可砍（连接池本身常建新连）；
- 与 Go 版逐字节对齐成本高，但 RFC 9849 已标准化（2026-03），比当年 Go 对着 draft 实现时清晰得多；
- 只支持 TLS 1.3（Go 侧 MinVersion 本来就强制 1.3，不构成差异）；
- 维护面：协议状态机 bug 排查成本高（H）。

### (b) GuardianProject Conscrypt fork 当库用（或等 upstream 合入后升级）—— 集成工作量最小，风险 M

- 直接 `implementation 'info.guardianproject.conscrypt:conscrypt-android:2.6.alpha1647601986.job2220801545'`，DoH 取到的 ECHConfigList 字节 → `Conscrypt.setEchConfigList(sslSocket, bytes)`，平台其余代码几乎不动（OkHttp 可配自定义 `SSLSocketFactory` 包裹）。
- **关键词题：是否值得把"上游 Conscrypt 的 Java 层 ECH"作为依赖升级路线**——upstream #1374 已合入 native 层，Java 层 PR #1406/#1340 仍 open；合并时间不可控，不能当计划内依赖，只能当"可选项"。若 upstream 发布带 `setEchConfigList` 的版本，本路线自动升级为最优先。
- 风险：fork 底座陈旧（2022）、安全维护缺位；native 库体积（conscrypt-android 每 ABI ≥ 1-2MB）；4 ABI 的 so 是否齐备需验证；长期维护依赖第三方社区（Guardian Project 生态）。
- 适用：产品愿意接受一个带 ECH 的成熟 native TLS 栈，换取最短交付路径。

### (c) 平台 SSLSocket + "手工改 ClientHello" —— **不可行（论证）**

- Android 平台 TLS 握手在 provider（系统 Conscrypt）的 **native（BoringSSL）层**完成；JSSE `SSLSocket` 只在握手前接受 `SSLParameters`（cipher suites、protocols、SNI 服务器名、ALPN），**没有任何扩展注入钩子**；`SSLSocketFactory` 只能换实现，不能改握手字节。TLS 握手期间 Java 层拿不到 ClientHello 构造权（无回调/无拦截点）。
- 唯一"用手改 CH"的办法是自己实现一个假的 `SSLSocket`/`SSLEngine` 子类接管握手——那就等于路线 (a)，没有任何捷径。**因此路线 (c) 不成立。**

### (d) 极小白 C TLS 握手 shim（只做握手，之后裸 socket 交回 Java）—— 是折中决策点，风险 M+

- 形态：JNI 小库，仅用 BoringSSL（Chromium 的库，支持 `SSL_set1_ech_config_list`）或自编 OpenSSL master（含 ECH 但未发布）完成 TLS 1.3+ECH 握手；握手成功后把已加密的 `SSL*` 一侧暴露给 Java 的 `Socket`（阻塞 IO 直通），应用数据由 Java 层自定义（本项目只需在其上跑 WS 帧）——**本质是把"握手"外包给 C，数据面仍是 Java**。
- 工作量：BoringSSL 静态链接 + JNI 桥 + 4 ABI 构建（GitHub Actions 已有 NDK 通道）≈ **800–1500 LOC C** + 构建/体积成本（BoringSSL 每 ABI 3-10MB，可用符号裁剪）。纯自研最小 C TLS 1.3（不依赖任何库）则 5000+ LOC，不划算。
- **标注**：违反"纯 Java"总目标（项目背景明确要求原生 Java 复刻 golib+hev 组合，对 C 的容忍度仅在 hev-socks5-tunnel 这类既有组件上）；安全面（native 内存 bug）回归到 C 时代。属"需要用户拍板"的决策点。

### (e) 调研发现的其他路径

- **(e1) fork BouncyCastle bctls 加 ECH 补丁（中间路线，推荐关注）**：bctls 是纯 Java TLS 1.3 客户端（TLS 1.2/1.3 全覆盖、JSSE provider 可选），在其 `TlsClientProtocol` 层补 ECH 扩展构造/解密判断/retry 逻辑——只需动客户端 CH 构造与 ServerHello 判定两处，工作量估计 **800–1500 LOC 改动 + vendor 整个 bctls 源码树**（BC License 允许）。风险：bctls 行为面大、需要维护 fork；但相比路线 (a)，TLS 1.3 本体是久经考验的，**总风险低于 (a) 且不引入旧 native 依赖**。
- **(e2) 等 upstream Conscrypt Java 层 ECH（#1406）合入后整体升级**：纯"等待"策略，不构成计划内路线，但值得在 CI 里盯 release notes。
- **(e3) HPKE/ECH 密码学部分直接自研、TLS 部分仍用平台 SSLSocket 的"混合"**——不存在：手写 ECH 必然要手写 TLS 握手（见 c）。

**路线综合对比（篇幅所限，详见 §5 量表）**：a 路线纯 Java 但 4000-6500 LOC/风险 H；e1 路线纯 Java 且 TLS 本体成熟、改动集中，风险 M+（vendor 成本）；b 路线最快但依赖陈旧 native 库，风险 M（供应链）；d 路线最快且协议可靠但违反纯 Java 前提，风险 M+（安全面）。

---

## 4. DoH / UDP DNS / WebSocket 的 Java 方案

- **DoH（对照 §1.2 逐条）**：
  - JSON 格式：照抄 `DoHResponse` 结构用 Gson/org.json 解析；请求 `GET {url}?name=&type=`，`Accept: application/dns-json`；**Google 特判** `/dns-query→/resolve`（host 匹配 `dns.google`/`google.com`）。
  - RFC 8484 格式：手写 DNS 查询报文（12B 头 + QNAME label 编码 + QTYPE/QCLASS），POST `Content-Type: application/dns-message`；响应侧解析需 A/AAAA/HTTPS(65)（UnknownResource 取原始字节）。
  - 服务器轮转：`AtomicInteger lastServerIdx` 记忆上次成功；每服务器独立超时（OkHttp 每请求 timeout 或 HttpURLConnection 独立实例）；重试 2 次、仅 `TLS/connection/EOF` 类错误重试。
  - 载体：**OkHttp（推荐，http2+连接池+超时语义接近 net/http）或 HttpURLConnection（零依赖）**；两者均 ≥300-500 LOC 左右的封装量。
- **UDP DNS 回退**：`DatagramSocket` + 5s 超时 + 手写 DNS 报文（与 RFC8484 同一编解码器复用）；8.8.8.8:53 硬编码现值从配置读取（Go 是常量）。SVCB/HTTPS 解析器（wire `\# len hex` + 文本格式两套，key5=ech/key1=alpn）**纯 Java 直抄 200-300 LOC**；普通 A/AAAA 解析在 DoH JSON/RFC8484 路径内已覆盖，UDP 路径 Go 版只查 HTTPS(65)、Java 对齐即可。
- **WebSocket（RFC 6455 客户端）**：
  - 若 ECH 延后（阶段一）：直接用 **OkHttp WebSocket**（0 LOC；minSdk 21+ ✓），注意对齐 Go 侧行为：wss URL（`?client_id&ch_id` 或 `?fallbackip`）、HTTP Headers（Host/UA）、Subprotocol=Token、握手超时/读写缓冲。
  - 若走纯 Java TLS/ECH（阶段二）：OkHttp 无法注入 ECH（其 TLS 走平台 JSSE），需自研 RFC 6455 客户端：`Sec-WebSocket-Key` 随机 16B base64、`Sec-WebSocket-Accept = base64(SHA1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`、帧头解析（FIN/opcode/mask/len 编码）、客户端 masking、分片聚合、ping/pong、close 帧 ≈ **300-500 LOC**；若走 Conscrypt fork 路线则 OkHttp + 自定义 `SSLSocketFactory` 仍可用。
- **分阶段建议**：阶段一 = OkHttp WS + OkHttp DoH + 手写 DNS 编解码 + 缓存/预热（无 ECH，与 Go 明文路径行为一致）；阶段二 = 在自研 TLS 栈（a/e1）或 Conscrypt fork（b）上叠加 ECH 与自研 WS。

---

## 5. 组件可行性总表

| 组件 | Go 行为摘要 | Java 方案 | 依赖（Maven 坐标或纯 Java） | 估算 LOC | 风险 |
|---|---|---|---|---|---|
| ECH outer ClientHello 构造 | outer CH：SNI=public_name、key_share、ECH 扩展（config_id/cipher_suite/enc/密文） | 自研 TLS13 客户端（路线 a/e1） | 纯 Java | 300-500 | H |
| ECH inner（EncodedClientHelloInner + padding） | inner CH 拷贝、legacy_session_id 置空、零填充 | 同上 | 纯 Java | 200-350 | H |
| HPKE（RFC 9180） | `hpke.NewSender(…, info="tls ech\0"+config.raw)` + Seal/Open | **BC `org.bouncycastle.crypto.hpke.HPKE`（实测存在）** | `org.bouncycastle:bcprov-jdk18on:1.80` | 0（库）／备选自研 400-600 | M（依赖版级） |
| TLS 1.3 客户端（record+握手+kx+证书） | crypto/tls 1.3（MinVersion 强制 1.3） | 自研最小 1.3（a）或 **vendor bctls + ECH 补丁（e1）** | 纯 Java（bctls 需 vendor） | 3000-5000（a）／bctls 全量 +800-1500 补丁（e1） | H（a）／M+（e1） |
| ECH accept_confirmation / HRR 确认 | HKDF-Extract(0,inner.random)+ExpandLabel("ech accept confirmation",transcript,8) | 自研（在 TLS13 栈内） | BC HKDF 或自写 ~50 LOC | 150-250 | H |
| ECH 拒绝→报错→重试→降级语义 | ECHRejectionError + 错误匹配 + Refresh + 3次→5min 降级 + 计数清零 | 错误类型/串判定 + 定时器 + Refresh 线程 | 纯 Java | 100-250 | L（逻辑简单、语义要对齐） |
| WebSocket（RFC 6455 客户端） | gorilla/websocket v1.5.3：wss、Subprotocol=Token、headers、NetDial（relay） | 阶段一 OkHttp；阶段二自研（握手+帧/mask/分片） | `com.squareup.okhttp3:okhttp:4.12+`（minSdk21 OK） | 0 或 300-500 | L（OkHttp）／M（自研） |
| DoH 客户端（双格式+轮转+重试） | 3 内置服务器、lastServerIdx 记忆、RFC8484 POST→JSON GET、Google /resolve 特判、每服务器独立超时 | OkHttp 或 HttpURLConnection，逻辑照抄 | okhttp（可选） | 300-500 | L |
| UDP DNS 回退 | 8.8.8.8:53、5s、HTTPS(65) 查询 | DatagramSocket + 手写 DNS 编解码 | 纯 Java | 250-400 | M（手写解析） |
| SVCB/HTTPS 记录解析（text + wire） | parseHTTPSRecord/Wire：key5=ech（原始字节）、key1=alpn、RFC3597 `\# len hex` | 纯 Java 直抄 | 纯 Java | 200-300 | L |
| DNS 缓存 | map `domain:type`、TTL 5min、cleanupLoop 1min、统计 | ConcurrentHashMap + ScheduledExecutor | 纯 Java | 150-250 | L |
| DNS 预热 | 40 域名 ×(A+AAAA)、并发 8、15s 总超时 | ExecutorService + Semaphore | 纯 Java | 80-150 | L |
| ECH 缓存管理 | 24h TTL + 12h 定时刷新 + singleflight + 冷启动预注入 | ConcurrentHashMap + CompletableFuture singleflight + ScheduledExecutor | 纯 Java | 150-250 | L |

**汇总**：阶段一（无 ECH）≈ **900-1700 LOC，风险整体 L-M**；阶段二 ECH：
- 路线 a（自研 TLS13+ECH）≈ **4000-6500 LOC，风险 H**；
- 路线 e1（vendor bctls + ECH 补丁）≈ **800-1500 LOC 改动（+vendor 源码树），风险 M+**；
- 路线 b（GuardianProject fork）≈ **100-300 LOC 集成，风险 M（依赖陈旧）**；
- 路线 d（C shim/BoringSSL）≈ **800-1500 LOC C（+构建/体积成本），风险 M+（违反纯 Java）**。

---

## 6. 推荐技术路线（分阶段）

**第一阶段（不依赖任何拍板即可启动；目标：与 Go 版的"明文路径"和协议完全一致）**
- DoH（OkHttp，双格式+轮转+重试+Google 特判）+ UDP DNS 回退 + SVCB/HTTPS 解析 + DNS 缓存/预热 + ECH 配置管理器（缓存/singleflight/定时刷新/冷启动注入，其中 ECH 字节仅缓存不消费）+ WebSocket（OkHttp）+ TLS1.3 用平台 SSLSocket（API 29+ 才有，minSdk24 设备上 TLS1.2 回退需另议——**这本身是一个阶段一的兼容性决策点**）。
- 行为对齐点：`enable_ech=false` 或 ECH 配置获取失败时与 Go 完全一致（明文 wss 连接）；服务器端 ECH 兼容性无需关心（客户端不发 ECH 扩展即是纯 TLS1.3）。

**第二阶段（补 ECH；前置条件见下）**
- **推荐次序**：先做 **e1（vendor BC bctls + ECH 补丁）**——纯 Java、TLS 本体成熟、改动集中、无陈旧依赖；同时把 **upstream Conscrypt Java 层 ECH（#1406）** 加入 CI 监控作为"白捡升级"；若产品对交付周期极其敏感且接受供应链风险，则并行评估 **b（GuardianProject fork）** 作为最快试用验证；**a（全自研 TLS13）** 只在 e1 因 bctls 行为面问题受阻时作为后备（不鼓励首启）；**d（C shim）** 只在"可靠性 > 纯 Java 原则"时由用户拍板。
- 前置条件（拍板后行动）：
  1. 获得可联调的 ECH 服务端/域名（服务端需发布 HTTPS 记录的 ech 参数 + 配套 ECH 密钥）；无则可用 BoringSSL HTTP server 示例或 c2FmZQ/ech 之类测试拓扑搭建。
  2. 建立"Go 版 vs Java 版"抓包对拍基线（ClientHello 字节、accept_confirmation、错误路径）。
  3. 用真实 DoH 域名（v.recipes / doh.090227.xyz / doh.pub）验证 ECHConfigList 字节两栈互通（§2 已确认字节格式同构，属低风险项）。

---

## 7. 需要用户拍板的决策点清单

1. **ECH 是否允许延后一个版本交付**？（决定是否采用第一阶段/第二阶段切分；Go 版 xtunnel 默认 `EnableECH=true`，若交付要求与 Go 行为全等，则 ECH 不能延后，必须选 b 或 d 快速铺底。）——— 若延后可接受，强烈建议阶段一 + 阶段二 e1。
2. **是否接受路线 d 的极小 C（BoringSSL）握手 shim**？（违反"纯 Java"项目约束；换取最高的协议可靠性；需接受 native 安全面与每 ABI 3-10MB 体积。）
3. **是否引入 BouncyCastle 依赖**？（bcprov-jdk18on 1.80；纯 Java 路线必需；许可宽松；需 R8 裁剪与 CI 验证 ART 兼容性。）
4. **是否接受 GuardianProject Conscrypt fork 作为（临时）依赖**？（2022 年底座、无维护承诺；仅建议用于试用验证，不建议作为长期生产依赖。）
5. **ECH 失败语义的对齐基准**：Go 当前错误串 `"tls: server rejected ECH"` 与 GCM 的错误匹配条件（`"ech"` 小写/`"encrypted_client_hello"`）存在不一致，需要服务端确认"以哪个为准"（是照抄 Go 现在的字符串匹配，还是按 Go 1.25.5 实际错误类型对齐）——建议移植时统一用"错误类型 + 重试次数"替代字符串匹配。
6. **minSdk24 上的 TLS1.3 覆盖**：平台 SSLSocket 的 TLS1.3 需要 API 29+；阶段一若要求 minSdk24 设备也走 TLS1.3，需尽早引入 BC bctls 或自研 TLS（即提前进入阶段二路线）；否则明确"低版本设备明文 TLS1.2"可接受。
7. **ECH 联调环境**：是否有真实 ECH 服务端供应（ech 参数 + 私钥）？GCM worker / x-tunnel server 是否已发布 ECH 配置？（影响验收标准与测试方案。）
8. **消除 open question**：确认 xtunnel default `ECHDomain=cloudflare-ech.com` 与 `DNSServer=https://v.recipes/dns-query` 是否在 Android 侧可配置（CLAUDE.md 提及参数经 TProxyService 组装）。

---

## 8. 参考资料（URL 清单，2026-09 抓取）

**项目代码（本地仓库，只读）**
- `golib/shared/ech/manager.go`（L14 常量、L50-303 全函数）
- `golib/shared/dns/doh.go`（L41-46 内置服务器、L109-556 查询链路、L592-757 解析）
- `golib/gcm/pool/connection.go`（L451-475 getTLSConfig、L477-528 handleDialError、L434-441 buildWSSURL、L585-605/726-744/888-905 拨号）
- `golib/xtunnel/dialer.go`（L33-109）、`golib/xtunnel/config.go`（L85-110 默认值）
- `golib/shared/dns/cache.go`（L61-451）、`golib/shared/dns/warmup_list.go`（L3-69）
- `golib/go.mod`（go 1.25.5；gorilla/websocket v1.5.3；golang.org/x/net v0.17.0）

**Go crypto/tls ECH 语义**
- https://github.com/golang/go/blob/master/src/crypto/tls/common.go （L841-861 EncryptedClientHelloConfigList）
- https://github.com/golang/go/blob/master/src/crypto/tls/handshake_client.go （L174-207）
- https://github.com/golang/go/blob/master/src/crypto/tls/handshake_client_tls13.go （L88-157）
- https://github.com/golang/go/blob/master/src/crypto/tls/ech.go （L482-494 ECHRejectionError）

**协议规范**
- RFC 9849 TLS Encrypted Client Hello：https://www.rfc-editor.org/rfc/rfc9849.txt（§4 ECHConfig、§5.1 EncodedClientHelloInner、§6 ClientHelloOuterAAD/Seal、§6.1.4/6.1.5/6.1.6 接受/拒绝语义、§7.2 accept_confirmation）
- RFC 9848 Bootstrapping TLS ECH with DNS Service Bindings：https://www.rfc-editor.org/rfc/rfc9848.txt（§3 ech SvcParam = ECHConfigList wire 值）
- RFC 9180 HPKE：https://www.rfc-editor.org/rfc/rfc9180.txt
- RFC 9460 SVCB/HTTPS：https://www.rfc-editor.org/rfc/rfc9460.txt
- RFC 8484 DoH：https://www.rfc-editor.org/rfc/rfc8484.txt；RFC 8452 AES-GCM-SIV：https://www.rfc-editor.org/rfc/rfc8452.txt

**生态**
- Conscrypt upstream ECH Issue #730 / PR #1044 / #1340 / #1374 / #1406：#730、#1044、#1340、#1374、#1406 均见 https://github.com/google/conscrypt/issues?q=ECH（各条 https://github.com/google/conscrypt/issues/730 / pull/1044 / issues/1340 / pull/1374 / issues/1406）
- PR #1044 diff（setEchConfigList API、SSL_set1_ech_config_list）：https://patch-diff.githubusercontent.com/raw/google/conscrypt/pull/1044.diff
- GuardianProject conscrypt fork：https://github.com/guardianproject/conscrypt ；Maven 构件目录 https://repo1.maven.org/maven2/info/guardianproject/conscrypt/conscrypt-android/（3 个 2.6.alpha 版本，2022-01~03）
- Conscrypt release 标签/日期：https://api.github.com/repos/google/conscrypt/releases/tags/2.7.0
- Chromium ECH 特性（Chrome 117，desktop=117/android=117 默认启用）：https://chromestatus.com/api/v0/features/6196703843581952（页面版 https://chromestatus.com/feature/6196703843581952）
- BouncyCastle Maven：bcprov/bcpkix/bctls-jdk18on 1.80 —— https://repo1.maven.org/maven2/org/bouncycastle/（本报告以 jar 内类清单实测为准：org.bouncycastle.crypto.hpke.*、X25519*、HKDF*、GCMSIVBlockCipher 等）
- OpenSSL ECH：https://raw.githubusercontent.com/openssl/openssl/master/CHANGES.md（L1227 附近）、`ssl/ech` 目录（https://api.github.com/repos/openssl/openssl/contents/ssl/ech）
- OkHttp 兼容性说明：https://github.com/square/okhttp/blob/master/README.md（"Android 5.0+ (API 21+) and Java 8+"）
- Android X25519 JCA 需 API 31+、AES/GCM/NoPadding 自 API 21+：developer.android.com 参考文档（本环境访问受限，建议 CI 阶段复验；备选依据：Conscrypt README "Lollipop (API 21+)" https://raw.githubusercontent.com/google/conscrypt/master/README.md）
- 纯 Java ECH 先例检索（GitHub search API）：`q=hpke+language:java`、`q=rfc9180`、`q="encrypted client hello"+language:java`、`q=ech+client+hello+java+OR+kotlin`（命中的非成熟项目：vinodjavvadi/hpke-backend-java 等；Go 侧：c2FmZQ/ech、salrashid123/go_ech、OmarTariq612/goech；KMP：a-sit-plus/signum）