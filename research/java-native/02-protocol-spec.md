# X Client 对外协议规格书（比特级兼容）

> 面向对象：Java 原生实现者（替代 golib + hev-socks5-tunnel 组合）
> 来源：golib/ 全部 Go 源码逐行提取，服务器可见行为以源码为准。
> 结论先行：两类协议的"对外（服务器可见）协议"集中在极少数文件，绝大多数 golib 代码是客户端内部状态机/负载均衡，**不产生线上协议字节**。Java 实现必须逐比特复刻的仅是被标记为"★服务器可见"的部分。

---

## 0. 调研结论（先结论后证据）

1. **GCM 是客户端定义的轻量协议**：完整服务器可见协议只有 2 字节头 `[STREAM_ID:1][TYPE:1][DATA]`，外加 WebSocket 连接层的 URL 路径/query、TLS SNI、Host 头、UA 头。它是一次写死进 worker 的实现，worker 侧无版本协商。
2. **x-tunnel 是一个完整的二进制多路复用协议**：8 字节头帧格式、3 类背压状态、双向通道竞争（SelectUplink/SelectDownlink）、预绑定（prebind）与 Hot Pair、"fast retry" 客户端重连机制、"IP strategy" 目标解析。服务器可见部分比 GCM 大得多。
3. **连接建立时序是兼容性关键**：GCM 乐观回复 SOCKS5、先注册 handler 后发 CONNECT；x-tunnel 先广播登记再等待服务器回 SelectUplink/ConnStatus。Java 必须复刻这些时序，否则会丢包/超时。
4. **多处值硬编码/写死**（UA、handler 超时、退避序列等）——这些直接影响服务器可观测行为，已全部列出为"不变量"。

网络调研（web_search/fetch_content）工具在本子代理环境中不可用，本报告仅基于仓库源码。凡涉及"需向服务端确认"的行为在 §4 疑问清单单列。

---

# 1. GCM 协议规格

## 1.1 消息帧格式（★服务器可见）

来源：`golib/gcm/protocol/message.go`

- 头固定 **2 字节**，`HeaderSize = 2`，大端（字节序天然，因每字段恰好 1 字节）。
- `[STREAM_ID:1][TYPE:1][可选 DATA]`。
- 帧类型（`MessageType`，均 `uint8`）：
  - `MsgTypeConnect = 0`：发起连接。DATA 为 ASCII 文本 `"host:port|"`。
  - `MsgTypeConnected = 1`：连接建立成功，**无 DATA**。
  - `MsgTypeData = 2`：数据。DATA 为任意二进制。
  - `MsgTypeClose = 3`：关闭流，**无 DATA**。

编码（`Encode()`，message.go:42-48）：逐字节写 StreamID、Type，后接 Data 原样。**无长度字段**，帧边界由上层 WebSocket 二进制消息（message frame）天然界定——即**一条 WebSocket Binary 消息 = 一个 GCM 帧**。Java 端读 WS 消息后直接取 `data[0]`、`data[1]`、`data[2:]`。

解码（`Decode()`，message.go:52-60）：`len(data) < 2` 拒绝。

## 1.2 CONNECT 载荷：`"host:port|"`（★服务器可见）

来源：`NewConnectMessage`（message.go:65-69）与调用处。

- 格式：`fmt.Sprintf("%s:%d|", host, port)`，`host` 为字符串，`port` 为 `uint16` 十进制，以 ASCII `|`（0x7C）结尾。
- **端口范围**：`uint16`（0–65535），十进制无前导零。
- **IPv6 表示**：取决于 host 传入形式。SOCKS5 服务器（`shared/socks5/server.go`）对 IPv6 目标已用 `[v6]` 包裹（`resolvedHost = fmt.Sprintf("[%s]", resolvedHost)`，server.go:318-320），随后 CONNECT 载荷中的 host 即为 `[v6]` 形式 → 载荷形如 `[2606:4700::1111]:443|`。即 **IPv6 用方括号包裹**，与 Go `net.JoinHostPort` 语义一致。
- Java 实现要点：`"[IPv6]:port|"` 与 `"host:port|"` 两种形式都要能产出/解析；`port` 恒为十进制。

CONNECT 的 host 来自哪里（SOCKS5 路径）：
- `shared/socks5/server.go: handleRequest` 解析 ATYP（1=IPv4, 3=DOMAIN, 4=IPv6）。
- DOMAIN 时若 `cfg.EnableDoH`，会用 DoH 预解析成 IP（`ResolveAny`），`resolvedHost = ip`；否则保留域名。**因此 CONNECT 载荷中既可能出现 IP 也可能出现域名**（取决于 DoH 是否解析成功与配置）。
- IPv4/IPv6：直接取字节转点分/冒号文本。
- 版本/命令不合法时的错误应答见 §1.6。

## 1.3 流 ID 生命周期（部分服务器可见：STREAM_ID 字段值）

来源：`golib/gcm/pool/stream_manager.go`

- **分配**：`findFreeBit` 用 256 位位图（`allocBitmap [4]uint64`）从 `nextHint` 开始循环扫描，O(1) 找第一个空闲位。`nextHint` 每次分配后置为 `(id+1)%256`。**流 ID 是环形复用的 byte（0–255）**，上限 256 个并发流。
- `MaxStreamsPerConnection` 默认 5（`DefaultConfig`），故实际并发流远小于 256；上限 256 只是 ID 空间。
- **状态机**（StreamState）：`Idle → SynSent（已发 CONNECT）→ Established（收到 CONNECTED）→ FinWait（收到/发 CLOSE）→ Closed`；SynSent 可直接→Closed；Established→FinWait/Closed。
- **清理**：`UnregisterStream` 调用 `Handler.OnCleanup`，从 map 删除，`clearBit` 释放 ID，`conn.Streams--`。连接关闭时 `HandleConnectionClose` 回调所有 `OnClose`、清空 map 与位图、`nextHint=0`。
- **流与连接池（服务器可见部分微弱）**：`GetConnectionWithStream` 依次尝试空闲池 → 目标亲和连接 → 活跃中流最少连接 → 新建。这是客户端调度，非协议字节，但决定同一 WS 上多个 CONNECT 的 STREAM_ID 分配顺序，间接影响服务器看到的帧序列。

## 1.4 WebSocket 拨号与 URL（★服务器可见）

来源：`golib/gcm/pool/connection.go`

### URL 精确格式
`buildWSSURL()`（connection.go:396-402）：
```
wss://<WorkerHost>/<UserID>?fallbackip=<ProxyIP>
```
- **无 query 时**（`ProxyIP==""`）：`wss://<WorkerHost>/<UserID>`（**无尾 ?**，Go `url` 拼接逻辑）。
- **有 ProxyIP 时**：`?fallbackip=` 为唯一 query 参数，`ProxyIP` **原样追加，不做 URL 编码**（Go `fmt.Sprintf` 直接拼接）。`ProxyIP` 是"优选的中转 IP:端口"（在 Android 侧由 TProxyService 解析 fip 传入）。
- 路径 = `/` + `UserID`。UserID 若含特殊字符不做转移（按字符串直接拼）。**Java 需复刻"不编码 fallbackip"这一行为**；若 Java 用 `URI` 自动编码参数会破坏兼容，应手动拼接。
- createConnectionWithRelay 分支也是同一 `wss://Worker/UserID` 形式（省略了 fallbackip，因为走中转节点）。

### 连接层头（★服务器可见）
- **直连模式**：TCP 连接 WorkerHost，但 URL 保持 `wss://WorkerHost/UserID`。
- **中转（relay）模式**：`NetDial` 把 TCP 连到 `relay.IP:relay.Port`，但 **URL 仍是原始 `wss://WorkerHost/UserID`，TLS SNI/ServerName 仍是 WorkerHost**。即只有 TCP 层走中转，HTTP 层 Host 与 SNI 都指向 WorkerHost（见 `getTLSConfig` 用 `p.cfg.WorkerHost`）。
- 请求头（★服务器可见，`createConnectionSync` 与 `createConnection` 一致）：
  - `Host: <WorkerHost>`
  - `User-Agent: Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36 Edg/109.0.1518.140`（**写死字符串**，Java 必须原样，这是服务器指纹）
- WebSocket 握手：`HandshakeTimeout = cfg.GetConnectionTimeout()`（默认 1s），`NetDial` 直连/中转均带 `DialTimeout = cfg.GetConnectionTimeout()`。
- 并发保护：`createConnection` 用 goroutine + channel 实现 `2×ConnectionTimeout` 外层超时（2 秒默认）。Java 需实现等价的总超时。

### TLS 参数（★服务器可见）
来源：`getTLSConfig`（connection.go:407-433）
- `MinVersion = tls.VersionTLS13`（最低 TLS 1.3）。
- `ServerName = WorkerHost`（SNI）。
- ECH 启用且未降级时：通过 `echManager.GetTlsConfig(WorkerHost, useECH)` 设置 `EncryptedClientHelloConfigList`。

### fallbackip 在协议层的角色（★服务器可见）
- fallbackip **仅是 URL query 参数透传给 worker**，客户端不解析其内容做 DNS/直连。
- 客户端真正决定 TCP 去往哪里的逻辑是 relay 选择：无 relay 节点→直连 WorkerHost；有 relay→TCP 连 relay。**往返/切换顺序不是由 fallbackip 驱动**，而是 relay manager 的评分。
- Java 必须把 `fallbackip` 当作不透明字符串拼进 URL，**不要本地解析或改写**。

## 1.5 ECH 生效与降级（服务器可见：ClientHello 是否含 ECH）

来源：`golib/shared/ech/manager.go`、`golib/gcm/pool/connection.go`

### ECH 配置获取顺序
1. **DoH 优先**（多服务器 fallback），失败回退 **UDP DNS 8.8.8.8:53**（`ResolveHTTPSUDP`），再失败回退标准 TLS。
   - 共享 ECH 管理器 `dohFunc`：`dohClient.GetECHConfig(domain)`；失败再 `udpFunc(domain)` → `ResolveHTTPSUDP(domain, "8.8.8.8:53")`。
2. 缓存键 = `echDomain`（**不是** workerHost），TTL 默认 24h，singleflight。
3. `GetTlsConfig(domain, useEch)`：
   - `useEch=false` → 直接返回 `{MinVersion:TLS13, ServerName:domain}`（标准 TLS，**不报错**）。
   - `useEch=true` 但取配置失败 → **打日志并回退标准 TLS，返回 nil 错误**（manager.go:92-98）。即 **ECH 查询失败后继续明文/标准 TLS 连接，不终止**。
   - 取到配置 → 设置 `EncryptedClientHelloConfigList = echConfig`。

### 降级路径（GCM 特有）
`handleDialError`（connection.go:434-471）：
- 错误串含 `"ech"` / `"encrypted_client_hello"` / `"tls: handshake failure"` 视为 ECH 相关。
- 连续失败计数 `echFailureCount`；**≥3 次** → 启用降级 `echFallbackEnabled=true`，`echDisabledUntil = now + 5min`，此后 `getTLSConfig` 里 `useECH=false`（标准 TLS），持续 5 分钟。
- 未达 3 次 → 后台 `Refresh(ECHDomain)` 刷新配置后重试。
- **握手失败后是继续明文还是失败**：single dial 层面，一次 dial 失败即返回错误；重试会换/刷新 ECH。最终若持续失败 → 多次重试失败后走 `getTLSConfig` 的降级标准 TLS。整体语义：**ECH 失败不会阻塞连接，最终回退标准 TLS**。

## 1.6 服务器可见时序（★验收关键）

来源：`golib/shared/socks5/server.go`（GCM server 入口）

1. `handleAuth`（server.go:157-195）：读 `[VER:1][NMETHODS:1]+methods`，要求 `VER==0x05`；**无论客户端提供什么 methods，都回 `{0x05, 0x00}`（no-auth）**，不做鉴权协商。读/写超时 10s。
2. `handleRequest`（server.go:198-330）：读 `[VER CMD RSV ATYP]`。
   - `CMD != 0x01`（CONNECT）→ 回 `{05 07 00 01 00000000 0000}`（Cmd not supported，10 字节）。
   - ATYP 解析（IPv4=4B、DOMAIN=1B len + nB、IPv6=16B，端口 2B）。
   - ATYP 非法 → 回 `{05 08 00 01 00000000 0000}`（Addr type not supported）。
   - DoH 预解析域名（若启用）。
3. `createTunnel`（server.go:333）：
   - **先 `RegisterStreamHandler` 注册 handler，再发 CONNECT**——保证早期 CONNECTED 不被漏收。
   - **乐观回复 SOCKS5 success**（`{05 00 00 01 00000000 0000}`）——在等待 CONNECTED 之前就回复客户端，降低首包延迟（servery 侧注释"Optimistically acknowledge SOCKS after CONNECT is on the wire"）。
   - 用 `TunnelTimeout`（默认 1 分钟）限定"取连接 + CONNECT 握手"；`waitForTunnel` 等待 `connected` 或超时。
   - **连接建立前收到 CLOSE** → `RecordFailure` + cleanup（视为失败）。
   - 收 DATA 但下游队列塞满超 `downstreamQueueTimeout=2s` → 关闭流以保护 WS 读循环。
4. 数据面：
   - 上行：读客户端 32KB 缓冲 → 每块一个 `DATA` 帧（`NewDataMessage`）。
   - 下行：每条 DATA 帧写入本地客户端（`localClientWriteTimeout=10s` 写超时）。
   - 关闭：`cleanup` 主动发 `CLOSE` 帧（`NewCloseMessage`）再 `UnregisterStreamHandler` + `ReleaseConnection`。

### 心跳（★服务器可见：WebSocket Ping/Pong）
`messageLoop`（connection.go:575-...）：
- `PongHandler` 收到 Pong 后刷新读 deadline；收到任意 data 消息也刷新（"Application data is proof that path is alive"）。
- `heartbeatLoop` 每 `HeartbeatInterval`（默认 15s）遍历所有连接发 `websocket.PingMessage`；**若上次 Ping 发出后超过 `HeartbeatTimeout`（默认 3s）未见 Pong → 退休该连接**（视为死链）。
- 写超时 `writeTimeout = cfg.GetHeartbeatTimeout()`（3s），兜底 `defaultWebSocketWriteTimeout=5s`。
- read 超时初始/刷新 = `HeartbeatInterval + HeartbeatTimeout`（18s 默认）。

### 重连与退避（数值）
- GCM 连接池无重复拨号退避（失败由 `maintainLoop` 每秒补给）；`maintainPool`：池大小 < `currentMinPoolSize` → 立即创建新连接。
- `Reconnect(reason)`：关闭所有 WS，维护循环在新网络重建。
- relay 退避/重评：`testLatency` 用 `2s` TCP 拨号超时；`RelayMaxLatency=500ms` 阈值筛选；`RelayFailureThreshold=3` 次失败移除节点；`RelayRescoreInterval=10min`；`RelayForceRescoreCooldown=1min`；`calculateScore = latency(ms) + failCount*500`。这些都是客户端调度，非协议字节，但影响新建连接的 fallbackip/时序。

## 1.7 GCM 默认数值汇总（Java 端用）

来源：`golib/shared/config/config.go  DefaultConfig()`
```
ListenAddress :1080
MinPoolSize 5, MaxPoolSize 15, ConnectionTTL 5m, ConnectionTimeout 1s
RelayMaxLatency 500ms, RelayFailureThreshold 3, RelayRescoreInterval 10m, RelayForceRescoreCooldown 1m
DoHTimeout 3s, DNSCacheTTL 5m
EnableECH false, ECHDomain cloudflare-ech.com, ECHCacheTTL 24h, ECHRefreshInterval 12h
HeartbeatInterval 15s, HeartbeatTimeout 3s, EnableTcpNoDelay true
WarmupConcurrency 3, WarmupTimeout 30s
EnableAutoReconnect true, MaxReconnectAttempts 3, ReconnectDelay 1s
TunnelTimeout 1m
EnableDynamicPool true, DynamicPoolMaxSize 15, DynamicPoolLowThreshold 0.3, High 0.8
MaxStreamsPerConnection 5
DefaultWindowSize 1MB, MinWindowSize 64KB, MaxWindowSize 4MB, WindowTimeout 5s
CongestionControlInterval 1m
QualityCheckInterval 10s, QualityDegradeThreshold 60, SwitchCooldown 5m, MinDegradedCount 2
```

---

# 2. x-tunnel 协议规格

## 2.1 二进制帧头（★服务器可见，逐字节）

来源：`golib/xtunnel/protocol/protocol.go  EncodeMessage/DecodeMessage`

`headerLen = 8` 字节，**大端**：

| 偏移 | 字节数 | 字段 | 说明 |
|---|---|---|---|
| 0 | 1 | MessageType (t) | 见下 |
| 1 | 1 | len(connID) | connID 长度（≤255） |
| 2–3 | 2 | len(meta) | meta 长度（uint16 BE） |
| 4–7 | 4 | len(payload) | payload 长度（uint32 BE） |

紧随其后依次：`connID`（原样字节，非 ASCII 限制）、`meta`、`payload`。

- 解码校验（protocol.go:77-91）：`len(b)<8` 拒；总长用 uint64 计算防溢出，`total > len(b) || total > maxInt` 拒。
- `EncodeMessage` 对 `len(connID)>255` 时截断为前 255 字节（protocol.go:64）。

### 消息类型（MessageType，uint8，protocol.go:13-23）
```
1 = MsgTCPConnect     2 = MsgTCPData    3 = MsgTCPClose
4 = MsgUDPConnect     5 = MsgUDPData    6 = MsgUDPClose
7 = MsgConnStatus     8 = MsgSelectUplink  9 = MsgSelectDownlink
10 = MsgBackpressure
0x10 = MsgPrebindRequest  0x11 = MsgChannelReset
```

## 2.2 各消息载荷格式（★服务器可见）

### TCP 连接
- **客户端→服务器** `MsgTCPConnect`（pool.go `RegisterAndBroadcastTCP`）：
  - connID = `uuid.New().String()`（36 字符 ASCII，Python/Java 用 RFC4122 v4 UUID 文本，`github.com/google/uuid` 是标准 v4）。
  - meta = `[IPStrategy:1][target:ASCII]`，`meta[0]=byte(p.config.IPStrategy)`；target 为 `host:port` 或 `[v6]:port`。
  - payload = 首个数据字节（可能为空）。
- **服务器→客户端** `MsgConnStatus`（handleChannel case）：
  - meta `[0]` = `ConnStatus`：`0=StatusOK`（连接建立），`1=StatusERR`。
  - OK → `signalConnected(connID)`（释放本地代理等待方）；ERR → `Unregister(connID)`。
- **客户端→服务器** `MsgTCPData`：connID 承载；meta=nil；payload=二进制数据块。
- **客户端→服务器** `MsgTCPClose`：connID，meta/payload 空。

### 通道选择（上行/下行竞争，★服务器可见）
- 服务器在收到 TCPConnect 后回 **`MsgSelectUplink`**，meta 承载 `uint32 BE` 的服务器选定上行通道 ID（`uplinkChID = BE32(meta[0:4])`；兼容旧版：meta<4 字节则用当前接收通道）。`noteUplink(connID, uplinkChID)`。
- 客户端在最快收到 SelectUplink 的那个通道上回调 `selectDownlink` 竞争下行：`selectDownlink` 用 `CompareAndSwap(&st.downlink, 0, chID)`，**第一个 CAS 成功的通道成为下行**。
- 客户端随后通过 uplink 通道发 **`MsgSelectDownlink`**，meta = `uint32 BE` 的已选下行通道 ID。
- **UDP 场景下行竞争**（handleChannel MsgUDPData，pool.go）：收到首条下行 UDPData 且 downlink 未定 → 竞争，获胜者通过**当前通道自身**（`chID`）发 `MsgSelectDownlink`（注意 UDP 用 chID 而非 uplink）。
- **预绑定（prebind）行为**：当 `st.target == PrebindTarget("x-tunnel.prebind")` 且 `chID == uplink` 时，`selectDownlink` **主动让出**（不设 downlink、chosen 保持 0），强制 uplink≠downlink（pool.go selectDownlink 内注释）。Hots Pair 正是依赖此强制耦合。

### 背压（★服务器可见：下行帧流控制）
- **服务器→客户端** `MsgBackpressure`：meta `[0]` = `BackpressureState`：
  - `0=Normal（恢复）`、`1=SlowDown（减速）`、`2=Pause（暂停）`（protocol.go:30-35）。
- 客户端处理（pool.go handleBackpressure）：
  - Normal → 向 `resumeCh` 发信号，恢复发送。
  - SlowDown → `getBackpressureDelay()` 返回 **10ms**，`asyncWriteDirect` 每帧休眠 10ms。
  - Pause → `waitForBackpressure()` 最多等待 **3s**；`3s` 内未收到 Normal 则强制降级为 SlowDown（`CAS(Pause→SlowDown)`）继续发送，避免永久卡死（pool.go:895-925）。
- 全局队列上限 `BackpressureLimitBytes` 默认 **8MB**（`DefaultBackpressureLimitBytes = 8<<20`）：`reserveQueueBytes` 超限 → 返回"全局写队列超限"错误 → 调用方（SOCKS5/HTTP 数据写入）报错并关闭流。写队列满且 `WriteQueueWaitTimeout`（默认 500ms）内未恢复 → "通道缓冲区拥堵"错误。

### fast retry（客户端状态机，非独立线协议，但产生重连时序）
- 参数：`FastRetryAttempts`（默认 **1**）、`FastRetryWindow`（默认 **1s**）、`MaxFastRetryConsecutive`（默认 **3**）。
- 判定 `ShouldFastRetryWithinWindow`：`consecutiveFailures < maxConsecutive` 且距上次失败 `≤ window`。
- 命中 → 短延迟抖动重试 `delay = 100ms + rand.Intn(300)ms`（每次窗口内最多 `FastRetryAttempts` 次，之后 `fastRetryCount=0` 走指数退避）。
- 指数退避：`currentDelay` 从 `dialAndServeBaseDelay=3s` 起每次 ×1.5，封顶 `dialAndServeMaxDelay=60s`。
- 超过 `dialAndServeMaxRetries=20` 次 → 转入慢速持续重试（`slowRetryMode`，固定 delay=60s）。

### 通道/会话（★服务器可见：连接建立）
- WS URL：`wss://<server>?client_id=<clientID>&ch_id=<N>`（`q.Set("client_id",...)`、`q.Set("ch_id",...)` 后 `q.Encode()`）—— **`client_id` 与 `ch_id` 均 URL 编码**（Go `url.Values.Encode` 会对空格/特殊字符做 %XX）。
- `ch_id` = 通道号，从 1 开始（`chID=idx+1`）。初始 `Connections`（默认 3）条，若配置 relay 节点则为 `节点数×Connections` 条。
- `clientID`：`cfg.ClientID==""` 时 `uuid.NewString()`。
- **Token 认证**：通过 **WebSocket 子协议** `dialer.Subprotocols = []string{Token}`（dialer.go:80-83）。服务器 401（`resp.StatusCode == http.StatusUnauthorized`）→ 终止，报"认证失败:Token 不匹配或未提供"。
- **中转（relay）**：`NetDial` 把 `address` 的端口替换为 relay 端口（`net.JoinHostPort(relayIP, port)`；若 relayIP 已带端口则直接使用）。TLS SNI 仍为 `u.Hostname()`。
- 拨号重试 `maxRetries=3`；ECH 失败（错误串含 "ECH"/"ech"）时 `Refresh(ECHDomain)` 后隔 `dialWebSocketRetryDelay=1s` 重试。
- 通道就绪：写 worker 先启动、`readyChannels++`、向 `chReadyCh` 发通知。

### WebSocket 保活（★服务器可见）
- 客户端 `writeWorker` 每 `PingInterval`（默认 5s）发 `websocket.PingMessage`。
- 收到服务器 Ping → 回 `PongMessage`（`writeControlDirect`，绕过写队列/背压）；收到 Pong → 刷新读限期（`ReadTimeout=15s`）。
- 关闭帧：Shutdown 时发 `websocket.CloseMessage`，`CloseNormalClosure(1000)`，等 500ms 读响应后关闭。

## 2.3 UDP associate 帧（★服务器可见）

来源：`golib/xtunnel/socks5.go`、`pool.go`

### UDP 数据路径
- 客户端收到本地 SOCKS5 UDP 包后：`StartUDPRace` 广播 `MsgUDPConnect`（connID=uuid，meta=`[IPStrategy][target]`，payload 空）→ 首次发送 `MsgUDPData` 广播（uplink 未定）→ 握手/下行定通道后用 `SendUDPDataDirect(chID, connID, data)` 发 `MsgUDPData`。
- `MsgUDPData` 的 **meta = 服务器回显的目标地址**（`handleUDPResponse(string(meta), payload)`），目标 `host:port` 文本。
- 关闭：`SendUDPCloseDirect` 发 `MsgUDPClose` 后 `Unregister`。

### 生命周期
- TCP 控制连接 `io.Copy(io.Discard, c)` 直到对端关闭 → `assoc.Close()`。
- `assoc.Close()`：若 `receiving` 且 `channelID>=0` → 发 `MsgUDPClose`；否则广播 `MsgUDPClose`。关闭 UDP listener。
- UDP 端口拦截：`UDPBlockedPorts` 默认 `[443]`（拦截 QUIC 443）。
- IP 策略过滤在客户端本地做（仅对 IP 目标）：IPv4Only 丢弃纯 IPv6、IPv6Only 丢弃 IPv4。

## 2.4 IP 策略（★ 影响 meta[0] 与目标解析）

来源：`golib/xtunnel/protocol/ip_strategy.go`

- `IPStrategy`（byte）：`0=Default（系统解析）`、`1=IPv4Only`、`2=IPv6Only`、`3=IPv4 优先`、`4=IPv6 优先`。
- 解析函数 `ResolveWithStrategy(target, strategy)`：若 host 已是 IP 则原样（IPv6 自动加 `[]` 合适时）；否则按策略 DNS 解析后返回 IP。
- **meta[0] 恒为 `byte(IPStrategy)`**，服务器据此决定出口。客户端 DNS 缓存 TTL=1min（`dnsCacheTTL`），`sync.Map` 缓存。
- 注意：xtunnel 普通 TCP **不做客户端 DNS 解析**（注释：域名由服务器解析），仅在 SOCKS5 UDP 的本地 IP 过滤用。

## 2.5 Hot Pair / 预绑定（★服务器可见：prebind 请求帧与 ID）

来源：`golib/xtunnel/pair_warmer.go`

- `PrebindTarget = "x-tunnel.prebind"`（protocol.go）。
- `BuildPair` 构造临时 `connID = "prebind-" + uuid`，注册临时状态（target=PrebindTarget）。发 `MsgPrebindRequest`（0x10），meta = `[IPStrategy][PrebindTarget]`，广播到所有可用通道。
- 服务器对 prebind 请求回 `MsgSelectUplink`；客户端在收到 SelectUplink 的通道上 `selectDownlink`（因 target==PrebindTarget，强制 uplink≠downlink），回 `MsgSelectDownlink`，并发 `HandlePrebindResult(connID, uplink, chosen, nil)`。
- `HandlePrebindResult` 把 `(uplinkChID, downlinkChID)` 塞进 `prebindResultCh`（cap 8），`BuildPair` 拿到后建 `HotChannelPair{UplinkChID, DownlinkChID, state:Ready}`。
- **Pair ID**：槽位 `01`–`08`（`assignPairSlot`，两位十进制），复用未占用最小槽位；`primary` 是首选 pair。
- Pair 状态机：`Ready / Draining / Closed`。引用计数 `refs`；`AcquirePrimary` 取 Ready pair 并 refs++；`ReleasePair` refs--，Draining 且 refs==0 时移除。
- 用途：`RegisterAndBroadcastTCP` 时若 EnableHotPair 且有 primary Ready pair → 直接把 `MsgTCPConnect` 发到 `pair.UplinkChID`（不再广播）；发失败则释放 pair 回退广播。

## 2.6 HTTP CONNECT / 普通 HTTP 语义（服务器可见：本地代理行为）

来源：`golib/xtunnel/http_proxy.go`

- 支持 `CONNECT`（→`handleHTTPProxyConnect`，成功回 `HTTP/1.1 200 Connection Established\r\n\r\n`）与普通方法（`GET` 等 → `handleHTTPProxyForward`）。
- 带认证：`Proxy-Authorization: Basic <base64(user:pass)>`，常量时间比较；失败回 `407 Proxy Authentication Required` + `Proxy-Authenticate: Basic realm="x-tunnel"`。
- 普通请求重建上游：移除 `Proxy-Connection`/`Proxy-Authorization`，`URI = URL.EscapedPath() (+ "?RawQuery")`，行格式 `method path HTTP/1.1\r\n`。
- 超时：`connectTimeout` 默认 15s；重连/握手 `proxyHandshakeTimeout` 默认 3s。
- 直连绕过：`shouldBypass` 命中则 `dialBypassTarget` 直连（`DialContext` timeout=`connectTimeout`），HTTP CONNECT 回 `200 Connection Established`，普通请求直接转上游转发。

---

# 3. 服务器可见行为清单（验收对照基线）

> Java 版必须逐条复刻。标记【GCM】/【XT】区分协议。

**A. 连接建立与握手**
1.【GCM】URL：`wss://<WorkerHost>/<UserID>[?fallbackip=<ProxyIP>]`，fallbackip **不 URL 编码**；无尾 `?`。【GCM】
2.【GCM】HTTP 头：`Host: <WorkerHost>` + 写死 UA 字符串（见 §1.4）。【GCM】
3.【GCM】直连 TCP→WorkerHost；中转 TCP→relay.IP:Port 但 HTTP Host/SNI=WorkerHost。【GCM】
4.【XT】URL：`wss://<serverAddr>?client_id=<..>&ch_id=<N>`，两参数 URL 编码，`ch_id` 从 1 起。【XT】
5.【XT】Token 经 WebSocket 子协议传输；服务器回 401 即中止。【XT】
6.【GCM】TLS MinVersion=TLS1.3；ECH 启用时 ClientHello 带 ECH；ECH 查询/握手失败回退标准 TLS 不阻塞。
7.【GCM】SOCKS5 无鉴权协商，无论 methods 回 `{05 00}`。

**B. GCM 协议帧**
8.【GCM】每条 WS Binary 消息恰为一个 2 字节头帧 `[streamID:1][type:1][data]`。
9.【GCM】CONNECT 载荷 ASCII `host:port|`（IPv6 `[v6]:port|`，port 十进制 0-65535）。
10.【GCM】CONNECTED/CLOSE 无 data；DATA 原样二进制。
11.【GCM】先注册 handler 再发 CONNECT；乐观回 SOCKS5 success 后再等 CONNECTED；CONNECTED 前收到 CLOSE 记失败。

**C. x-tunnel 协议帧**
12.【XT】8 字节大端头 `[t:1][len(id):1][len(meta):2][len(payload):4]` + id + meta + payload。
13.【XT】TCPConnect meta=`[IPStrategy][target]`，payload=首包；connID=UUIDv4 文本。
14.【XT】服务器 SelectUplink meta=BE32 通道号；客户端 SelectDownlink meta=BE32 通道号（TCP 走 uplink，UDP 走当前通道）。
15.【XT】ConnStatus meta[0]=0(OK)/1(ERR)。
16.【XT】Backpressure meta[0]=0/1/2（Normal/SlowDown/Pause）；减速=10ms/帧；Pause 等待≤3s。
17.【XT】背压队列上限 8MB，写队列超时 500ms。
18.【XT】prebind：`MsgPrebindRequest(0x10)` + connID=`prebind-<uuid>` + meta=[strategy]["x-tunnel.prebind"]；强制 uplink≠downlink。
19.【XT】UDP：UDPConnect 广播；UDPData meta=服务端回显目标；UDPClose 关关联。
20.【XT】TCPData 可被 writeWorker 聚合（同 connID、meta 空、总长≤4×ReadBuffer 的相邻帧合成一帧）。
21.【XT】WS 保活：客户端每 5s Ping；收 Ping 回 Pong；读超时 15s。
22.【XT】Shutdown 发 WS Close(1000) 帧。

**D. 时序与错误**
23.【GCM】CONNECT 握手等待 `TunnelTimeout`（1m）；等待 CONNECTED 5s（ProxyTransport DoH 路径）/TunnelTimeout（SOCKS5 路径）。
24.【GCM】读 deadline=心跳间隔+心跳超时（18s）；Ping 15s，Pong 3s 超时退休连接。
25.【XT】关联失败应答：SOCKS5 回 `{05 01 00 01 00000000 0000}`（失败）或 `{05 07 ..}`（命令不支持）；HTTP 回 502（无通道）/504（超时）/401/407。

**E. 路由绕过（本地行为，服务器看不到但影响功能一致性）**
26.【XT/GCM】bypass 命中 → 本地直连 TCP 转发，GCM SOCKS5 回 `{05 00}`；xtunnel HTTP CONNECT 回 200。

---

# 4. 疑问清单（需向服务端/用户确认）

1. **GCM CONNECTED/CLOSE 是否真无载荷**：源码确定无 data；但 worker 端是否有版本差异（如有的 worker CONNECTED 带空 body）需实测确认。
2. **fallbackip 的字段语义**：多 IP 时 Go 端把整个字符串（可能带逗号）原样进 query；worker 如何拆分/编码多 IP（逗号？`&`？）——源码未解析，需服务端确认。
3. **GCM WS URL 中 UserID 含特殊字符时的编码**：源码不做转移，但 worker 路由层如何接收需确认。
4. **x-tunnel Meta 中 IPStrategy 语义的服务器端编码**：`meta[0]` 是否严格按 0-4 值解释（还是服务器只关心非零/特定值）。
5. **Backpressure 触发的服务器阈值**：客户端只响应状态帧，服务器在何种队列水位发 Pause/SlowDown 未在客户端体现，需服务端文档。
6. **MsgChannelReset（0x11）meta=BE32 通道号**：服务器何时发、客户端仅用于废 Pair，服务端语义需确认。
7. **Hot Pair 的 prebind 是否要求服务器特判 `x-tunnel.prebind` target**：若服务器不识别，prebind 可能被当作真实连接建立，产生副作用；需确认服务器支持。
8. **Token 经 WS 子协议的服务器判定**：401 之外是否还有 403/其他码。
9. **UDPData meta 里目标地址格式**：服务器回显的 target 是否始终 `host:port`（IPv6 加 `[]`），需实测。
10. **GCM DoH 预解析域名**：Android 侧若关闭 DoH，CONNECT 载荷将带域名而非 IP——需与服务器确认其接受域名形式。
11. **fast retry 的"对服务器可见"程度**：快速重连会快速重拨新 WS；服务器是否对同一 `client_id` 短时间多条连接有限流需确认。
12. **GCM writeTimeout/2×ConnectionTimeout 等数值**：是否必须与 Go 完全一致，还是仅超时顺序重要。

---

# 5. Java 实现要点提示（承接上述规格）

- 不要引入长度前缀到 GCM 帧（帧边界靠 WS 消息）。
- GCM URL query 用手动拼接，禁用 URI 自动编码。
- x-tunnel 用 `BigEndian` 写 8 字节头；长度用 `int` 但按 uint64 做越界检查。
- UUID：两端用 RFC4122 v4（Java `UUID.randomUUID().toString()`）与 Go `github.com/google/uuid` 一致（36 字符，小写 hex）。
- WS 子协议 Token、Host/UA 头、TLS MinVersion、SNI 必须可配置且默认与 Go 一致。
- 心跳 Ping/Pong 复用 Go 的定时与超时数值即可保证服务器侧一致。

---

*报告生成：基于 golib/ 源码阅读，无构建。所有引用路径相对仓库根 /opt/projects/x-client。*
