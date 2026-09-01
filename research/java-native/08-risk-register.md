# 08 · Phase 2 对抗性架构审计与风险登记表（风险审计）

> 子代理：risk-audit（对抗性评审）
> 评审对象：Phase 1 六份调研报告（01–06）的可行性结论
> 本文件是"可行性结论的哪里会翻车"部分：结论前置，证据随附；所有论断基于仓库 golib/ 与 app/ 源码实读核对（只读、零构建）。
> 日期：2026-09

---

## 0. 总评（结论前置）

**六份报告的"逐文件盘点"工程质量高、行号与默认值大体准确（本次复核抽查了 ECH 降级、心跳、写队列、URL 编码、SOCKS5 命令等关键点均与源码一致），但它们共同把"工程可移植性"当成了"逻辑一致性+性能可行性"的充分条件。作为对抗性评审，我要指出：真正的翻车点不在"代码搬得动"，而在四类"搬了等于没搬"的隐蔽失配——协议 bit 级、密码学语义级、Android 平台行为级、Go 并发语义级。更严重的是，六份报告在 ECH 失败语义、错误串匹配、golib Go 版本、Xclient 调用点数、写队列是否重分配这几处存在相互矛盾或自我矛盾，且全体遗漏了"DoH/DNSSEC 不做任何认证校验"这一安全一致性问题，以及"目标架构在 C-TUN 过渡 vs 全 Java 之间摇摆"这一最大架构悬而未决。总体判断：**有条件可行**——可行区间是"保留 C TUN 转发器 + Java SOCKS/协议栈 + ECH 分阶段"；若坚持"全 Java 用户态 TCP + 一期交付 ECH 全等"，则从"有条件可行"降级为"不建议"。

---

## 1. 对抗性验证（逐条攻击六份报告的结论）

### 1.1 报告间矛盾（直接指认）

**R1. ECH 失败语义：02 与 04 实质上矛盾，且矛盾点恰好是 Java 移植最易写错处。**
- 02 §1.5 写"ECH 握手失败回退标准 TLS **不阻塞**"；04 §1.5 明确纠正"Go 的 ECH 失败**不会悄悄退回明文连接**"，且给出逐条证据（crypto/tls 走完 outer CH 完成握手后返回 `ECHRejectionError`）。
- 二者在其各自的"层"上都对（02 讲的是配置查询失败 `GetTlsConfig` 返回纯标准 TLS；04 讲的是握手 reject 报错），但**合并到一句移植指令时互相打架**：Java 少年若按 02 的"失败即回退明文"实现，会在服务器强 ECH 时把每次握手机制搞错（应为"错误→刷新→重试→连败 3→5min 降级"）。这是本次审计发现的最典型"报告们各自局部正确、全局误导"案例。
- **证据**：`golib/gcm/pool/connection.go:486-488` 匹配串为小写 `"ech"`/`"encrypted_client_hello"`/`"tls: handshake failure"`；`xtunnel/dialer.go:95` 匹配大写 `"ECH"` 与小写 `"ech"`。而 Go 现版本（go.mod=1.25.5）实际错误串是 `"tls: server rejected ECH"`——**只有大写 `"ECH"`（xtunnel）能命中；GCM 的小写 `"ech"` 和 `"tls: handshake failure"` 大概率都不命中**。也就是说 **Go 版的 GCM ECH 降级逻辑在当前错误串下可能根本不触发（潜在已存在的 bug）**。04 已在 open question 里点到但未升级为"移植红线"。**Java 移植绝不能照抄这套字符串匹配**，必须用"错误类型 + 重试计数"实现，否则会把一个 Go 端就坏了的语义原样搬过去。这是 R1 的延伸结论。

**R2. golib Go 版本：01 / CLAUDE.md 与 04 / go.mod 互相矛盾。**
- CLAUDE.md 与 01 摘要称"Go 1.23"；04 读取 `golib/go.mod` 称 `go 1.25.5`；本次实读 `golib/go.mod:3` = `go 1.25.5` **确认 04 正确**。01 摘要的版本声明已过时（继承 CLAUDE.md）。
- 影响：ECHRejectionError/错误串的分析基准必须挂在 1.25.5 上；若后续回退版本，密码学/错误语义基准会整体移位。建议在决策前把"锁定 go 版本以固定 ECH 错误语义"记为一条。

**R3. Xclient 调用点计数：06 自相矛盾（7 处 vs 8 处；6 个唯一方法 vs 实际 7 个）。**
- 06 正文摘要写"7 处、6 个唯一方法"，附录 C 写"**共 8 处（6 个唯一方法）**"。实际调用点：TProxyService L289/366/465/644/663/672 = 6，XclientApplication L13 = 1，SettingsActivity L166 = 1 → **共 8 个调用点**；唯一被调方法 = startSocksProxy/stopSocksProxy/reconnect/setTimeZone/appendRuntimeLog/getRuntimeLogs/validateBypassRules = **7 个**（加未调用的 NotifyNetworkChanged 则 gomobile 面为 8 个导出方法）。
- 影响：这是集成面统计，数字错不影响架构，但说明 06 在"穷举面"上的严谨度有限，其"app 仅需改 3 文件"的乐观结论需保留折扣（TProxyService 的改动涉及 startVpn 重写 + monitorNativeTunnel 重写 + writeTProxyConfig 删除，实际远超"40-60 行"）。

**R4. xtunnel 写队列：01 摘要"创建时 4096 后重分配，最终 16384"会误导成"实现动态 resize"。**
- 源码 `golib/xtunnel/pool.go:551` = `queue := make(chan writeJob, writeQueueSize)`，且 `writeQueueSize = 16384` 是常量（pool.go:396）。"4096→16384"是 progress.md 里的**历史调参记录**，不是代码里的重分配。Java 移植应直接开 16384 容量，不要实现 resize。

**R5. 全体遗漏：DoH / DNSSEC 认证。**
- 任务清单要求审计"DoH 签名头"一致性，但 Go 实现（`golib/shared/dns/doh.go`）**不做任何 DNSSEC/响应签名校验**（RFC8484/JSON 响应按明文信任；no DNSSEC validation）——05/04 均未提。这是**特性上的一致点**（Java 也不该加校验，加了反而不一致/变慢），但必须写进实现约束，否则 Java 端天然想用 OkHttp+证书校验/dev 环境自签 DohTLS 时可能引入与 Go 不一致的行为。属"报告共同遗漏、Java 易画蛇添足"项。

### 1.2 协议比特级易错点（Top 点位，Java 最易写错）

方向看，**GCM 协议极薄（2 字节头），风险集中在连接层；x-tunnel 协议重，风险在帧编解码与时序**。按"写错即服务器不兼容/丢包"排序：

1. **WS 客户端 masking（最高）**：RFC 6455 规定**客户端→服务器帧必须掩码**（随机 4 字节 key，`payload[i] ^= mask[i%4]`）。Gorilla/OkHttp 自动做；若走 04 的路线 a/e1（自研 WS，300–500 LOC），**漏掩码服务器直接拒**。这是自研 WS 第一坑，02/04 都只提"帧头解析/mask"带过，未把"掩码是强制且随机、每帧新 key"设为验收项。
2. **GCM fallbackip 不做 URL 编码 vs xtunnel client_id/ch_id 必须 URL 编码——两者相反**。源码证实：GCM `buildWSSURL`（connection.go:434-442）用 `fmt.Sprintf` 手拼，**不编码**；xtunnel `dialer.go:29-31` 用 `url.Values.Encode()` **编码**。Java 若统一用 `UriUtils.encodeQuery` 会破坏 GCM，若复制粘贴把编码逻辑混用会破坏 xtunnel。属"协议内两处相反、最易抄错"。
3. **GCM CONNECT 载荷 `host:port|`**：无长度字段，帧=整条 WS Binary 消息；IPv6 `[v6]:port|`；`|` 结尾。Java 若追加长度前缀或漏 `|` 即全盘错。02 已精确，但需强调"帧边界=WS 消息"而非自造 TLV。
4. **xtunnel 8 字节大端头 + 长度溢出**：`[t:1][idLen:1][metaLen:2][payloadLen:4]`，解码须按 uint64 计算总长防 32 位溢出（`EncodeMessage` 对 `len(connID)>255` 截断为 255）。Java 的 int 是 32 位，**读 payloadLen 必须用 long 做加法再 cast**，否则大 length 字段会整型回绕。02 已提，列为必测。
5. **双向通道竞争顺序**：`selectDownlink` 用 `CAS(&downlink,0,chID)` 首次抢占；TCP 场景回 `MsgSelectDownlink` 走 **uplink 通道**，UDP 场景走**当前通道自身**——两处去向不同（02 §2.2）。且在 Hot Pair prebind（target=`x-tunnel.prebind`）时 `selectDownlink` **主动让位**强制 uplink≠downlink。Java 若统一"都走 uplink"会与服务器下行竞争语义不符。
6. **UDP associate 帧格式**：xtunnel `buildSOCKS5UDPPacket` / hev session-udp 发出 `[RSV:2][FRAG:1][ATYP][ADDR][PORT][DATA]`；UDP-in-TCP 是 `[datlen:2][hdrlen:1]...`。03 提到 wire 格式，但 03 与 02 都未给出可判定的"Java TUN forwarder 发出的 SOCKS5 UDP 帧必须与 Go 后端字节一致"的契约——**这是 TUN 转发器与 Java SOCKS 后端之间的内部契约，六份报告都没锁死**（见 R6 架构遗漏）。
7. **ConnStatus meta[0]=0/1**：GCM 用 TYPE=1(CONNECTED) 无 data；xtunnel 用 MsgConnStatus meta[0] 区分 OK/ERR。两协议的"建链应答"编码不同，移植别混。
8. **心跳数值**：GCM Ping 15s / Pong 超时 3s / 读 deadline 18s（=15+3，connection.go:1002）；xtunnel Ping 5s / 读超时 15s。02 已给，需锚定"写超时兜底 5s（defaultWebSocketWriteTimeout，connection.go:29）"。
9. **WS 关闭帧**：xtunnel Shutdown 发 `CloseMessage(1000)`；正常关闭帧判定 `IsNormalCloseError`（xtunnel/protocol/errors.go）。Java 要区分"正常关闭"与"异常"以决定是否重连。

### 1.3 密码学细节（ECH 隐蔽失配点）

04 的密码学梳理质量高（HPKE info="tls ech\x00"+config.raw、nonce=base_nonce XOR seq、AAD=ClientHelloOuterAAD 将 ECH 扩展置零、accept_confirmation=HKDF-Expand-Label(HKDF-Extract(0,inner.random),"ech accept confirmation",transcript(innerCH‖修改后ServerHello),8)、HRR 用 "hrr ech accept confirmation"）。对抗性补充三个 Java 端最易错点：
1. **AAD 的"置零"边界**：outer CH 中 ECH 扩展的 payload 整体置等长全零，但**扩展头/长度字段保留**。自研 TLS13 时若把整段（含 type/len）清零，解密校验全错。
2. **nonce 序列号**：第一次 Seal 时 seq=0，后续每条 outer CH 用**新 HPKE context、新随机 inner.random 与 key_share**——每条 ClientHello 都是新 handshake，非复用。自研时若"缓存 context 复用 seq"会灾难。
3. **accept_confirmation 的 transcript 用"random 末 8 字节清零后的 ServerHello"**：不是全部 ServerHello，只是 random 的后 8 字节清零。这个"只改 random 不清 header/其他字段"极易写成"改整个 random"或"用全量 transcript"。
- 结论：这些只有"抓包对拍 Go 版"能发现，04 的路线 a（自研 TLS13）把这 800–1500 LOC 的 ECH 逻辑风险标 H 是客观的；**建议任何路线都必须先建 ClientHello 字节级对拍基线**。

### 1.4 Android 碎片化（minSdk24→34 影响实现的点）

1. **TLS1.3 平台栈需 API 29+**：平台 SSLSocket 的 TLS1.3 支持是 API 29 引入的；minSdk24 设备（虽然少，但有）平台栈只能 TLS1.2。而 Go 侧 GCM **强制 MinVersion=TLS1.3**（connection.go getTLSConfig）。→ 04 §7 point 6 已承认：minSdk24 上若走平台栈，**连接直接失败**，除非降低 minSdk 或引入 BC bctls/自研。这是一个真实的**功能一致性断裂**，不是"稍后再说"。
2. **X25519 / HKDF JCA 需 API 31+**：minSdk24 下 ECH 自研的 X25519/HKDF 必须走 BouncyCastle lightweight API（04 §2.3 已证）。这条影响"BC 依赖是否可接受"的决策。
3. **VpnService 行为随版本漂移**：establish().getFd()、setBlocking、MTU、DNS 代理语义在不同 API 有细微差异（尤其 26+ 后台限制、29+ 的网络能力细节）；03/05 覆盖了 fd/Os.poll，但未系统整理 minSdk24–34 之间 VpnService 的事件差异。属"真机矩阵问题"。
4. **后台/前台服务限制（API 26+，targetSdk 34）**：Java 化把大量线程移到 :vpn 进程，若进程被系统冻结/杀，Java 无 hev 那样独立原生线程的兜底；AGP targetSdk34 的 FOREGROUND_SERVICE 约束需保持。
5. **android.system.Os.poll(API21+) 等待 tun fd**：05 §2.1 方案可行且无 JNI，但注意 StructPollfd 每轮 new 对象（热路径零分配目标下要池化），且 poll 返回后仍需消费到 EAGAIN。05 已合理覆盖。

### 1.5 性能最坏情况（六份报告都偏乐观）

1. **TCP 海量短连接/连接表**：hev 的 lwIP 池 `MEMP_NUM_TCP_PCB=4096`、`TCP_SEG=8192` 有硬上限；Java 若用 `HashMap<tuple,state>` 无上限或线程/任务失控，几千连接即顶到内存/调度墙（05 §5 线程数警告）。**必须显式连接表上限 + 与 hev 4096 对齐或更小**，且最低在 C-TUN 过渡期沿用 hev 上限。
2. **UDP 洪泛**：hev 每流帧队列 512、超限丢包；Java SOCKS5 UDP 若复制 05 的"每包新对象"，512 池本身的 GC 压力放大。UDP block 443 也需复刻（QUIC 拦截）。
3. **DNS 放大/缓存无容量上限**：DNS 缓存 key=`domain:type`、TTL 5min、cleanup 1min，**无容量上限（仅 TTL 淘汰）**。Go 是 map 天然无界，但 Java 若 plain `ConcurrentHashMap` 在攻击/随机域名下可被撑大；且 DoH 预热 40 域名×2×并发 8 在弱网会放大 DoH 流量。Java 应加容量上限（这是与 Go 的**有意的**差异点，需确认可接受）。
4. **背压失效 OOM**：8MB 全局 + 16384 槽如果 Java 用"每 job 一个对象 + 拷贝载荷"而非"存引用+池化 job"，8MB 预算会因对象头/副本虚增 2–3 倍 → 背压前先 OOM。05 §7 已列为中风险，需升级为硬性实现约束。
5. **writeWorker 帧聚合 maxAgg=256KB（ReadBuffer*4）**：Java 若不做聚合，WS 帧数激增，服务器与 CPU 都被打爆；02/05 都提了，必须实现并测"帧数下降"。

### 1.6 并发陷阱（Go→Java 语义丢失）

1. **select 多路等待**：Go `select { case <-chA: case <-chB: case time.After: }` 无直接 Java 等价；用 `CompletableFuture.anyOf` + 定时器可实现，但**醒来后必须显式判"空 channel 关闭/已完成"**，否则误读。
2. **channel close 语义**：Go 关闭 channel 后 receive 返回零值（可用来广播停止）；Java 无此语义，必须用显式 `closed` volatile + `synchronized` 通知，否则"停止信号"会丢失导致后台循环永挂。
3. **defer 清理**：Go `defer conn.Close()` 在函数出栈/panic 都执行；Java 必须 try/finally 且**防 panic/exception 中断 finally**。多路 goroutine（下行队列 goroutine + 读 goroutine）时**双 close** 极易（wsConn.Close 二次调用、channel close 二次 panic）。Go 用 `sync.Once`/CAS，Java 用 `AtomicBoolean compareAndSet` 防重入——01 提到 SOCKS5 cleanup 用原子 CAS，Java 端所有清理点都要如此。
4. **背压 resume 通道**：`waitForBackpressure` 等 3s、resumeCh 容量 1；Java 若用 `Semaphore` 或 `Condition`，要复刻"仅一条 pending 通知、Pause→SlowDown 降级后新 Data 仍能续发"的语义，否则恢复时多线程同时抢导致重复发送/丢序。
5. **竞态窗口**：`selectDownlink` CAS、`reserveQueueBytes` CAS、`readyChannels` atomic——Java 的 `AtomicLong/AtomicInteger.compareAndSet` 可等价，但**必须全用 atomic 而非 synchronized**，否则吞吐掉、且与 Go 的"无锁热路径"行为不同。
6. **通知丢失**：Go 用容量 1 的 channel 做"邮箱"（如 resumeCh、chReadyCh），重复 notify 只保留一个；Java 若盲目 `signal()` 可能丢 pending 状态或通知错线程。**建议全部用"位标志 + 一次唤醒循环"模式复刻容量-1 语义**。

### 1.7 报告共同遗漏 / 悬而未决的架构契约

**R6（最重要）：目标架构本身摇摆**。stated goal 是"全 Java 替换 golib + hev（含用户态 TCP）"；但 03 结论、05 §9、06 风险表都建议"保留 C TUN 转发器过渡，只把 SOCKS+协议栈迁 Java"。两份报告的建议其实把**范围从"全 Java"悄悄改成了"半 Java"**，而项目背景要求"全部用原生 Java 替代"。这个矛盾没被任何报告升级为"需要用户拍板的第一决策"。**它决定整个工程量级（±3000–5000 行用户态 TCP 与 ±工期 1–2 人月）**，必须先定。
**R7：TUN 转发器 ↔ Java SOCKS 后端的内部 wire 契约未锁定**。hev 产生的 SOCKS5 客户端流量（CONNECT 的 `127.0.0.1:1080`、UDP ASSOCIATE 帧）与 Java SOCKS 后端的字节契约，六份报告没有一份给出可测的验收接口。GCM 后端只支持 CONNECT（UDP 回 0x07，已实读 server.go:176-180 证实），xtunnel 后端支持 UDP ASSOCIATE 但不支持 FWD UDP——**这意味着 GCM 下 UDP 在数据面根本不通**（03 已发现）。Java 移植若想"修好"UDP 是改变行为；若照抄则 GCM 用户 UDP 仍不可用。**这是一个"功能是否要保住"的产品决策，报告未要求拍板。**
**R8：没有任何真实/测试服务器可做一致性验证**。02 列 12 条、04 列 ECH 联调前置，都指向"需要服务器侧确认"，但**没有任何报告确认这些服务器是否可用/可租**。没有服务器，比特级协议全等、ECH 语义、relay 评分、背压真实触发都无从验收。这必须由用户在开工前列为 gate。

---

## 2. 静默功能丢失清单（对照 01 功能盘点，最易悄悄丢掉）

| # | 功能 | 丢失后用户可感知影响 | 检测难度 | 验证手段 |
|---|---|---|---|---|
| S1 | **动态测量间隔/测速节流**（xtunnel relay 健康分数驱动 15/30/60s 测速间隔 + 域名→Top2 选择；GCM relay 批量测速取 Top2） | 节点选择退化：拿到慢/死节点，首包延迟与成功率下降 | 高（时序行为） | 对拍 Go：长跑观察横竖 relay 切换时间戳 |
| S2 | **ECH 缓存 TTL 24h + 定时刷新 12h + singleflight 合并 + 冷启动预注入** | 多连接并发触发重复 DoH 查询、服务器偶发拒 ECH、冷启动抖动 | 中（需打日志） | 抓包数 DoH 请求数；核对缓存命中 |
| S3 | **DNS 预热（40 域名并发 8/总超时 15s）与 DoH warmup 后启动** | 首开网页/视频变慢（缓冲未预热） | 中 | 计时首请求 TTFB；抓 DoH 时序 |
| S4 | **GCM pool 后台 6 循环**（maintain1s/cull5s/stats10s/heartbeat15s/rate1s/congest1min/动态池1min） | 连接池补给/回收失灵→池空、拥塞窗口永不调整、动态池失效 | 高 | 对拍连接数曲线、窗口调整日志 |
| S5 | **quality_monitor.go（ConnectionQualityMonitor）**——超级易漏 | Android 路径 Go 从不实例化，**属于死代码**；但 UI 假定"quality"存在 | 低（本就不跑） | **明确不进迁移范围**，但需产品确认不启用 |
| S6 | **代理 DoH 的 proxy_transport.go（EnableDoHProxy）** | 恒 false，死代码 | 低 | 同 S5，排除 |
| S7 | **relay 恢复逻辑**（失败≥3 移出最优表、ForceRescore 有 1min 防抖但成功后重新列入、重试时排除 lastIP） | 节点池长期停留在坏死表、无法自愈 | 高 | 注入虚假失败对拍恢复时间 |
| S8 | **背压 SlowDown 10ms/帧 + Pause 3s 降级 + 8MB 预算 + 写队 500ms 超时** | 高负载上传断连/卡死（07 真机调参正为此） | 中 | 构造突发上传，测是否 3s 后恢复正常而不 OOM/断连 |
| S9 | **GCM 窗口流控/拥塞 AIMD**（sendWindow/1MB、AggregateWindow、RTT>2×基线 或 丢包>5%→减半） | 慢网络下吞吐崩塌或窗口暴涨吞内存 | 高 | 低带宽对拍吞吐曲线 |
| S10 | **GCM 心跳退休**（Ping 15s/Pong 3s 超时 RetireConnection）+ 读 deadline 18s | 死链不释放、连接池泄漏 | 中 | 断服务器观察连接回收 |
| S11 | **日志环形缓冲语义**（2000 行/256KB、UTF-8 安全截断、scope、每次 start 清空、格式 [HH:mm:ss] [L] [scope] msg） | 用户日志格式变乱/截断乱码/scope 丢，影响排障与 copy 出去的内容 | 低 | 单测 + 与 Go logger.go 逐条比 |
| S12 | **时区同步 setTimeZone**（Go time.Local） | Java 化后变空操作；若 Java 日志用系统时区则无感 | 低 | 确认行为；若删方法需保证 :vpn 双进程日志一致 |
| S13 | **重连退避**（xtunnel fast retry 100ms+rand 抖动/窗口1s/连失3退出；指数 3s×1.5 封顶 60s；20 次后慢速 60s） | 短促断线风暴 vs 长离线探测节奏全变；服务器侧重连风暴 | 中 | 注入断线，观察重连间隔序列 |
| S14 | **空闲检测/超时**（TCP idle 5min、UDP idle 60s、SOCKS 握手 10s、TunnelTimeout 1min） | 连接泄漏/半开连接堆积 | 中 | 压力短连接观察内存/连接表 |
| S15 | **流量统计 traffic_counter（字节/瞬时速率/峰值）** | 关闭日志里缺吞吐；若 UI 依赖则信息缺失 | 低 | 对拍 GetSnapshot 数值 |
| S16 | **流 ID 位图 + 亲和 targetToConn + 空闲池 QualityScore 降序** | 负载不均、同目标不亲和导致连接抖动 | 中 | 对拍连接分配序列 |
| S17 | **DoH 服务器轮转 lastServerIdx + 每服务器独立 timeout + 仅 TLS/conn/EOF 重试** | 一个慢服务器耗尽总预算，DNS 全部超时 | 中 | 压制单服务器观察 failover 顺序 |
| S18 | **geoip/geosite 文本格式解析**（routing matcher：private 前缀 + geoip_cn 5822 行 + geosite_cn 6410 行，go:embed→assets） | 域名/IP 路由绕过判断错误，流量误走代理或误直连 | 低（格式简单） | 单测 + 对拍 matcher 判定 |

---

## 3. 风险登记表（id / 类别 / 可能性 / 影响 / 描述 / 缓解 / 阶段 / 验证）

| ID | 类别 | 可能性 | 影响 | 描述 | 缓解措施 | 阶段 | 验证方式 |
|---|---|---|---|---|---|---|---|
| X1 | 范围/架构 | H | H | 目标架构在"全 Java 含用户态 TCP"与"C TUN 过渡"间摇摆，决定 ±3000–5000 行与 ±1–2 人月 | 第一决策：产品先定全 Java 还是含 C shim | P0/Phase3 | 拍板纪要 |
| X2 | 协议 | H | H | 自研 WS 漏 masking / 掩码 key 复用 → 服务器拒帧 | 强制掩码验收项；复用量产 WS 库（OkHttp）优先 | Phase4 | 抓包对拍 |
| X3 | 协议 | H | H | ECH 错误串匹配照抄 Go（GCM 端本身可能不触发）→ 降级语义失效 | 改用错误类型+重试计数，不抄字符串 | Phase4/6 | 强 ECH 服务器对拍 |
| X4 | 密码 | H | H | ECH AAD/transcript/nonce seq 一处错全盘解密失败 | 建 ClientHello 字节级对拍基线；自研必测 | Phase6 | ttls/抓包对拍 |
| X5 | Android | H | H | minSdk24 平台栈无 TLS1.3；GCM 强制 1.3 → 低版本设备**连不上** | 需引入 BC bctls/自研或接受 TLS1.2 降级决策 | Phase3 | minSdk24 真机 |
| X6 | 并发 | M | H | channel close / 双 close / select 语义丢失 → 死锁或泄漏 | AtomicBoolean CAS 防重入；显式 closed 标志；try/finally | Phase4/5 | 并发压力 + 注入测试 |
| X7 | 性能 | M | H | 背压 job 非池化/载荷拷贝 → 8MB 预算虚增 OOM | job 池化 + 存引用不拷载荷 | Phase4/5 | 背压压力测试 |
| X8 | 性能 | M | M | TCP/连接表无上限 → 海量短连接内存爆 | 显式连接表上限（对齐 hev 4096） | Phase5 | 负载测试 |
| X9 | 数据一致性 | M | M | 无真实服务器，比特级全等/ECH/relay/背压无法验收 | 开工前列为 gate；准备测试服务器 | P0 | 服务器联调 |
| X10 | 依赖 | M | M | ECH 路线 b（GuardianProject fork 2022 陈旧）供应链/漏洞风险 | 仅试用；长期用 e1 或 a | Phase6 | 安全评审 |
| X11 | 依赖 | M | M | 引入 BouncyCastle 影响包体积/R8/ART；需确认许可与裁剪 | P0 拍板是否接受 BC | Phase3 | R8 后体积+ART 冒烟 |
| X12 | 协议 | M | M | GCM 数据面 UDP 不通（后端仅 CONNECT）；Java 要不要"顺手修好" | 明确"保持行为"还是"改进" | P0 | 产品拍板 |
| X13 | 集成 | M | L | 06 引用 AAR 依赖删除后 gradle configuration-cache 冲突 | 首次 `gradlew clean` | Phase7 | CI 冒烟 |
| X14 | 集成 | M | L | :vpn 多进程 + MODE_MULTI_PROCESS 的 killProcess 误删 → 旧 SP 缓存坑复现 | 代码审查锁定 onDestroy killProcess | Phase7 | 真机重启 |
| X15 | 逻辑 | L | M | GCM ECH 降级计数用 atomic，Java 漏用 → 竞态 | atomic 全覆盖 | Phase6 | 并发测试 |
| X16 | 逻辑 | L | M | DNS 缓存 Java 无容量上限 → 随机域名撑大 | 显式容量上限（与 Go 有意差异，需确认） | Phase4 | 攻击测试 |
| X17 | 逻辑 | L | L | 心跳 18s/写超时兜底 5s 数值错 | 对齐常量表 | Phase4 | 单测 |

---

## 4. Top 10 风险（附排序理由）

1. **X1 架构摇摆（全 Java vs C TUN 过渡）**——第一决策，决定量级与一切后续排期。若坚持全 Java，X5 与用户态 TCP 是高危前置；若接受 C-TUN 过渡，风险面骤降一半。**排在 No.1 因为它不是工程问题是方向问题。**
2. **X4 自研 TLS13+ECH（路线 a/e1 的密码学与状态机）**——4000–6500 LOC 且需要字节级对拍；06 无现成 Java 先例，正确性极难收敛。若 ECH 可延后则降到 No.5。
3. **X5 minSdk24 上 TLS1.3 断链**——功能一致性最直接的硬断裂，直接影响兼容面，且被 04 归为"待拍板"而非已解决。
4. **X2/X3 协议 bit 级与 ECH 语义**——GCM fallbackip 不编码 vs xtunnel 编码、WS masking、ECH 降级字符串匹配失效，都直接造成服务器不兼容。
5. **X6 并发语义丢失（channel/select/defer/双 close）**——Go→Java 最隐蔽，出 bug 即死锁/泄漏/丢包，单测难覆盖。
6. **X7/X8 背压与连接表内存/性能崩溃**——无原生 lwIP 上限保护，Java 手动管理最易在 +300Mbps/3000 流时崩。
7. **X9 无真实服务器验收**——没有 gate，上面所有 bit 级结论都是纸面，工期必然返工。
8. **X10/X11 依赖风险（GuardianProject fork / BouncyCastle）**——引入第三方安全敏感组件需拍板与评审。
9. **R5 全体遗漏的 DNSSEC 不做校验**——不是高风险，但易画蛇添足引入不一致，列为一致性约束。
10. **S11/S12 运行时日志/时区这些"低风险但易被当成可丢"项**——对用户排障与既有 UI 契约有实感影响，且最容易在"反正功能没变"心态下被悄悄改坏格式。

---

## 5. 需要用户拍板的决策问题清单

1. **ECH 是否允许分阶段交付（延后一个版本）？** Metro：若同意→强烈建议阶段一（无 ECH）+ 阶段二路线 e1（vendor bctls+ECH 补丁）；若要求一期全等→必须选 b（GuardianProject fork）或 d（BoringSSL C shim）快速铺底，纯 Java 自研（a）太慢。
2. **是否接受保留 C TUN 转发器（hev-socks5-tunnel）的过渡方案？** 即"先只把 SOCKS5/HTTP + 协议栈迁 Java，用户态 TCP 留 C"。这直接决定±3000–5000 行用户态 TCP 的去留；若接受，则"全 Java"被改为"半 Java"，需明确这仍是可接受的交付形态。
3. **性能验收阈值定多少？** 建议：下行≤150 Mbps / 并发≤2000 流 / 小包抖动<30ms / 长稳≥6h 为"可达标线"；>300 Mbps / 3000 流则必须保留原生数据面。低于 0.7×目标即切回过渡方案。
4. **是否有可用的真实/测试服务器（GCM worker + x-tunnel server，且可发布 ECH 配置）做一致性验证？** 没有则开工即不可验收，必须列为前置 gate。
5. **BouncyCastle 依赖是否可接受？** bcprov-jdk18on 1.80，纯 Java ECH 路线必需；确认许可、R8 裁剪、ART 兼容、体积。
6. **是否接受 GuardianProject Conscrypt fork（2022 底、无维护）作为临时 ECH 依赖？** 仅建议试用验证，不建议长期生产；若 reject，则 ECH 只能自研/等 upstream。
7. **APK 从 4 ABI 变 1 个 universal 的影响？** 体积减 60-85%、单包可装所有架构，但需确认：Play/发布渠道是否要求 x86 支持、CI release 产物形态、以及去掉 per-ABI 后低端设备兼容策略。
8. **GCM 数据面 UDP 目前不通（后端仅 CONNECT），Java 版是否要顺手支持 UDP？** 是"保持行为一致（仍不通）"还是"改进为支持"——两者工作量与对外协议完全不同。
9. **minSdk24 上 TLS1.3 覆盖的取舍**：接受低版本设备走 TLS1.2，还是把 minSdk 抬到 29，还是引入 BC bctls 保证 24 也 1.3？
10. **DNS 缓存容量上限**：Go 版无上限（仅 TTL 淘汰），Java 是否允许加显式容量上限（有意的安全差异）？

---

## 6. 结论

**结论：有条件可行。**

**一句话总结**：工程上有付出但整体可行，前提是——接受"C TUN 转发器过渡 + ECH 分阶段 + 性能目标≤150Mbps/2000 流"，并把"真实服务器验收"列为开工 gate；若坚持"全 Java 用户态 TCP + 一期 ECH 全等"，则降级为不建议。

**三条核心论据**：
1. **协议面（可移植性强）**：GCM 服务器可见协议极薄（2 字节头），x-tunnel 虽有 8 字节头状态机但帧格式清晰、无专有密码学，比特级可复刻；真正的坑在连接层细节（fallbackip 编码、WS 掩码、ECH 语义、IPv6/端口文本）——均为"可对拍、可验收"的确定性工作，而非不可逾越。
2. **风险集中在"自建系统"而非"协议理解"**：用户态 TCP（Java 3000–5000 行，正确性极难）、自研 TLS13+ECH（密码学状态机）、Go 并发语义丢失——这三者正是 C-TUN 过渡与 ECH 延后可以直接规避的；因此"有条件"的"条件"就是：接受范围切分与依赖引入。
3. **存在明确的兜底开关**：真机基准若下行<0.7×目标或 CPU>80% 或 GC 尖峰>30ms，即退回 C-TUN 过渡方案，Java 侧仍保住协议栈价值；风险可量化、可回退，而非孤注一掷。

**给评审的一句话警告**：不要被"App 仅改 3 文件 / 调用仅 7 处 / 协议仅几行"的乐观盘点麻痹——这些是**集成面**的乐观，掩盖了**数据面与连接语义**的真正复杂度；风险登记表中 X1/X2/X4/X5/X9 项若不能在产品层面先拍板，任何 Phase 3 的"细化设计"都会建立在流沙上。
