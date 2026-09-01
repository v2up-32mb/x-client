# golib + hev-socks5-tunnel 原生 Java 化可行性报告

> 分支：`feat/java-native-rewrite`（基于 main @ 0242f48，v1.1.9）
> 日期：2026-09-01
> 调研方式：8 路并发子代理深度调研（golib 全量盘点 / 协议规格 / TUN 链路 / ECH 可行性 / 性能 / Android 集成面 / 架构设计 / 对抗性风险审计），全部结论附源码行号或来源 URL。
> 明细报告：`research/java-native/01-08*.md`（本报告为决策汇总，细节以明细为准）。

---

## 0. 结论（TL;DR）

**有条件可行。** 协议栈（golib，约 12.8k 行 Go 净迁移面）用 Java 复刻是确定性工程；真正的两个"方向性"难点是：

1. **hev-socks5-tunnel 不是"逐包转发器"，而是内嵌了完整用户态 lwIP TCP/IP 协议栈**（TCP 状态机/重传/窗口/IP 分片/UDP NAT/伪映射 DNS）。纯 Java 替代它 = 用 Java 重写 lwIP 级协议栈（约 3000–5000 行、正确性极难、业内无生产级先例）。
2. **ECH 是唯一无法用标准 Java/Android 库 1:1 复刻的点**（平台 SSLSocket 无 ECH 钩子、Conscrypt 上游无公开 ECH API、纯 Java ECH 客户端生态为零）。但 ECH 可分阶段：第一阶段不发 ECH = 与 Go 版"ECH 降级路径"完全等价，服务端无感。

**推荐路线（核心建议）**：分 6 阶段——

| 阶段 | 内容 | 人天 | 交付形态 |
|---|---|---|---|
| P0 | 工程骨架 + 测试基建 + GHA 测试流 + 黄金向量导出 | 3–5 | — |
| P1 | GCM 协议栈 Java 化（**不含 ECH**） | 15–20 | 可上线功能（=Go 版 ECH 降级路径） |
| P1.5 | x-tunnel 协议栈 Java 化（**不含 ECH**，含背压/Hot Pair/UDP） | 20–25 | 双协议全功能（无 ECH） |
| P2 | ECH 完整链（推荐 vendor BouncyCastle bctls + ECH 补丁） | 15–25 | 与 Go 版 ECH 行为对齐 |
| P3 | 一致性回归 + 真机性能对标 | 10–15 | 验收版 |
| P4（可选） | 纯 Java TUN 转发器（替换 C 库的用户态 TCP） | 30–45 | 纯 Java 全栈 |

- **P0–P3 合计 63–90 人天（约 3–4.5 人月）**，期间保留 C TUN 转发器（"半 Java"过渡形态）；
- **P0–P4 合计 93–135 人天（约 4.5–6.5 人月）**，达成用户要求的"全部原生 Java"；
- **兜底开关**：真机基准若 下行 < 目标 0.7× / 单核 CPU > 80% / GC 尖峰 > 30ms，P4 可整体放弃，停在过渡形态（协议栈价值已保住，风险可量化、可回退）。

**若坚持"纯 Java 用户态 TCP + 一期 ECH 全等"，可行性从"有条件可行"降级为"不建议"**（自研 lwIP 级 TCP + 自研 TLS1.3+ECH 两条最高风险线并行，且无任何先例兜底）。

---

## 1. 替换范围：到底在替换什么

### 1.1 现状数据流

```
App (VpnService, :vpn 进程)
  │  TUN fd（裸 IP 包，MTU 8500，非阻塞）
  ▼
hev-socks5-tunnel (C，JNI)
  │  用户态 lwIP TCP/IP 协议栈（TCP 终结/重传/分片/UDP NAT/映射 DNS 240.0.0.0/8）
  │  每会话一个协作任务 → 本地 SOCKS5 客户端
  ▼
127.0.0.1:1080 本地 SOCKS5（Go, gomobile AAR xclient.aar）
  │  GCM 或 x-tunnel 协议栈
  │  （连接池/多路复用/背压/relay 评分/ECH/DoH/路由绕过）
  ▼
wss:// 服务器（Cloudflare Worker / x-tunnel server / relay 节点）
```

### 1.2 golib 迁移面盘点（明细：01-golib-inventory.md）

- 非测试代码 **14,113 行**，测试 4,679 行（152 个测试函数）。
- **Android 真实运行路径只经过 8 个包**；CLI 专用（urfave/cli + yaml，569+ 行）与两处死代码（`quality_monitor.go` 256 行 Android 路径从未实例化、`proxy_transport.go` 386 行 EnableDoHProxy 恒 false）可整体排除。
- **净迁移面约 12.8k 行 Go → 预估 14.5–18k 行 Java**（并发原语显式化会增行）。
- 外部依赖仅 3 个运行时依赖：gorilla/websocket（WS 拨号/读写）→ Java 用 OkHttp；golang.org/x/net（仅 DNS 报文编解码）→ 自研 450–700 行；google/uuid → `java.util.UUID`。
- 最难的两个文件：`gcm/pool/connection.go`（2088 行：连接池 + 6 个后台循环 + 流位图 + 心跳/拥塞控制）、`xtunnel/pool.go`（1674 行：多通道竞争 + 背压 + 帧聚合 + fast retry 状态机）。两者都是"逻辑 1:1 移植、只换并发原语"，难度 5/5。

### 1.3 hev-socks5-tunnel 真相（明细：03-hev-tunnel-analysis.md）★关键发现

- 它是 **tun2socks 实现**，内嵌 hev 维护的 **lwIP fork**：SYN-ACK/重传/拥塞窗口/IP 分片重组/NDP/UDP 源端口映射（Fullcone NAT）全部在用户态完成，单线程 epoll 协作调度（hev-task-system）。
- **为什么不能"廉价替代"**：TUN 上收到的是发往外部地址的原始 IP 包，内核 TCP 不会替我们回 SYN-ACK——必须有人实现完整 TCP 栈来终结这些流。逐连接"套内核 socket"的简化方案**不成立**。
- 另有 **mapped DNS 双向映射**（198.18.0.2:53 拦截 A 查询 → 伪造 240.x.y.z 假 IP → 会话建链时反查域名为 SOCKS5 NAME 目标 → 防 DNS 泄漏），Java 版必须完整复刻。
- 容量参照：TCP PCB 4096、UDP PCB 1024、TCP 空闲 5min、UDP 空闲 60s、UDP 帧队列 512 超限丢弃。
- **意外发现（产品决策点）**：GCM 后端（`shared/socks5/server.go`）只实现 CONNECT，UDP ASSOCIATE/FWD UDP 一律回 `0x07` 断开 → **当前 GCM 协议下数据面 UDP 根本不通**；x-tunnel 后端支持 UDP ASSOCIATE 但不支持 FWD UDP。Java 版是"保持行为（GCM UDP 仍不通）"还是"顺手修好"，需拍板。

---

## 2. 三个决定方向的关键发现

### 发现 1：用户态 TCP/IP 栈是最大工作量与最高风险（03 + 05）

- 纯 Java 替代 = 实现 lwIP 级 TCP/IP（预估 3000–5000 行 + 独立 TCP 一致性测试），**业内无纯 Java 生产级先例**（v2rayNG/sing-box/NekoBox/Outline 数据面全是 Go/C/Rust；连 hev 作者的 sockstun 也是 Java UI + C 核）。
- **好消息（性能面）**：tun fd 每 read 只返回一个 IP 包，Java 与 C 的每包 syscall 数持平；头解析在 ART 上是纳秒级；`android.system.Os.poll()`（API 21+）可让纯 Java 对裸 tun fd 做事件等待，**无需 JNI**。主要成本在每包对象分配→GC 与线程模型，"池化 + 热路径零分配 + 单读线程 + NIO Selector"可压到可接受。
- **量化结论**：目标 ≤150 Mbps / ≤2000 并发 TCP 流 → 纯 Java 可达；>300 Mbps / 3000+ 流 → 应保留原生数据面。最终必须真机基准（GHA 上只能跑 JVM 参考基准）。
- 因此 P4 设计为**可选阶段**，前置条件是 P3 真机基准达标 + 独立 TCP 一致性测试通过。

### 发现 2：ECH 可分阶段，且"字节红利 + 一个 Go 侧潜在 bug"（04 + 08）

- **Go 的 ECH 失败语义不是静默回退**，而是：握手被服务器拒绝 → `ECHRejectionError` → 应用层刷新 ECH 配置 → 重试 → 连续 3 次失败禁用 ECH 5 分钟（期间走明文 TLS1.3）。Java 必须复刻这套"错误→重试→降级"语义。
- **白送红利**：DNS HTTPS 记录里的 `ech` SvcParam 原始字节 = ECHConfigList，与 Go `EncryptedClientHelloConfigList`、BoringSSL `SSL_set1_ech_config_list` 是**同一种字节格式**——Java 端 DoH/UDP 查到的字节可原样喂给任何 ECH 能力方。
- **ECH 路线对比**（明细 04 §3）：

| 路线 | 内容 | 工作量 | 风险 | 备注 |
|---|---|---|---|---|
| a | 纯 Java 自研最小 TLS1.3 客户端 + ECH | 4000–6500 行 | H | 无先例可抄；密码学原语 BC 全覆盖（HPKE 实测 jar 内存在） |
| b | GuardianProject Conscrypt fork（Maven 有构件） | 100–300 行集成 | M（供应链） | 2022 底旧底座、无维护承诺；仅建议试用验证 |
| e1 | **vendor BouncyCastle bctls + ECH 补丁**（推荐） | 800–1500 行改动 + vendor 源码树 | M+ | 纯 Java、TLS1.3 本体成熟、改动集中在 CH 构造/ServerHello 判定两处 |
| d | 极小 C（BoringSSL）握手 shim，之后裸 socket 交回 Java | 800–1500 行 C | M+ | **违反纯 Java 总目标**，仅在"可靠性 > 纯 Java 原则"时由用户拍板 |
| (c) 平台 SSLSocket 手工改 ClientHello | — | — | — | **论证为不可行**（native 层无扩展注入钩子） |

- **对抗审计抓出的 Go 侧潜在 bug（X3）**：GCM 的 ECH 降级靠错误串匹配 `"ech"`/`"tls: handshake failure"`，而 Go 1.25.5 实际错误串是 `"tls: server rejected ECH"`（大写）——**GCM 的降级逻辑在当前错误串下可能根本不触发**（xtunnel 端匹配大写 "ECH" 可命中）。Java 移植**不能照抄字符串匹配**，应改用"错误类型 + 重试计数"，顺手把这个语义修对（需要与服务端确认基准）。
- **minSdk 24 硬约束（X5）**：平台 SSLSocket 的 TLS1.3 需 API 29+，而 GCM 强制 TLS1.3 → 低版本设备走平台栈**直接连不上**。解法：引入 BC bctls（同时解决 ECH + TLS1.3 覆盖）或接受"低版本设备 TLS1.2"或抬高 minSdk。
- **DoH/DNS 侧零风险**：3 内置服务器轮转、RFC8484/JSON 双格式、UDP 8.8.8.8 回退、SVCB/HTTPS 解析、5min 缓存、40 域名预热——全部纯 Java 直抄（900–1700 行，风险 L）。注意一致性约束：Go 版 DoH **不做任何 DNSSEC 校验**，Java 版也不该加（加了反而不一致）。

### 发现 3：集成面极度收敛，CI 是纯正收益（06）

- `xclient.Xclient` 调用点共 **8 处、7 个唯一方法**，集中在 3 个文件；hev JNI 仅 4 个 native 方法（`TProxyGetStats` 是死代码，无人消费）。
- **推荐保留 `xclient.Xclient` 静态 facade（方法签名与 AAR 版完全一致）→ app 调用点零改动**，`:vpn` 多进程与 `MODE_MULTI_PROCESS` 既有设计不受影响。
- CI：移除 Go setup / gomobile / NDK / hev clone 四个环节（若 P4 落地），构建时间约 -50%；产物从 **4 个 per-ABI APK（合计 30–80MB）→ 1 个 universal APK（2–5MB）**。
- 顺带消除一个现存隐患：hev 的 CI clone **未锁 ref**（main 分支漂移风险）。

---

## 3. 分模块可行性评估

| 模块（Go → Java） | 规模 | Java 方案 | 难度 | 风险 |
|---|---|---|---|---|
| GCM 协议编解码（2B 头） | 92 行 | 直译，帧边界=WS 消息 | 1 | 低（CONNECT 载荷 `host:port\|`、IPv6 表示易错） |
| x-tunnel 协议编解码（8B 头） | 331 行 | `ByteBuffer` 大端；**长度必须 long 累加防 32 位回绕** | 2 | 中（截断/溢出边界） |
| GCM 连接池 + 流管理 | 2797 行 | 线程池 + BlockingQueue + 位图 + AIMD 窗口 | 5 | 高（死锁/竞态；6 个后台循环、心跳 15s/3s、读 deadline 18s） |
| x-tunnel 连接池 + 背压 + 帧聚合 + fast retry | 1674 行 | AtomicLong 记账 8MB、ArrayBlockingQueue 16384、CAS 通道竞争 | 5 | 高（Pause 3s 降级、聚合 256KB、退避序列须逐条对拍） |
| Hot Pair / PairWarmer | 697 行 | 状态机 + refs 计数 + 30s 刷新 + prebind 3s | 4 | 中（refs 与 Invalidate 竞态） |
| relay 评分（两套独立实现） | 737+613 行 | 并发测速 + 加权随机 + 动态测速间隔 | 3 | 中（公式/时间衰减/恢复逻辑易静默丢失） |
| SOCKS5 服务（GCM 版 + xtunnel 版含 UDP） | 630+624 行 | NIO Selector；乐观应答；无通道必须回标准失败应答 | 3 | 中（半关闭语义、信号量+100ms 突发窗口） |
| HTTP 代理 | 348 行 | OkHttp 解析；请求重建去代理头 | 2 | 低 |
| DoH/DNS 缓存/预热/SVCB 解析 | 1268 行 | OkHttp + 手写 DNS wire 编解码 | 2–4 | 低–中 |
| ECH 管理器（缓存/singleflight/刷新） | 303 行 | ConcurrentHashMap + CompletableFuture singleflight | 3 | 低（**注入 TLS 握手是另一回事，见 ECH 路线**） |
| 路由 Matcher + geoip/geosite 数据 | 335 行 + 12,232 行数据（227KB） | assets 内嵌 + 前缀分桶最长匹配 + 后缀 Trie | 2 | 低（数据许可 CC-BY-SA/MIT，README 已记录来源与 SHA-256） |
| 运行时日志/配置/时区 | ~500 行有效 | 环形缓冲 2000 行/256KB、格式逐字对齐 | 1–2 | 低（格式是 UI 契约，易被"顺手改坏"） |
| **TUN 转发器（用户态 TCP/IP + 映射 DNS）** | **C+lwIP（数万行，核心状态机数千行）** | **纯 Java 需 3000–5000 行 + TCP 一致性测试** | **5+** | **最高（无先例）** |
| Android 集成（facade/参数/监控） | app 3 文件微调 | 保留 Xclient 签名，TProxyService 改调 Java | 2 | 低 |

**汇总**：协议栈部分（不含 TUN 转发器）约 12.8k 行 Go → 14.5–18k 行 Java，属"确定性高、工作量可预估"的移植；TUN 转发器是唯一"未知正确性"的大块头。

---

## 4. 推荐实施路线（分阶段，每步可真机验证）

```
P0 骨架+测试 ──► P1 GCM(无ECH) ──► P1.5 x-tunnel(无ECH) ──► P2 ECH ──► P3 回归+性能 ──►(基准达标?)──► P4 纯Java TUN
                     └────────── 全程保留 C TUN 转发器（过渡形态）──────────┘
```

| 阶段 | 目标 | 退出准则（摘要） | 人天 |
|---|---|---|---|
| **P0** | `com.x.client.core` 包骨架、`xclient.Xclient` facade、JUnit5+MockWebServer、GHA `test.yml`、Go 侧黄金向量导出工具（GHA `go run` 生成 JSON） | 空测试流水线绿；向量 JSON 落盘 | 3–5 |
| **P1** | shared 层（config/dns/routing/logger）+ GCM 全链路（连接池/流/relay/SOCKS5/心跳/动态池）+ 真机联调（**保留 C TUN**，`enable_ech=false`） | 黄金向量全过；真机 VPN 建立→网页可加载；协议基线 26 条 GCM 项逐条过 | 15–20 |
| **P1.5** | x-tunnel 全链路（8B 帧/背压 8MB+16384+500ms+Pause 3s/帧聚合 256KB/fast retry/Hot Pair/UDP associate/HTTP 代理） | 向量全过；真机 speedtest 上下行无断连、背压触发正常；xtunnel 基线逐条过 | 20–25 |
| **P2** | ECH 完整链：推荐 **e1（vendor BC bctls + ECH 补丁）**；ClientHello 字节级对拍；降级语义用"错误类型+计数"实现（不抄 Go 字符串匹配） | ECH 启用连上 + 服务器不支持时降级路径正确；与 Go 版抓包对拍一致 | 15–25 |
| **P3** | 全量回归（02 的 26 条服务器可见基线 + 07 的 39 条内部逻辑 + 45 条用户可见）；真机 speedtest Go vs Java 对比；Perfetto GC 分析；6h 长稳 | speedtest 差异 <15%；无 >30ms GC 尖峰/内存泄漏；长稳通过 | 10–15 |
| **P4（可选）** | 纯 Java TUN 转发器：`Os.poll`+FileInputStream 读循环、用户态 TCP（对齐 lwIP 语义：重传/窗口/MSS 8191/空闲 5min）、UDP NAT（对齐 Fullcone/60s/512 帧上限）、映射 DNS 双向映射 | 独立 TCP 一致性测试（与内核 TCP 对拍）；真机与 C 版差异 <10%；CI 移除 NDK | 30–45 |

**工作量**：P0–P3 = **63–90 人天**；含 P4 = **93–135 人天**。

**P4 的决策门（量化开关）**：P3 真机基准 下行 ≥ Go 版 0.7×、单核 CPU <80%、GC 尖峰 <30ms、丢包 <2% → 推进 P4；否则项目停在"Java 协议栈 + C TUN 转发器"过渡形态交付（此时已消除 Go/NDK 构建链、APK 仍含 TUN 的 .so，但协议栈维护、调试、协议演进收益全部拿到）。

---

## 5. 测试与验收策略（详见 07 §4 / 08）

1. **三层测试**：
   - L1 单测（GHA JVM）：协议编解码黄金向量（Go 侧 GHA 导出 JSON，Java 侧 `@ParameterizedTest` 消费）、位图/缓存/评分/状态机、背压阈值与退避序列、DNS wire 编解码、SVCB 解析。
   - L2 集成（GHA JVM）：MockWebServer 模拟 GCM worker 与 x-tunnel server，验证 SOCKS5→CONNECT→CONNECTED→DATA→CLOSE 全时序、背压触发/降级、fast retry 退避、路由绕过。
   - L3 真机：speedtest 对比、Perfetto 60s trace、100ms 采样统计、6h 长稳、网络切换/息屏重连、**Go 版 vs Java 版抓包对拍（ClientHello 字节级，P2 前置）**。
2. **26 条服务器可见协议基线**（02 §3）+ **39 条内部逻辑一致性** + **用户可见行为**（07 §5）= 验收对照表。
3. **18 条"静默功能丢失"清单**（08 §2）：动态测速间隔、ECH singleflight、DNS 预热、GCM 6 后台循环、relay 恢复逻辑、背压四参数、AIMD 窗口、心跳退休、日志环形语义、重连退避序列、空闲超时、流量统计、流位图+亲和、DoH 轮转、geoip/geosite 解析——每条都有检测难度与验证手段，移植时逐条勾销。
4. **Go 测试覆盖对照**：4,679 行 Go 测试中"真实 WS 服务端端到端、ECH 真实握手、背压真实打满、心跳真实时序"等场景**原本就没有覆盖**，Java 侧需自建（MockWebServer + 真机清单补位）。

---

## 6. 风险 Top 10（完整登记表见 08 §3）

| # | 风险 | 可能性×影响 | 缓解 |
|---|---|---|---|
| 1 | **架构摇摆**：全 Java vs C TUN 过渡未拍板（决定 ±3000–5000 行与 ±1–2 人月） | H×H | 开工前用户拍板（本报告 §8 问题 2） |
| 2 | **自研/补丁 TLS1.3+ECH**：AAD 置零边界、nonce seq、accept_confirmation transcript 一处错全盘失败 | H×H | 先建 ClientHello 字节级对拍基线；优先 e1（bctls 本体成熟） |
| 3 | **minSdk24 无平台 TLS1.3**，GCM 强制 1.3 → 低版本设备连不上 | H×H | BC bctls（P2 一并解决）或拍板 minSdk/TLS1.2 降级 |
| 4 | **协议 bit 级易错点**：GCM fallbackip 不编码 vs xtunnel client_id/ch_id 编码（两处相反）、自研 WS 漏 masking、8B 头 long 防溢出、下行通道竞争 TCP/UDP 去向不同 | H×H | 全部列入验收基线 + 抓包对拍 |
| 5 | **ECH 降级字符串匹配照抄 Go**（GCM 端可能本来就不触发） | H×H | 改用错误类型+重试计数，与服务端确认基准 |
| 6 | **Go 并发语义丢失**（channel close 广播/双 close/select 邮箱语义/defer）→ 死锁/泄漏 | M×H | AtomicBoolean CAS 防重入 + 显式 closed 标志 + "位标志+一次唤醒"模式 |
| 7 | **背压 job 非池化/载荷拷贝** → 8MB 预算虚增 2–3 倍先 OOM | M×H | job 池化 + 存引用不拷载荷（硬约束） |
| 8 | **无真实测试服务器** → bit 级全等/ECH/relay/背压无从验收 | M×M | **开工前置 gate**：用户确认服务器可用性（含可发布 ECH 配置） |
| 9 | **依赖风险**：GuardianProject fork（2022 陈旧）/ BouncyCastle（体积/R8/ART） | M×M | fork 仅试用；BC 走 P0 拍板 + CI 冒烟 |
| 10 | **GCM 数据面 UDP 现状不通**：保持行为 vs 顺手修好 | M×M | 产品拍板（默认保持行为一致） |

---

## 7. 收益（为什么值得做）

1. **构建链大幅简化**：去掉 Go 工具链 + gomobile + NDK + hev 递归 clone（4 个环节、~50% 构建时间），CI 不再受 NDK/gomobile 版本漂移困扰；**同时消除 hev clone 未锁 ref 的现存漂移风险**。
2. **APK 显著瘦身**：4×per-ABI（Go 运行时 + .so，合计 30–80MB）→ 1×universal 2–5MB（P4 落地后；过渡形态下 TUN 部分 .so 保留）。
3. **单一语言栈**：协议栈逻辑（背压调参、通道选择、relay 评分）可直接读改可断点调试，不再隔 gomobile 边界；排障从"logcat + Go 日志缓冲"变成统一 Java 日志。
4. **维护性**：golib 目前双协议两套独立实现（GCM/xtunnel 各一套 pool/relay/socks5），Java 化时可在 shared 层做受控收敛（可选，不改变对外行为）。
5. **服务端零改动**：对外协议字节级兼容（26 条基线验收），服务器/relay 无需任何配合升级。

---

## 8. 需要用户拍板的决策点（按优先级）

| # | 决策点 | 选项 | 推荐 |
|---|---|---|---|
| 1 | **总路线**：接受"分阶段 + C TUN 过渡"，还是坚持一期纯 Java 全栈？ | A 分阶段（P4 由基准决定）/ B 一期全 Java | **A**（B 会把两条最高风险线并行，不建议） |
| 2 | **ECH 是否允许延后一个版本交付**（P1/P1.5 不含 ECH，行为=Go 版 ECH 降级路径） | 可延后 / 必须一期全等 | **可延后**（若必须全等：选 b 快速试用或 d C shim） |
| 3 | **ECH 技术路线**（P2） | e1 bctls 补丁 / a 全自研 / b GP fork / d C shim | **e1**（纯 Java、本体成熟、改动集中） |
| 4 | **是否有可用的真实/测试服务器**（GCM worker + x-tunnel server + 可发布 ECH 配置）做一致性验证？ | 有 / 无（需先准备） | **开工前置 gate，必须有** |
| 5 | **minSdk24 的 TLS1.3 取舍**：BC bctls 保 24+ 全 1.3 / 接受低版本 TLS1.2 / 抬高 minSdk 到 29 | 三选一 | **BC bctls**（P2 一并解决） |
| 6 | **BouncyCastle 依赖是否可接受**（bcprov/bctls-jdk18on 1.80，宽松许可） | 接受 / 不接受（则 ECH 只能自研 a 或 shim d） | **接受**（P0 做 R8/ART 冒烟验证） |
| 7 | **性能验收阈值**：下行 ≤150Mbps / ≤2000 流 / CPU<80% / GC 尖峰<30ms 作为 P4 推进门槛？ | 确认 / 调整 | **确认** |
| 8 | **GCM 数据面 UDP 现状不通**：保持行为一致（仍不通）还是 Java 版顺手支持？ | 保持 / 改进 | **保持**（改进会改变对外行为，另行立项） |
| 9 | **APK 从 4 per-ABI → 1 universal** 的发布渠道影响（x86 支持、渠道要求） | 接受 / 需保留 x86 | 确认渠道要求后定 |
| 10 | **DNS 缓存容量上限**：Go 版无上限（仅 TTL），Java 版是否允许加显式上限（有意的安全改进）？ | 加上限 / 严格一致 | **加上限**（攻击面收敛，属可声明差异） |

---

## 9. 调研明细文档索引

| 文件 | 内容 |
|---|---|
| `research/java-native/01-golib-inventory.md` | golib 全量盘点：逐模块职责/API/并发/参数/依赖、10 条 1:1 复刻要点、工作量表、测试覆盖统计 |
| `research/java-native/02-protocol-spec.md` | GCM + x-tunnel 比特级协议规格（服务器可见行为清单 26 条、疑问清单、Java 实现要点） |
| `research/java-native/03-hev-tunnel-analysis.md` | hev-socks5-tunnel 源码分析（lwIP 架构/TUN 读写/映射 DNS/JNI 生命周期）、Java TUN 转发器需求规格与边界清单、SOCKS5 客户端-Go 服务端配对结论 |
| `research/java-native/04-ech-java-feasibility.md` | ECH/DoH/DNS Java 化路线：Go 语义基准线（含 Go 源码证据）、生态调研（Conscrypt/BC/先例，全部附 URL）、路线 a/b/c/d/e 对比、组件可行性总表、决策点 |
| `research/java-native/05-java-perf-feasibility.md` | 纯 Java 数据面性能：tun fd 读写路径（Os.poll）、每包开销、ART GC/JIT/线程约束、行业先例、基准方案（GHA JMH + 真机）、达标阈值 |
| `research/java-native/06-android-ci-surface.md` | Xclient/JNI 调用面穷举、GCM/xtunnel 参数映射表（字段级）、tproxy.conf 字段、CI 全流程与重构方案、APK 体积对比 |
| `research/java-native/07-target-architecture.md` | 目标架构（包布局/类级映射表/线程模型/数据流图）、7 项关键设计决策、P0–P4 分阶段计划、三层测试策略、45 条验收清单、GHA workflow 草图、Go→Java 并发翻译速查 |
| `research/java-native/08-risk-register.md` | 对抗性审计：报告间矛盾指认（5 处）、协议/密码学/Android/并发四类隐蔽失配、18 条静默功能丢失清单、17 项风险登记表、Top 10 风险、10 个拍板问题 |

---

## 附录：关键数值速查（移植时必须逐字对齐）

| 项 | 数值 |
|---|---|
| GCM WS URL | `wss://<WorkerHost>/<UserID>[?fallbackip=<ProxyIP>]`，fallbackip **不 URL 编码**；UA 写死 Chrome/109 Edge |
| GCM 帧 | 2 字节头 `[streamID:1][type:1][data]`，帧边界=WS 消息；CONNECT data=`host:port\|`（IPv6 `[v6]:port\|`） |
| GCM 心跳/超时 | Ping 15s、Pong 超时 3s 退休、读 deadline 18s、写超时兜底 5s、TunnelTimeout 1min |
| GCM 流控制 | 每连接流上限默认 5（位图 256 位）、窗口 1MB/64KB/4MB、WindowTimeout 5s、AIMD 减半/+8KB |
| xtunnel 帧 | 8 字节大端头 `[type:1][idLen:1][metaLen:2][payloadLen:4]` + connID + meta + payload；12 种消息类型；connID=UUIDv4 文本 |
| xtunnel 背压 | 全局 8MB、单通道写队列 16384、满等 500ms、Pause 等 3s 降级 SlowDown、SlowDown 每写睡 10ms、TCPData 聚合 ≤256KB |
| xtunnel 保活/超时 | Ping 5s、读 15s、握手 5s、ConnectTimeout 15s、SOCKS5 并发 1024（+100ms 突发窗口）、UDP 拦截端口 [443] |
| fast retry | 连失<3 且窗口 1s 内 → 100ms+rand(0–300ms) 短重试（限 1 次）→ 指数 3s×1.5 封顶 60s → 20 次后恒 60s；换节点排除上一失败 IP |
| Hot Pair | 默认 1 对、30s 刷新、prebind 3s 超时、槽位 01–08、强制 uplink≠downlink |
| relay（GCM） | 评分=延迟ms+失败×500；Top5 加权随机（1000/(ms+1)×负载×质量EMA）；rescore 10min、强制冷却 1min、失败阈值 3 |
| relay（xtunnel） | 评分=(1-lat/5000ms)×0.7+成功率×0.3，1h 后衰减；健康分数 → 测速间隔 15/30/60s；失败冷却 3 次/30s |
| DoH | 3 内置服务器（v.recipes / doh.090227.xyz / doh.pub）、lastServerIdx 轮转、每服务器 3s 超时×2 重试（仅 TLS/conn/EOF）、RFC8484 优先→JSON 回退（Google /resolve 特判）→UDP 8.8.8.8:53 |
| ECH | 缓存 TTL 24h、定时刷新 12h、singleflight、3 连败禁用 5min、SNI=WS host（≠echDomain） |
| TUN（C 版参照） | MTU 8500、MSS 8191、TCP 空闲 5min、UDP 空闲 60s、UDP 帧队列 512、TCP PCB 4096、映射 DNS 240.0.0.0/8 + LRU 10000 |
| 日志 | 环形 2000 行/256KB、单行 255KB UTF-8 安全截断、格式 `[HH:mm:ss] [级别] [scope] msg`、每次 start 清空 |
| 路由数据 | geoip_cn 5822 行 CIDR + geosite_cn 6410 行（assets 内嵌，来源与 SHA-256 见 golib/shared/routing/data/README.md） |
