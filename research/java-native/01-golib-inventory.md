# golib Go 代码全量盘点与复杂度地图（01-golib-inventory）

调研方式说明：本会话可用工具仅 read/bash(只读)；web_search/fetch_content/write 均不可用（write 不可用导致本报告以 structured_output "report" 字段返回，未能落盘 research/java-native/01-golib-inventory.md，fileWritten=false）。仓库内 62 个 Go 文件（非测试 14,113 行、测试 4,679 行/152 个测试函数）已全部逐一阅读并核对行号；progress.md/tasks.md 调参历史已核对。以下所有论断附文件:函数名:行号。

## 一、结论（先结论后证据）

1. **Android 真实运行路径只经过 8 个包**：android.go → {gcm, xtunnel}/backend.go → {gcm/pool, gcm/relay, gcm/protocol, xtunnel/{pool,socks5,http_proxy,pair_warmer,dialer,client}, xtunnel/protocol, xtunnel/relay, shared/{config,dns,ech,logger,routing,socks5}}。**CLI-only（不在迁移范围）**：shared/config/flags.go(428L, urfave/cli/v3)、shared/config/loader.go(141L, gopkg.in/yaml.v3)、config.go 的 YAML/JSON 序列化方法、logger 的 FileLogger（Android 恒 EnableLogFile=false，文件写入逻辑可整体省略）。
2. **死代码两个**：gcm/pool/quality_monitor.go 的 ConnectionQualityMonitor（256 行）在 gcm/backend.go 的 Start 中从未创建/启动（仅 pool.EnableQualityMonitor=true 一行设置，无人读取）；gcm/pool/proxy_transport.go（386 行 DoH 代理 RoundTripper）因 EnableDoHProxy 恒 false（DefaultConfig 默认 false 且 buildConfig 无该参数入口）从不启用。迁移时可排除（或作为"等行为"保留常量，无需复刻逻辑）。
3. **最大迁移风险是 ECH**：Go 侧用 crypto/tls 的 tls.Config.EncryptedClientHelloConfigList 注入 ECH（shared/ech/manager.go:180 GetTlsConfig），Android Conscrypt 标准栈不支持 ECH；而 xtunnel 默认 EnableECH=true（xtunnel/config.go:110）、GCM 由 UI 开关决定。功能一致目标下这是唯一无法用标准库 1:1 复刻的点，需产品决策（降级 TLS1.3 或引入 BoringSSL）。
4. **外部依赖仅 5 个**：gorilla/websocket v1.5.3（gcm/pool/connection.go 拨号与读写、shared/socks5/server.go 发 BinaryMessage、xtunnel 全部通道层）、golang.org/x/net v0.17.0 仅 dns/dnsmessage（shared/dns/doh.go 的 RFC8484 编解码 + UDP type65 查询）、google/uuid v1.6.0（xtunnel/client.go:83 生成 ClientID；xtunnel/socks5.go:239/307、http_proxy.go:127、pair_warmer.go:163 生成 connID）、urfave/cli/yaml（仅 CLI）。Java 侧需自研 DNS 报文编解码或引 dnsjava，WebSocket 选 OkHttp/Java-WebSocket，UUID 用 java.util.UUID。

## 二、入口与参数契约

- android.go:58 StartSocksProxy(listenAddr, protocol, paramsJSON, verbose)：lifecycleMu 全局互斥保证单后端；protocol 空/小写 gcm → gcm.NewBackend()，xtunnel → xtunnel.NewBackend()（android.go:156-166 newBackend）。
- parseParamsJSON（android.go:86-116）：接受 string/float64/bool/nil 标量，非标量报错；Android TProxyService.java 恒以 verbose=true 调用并传入 JSONObject.toString()。
- GCM 参数全集（gcm/backend.go:37-55 常量，buildConfig 解析）：worker_host(必填,自动剥 wss://、https://、尾部/)，ws_conn(默认3,上限64)，relay_ips(逗号分隔,host:port，"优选IP:端口")，user_id，proxy_ip(→URL?fallbackip=)，ech_domain/ech_dns(留空用内置列表)，enable_ech，disable_ipv6_route(仅校验类型,路由由 VPN 层处理)，enable_dns_warmup(默认关)，bypass_private/geoip_cn/geosite_cn/bypass_rules，enable_dynamic_pool(默认关)/dynamic_pool_max(默认16,上限64)，log_level(优先级高于 verbose)。buildConfig 里 normalizePoolSettings(gcm/backend.go:170-185)：wsConn<=0→3，>64→64；不开动态池时 Min=Max=wsConn。
- xtunnel 参数全集（xtunnel/backend.go:13-40 常量，buildConfig 解析）：server_addr(wss:// 必填且须含主机)，token(→WS Subprotocol)，connections(默认3,上限16 由 Preferences 侧限制)，client_id，relay_nodes(逗号分隔)，enable_ech(默认 true)/ech_domain/dns_server/insecure(默认 false)/enable_hot_pair/hot_pair_count(1..8,maxHotPairCount=8)，log_level；全局 bypass_* 三键+bypass_rules（与 GCM 共用语义）；高级参数（android 高级 JSON 覆盖，毫秒/字节整数）：backpressure_limit、write_queue_wait_timeout、dial/handshake/read/write/connect_timeout、ping_interval、reconnect_delay、max_socks5_connections(0=无限制)、udp_blocked_ports。所有整数参数 <0 报错、0/缺省用默认。

## 三、逐模块盘点（职责/API/状态/并发/参数/依赖）

### 3.1 gcm/backend.go（339L, Backend）
职责：GCM 栈装配。Start 顺序（backend.go:47-130）：清日志→buildConfig→routing.NewMatcher（校验 bypass 规则）→logger.InitGlobalLogger→dns.NewDoHClient→dns.NewDNSCache→relay.NewRelayManager.Init(域名节点 DoH 解析+批量测速)→若 EnableECH 则 ech.NewEchManager 并预取 CacheConfig→pool.NewConnectionPool→若 EnableDoHProxy(恒false) 挂代理→go Warmup（panic 恢复）→(EnableDNSWarmup)go 等待 warmup 后 dc.Warmup→socks5.NewServer.Start→ECH StartAutoRefresh。Stop 逆序释放；Reconnect→connPool.Reconnect；NotifyNetworkChanged→Reconnect。状态：b.mu 保护全部字段指针。并发：mu + goroutine。

### 3.2 gcm/pool/connection.go（2088L, 最难）
- ConnItem（connection.go:32-59）：WS、ConnectionID(3字节随机)、RelayAddr、RTT(atomic 纳秒)、Streams、Traffic、targets 亲和集合、active/closed atomic.Bool、writeMu(WS 写锁)、writeTimeout=GetHeartbeatTimeout()(3s)、质量字段（QualityScore 初值 100）。WriteMessage：broken pipe/markClosed 语义（69-105）。RTT EMA：acknowledgeHeartbeat 中 RTT=old*7/10+rtt*3/10（1063-1085）。
- ConnectionPool（236-316）：requestQueue 缓冲 MaxPoolSize*2 的 connRequest；managerByConn map；pendingHeartbeats map；targetToConn 亲和 map；echFailureCount/echDisabledUntil/echFallbackEnabled；stopChan。构造时启动 6 个后台循环：maintainLoop 1s、cullLoop 5s、statsLoop 10s、heartbeatLoop=GetHeartbeatInterval()(15s)、rateUpdateLoop 1s、congestionControlLoop=GetCongestionControlInterval()(1min)，动态池启时 dynamicPoolLoop(1min)。
- 连接创建三路径：createConnectionSync(预热,同步)、createConnection(运行时,异步+2倍超时保护)、createConnectionWithRelay(节点切换)。统一：WSS URL=wss://workerHost/userID?fallbackip=proxyIP（buildWSSURL 435-442）；NetDial 替换为中转 IP:Port（SNI 仍 WorkerHost）；UA 固定 Chrome/109 Edge；TLS MinVersion=TLS13+ServerName；成功建流后 NewStreamManager 并启 messageLoop。
- 拨号错误智能处理 handleDialError(477-531)：ECH 相关错误（含 "ech"/"encrypted_client_hello"/"tls: handshake failure"）连续 3 次→echFallbackEnabled=true、禁用 ECH 5 分钟（echDisabledUntil），<3 次→异步刷新 ECH 配置；relay 连接 refused/reset/timeout→go ForceRescore。
- messageLoop(999-1061)：SetReadDeadline=HeartbeatInterval+HeartbeatTimeout(18s)；SetPongHandler 刷新 deadline+acknowledgeHeartbeat；所有应用数据帧也 ack 心跳（防忙碌流 Pong 延迟）；Decode 2 字节头后 mgr.DispatchMessage。defer 统计流量并 handleConnectionClose（从 pool/managerByConn/targetToConn/pendingHeartbeats 四索引移除后 mgr.HandleConnectionClose）。
- 取连接+流 GetConnectionWithStream(1149-1292)：循环内先弹空闲池尾部→位图分配失败放回头部；亲和连接检查（targetToConn）；多路复用下选活跃连接流数最少；全满则（pendingCount==0 时）go createConnection 并 50ms 重试轮询；ctx deadline 控制总超时；错误"所有连接已满载"。GetConnection(1293-1442) 旧路径：评分=loadFactor*0.6+rttNorm*0.4（rttNorm=ms/2000 截 1），空闲池按 QualityScore 降序头部取，quality<40 收集后锁外 Close，亲和加成 -0.5，无可用→入 requestQueue 等待。
- 心跳 sendHeartbeat(1822-1868)：先登记 pending 再锁外写 Ping；超时（now-lastPing>3s）→RetireConnection"心跳超时"。
- 拥塞控制 adjustAllStreamsWindow(2062-2088)：每 1min 对所有 Stream.AdjustWindowSize()。
- Reconnect(1869-1886)：复制 managerByConn 后逐个 RetireConnection。
- 关闭 Close(1983-2016)：先收集后锁外关闭避免死锁（注释明确）。

### 3.3 gcm/pool/stream_manager.go（709L）
- StreamManager：max 条数（运行时=cfg.MaxStreamsPerConnection=5，非 256！）、streams map、allocBitmap [4]uint64 位图 + nextHint 循环分配（findFreeBit 67-99，O(最大256) 扫描，平均 O(1)）；ID 0..255 均可分配但 max 限制 len<max。
- Stream 窗口流控（借鉴 yamux/smux）：sendWindow/recvWindow/windowSize 初值=cfg.DefaultWindowSize=**1MB**（注意：stream_manager.go:24-28 的常量 256KB/32KB/1MB 只是兜底，运行时用 config 的 1MB/64KB/4MB）；WaitForSendWindow 超时=WindowTimeout 5s，通知通道 sendBlocked(容量1)+100ms 定期重试；recvWindow<50% 时 Refill；AIMD：DetectCongestion=RTT 均值>2×基线 或 丢包率>5%→窗口减半，否则 +8KB 至上限。状态机 Idle→SynSent→Established→FinWait→Closed（TransitionState 496-536 校验）。
- HandleConnectionClose：锁外回调 OnClose，未注销流清空位图并扣减计数（396-437，防死锁注释）。

### 3.4 gcm/pool/quality_monitor.go（256L, 死代码）
ConnectionQualityMonitor 定义完整逻辑：评分=100 -（RTT 翻倍扣40/1.5倍扣20）-（丢包>5%扣40/>2%扣20）-（心跳失败>3扣20/>1扣10）；劣化阈值 60；节点切换条件=同节点劣化连接≥2 且 5min 冷却；切换=选 3 次新节点+旧节点连接 CreatedAt 提前 4 分钟加速 TTL 淘汰+预建 3 条。**但 gcm/backend.go 从未 New/Start 它 → 生产路径不执行**（只有 ReleaseConnection 按 QualityScore 排序空闲池仍在跑，但分数恒 100）。

### 3.5 gcm/pool/traffic_counter.go（161L）
原子 bytesSent/bytesRecv/累计及活跃 streamCount；每秒 UpdateRates 计算瞬时/移动平均/峰值速率；GetSnapshot 供连接关闭日志。

### 3.6 gcm/pool/proxy_transport.go（386L, 死代码）
tunnelConn 实现 net.Conn（readChan 容量100 的假 Read/Write→WS BinaryMessage），ProxyTransport.RoundTrip：CONNECT 等待 5s、TLS(不校验证书,MinVersion TLS1.2)、HTTP 请求直写 conn。仅供 DoH 代理；Android 不启用。

### 3.7 gcm/relay/manager.go（737L）
RelayManager：Init 解析 relay_ips：IP 直入，域名经 dnsCache.LookupIPs(DoH→系统 DNS) 解析，域名候选测速取 Top2；再批量测速（并发 goroutine+结果排序），Latency≥RelayMaxLatency(500ms) 的进 allNodes 不入选 optimalRelays（降级直连）；分数 Score=延迟ms+失败×500（calculateScore 395-397）；rescoreLoop 每 RelayRescoreInterval(10min) 全量重评（保留负载统计）；ReportFailure 失败≥RelayFailureThreshold(3) 移出最优表；ForceRescore 有 RelayForceRescoreCooldown(1min) 防抖，失败连接触发后重解析重测速。负载均衡 GetNextRelayWithLoadBalance(696-736)：Top5 候选，权重=1000/(延迟ms+1) × 负载因子(1-活跃/16*0.5,下限0.1) × 质量因子(AvgQualityScore/100，EMA 0.7*旧+0.3*新)；独立 rng 加权随机。testLatency 单节点 dial 超时 2s，失败 Latency=9999ms。

### 3.8 gcm/protocol/message.go（92L）
协议：[StreamID:1][Type:1][Data]；Type 0=CONNECT(data="host:port|")、1=CONNECTED、2=DATA、3=CLOSE。Encode/Decode 无长度字段（帧即整条 WS BinaryMessage）。StreamIDToString 输出 2 位十六进制。

### 3.9 shared/socks5/server.go（630L）
共享 SOCKS5：仅支持 CONNECT（不支持 UDP/BIND，UDP 属 xtunnel）。参数：downstreamQueueSize=64、downstreamQueueTimeout=2s、localClientWriteTimeout=10s、握手读超时 10s、上行/下行缓冲 32KB。流程 handleConnection：认证(仅 no-auth)→handleRequest(IPv4/域名/IPv6；EnableDoH 时 ResolveAny 预解析域名)→createTunnel。createTunnel(302-479)：bypassMatcher 命中→createDirectTunnel；否则 GetConnectionWithStream(ctx=TunnelTimeout 1min)→注册 handler→先发 CONNECT 再乐观回 SOCKS 应答（0x05 0x00...10 字节）→回环读→写 WS；下行经 64 容量队列+2s 超时保护 WS 读循环（enqueueDownstream 550-579，超时关闭流并 RecordFailure）；waitForTunnel(483-505) 处理 CONNECTED 与超时竞态（timer 与 connected 同时就绪时优先 established）。cleanup 原子 CAS 防重入，发送 CLOSE 帧+UnregisterStreamHandler+ReleaseConnection。

### 3.10 xtunnel/config.go（134L）
默认值（DefaultConfig 100-128）：Connections=3、DialTimeout=3s、HandshakeTimeout=5s、ReadTimeout=15s、WriteTimeout=5s、PingInterval=5s、ReconnectDelay=1s、ConnectTimeout=15s、EnableECH=true、ECHDomain=cloudflare-ech.com、DNSServer=https://v.recipes/dns-query、IPStrategy=Default、Read/WriteBuffer=64KB、BackpressureLimitBytes=8MB、WriteQueueWaitTimeout=500ms、UDPBlockedPorts=[443]、MaxSOCKS5Connections=1024、EnableHotPair=false、HotPairCount=1、HotPairRefreshInterval=30s、FastRetryAttempts=1、FastRetryWindow=1s、MaxFastRetryConsecutive=3。

### 3.11 xtunnel/protocol/（331L）
- protocol.go：8 字节头[Type:1][connIDLen:1][metaLen:2 BE][payloadLen:4 BE]，后接 connID/meta/payload；EncodeMessage 截断 connID>255；DecodeMessage 用 uint64 防 32 位溢出。消息类型：1 TCPConnect、2 TCPData、3 TCPClose、4 UDPConnect、5 UDPData、6 UDPClose、7 ConnStatus、8 SelectUplink、9 SelectDownlink、10 Backpressure(0正常/1减速/2暂停)、0x10 PrebindRequest、0x11 ChannelReset；PrebindTarget="x-tunnel.prebind"。
- ip_strategy.go：IPStrategy(0 默认/1 仅v4/2 仅v6/3 v4优先/4 v6优先)，本地 lookupIPCached 1min TTL sync.Map 缓存系统 DNS；meta[0]=strategy。
- errors.go：IsNormalCloseError 判定正常关闭帧。

### 3.12 xtunnel/pool.go（1674L, 最难之二）
- clientPool 状态（51-100）：wsConns[]/writeQueues[](chan writeJob,创建时 4096 后重分配,最终 16384)/connsWriteMutex[]/conns map[connID]clientConnState；globalQueueBytes/Limit(8MB)；backpressureState atomic(0/1/2)+resumeCh(容量1)；chReadyCh/chInvalidCh(容量64)；reconnectCh(容量1)；socks5Sem(容量 MaxSOCKS5Connections)；shutdownOnce+dialWG；listeners。
- 通道数：Start 中按 relay SelectBestNodes(n)×Connections 重新分配（pool.go:144-224）。
- writeJob 与写管道：asyncWriteDirect(813-867) 检查背压（SlowDown 睡 10ms；Pause 走 waitForBackpressure 最多 3s 后 CAS 降级为 SlowDown）→ reserveQueueBytes(全局 8MB 原子记账) → 队列 try 入队，满则等 WriteQueueWaitTimeout(500ms)，超时返回"通道缓冲区拥堵"。writeWorker(639-793)：Ping 每 5s；**TCPData 聚合**：同 connID 的连续 TCPData 负载合并到 maxAgg=ReadBufferSize*4=256KB 一帧再写；非 Binary 帧直写。writeControlDirect(794-812) 绕过队列直写控制帧（背压下仍保活）。
- 竞争与通道选择：RegisterAndBroadcastTCP(929-1001) 广播 TCPConnect（Hot Pair 启用时先 AcquirePrimary 单通道发，失败回退广播，广播 0 成功后向 connected<-false 发出标准拒绝）；handleChannel(1312-1530) 收到 MsgSelectUplink 时 selectDownlink(1129-1187) 首次 CAS 抢占下行（downlink atomic int32），随后经 uplink 通道回 MsgSelectDownlink；TCPData 只消费已选下行通道；MsgBackpressure 状态切换；MsgChannelReset 通知 pairWarmer；prebind- 前缀的 connID 触发 HandlePrebindResult。
- Fast retry：dialAndServe(430-605) 状态机：fastRetryState（连续失败<3 且窗口 1s 内 → 100ms+rand(0-300ms) 抖动短重试，FastRetryAttempts=1 次）；否则指数退避 currentDelay*=1.5 从 3s 到 60s；retryCount>=20 转 slowRetryMode 恒 60s；重连时 SelectNodeExcluding 排除 lastIP/ip 换新节点；成功重置并 MarkNodeSuccess/Acquire，失败 MarkNodeFailed/Release。
- Unregister(1053-1128)：记录上下行通道后清理并关 tcpConn/udpAssoc/ReleasePair；预绑定状态只删 map 不打日志。
- cleanupChannel(1284-1311)：通道失效时关闭挂在该通道上的全部连接状态。Shutdown：关监听器→stop ECH→stop relay→cancel→关写队列→优雅 Close 帧(500ms 读超时)→等 dialWG（2s 超时强制）。

### 3.13 xtunnel/socks5.go（624L）
与 shared 版不同：支持 CONNECT+UDP ASSOCIATE+（RFC1929 用户名密码）；并发上限信号量+softLimitWait=100ms 突发窗口（acquireProxySlot 91-109）；握手超时=HandshakeTimeout 5s；32KB 读缓冲。CONNECT：旁路命中→直连（handleSOCKS5Direct，半关闭语义）；否则广播 TCPConnect，等待 connected 通道（无通道→标准 0x05 0x01 失败应答；ConnectTimeout 15s 超时同样回标准失败）；上行数据 GetUplinkChannel 确定后单通道 SendDataDirect，未确定前广播。UDP associate：本机 UDP 监听，loop 64KB 缓冲，首包锁定 clientUDPAddr；IP 策略过滤（仅对 IP 目标）+ UDPBlockedPorts 拦截（默认 443 QUIC）；StartUDPRace 广播 UDPConnect 竞争下行，此后经由 uplink 通道 SendUDPDataDirect；handleUDPResponse 用 SOCKS5 UDP 报文重组（RSV/FRAG/ATYP）。

### 3.14 xtunnel/http_proxy.go（348L）
HTTP 代理（CONNECT+普通 GET 转发）：Basic 认证常量时间比较；普通请求重建上游请求（去 Proxy-Connection/Proxy-Authorization，保留 Host），bufio 已缓冲字节读出随首包发往隧道（readBufferedProxyBytes）；CONNECT 等待建链后回 "200 Connection Established"；无通道 502/超时 504；旁路直连分支半关闭转发。与 SOCKS5 共享 socks5Sem 并发上限。

### 3.15 xtunnel/pair_warmer.go（697L）
HotChannelPair 状态机 Ready/Draining/Closed + refs 计数。BuildPair(146-236)：connID="prebind-"+UUID，注册临时状态（target=PrebindTarget），广播 MsgPrebindRequest，等 prebindResultCh(容量8) 或 3s 超时；selectDownlink 对 PrebindTarget 强制 uplink≠downlink（PrebindTarget 路径当前通道==uplink 时让出）。AcquirePrimary(96-119) 读锁内 refs++ 二次检查。InvalidateChannel 失效即 Draining，refs<=0 立即移除；ReleasePair 归零+Draining 才移除；periodicRefresh（30s）：单 Pair 模式验证通道有效性；多 Pair 模式构建候选，与最老 Ready Pair 通道一致则丢弃候选（discardCandidatePair），不一致则候选继承槽位 ID 顶替；tryBuildPairs 直到 Ready 数=PairCount；槽位 ID "01".."08" 上限 8（assignPairSlot 265-283）。delayedStartPairWarmer：等全部通道就绪（500ms 轮询）或 30s 超时折衷启动。

### 3.16 xtunnel/relay/relay.go（613L+6L log）
与 GCM relay 独立实现。RelayNodeManager：域名节点 periodic refreshHostnameNodes；TestNodeSpeed TCP dial 3s 超时；评分 CalculateScore=(1-lat/5000ms)*0.7+SuccessRate*0.3，最后测试>1h 衰减 1/(1+hours*0.1)；健康分数 healthScore（失败节点比例 100-失败%*100）驱动 **动态测速间隔 CurrentTestInterval**：<30→15s、≥70→60s、否则 30s（346-358）；SelectNodeExcluding 排除列表+失败冷却（FailCount≥3 且 30s 内）+加权随机（权重=评分×负载因子(1-load/16*0.5 下限 0.1)，maxLoadPerNode=16）；无健康候选回退 refreshHostnameNodes 再选，再不行按 FailCount 升序/Score 降序兜底。MarkNodeFailed 置 Score=0/SuccessRate=0；MarkNodeSuccess 重置并重算。

### 3.17 xtunnel/dialer.go（110L）
dialWebSocket：仅 wss；URL 增加 client_id 与 ch_id 查询参数；Token→Subprotocols；ECH 用 shared echManager.GetTlsConfig（失败降级标准 TLS，重试 3 次，1s 间隔刷新 ECH）；relayIP 经 NetDial 替换目标（含端口或无端口 JoinHostPort）；401→"认证失败:Token 不匹配"。

### 3.18 shared/dns/doh.go（757L）
DoHClient：内置 DefaultDoHServers=[v.recipes/dns-query, doh.090227.xyz/CMLiussss, doh.pub/dns-query]（doh.go:43-48）；doHURLs 单服务器或多服务器；lastServerIdx 轮转起点记忆。Resolve：每服务器独立 3s 超时（DoHTimeout），多服务器依次尝试；resolveWithServer 每服务器重试 2 次（attempt*100ms 退避，TLS/connection/EOF 类才重试）；resolveAttempt 先 RFC8484 POST（dnsmessage 构造，dnsmessage.Type 65=HTTPS，响应解析 A/AAAA/HTTPS wire→"\\# len hex"），失败回退 JSON API（Google 特判 /dns-query→/resolve；GET name&type）。ResolveHTTPSUDP(342-430)：UDP DNS type65 查询 8.8.8.8:53（5s 超时），解析 wire HTTPS 记录→ECH。
parseHTTPSRecord/parseHTTPSRecordWire(592-709)：文本与 wire 两种格式，key5=ech（base64→ECH 字节）、key1=alpn、其他 keyN=hex。LookupIP 系统 DNS 优先 v4 后 v6。

### 3.19 shared/dns/cache.go（452L）+ warmup_list.go（69L）
DNSCache：map[domain:type]；TTL=5min、清理间隔 1min（cleanupLoop 261-274）；无容量上限、无 LRU，仅 TTL 淘汰；Get 命中/过期删；ResolveAny A→AAAA→系统 DNS 回退并缓存；GetECHConfig 走 HTTPS 记录缓存；Warmup(327-384)：合并 DefaultWarmupDomains（google/youtube/x/facebook/github/wikipedia/reddit/openai/cloudflare/netflix/spotify/aws/microsoft/apple/telegram 约 30 条），并发 8 信号量、总超时 15s；LookupIPs 供 GCM relay 解析。

### 3.20 shared/ech/manager.go（303L）
EchManager：cache map 键=echDomain；TTL 默认 24h；autoRefreshLoop 默认 12h；**singleflight**（inflight map+done channel，78-119 注释：21 条连接并发启动防打爆 DoH）；doHFunc=DoH GetECHConfig（内部多服务器 fallback），失败 udpFunc=ResolveHTTPSUDP(8.8.8.8:53)，再失败 GetTlsConfig 返回标准 TLS1.3（不报错，176-186）；GetTlsConfig 成功时设置 EncryptedClientHelloConfigList。CacheConfig 供 backend 冷启动预取。

### 3.21 shared/logger/logger.go（431L）
runtimeLogStore：2000 行/256KB 双上限；append 超限从头部逐条弹出；单行截断到 255KB 且 UTF-8 安全（truncateUTF8）；snapshot 用 "\n" join，行格式 "[HH:MM:SS] [级别] [scope] msg"；scope 默认 "Android"。Logger：级别过滤（DEBUG<INFO<WARN<ERROR）、锁、stdout 打印（gomobile→logcat）+可选 FileLogger（Android 关）；GetLogger 按 scope 单例；InitGlobalLogger 通过 SetGlobalLevel 传播已建 logger（xtunnel 包级 sysLog 在 init 期已建，必须走 SetGlobalLevel，logger.go:359-385 注释）。GetRuntimeLogs/AppendRuntimeLog/ClearRuntimeLogs 是 Android 读取通道。

### 3.22 shared/routing/matcher.go（335L）
Matcher：IPv4/IPv6 按前缀长度分桶（[33]/[129] 桶，每桶 map 存储网络地址，匹配时逐长度查表即最长前缀匹配）；域名规则 domain(后缀)/full(精确)/keyword/regexp；manual 规则支持 IP/CIDR/domain:/suffix:/full:/private|lan|local/geoip:cn/geosite:cn（# 注释）；内置 privatePrefixes 10 条（10/8、100.64/10、127/8、169.254/16、172.16/12、192.168/16、::1、fc00/7、fe80/10）+localhost/local。数据：**go:embed data/geoip_cn.txt（5822 行 CIDR）与 data/geosite_cn.txt（6410 行 domain:xxx）**，来源记录在 data/README.md：Loyalsoldier/geoip commit d57a38b（CC-BY-SA-4.0）+ v2fly/domain-list-community commit c2aeccd（--exportlists=cn，MIT），附 SHA-256。→ Java 迁移须将 12,232 行文本打包进 assets/res 并在启动时解析建桶（解析为一次性 O(12k) 开销，可接受）。

### 3.23 shared/config/（1096L）
config.go：Config 全字段+DefaultConfig（3.10 同款数值；GCM 用的：连接池 Min5/Max15、TTL 5min、超时 1s；relay 间隔 30s、500ms 阈值、失败 3、重评 10min、强制冷却 1min；DNS TTL 5min/清理 1min；DoH 超时 3s；心跳 15s/3s；预热并发 3/超时 30s；动态池 1min/低 0.3/高 0.8；多路复用 on/每连接 5 流；窗口 1MB/64KB/4MB/5s；拥塞 1min；质量检查 10s/阈值 60/切换冷却 5min/劣化数 2）。flags.go+loader.go=CLI 专用（urfave/cli v3，-c/--config YAML-JSON）。**迁移范围：只需 DefaultConfig 数值常量与 Getter；YAML/JSON Marshal 与 CLI 全排除。**

### 3.24 xtunnel/backend.go（324L）+ client.go（149L）
backend.go：Start 组装 Config+Matcher→logger→NewClient→SetBypassMatcher→c.Start()→ListenSOCKS5（仅 SOCKS5；ListenHTTP 保留但 Android 不调用）；Stop→Shutdown；Reconnect→client.Reconnect。client.go：NewClient Validate+ClientID=uuid；Start→pool.Start(relayNodes)；RegisterTCP 暴露但运行时由 socks5/http_proxy 内部使用 uuid 注册。

## 四、关键算法 1:1 复刻要点（迁移必须逐字对照的）

1. **GCM 流多路复用**：2 字节头、位图 256 位分配（nextHint 环形）、每连接 StreamManager 上限=配置值(默认 5，**protocol 上限仅 256，勿混**)、CONNECT data="host:port|"、Register 先于 CONNECT（防丢包）、乐观 SOCKS 应答、下游 64 队列/2s 超时、上行 32KB 直写 WS（无窗口更新，靠 WS 写背压）。
2. **GCM 连接池选择**：亲和 targetToConn→空闲池(QualityScore 降序,<40 剔除)→活跃最少流→建新连接(50ms 轮询)；requestQueue 缓冲=MaxPoolSize*2；RTT EMA 7/3。
3. **xtunnel 多通道竞争**：上行=服务端 SelectUplink(meta 4B BE 通道号)选择；下行=客户端首次收到 SelectUplink 的通道 CAS atomic.CompareAndSwap 抢占（Hot Pair/Prebind 强制 uplink≠downlink）；TCPData 只读已选下行；每连接一个写队列+一个 writeWorker。
4. **背压**（progress.md 阶段 13/17 调参史，最终值）：客户端全局 8MB（16MB 曾试后回退，progress.md 2026-08-16 4443c69/5b10fc1）、单通道写队列 16384、写满等 500ms（曾 100ms）、Pause 等 3s 降级 SlowDown 继续（waitForBackpressure, pool.go:1584-1609）、SlowDown 每写睡 10ms；服务端阈值 32MB（本仓库不涉及，仅协议层 Backpressure 消息）。
5. **fast retry 状态机**：连续失败<3 且距上次失败≤1s→100ms+rand300ms 短重试（限 1 次）→否则指数退避 3s×1.5 至 60s→20 次后恒 60s 慢速重试；换节点排除上一失败 IP。
6. **PairWarmer**：Ready/Draining/Closed+refs；prebind 3s 超时；周期 30s；单 Pair 验证/多 Pair 候选顶替；槽位 01-08。
7. **xtunnel relay 评分**：(1-lat/5000)*0.7+成功率*0.3，1h 后时间衰减；健康分数→测速间隔 15/30/60s；失败冷却 3 次/30s。
8. **GCM relay 评分**：延迟ms+失败×500；加权随机 Top5（权重=1000/(ms+1)×负载×质量 EMA）。
9. **DoH/ECH 链路**：多服务器轮转（lastServerIdx）→每服 RFC8484 优先→JSON 回退→UDP type65→系统 DNS；ECH 3 连败禁 5min；singleflight+24h TTL+12h 刷新。
10. **SOCKS5/HTTP 语义**：连接数信号量+100ms 突发窗口；握手 5s；无通道/超时必须回标准失败应答（0x05 0x01…/502/504，上游修复点）；HTTP 上游请求重建去除代理头；TCP 半关闭 Keep-Open 语义。

## 五、迁移工作量表（模块/非测试LOC/难度(1-5)/Java要点/风险）

| 模块 | LOC | 难度 | Java 实现要点 | 关键风险 |
|---|---|---|---|---|
| gcm/pool/connection.go | 2088 | 5 | 线程池+BlockingQueue+Future/线程调度替换 goroutine；原子性用 AtomicLong/乐观 CAS；WS 写锁 | 死锁/竞态最多；50ms 轮询语义；锁外回调顺序 |
| xtunnel/pool.go | 1674 | 5 | 多通道写线程+聚合缓冲；背压原子记账；通道竞争 CAS | TCPData 聚合边界；背压状态机时序 |
| gcm/pool/stream_manager.go | 709 | 3 | 位图+nextHint；AIMD 窗口；状态机 | 窗口流控精度 |
| xtunnel/pair_warmer.go | 697 | 4 | Pair 状态机+refs；prebind 竞争 | 竞态：refs 与 Invalidate 交错 |
| gcm/relay/manager.go | 737 | 3 | 并发测速；加权随机（rng 种子） | 权重公式浮点一致性 |
| xtunnel/relay/relay.go | 613 | 3 | 健康分数+动态间隔定时器 | 时间衰减公式 |
| shared/socks5/server.go | 630 | 3 | NIO/阻塞 IO 线程模型；队列背压；CAS cleanup | 半关闭语义 |
| xtunnel/socks5.go | 624 | 3 | 双向复制+UDP 关联；信号量 | UDP 生命周期 |
| shared/dns/doh.go | 757 | 4 | DNS 报文手写编解码（无 x/net）；HTTP 客户端 | RFC8484 wire 兼容 |
| xtunnel/http_proxy.go | 348 | 2 | HTTP parser（OkHttp 自带） | 请求重建字节一致性 |
| shared/dns/cache.go | 452 | 1 | ConcurrentHashMap+TTL 清理线程 | 无 |
| shared/config/config.go | 527 | 1 | 常量类+Getter | 无 |
| shared/logger/logger.go | 431 | 2 | 环形缓冲+List 级联删除；级别传播 | 无 |
| shared/ech/manager.go | 303 | 3 | singleflight+TTL+定时刷新 | TLS ECH 注入（根本性） |
| shared/routing/matcher.go | 335+12k 数据 | 2 | 前缀分桶最长匹配；assets 内嵌解析 | 数据许可/体积(227KB) |
| xtunnel/dialer.go | 110 | 2 | WS URL 组参+Subprotocol | ECH 重试 |
| gcm/backend.go+xtunnel/backend.go+client.go+android.go | 1033 | 2 | 生命周期互斥+参数解析 | 无 |
| xtunnel/protocol/ | 331 | 2 | 字节缓冲编解码；uint64 长度校验 | 32 位溢出防护 |
| gcm/protocol/message.go | 92 | 1 | 2 字节头 | 无 |
| gcm/pool/traffic_counter.go | 161 | 1 | AtomicLong | 无 |
| 死代码 quality_monitor.go+proxy_transport.go | 642 | - | 排除 | 决策待确认 |
| CLI-only flags+loader+FileLogger | 569+ | - | 排除 | 决策待确认 |

合计迁移核心约 12k 行 Go → 预估 14-18k 行 Java（含线程模型显式化+锁细节），3-5 人周（不含 ECH 方案）。

## 六、测试覆盖统计（4,679 行/152 个函数）

已覆盖（可作 Java 重写对照）：GCM 位图分配/并发分配/满分配、流控窗口、AIMD、状态机合法性、连接恢复（Retire/Release 不重用死链/Reconnect 全关/心跳锁序/应用数据确认心跳）、relay rescore 锁序；xtunnel 协议编解码全边界（截断/超长/溢出）、IP 策略缓存、写队列记账、广播空通道、统计字节数、心跳默认值、ECH 启动不阻塞、dialAndServe 取消/重试/慢速、重连关通道、背压状态/延迟/等待/取消、fast retry 状态、PairWarmer 全状态机（Acquire/Release/Draining/预绑定超时/槽位/候选丢弃/结余清理/pair 相等）、relay 健康分数与选择回退、SOCKS5 认证拒绝路径/无通道失败应答/UDP 报文解析/assoc 幂等关闭、HTTP 代理认证/502/请求重建不吞 body/缓冲字节、绕过直连端到端（本机 echo 服务，TCP+HTTP CONNECT+普通 HTTP）、并发信号量语义、DoH 服务器列表/回退/系统 DNS、ECH DoH→UDP→标准 TLS/singleflight、Matcher 手动/private/GEOIP/GEOSITE 规则与行号报错、runtimeLog 双上限/UTF8、全局日志级别传播。
未覆盖（迁移后无对照，需自建）：GCM 与 xtunnel 均无"真实 WebSocket 服务端"的端到端联通测试；无 GCM CONNECTED/DATA/CLOSE 全链路回环测试；无多路复用并发流吞吐测试；无 ECH 真实握手测试（依赖线上）；无心跳超时真实时序测试（仅锁序）；无背压"真实打满"压力测试；无 relay 网络波动下的负载均衡统计断言；无跨协议（GCM↔xtunnel）行为一致性测试。建议 Java 端优先补：协议编解码全边界、双端回环 echo 隧道、背压降级时序、流 ID 争用。

## 七、悬而未决（详见 openQuestions 字段）
ECH 实现策略为第一大决策；死代码是否复刻；数据文件许可打包；TLS1.3 minSdk 24 兼容；WebSocket 库选型；测试对照范围；服务端联调账号。

*（因 write 工具不可用，本报告以结构化输出返回；任务要求的落盘路径 research/java-native/01-golib-inventory.md 未能写入，建议在具备 write 工具的下一次会话中落盘。）*