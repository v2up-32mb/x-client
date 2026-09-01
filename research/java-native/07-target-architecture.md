# 07 · 目标架构与分阶段实施计划——"golib + hev-socks5-tunnel Java 化"

> **Phase 2 架构设计与风险审计**  
> 日期：2026-09-01  
> 仓库：`v2up-32mb/x-client` / 分支：`main`  
> 基于 Phase 1 六份调研报告（01-golib-inventory / 02-protocol-spec / 03-hev-tunnel-analysis / 04-ech-java-feasibility / 05-java-perf-feasibility / 06-android-ci-surface）  

---

## 0. 结论（先结论后证据）

**核心结论：Java 化完全可行，但必须分两步走——先用已验证的 C TUN 转发器 + Java 协议栈的"混合方案"确保功能交付，再根据真机基准决定是否推进纯 Java TUN。**

六项关键判定（均有下文证据链）：

1. **golib 核心 12k 行 Go → 预估 14–18k 行 Java，是确定可行的迁移**：Android 真实运行路径只经 gcm、xtunnel、shared 三组包（01-golib-inventory）；协议层极薄（GCM 2 字节头/xtunnel 8 字节头，02-protocol-spec）；ECH 是唯一"标准库无法 1:1 复刻"的点，但可延后（04-ech-java-feasibility）。

2. **hev-socks5-tunnel 不能直接用纯 Java 替代**：它内嵌完整 lwIP TCP/IP 协议栈（状态机/重传/窗口/IP 分片/UDP NAT/mapped DNS），Java 等价实现需 3000–5000 行且正确性极难（03-hev-tunnel-analysis, 05-java-perf-feasibility）。**建议保留 C TUN 转发器过渡**。

3. **对外协议兼容性有保障**：GCM 与 x-tunnel 的服务器可见协议字节集中在极少数编解码函数中（02-protocol-spec 全部 26 条验收基线），Java 逐比特复刻成本低；背压/fast retry/hot pair/relay 评分等内部逻辑均为"逻辑 1:1 移植、只换并发原语"。

4. **集成面极度收敛**：Xclient API 仅 7 处调用、6 个唯一方法；hev JNI 仅 4 个 native 方法（1 个死代码）；app 侧只需改 3 个文件（06-android-ci-surface）。

5. **性能目标务实可达**：≤150 Mbps / ≤2000 TCP 流是纯 Java 的安全区间（05-java-perf-feasibility），但最终只能真机验证；保持 C TUN 转发器可消除 TCP 正确性这一最高风险。

6. **CI 简化是纯正收益**：移除 Go/gomobile/NDK/jni clone 四个步骤，构建时间缩减 50%+，APK 从 4 ABI（30–80MB）缩至 1 universal（2–5MB）（06-android-ci-surface）。

---

## 1. 目标架构

### 1.1 Java 包/模块布局

```
com.x.client.app/                    ← Android App（已有，仅微调）
  TProxyService.java                    VPN 服务，改 hev→Java TUN 转发
  Preferences.java                      配置读写（不变）
  ProfileEditActivity.java              Profile 编辑（不变）
  ...（其他 Activity/Adapter 不变）

xclient/                             ← Facade 层（替代 gomobile AAR）
  Xclient.java                          静态方法 facade，签名不变
  XclientFacade.java                    内部实现（路由到 GCM/Xtunnel backend）

com.x.client.core/                   ← 核心库（替代 golib/）
  │
  ├── Backend.java                      ProxyBackend 接口（替代 Go interface）
  ├── LifecycleManager.java             全局互斥 + activeBackend 管理
  │
  ├── protocol/
  │   ├── gcm/
  │   │   ├── GcmBackend.java           gcm/backend.go
  │   │   ├── GcmConfig.java            gcm/backend.go buildConfig
  │   │   ├── protocol/
  │   │   │   └── GcmMessage.java       gcm/protocol/message.go（2B 头编解码）
  │   │   ├── pool/
  │   │   │   ├── ConnectionPool.java    gcm/pool/connection.go（连接池 + 6 后台循环）
  │   │   │   ├── ConnItem.java         gcm/pool/connection.go ConnItem
  │   │   │   ├── StreamManager.java    gcm/pool/stream_manager.go（位图 + 窗口流控）
  │   │   │   ├── Stream.java           gcm/pool/stream_manager.go Stream（AIMD 窗口）
  │   │   │   └── TrafficCounter.java   gcm/pool/traffic_counter.go
  │   │   ├── relay/
  │   │   │   └── RelayManager.java     gcm/relay/manager.go（评分/测速/负载均衡）
  │   │   └── socks5/
  │   │       └── GcmSocks5Server.java  shared/socks5/server.go（GCM 路径）
  │   │
  │   └── xtunnel/
  │       ├── XtunnelBackend.java       xtunnel/backend.go
  │       ├── XtunnelConfig.java        xtunnel/config.go
  │       ├── protocol/
  │       │   ├── XtProtocol.java       xtunnel/protocol/protocol.go（8B 头编解码）
  │       │   ├── IpStrategy.java       xtunnel/protocol/ip_strategy.go
  │       │   └── XtErrors.java         xtunnel/protocol/errors.go
  │       ├── pool/
  │       │   ├── ClientPool.java       xtunnel/pool.go（连接池 + 背压 + 帧聚合）
  │       │   ├── ClientConnState.java  xtunnel/pool.go clientConnState
  │       │   ├── WriteJob.java         xtunnel/pool.go writeJob
  │       │   └── UdpAssociation.java   xtunnel/pool.go（UDP 绑定）
  │       ├── relay/
  │       │   └── RelayNodeManager.java xtunnel/relay/manager.go
  │       ├── pair/
  │       │   ├── PairWarmer.java       xtunnel/pair_warmer.go
  │       │   └── HotChannelPair.java   xtunnel/pair_warmer.go HotChannelPair
  │       ├── dialer/
  │       │   └── XtDialer.java         xtunnel/dialer.go（WS 拨号 + ECH 重试）
  │       ├── proxy/
  │       │   ├── XtSocks5Server.java   xtunnel/socks5.go（CONNECT+UDP ASSOCIATE）
  │       │   └── HttpProxyServer.java  xtunnel/http_proxy.go
  │       └── client/
  │           └── XtClient.java         xtunnel/client.go（connID 管理/重试）
  │
  ├── shared/
  │   ├── config/
  │   │   └── SharedConfig.java         shared/config/config.go（全局默认值 + 解析）
  │   ├── dns/
  │   │   ├── DoHClient.java            shared/dns/doh.go（RFC8484 + JSON 双格式）
  │   │   ├── DnsCache.java             shared/dns/cache.go（TTL 5min + 清理）
  │   │   ├── DnsWarmup.java            shared/dns/cache.go Warmup + warmup_list.go
  │   │   └── SvcbParser.java           shared/dns/doh.go parseHTTPSRecord*（SVCB/HTTPS）
  │   ├── ech/
  │   │   ├── EchManager.java           shared/ech/manager.go（缓存/singleflight/自动刷新）
  │   │   └── EchConfig.java            shared/ech/manager.go（ECHConfigList 字节格式）
  │   ├── routing/
  │   │   ├── Matcher.java              shared/routing/matcher.go（路由匹配引擎）
  │   │   └── RuleParser.java           shared/routing/parser.go（IP CIDR/域名规则解析）
  │   ├── socks5/
  │   │   └── Socks5Server.java         shared/socks5/server.go（基础 SOCKS5 基类）
  │   └── logger/
  │       └── RuntimeLogger.java        shared/logger/logger.go（环形 2000 行/256KB）
  │
  ├── tunnel/                          ← TUN 转发器（过渡方案保留 C，最终目标纯 Java）
  │   ├── TunForwarder.java             接口：TUN fd → SOCKS5 连接
  │   ├── TunReadLoop.java              TUN 读循环（Os.poll + FileInputStream）
  │   ├── MappedDns.java                mapped DNS 双向映射（240.0.0.0/8 ↔ 域名）
  │   ├── TcpSessionManager.java        用户态 TCP 连接表（纯 Java 目标）
  │   └── UdpSessionManager.java        UDP NAT 表（纯 Java 目标）
  │
  └── util/
      ├── ByteBuf.java                  可复用 byte[] 包装（零分配热路径）
      ├── ByteArrayPool.java            byte[] 对象池
      └── StructPollWrapper.java        android.system.Os.poll 封装
```

### 1.2 Go → Java 类级映射表

#### 入口层

| Go 类型/函数 | Java 类 | 说明 |
|---|---|---|
| `android.go StartSocksProxy` | `Xclient.java` → `LifecycleManager` | 静态入口，lifecycleMu → `ReentrantLock` |
| `android.go parseParamsJSON` | `LifecycleManager.parseParamsJSON` | JSON→Map，用 `org.json.JSONObject` |
| `android.go newBackend` | `LifecycleManager.newBackend` | protocol 分发，switch/case |
| `ProxyBackend` interface | `Backend` interface | `start/stop/reconnect/notifyNetworkChanged` |
| `android.go SetTimeZone` | `Xclient.setTimeZone` | Java 化后变空操作或 `ZoneId.of(tz)` |
| `android.go ValidateBypassRules` | `Xclient.validateBypassRules` | `Matcher.validateManualRules` |
| `logger.go AppendRuntimeLog` | `Xclient.appendRuntimeLog` → `RuntimeLogger` | 环形缓冲 |
| `logger.go GetRuntimeLogs` | `Xclient.getRuntimeLogs` | `Join` 所有行 |

#### GCM 协议栈

| Go 类型 | Java 类 | 迁移要点 |
|---|---|---|
| `gcm/backend.go Backend` (339L) | `GcmBackend` | Start 顺序 1:1 对齐：清日志→config→routing→logger→dns→relay→ech→pool→warmup→socks5→ech refresh |
| `gcm/protocol/message.go` (92L) | `GcmMessage` | `encode()`/`decode()`，2 字节头 + Data，帧边界由 WS 消息界定 |
| `gcm/pool/connection.go ConnItem` | `ConnItem` | WS 连接 + RTT EMA（old×7/10 + new×3/10）+ 256 位流位图 + writeMu |
| `gcm/pool/connection.go ConnectionPool` | `ConnectionPool` | 6 个后台循环 → `ScheduledExecutorService` 6 个 `ScheduledFuture`；requestQueue → `LinkedBlockingQueue`；亲和 map → `ConcurrentHashMap` |
| `gcm/pool/stream_manager.go` (709L) | `StreamManager` | 位图 `[4]long` → `[4]long`；`findFreeBit` 直译；MaxStreams 默认 5 |
| `gcm/pool/stream_manager.go Stream` | `Stream` | AIMD 窗口流控：初值 1MB/64KB/4MB；`WaitForSendWindow` → `Semaphore` + `Condition` + 5s 超时 |
| `gcm/pool/traffic_counter.go` (161L) | `TrafficCounter` | `AtomicLong` bytes/rates；`ScheduledExecutor` 每秒 UpdateRates |
| `gcm/relay/manager.go` (737L) | `RelayManager` | 评分=延迟+失败×500；负载均衡 Top5 加权随机；rescoreLoop 10min |
| `shared/socks5/server.go` (630L) | `GcmSocks5Server` | 每连接双线程（→NIO Selector 减线程）；下行队列 64 槽 + 2s 超时；乐观回复 |

#### x-tunnel 协议栈

| Go 类型 | Java 类 | 迁移要点 |
|---|---|---|
| `xtunnel/backend.go Backend` | `XtunnelBackend` | Start：config→routing→logger→dns→ech→relay→pool→httpProxy→socks5→ech refresh |
| `xtunnel/protocol.go` (331L) | `XtProtocol` | 8 字节大端头 `ByteBuffer.order(BIG_ENDIAN)`；12 种消息类型 |
| `xtunnel/pool.go clientPool` (1674L) | `ClientPool` | **最复杂**：全局 8MB 字节预算 → `AtomicLong globalQueueBytes`；16384 槽写队列 → `LinkedBlockingQueue<WriteJob>`（或固定数组 + CAS）；backpressureState → `AtomicInteger`；resumeCh → `Semaphore(1)` |
| `xtunnel/pool.go writeWorker` | `ClientPool.WriteWorker` | TCPData 聚合（maxAgg=256KB）→ 同 connID 帧合并；Ping 5s；Pong 绕过队列直写 |
| `xtunnel/pool.go asyncWriteDirect` | `ClientPool.asyncWriteDirect` | SlowDown → `Thread.sleep(10)` / Pause → `Condition.await(3s)` → 降级 SlowDown |
| `xtunnel/pool.go dialAndServe` | `ClientPool.dialAndServe` | Fast retry：100ms+rand(300ms)，窗口 1s，连失 3 退出；指数退避 3s×1.5→60s；20 次→慢速 |
| `xtunnel/pool.go handleChannel` | `ClientPool.handleChannel` | 通道选择：CAS 竞争 downlink；prebind 强制 uplink≠downlink |
| `xtunnel/socks5.go` (624L) | `XtSocks5Server` | CONNECT + UDP ASSOCIATE + RFC1929 认证；信号量 + softLimitWait 100ms |
| `xtunnel/http_proxy.go` | `HttpProxyServer` | CONNECT/普通 HTTP；Basic auth；bypass 直连 |
| `xtunnel/pair_warmer.go` | `PairWarmer` | 1 对默认，30s 刷新，prebind 3s 超时，槽位 01-08，Ready/Draining/Closed |
| `xtunnel/dialer.go` (110L) | `XtDialer` | ECH 重试 3 次 + Refresh；Token→WS subprotocol；relay 替换端口 |
| `xtunnel/client.go` | `XtClient` | connID 管理（UUID v4）、TCP/UDP 连接生命周期 |

#### 共享层

| Go 类型 | Java 类 | 迁移要点 |
|---|---|---|
| `shared/config/config.go` (DefaultConfig) | `SharedConfig` | 所有常量 → Java `static final`（140+ 项） |
| `shared/dns/doh.go` (757L) | `DoHClient` | 3 服务器轮转；RFC8484 POST→JSON GET→UDP 8.8.8.8:53→系统 DNS；每服务器独立超时 |
| `shared/dns/cache.go` (423L) | `DnsCache` | `ConcurrentHashMap<String, CacheEntry>`；TTL 5min；cleanupLoop 1min |
| `shared/dns/warmup_list.go` (69L) | `DnsWarmup` | 40 域名列表硬编码；并发 8（`Semaphore(8)`）；总超时 15s |
| `shared/ech/manager.go` (303L) | `EchManager` | 缓存 24h；定时刷新 12h；singleflight → `ConcurrentHashMap<String, CompletableFuture<byte[]>>` |
| `shared/routing/matcher.go` | `Matcher` | `geoip_cn.txt`（5822 行）+ `geosite_cn.txt`（6410 行）→ `assets/`；Trie/HashMap 匹配 |
| `shared/socks5/server.go` (630L) | `Socks5Server` | 基类：handAuth→handleRequest→createTunnel；GCM/Xtunnel 各子类重写 |
| `shared/logger/logger.go` (86L) | `RuntimeLogger` | `ArrayList<String>` + `ReentrantReadWriteLock`；`DateTimeFormatter` HH:mm:ss |

#### 排除项（不在迁移范围）

| Go 文件 | 行数 | 排除原因 |
|---|---|---|
| `shared/config/flags.go` | 428 | urfave/cli v3，CLI-only |
| `shared/config/loader.go` | 141 | yaml.v3，CLI-only |
| `gcm/pool/quality_monitor.go` | 256 | Android 路径从未实例化（死代码） |
| `gcm/pool/proxy_transport.go` | 386 | EnableDoHProxy 恒 false（死代码） |
| Logger `FileLogger` | ~100 | Android 恒 EnableLogFile=false |
| `config.go` YAML/JSON 序列化方法 | ~80 | CLI-only |

### 1.3 线程模型与并发设计

#### 平台线程约束（Android API 24, Java 17）

- **Java 17 无虚拟线程**（Project Loom 为 Java 21+）；Android ART 不支持平台虚拟线程。
- **线程数预算**：常驻线程 ≤ 数百（避免 8MB 虚拟栈 × 数千线程 → 内存墙 + 调度抖动）。
- **推荐模型**：少量常驻线程 + 任务队列，避免每连接一线程。

#### Go → Java 并发原语映射

| Go 惯用法 | Java 等价 | 使用场景 |
|---|---|---|
| `go func()` | `ExecutorService.submit(() -> {...})` 或 `Thread.start()` | 后台循环/一次性任务 |
| `chan T`（有缓冲） | `LinkedBlockingQueue<T>` 或 `ArrayBlockingQueue<T>` | writeJob 队列、requestQueue |
| `chan T`（无缓冲/信号量） | `Semaphore` 或 `CountDownLatch` 或 `CompletableFuture` | 连接就绪通知、背压恢复信号 |
| `select { case <-ch: }` | `poll(timeout)` on `BlockingQueue`；或 `Semaphore.tryAcquire(timeout)` | 超时等待多路复用 |
| `sync.Mutex` | `ReentrantLock` | 保护共享状态 |
| `sync.RWMutex` | `ReentrantReadWriteLock` | 读多写少场景（连接表、配置） |
| `atomic.Int32/Int64` | `AtomicInteger` / `AtomicLong` / `VarHandle` | 原子计数器/状态位 |
| `atomic.Bool` | `AtomicBoolean` 或 `VarHandle` | 流/连接 active/closed 标记 |
| `sync.Map` | `ConcurrentHashMap` | DNS 缓存、亲和连接表 |
| `defer func()` | `try (resource) {}` 或 `try-finally` 块 | 资源释放（注意：Java defer 无闭包语义，需显式 finally） |
| `time.AfterFunc(d, fn)` | `ScheduledExecutorService.schedule(fn, d, MILLISECONDS)` | ECH 刷新、重试延迟 |
| `context.Context` | `CancellationToken`（自建轻量类）或 `ExecutorService.shutdownNow()` | 超时/取消传播 |
| `sync.Once` | `AtomicBoolean` + `compareAndSet` 或 `synchronized` 双检锁 | 延迟初始化/单次执行 |
| `WaitGroup` | `CountDownLatch` 或 `CompletableFuture.allOf()` | 等待多任务完成 |

#### 关键线程分配

```
┌─────────────────────────────────────────────────────────────────┐
│ 线程池/线程                          │ 来源               │ 数量 │
├──────────────────────────────────────┼────────────────────┼──────┤
│ TUN 读线程（单线程）                  │ TunReadLoop         │ 1    │
│ SOCKS5/HTTP 监听 Accept（NIO Selector）│ Selector 事件循环   │ 1-2  │
│ SOCKS5 连接中继（NIO Selector）        │ Selector 管理       │ 1-2  │
│ WS 写工作线程（per channel）           │ ClientPool.writeWorker │ 3-16 │
│ WS 读循环（per connection）           │ ClientPool.messageLoop│ 3-16 │
│ 后台定时任务（ScheduledExecutor）      │ ThreadPool(4)       │ 4    │
│ relay 测速/评分                       │ CachedThreadPool    │ 弹性 │
│ DNS 预热                              │ CachedThreadPool    │ 8    │
│ 网络/电源/时区 BroadcastReceiver       │ Android 主线程       │ 0    │
├──────────────────────────────────────┼────────────────────┼──────┤
│ 总常驻线程预算                        │                    │ ≤40  │
└─────────────────────────────────────────────────────────────────┘
```

**NIO Selector 设计**：SOCKS5/HTTP 监听用 `ServerSocketChannel` + `Selector`（单线程 Accept Loop）；每个 SOCKS5 连接的双向中继注册到同一 Selector（OP_READ），写就绪时从 `LinkedBlockingQueue<ByteBuffer>` 取数据注册 OP_WRITE。这比 Go 的"每连接双 goroutine + 32KB 缓冲"节省线程但增加 Selector 管理复杂度——推荐先用每连接阻塞 Socket（简单），后优化为 NIO。

### 1.4 ASCII 数据流图

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                          Android VpnService (:vpn 进程)                       │
│                                                                              │
│   ┌─────────────┐                                                             │
│   │ TProxyService│                                                           │
│   │  startVpn()  │                                                           │
│   └──────┬──────┘                                                             │
│          │                                                                    │
│   ┌──────▼──────┐     ┌────────────────────────────────────────────────────┐ │
│   │ Builder.     │     │                                                    │ │
│   │ establish()  │     │  ┌────────────────────────────────────────────┐    │ │
│   └──────┬──────┘     │  │    TUN 转发层（过渡：C hev-socks5-tunnel    │    │ │
│          │ tun fd      │  │    目标：纯 Java TunForwarder）              │    │ │
│          │             │  │                                            │    │ │
│   ┌──────▼──────┐     │  │  ┌──────────┐  ┌───────────┐  ┌────────┐  │    │ │
│   │  C hev-socks5│     │  │  │ TUN 读   │  │ lwIP TCP  │  │ SOCKS5 │  │    │ │
│   │  -tunnel     │◄────│  │  │ loop     │→ │ /IP 栈    │→ │ 客户端 │──│──┐ │ │
│   │  (JNI)       │     │  │  │(1 线程)  │  │(单线程协同)│  │(每会话)│  │  │ │ │
│   └──────┬──────┘     │  │  └──────────┘  └───────────┘  └────┬───┘  │  │ │ │
│          │             │  └──────────────────────────────────────│──────┘  │ │
│          │             └────────────────────────────────────────│──────────┘ │
│          │ SOCKS5 (127.0.0.1:1080)                             │            │
│          │                                                     │            │
│   ┌──────▼──────────────────────────────────────────────────────▼──────┐     │
│   │                     Java 协议栈层                                    │     │
│   │                                                                    │     │
│   │  ┌──────────────────────────────────────────────────────────────┐  │     │
│   │  │  Socks5Server（SOCKS5 监听）                                  │  │     │
│   │  │  GCM: GcmSocks5Server ← shared/Socks5Server                 │  │     │
│   │  │  XT:  XtSocks5Server  + HttpProxyServer                     │  │     │
│   │  │  (NIO Selector, 1-2 线程)                                    │  │     │
│   │  └───────────┬──────────────────────────────────┬───────────────┘  │     │
│   │              │                                  │                   │     │
│   │  ┌───────────▼──────────┐  ┌───────────────────▼──────────┐       │     │
│   │  │  GCM 协议栈            │  │  x-tunnel 协议栈               │       │     │
│   │  │                       │  │                               │       │     │
│   │  │  GcmBackend           │  │  XtunnelBackend               │       │     │
│   │  │   ├ ConnectionPool    │  │   ├ ClientPool                │       │     │
│   │  │   │  (6 定时循环)     │  │   │  (背压/帧聚合/fast retry) │       │     │
│   │  │   ├ StreamManager     │  │   ├ PairWarmer (Hot Pair)    │       │     │
│   │  │   │  (位图+AIMD窗口)  │  │   ├ WriteWorker (per chan)   │       │     │
│   │  │   ├ RelayManager      │  │   ├ RelayNodeManager         │       │     │
│   │  │   │  (评分/负载均衡)  │  │   ├ XtDialer (ECH重试)      │       │     │
│   │  │   └ GcmMessage        │  │   ├ XtProtocol (8B 编解码)  │       │     │
│   │  │     (2B 头编解码)     │  │   └ UdpAssociation          │       │     │
│   │  └───────────┬──────────┘  └───────────┬──────────────────┘       │     │
│   │              │                          │                          │     │
│   │  ┌───────────▼──────────────────────────▼──────────────────┐       │     │
│   │  │              共享层 (Shared)                              │       │     │
│   │  │  DoHClient (3服务器轮转)  │  EchManager (缓存/刷新)      │       │     │
│   │  │  DnsCache (TTL 5min)      │  Matcher (geoip/geosite)    │       │     │
│   │  │  RuntimeLogger (环形2000行)│  SharedConfig (140+ 常量)   │       │     │
│   │  └───────────┬──────────────────────────┬──────────────────┘       │     │
│   └──────────────│──────────────────────────│──────────────────────────┘     │
│                  │                          │                                │
│   ┌──────────────▼──────────────────────────▼──────────────────────────┐     │
│   │              WebSocket / TLS 层                                     │     │
│   │                                                                    │     │
│   │  GCM: OkHttp WebSocket                                             │     │
│   │       URL: wss://WorkerHost/UserID?fallbackip=IP                   │     │
│   │       Host + 写死 Chrome/109 Edge UA                                │     │
│   │       TLS 1.3 (SNI=WorkerHost)                                     │     │
│   │                                                                    │     │
│   │  XT:  OkHttp WebSocket (阶段1) / 自研 WS (阶段2+ECH)              │     │
│   │       URL: wss://server?client_id=...&ch_id=N                      │     │
│   │       Token→WS Subprotocol                                         │     │
│   │       TLS 1.3 (SNI=server hostname)                                │     │
│   │                                                                    │     │
│   │  ECH (阶段2):                                                     │     │
│   │    DoH→ECHConfigList→BouncyCastle bctls ECH patch 或纯 Java TLS13 │     │
│   └────────────────────────────────┬───────────────────────────────────┘     │
│                                    │                                         │
│   ┌────────────────────────────────▼───────────────────────────────────┐     │
│   │              网络联动（Java, 不变）                                  │     │
│   │  NetworkCallback (onAvailable/onLost → 300ms 防抖 reconnect)      │     │
│   │  ScreenReceiver (息屏 ≥60s → reconnect)                           │     │
│   │  TimeZoneReceiver (→ setTimeZone 空操作)                           │     │
│   └───────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ WebSocket over TLS
                                    ▼
                          ┌─────────────────┐
                          │  Cloudflare Worker │
                          │  / x-tunnel Server │
                          │  / Relay 节点      │
                          └─────────────────┘
```

---

## 2. 关键设计决策

### 2.1 决策 1：TUN 转发器——保留 C 还是纯 Java？

| 方案 | 推荐度 | 理由 |
|---|---|---|
| **A. 保留 hev-socks5-tunnel C 库（推荐）** | ⭐⭐⭐⭐⭐ | 正确性已验证；零工作量；无 TCP 状态机风险；纯 Java 迁移风险隔离在协议栈 |
| B. 纯 Java TUN 转发器 | ⭐⭐ | 需重写 lwIP 级 TCP 栈（3000–5000 LOC），正确性极难保证；业内无先例（03, 05） |
| C. 纯 Java 但简化（跳过 TCP 终结） | ⭐ | 不可行——TUN 是裸 IP 包，内核 TCP 不会替我们回 SYN-ACK（03） |

**推荐路径**：Phase 1–3 保留 C 库；Phase 4 根据真机基准决定是否推进纯 Java。若推进，先写 Java TCP 状态机（独立模块），经独立 TCP 一致性测试后替换 C 库。

**保留 C 库的副作用**：CI 仍需 NDK + hev clone（但可锁定 ref），APK 仍含 .so 文件。这是可接受的过渡代价。

### 2.2 决策 2：ECH 技术路线

基于 04-ech-java-feasibility 调研结论：

| 方案 | 推荐度 | 风险 | 工作量 |
|---|---|---|---|
| **A. 分阶段：Phase 1 不含 ECH，Phase 2 补 ECH** | ⭐⭐⭐⭐⭐ | L→M | Phase 1 零 ECH；Phase 2 vendor bctls + ECH 补丁 |
| B. BouncyCastle bctls vendor + ECH 补丁（Phase 2 首选） | ⭐⭐⭐⭐ | M+ | 800–1500 LOC 改动 + vendor 源码树 |
| C. GuardianProject Conscrypt fork（Phase 2 备选） | ⭐⭐⭐ | M（供应链） | 100–300 LOC 集成 |
| D. 纯 Java 自研 TLS13+ECH（Phase 2 后备） | ⭐⭐ | H | 4000–6500 LOC |
| E. C shim BoringSSL ECH（用户拍板） | ⭐⭐ | M+（违反纯 Java） | 800–1500 LOC C |

**关键约束**：xtunnel 默认 `EnableECH=true`；Phase 1 若以 `enable_ech=false` 交付，服务端兼容无碍（ECH 扩展不发 = 标准 TLS 1.3）。**Phase 1 功能等价于 Go 版"ECH 降级路径"**，用户体验差异仅在"某些 CDN 站点可能慢 10–50ms（无 ECH 保护）"。

**Phase 2 ECH 拍板前提**（来自 04 调研 §7）：
1. 确认 ECH 联调环境（服务端发布 ECH 配置 + 私钥）
2. 确认 minSdk24 上 TLS 1.3 覆盖（平台 SSLSocket 需 API 29+；若要 24+ TLS 1.3，需 BC bctls）
3. 确认是否引入 BouncyCastle 依赖（`bcprov-jdk18on:1.80`，宽松许可）

### 2.3 决策 3：WebSocket 选型

| 方案 | 推荐度 | 理由 |
|---|---|---|
| **A. OkHttp WebSocket（Phase 1 推荐）** | ⭐⭐⭐⭐⭐ | minSdk 21+；HTTP/2 连接池；Subprotocol 支持；NetDial 替代（relay 端口替换需 `OkHttpClient.Builder.socketFactory`） |
| B. java.net.http.WebSocket（Java 11+） | ⭐⭐⭐ | API 简洁但 Android API 21+ 不保证全量实现；无 Subprotocol API |
| C. 自研 WebSocket（Phase 2+ ECH 路线 D 时） | ⭐⭐ | 需 300–500 LOC（帧解析/mask/分片）；仅在自研 TLS 栈无法与 OkHttp 配合时需要 |

**relay 端口替换**：Go 版用 `gorilla/websocket.Dialer.NetDial` 替换 TCP 连接目标（URL SNI 不变）。OkHttp 无等价公开 API，需用 `OkHttpClient.Builder.socketFactory(SocketFactory)` 自定义工厂——当 `relayAddr != null` 时创建到 `relay.IP:Port` 的 Socket，TLS SNI 仍通过 `SSLParameters.setServerNames()` 设为 WorkerHost。这是 Phase 1 的已知适配点。

### 2.4 决策 4：背压与写队列

Go→Java 移植要点（基于 01 调参历史 + 02 协议规格）：

- **全局字节预算**：`AtomicLong globalQueueBytes`，`reserveQueueBytes(size)` CAS 递增 + 超限拒绝（8MB 默认）
- **单通道写队列**：`ArrayBlockingQueue<WriteJob>(16384)`（固定容量，满则等 500ms）
- **Pause 降级**：`CountDownLatch resumeSignal`；Pause 状态下 `await(3s)`，超时 → CAS 降级为 SlowDown
- **SlowDown**：`Thread.sleep(10)` per write（简单但有效；避免 `TimeUnit.NANOSECONDS` 过度精确）
- **TCPData 聚合**：同 connID 的连续 TCPData 帧合并，`maxAgg = 256KB`（`ByteArrayOutputStream` 预分配 + 累积，满则 flush 一帧）——这减少 WS 帧数 50–80%，对 GC 友好

### 2.5 决策 5：DoH/DNS/ECH 回退链

Go 版行为（04-ech-java-feasibility §1.2–1.3）：

```
DoH 多服务器（3 个内置）→ 每服务器 2 次重试 → UDP DNS 8.8.8.8:53 → 系统 DNS
     ↓ 查 ECH 配置
ECHConfigList (type 65 SVCB) → 缓存 24h → 定时刷新 12h → singleflight 去重
     ↓ 注入 TLS 握手
ECH 使能 → Go: tls.Config.EncryptedClientHelloConfigList
         → Java Phase 1: 不发 ECH 扩展（标准 TLS 1.3）
         → Java Phase 2: BC bctls ECH patch 或 Conscrypt fork
     ↓ 失败降级
连续 3 次 ECH 失败 → 禁用 5 分钟 → 刷新配置 → 重试
```

**Java 移植要点**：
- DoH：照抄轮转/重试逻辑，HTTP 层用 OkHttp（`POST application/dns-message` + `GET application/dns-json`）
- DNS 缓存：`ConcurrentHashMap<String, CacheEntry>`，key=`domain:type`，`ScheduledExecutor` 每 1min 清理
- ECH 管理器：Phase 1 完整移植（缓存/singleflight/定时刷新），仅 `GetTlsConfig` 返回标准 TLS（不注入 ECH）
- singleflight：`ConcurrentHashMap<String, CompletableFuture<byte[]>>`，`computeIfAbsent` + `thenCompose`

### 2.6 决策 6：路由匹配

Go 版用 `go:embed` 内嵌 `geoip_cn.txt`（5822 行 IP CIDR）+ `geosite_cn.txt`（6410 行域名后缀）。

**Java 方案**：
- 数据文件放 `app/src/main/assets/`（APK 内嵌，与 Go embed 等价）
- 首次使用时 `assets.open()` 读取 → 解析为内存数据结构
- IP 匹配：Trie 或 `LongestPrefixMatch`（IP CIDR → long 比较）
- 域名匹配：`HashMap<String, Boolean>`（后缀匹配，Go 版是倒序后缀 Trie）
- 启动开销：~50–100ms（文件解析），可后台线程预加载

### 2.7 决策 7：API 兼容层

基于 06--android-ci-surface 推荐：

**采用方案 A**：保留 `xclient.Xclient` Java 类（静态 facade），方法签名与 gomobile 生成的完全一致。

```
xclient.Xclient
  ├── startSocksProxy(String, String, String, boolean) → void (或映射为 exception)
  ├── stopSocksProxy()
  ├── reconnect(String)
  ├── setTimeZone(String) → 空操作
  ├── validateBypassRules(String) → void
  ├── appendRuntimeLog(String, String)
  └── getRuntimeLogs() → String
```

**理由**：app 7 处调用点零改动；参数 JSON 格式完全一致；`:vpn` 多进程 + `MODE_MULTI_PROCESS` 不受影响。

---

## 3. 分阶段实施计划

### Phase 0：工程骨架 + 测试基建 + GHA 测试流

| 项 | 内容 |
|---|---|
| **目标** | 建立 Java 化的工程骨架、测试基础设施、CI 流水线 |
| **范围** | `app/build.gradle` 重构（移除 NDK/splits/AAR 依赖）；新建 `com.x.client.core` 包结构；JUnit 5 + Mockito + OkHttp MockWebServer 依赖；GHA test workflow |
| **具体工作** | 1) 新建 `app/src/main/java/xclient/Xclient.java`（空 facade）；2) 新建 `app/src/main/java/com/x/client/core/` 全包结构（空类占位）；3) `app/build.gradle`：删除 NDK/externalNativeBuild/splits.abi/AAR 依赖，新增 `testImplementation 'org.junit.jupiter:junit-jupiter:5.10+'`、`testImplementation 'org.mockito:mockito-core:5.+'`、`testImplementation 'com.squareup.okhttp3:mockwebserver:4.12+'`；4) `.github/workflows/test.yml`：JVM 单测 + lint（detekt/ktlint 可选）；5) Go 侧导出黄金向量脚本（`golib/.../export_test_vectors.sh` → JSON 文件，用于 Java 对拍） |
| **退出准则** | GHA `test.yml` 通过（空测试也需绿）；`Xclient.java` 编译通过；黄金向量 JSON 已在 `app/src/test/resources/` |
| **预估工作量** | 3–5 人天 |
| **依赖** | 无前置 |

### Phase 1：GCM 基础链路（不含 ECH）

| 项 | 内容 |
|---|---|
| **目标** | GCM 协议全功能 Java 实现（不含 ECH），通过与真实服务器联调验证 |
| **范围** | `SharedConfig`（全局常量）；`GcmMessage`（2B 编解码）；`DoHClient`（RFC8484+JSON+UDP）；`DnsCache`+`DnsWarmup`；`Matcher`（路由匹配 + geoip/geosite assets）；`RuntimeLogger`（环形缓冲）；`RelayManager`（评分/测速/负载均衡）；`ConnectionPool`（连接创建/维护/心跳/位图流分配）；`StreamManager`（AIMD 窗口流控）；`TrafficCounter`；`GcmSocks5Server`（SOCKS5→GCM 隧道 + 乐观回复）；`LifecycleManager`；`Xclient.java` facade 实现 |
| **具体工作** | 1) shared 层（config/dns/routing/logger）先行；2) GCM 协议层（message/pool/stream）；3) relay 层；4) SOCKS5 层；5) 集成：TProxyService 改调 Java Xclient（**保留 C TUN 转发器**）；6) 与 GCM worker 服务器联调 |
| **退出准则** | 1) JUnit：所有 Go 测试向量（message 编解码、DNS 缓存、路由匹配、relay 评分）通过；2) 集成测试：Java SOCKS5 监听 → GCM WebSocket → server CONNECT → 双向数据；3) 真机：VPN 建立 → 浏览器 HTTP → 网页可加载（无 ECH） |
| **预估工作量** | 15–20 人天 |
| **依赖** | Phase 0 |

### Phase 1.5：x-tunnel 基础链路（不含 ECH）

| 项 | 内容 |
|---|---|
| **目标** | x-tunnel 协议全功能 Java 实现（不含 ECH），含 TCP/UDP 中继、背压、Hot Pair |
| **范围** | `XtProtocol`（8B 编解码）；`XtunnelConfig`；`ClientPool`（连接管理 + 背压 + 帧聚合 + fast retry + 通道选择）；`ClientConnState`/`WriteJob`/`UdpAssociation`；`RelayNodeManager`（xtunnel 版评分）；`PairWarmer`+`HotChannelPair`；`XtDialer`（WS 拨号，ECH 重试逻辑保留但 Phase 1 不发 ECH）；`XtSocks5Server`（CONNECT + UDP ASSOCIATE + RFC1929）；`HttpProxyServer`；`XtClient`；`XtunnelBackend` |
| **具体工作** | 1) 协议层（XtProtocol/IpStrategy）；2) 连接池（ClientPool — 最复杂模块，1674L Go → 预估 2000–2500L Java）；3) 背压（全局 8MB + 16384 槽 + Pause 3s 降级）；4) 帧聚合（writeWorker TCPData 合并）；5) fast retry + 指数退避；6) Hot Pair（prebind + 槽位 + 状态机）；7) SOCKS5 + HTTP 代理；8) 集成：协议分发（protocol 参数选择 GCM/Xtunnel） |
| **退出准则** | 1) JUnit：8B 编解码向量、背压阈值（8MB/500ms/Pause 3s）、帧聚合、fast retry 退避序列；2) 集成测试：Java SOCKS5 → x-tunnel WS → server TCPConnect/ConnStatus → 双向数据；3) 真机：VPN 建立 → speedtest 上下行 → 无断连（背压触发正常） |
| **预估工作量** | 20–25 人天 |
| **依赖** | Phase 1（共享层已就绪） |

### Phase 2：ECH + DoH 完整链

| 项 | 内容 |
|---|---|
| **目标** | ECH 功能完整交付，行为与 Go 版一致 |
| **范围** | `EchManager`（完整实现：缓存/singleflight/定时刷新/降级 5min）；ECH 注入 TLS 握手（Phase 2 首选 vendor BouncyCastle bctls + ECH 补丁）；GCM `handleDialError` ECH 降级逻辑；xtunnel `XtDialer` ECH 重试逻辑 |
| **具体工作** | 1) vendor BouncyCastle bctls 源码；2) 实现 ECH 扩展构造（outer/inner ClientHello + HPKE Seal + accept_confirmation）；3) 在 bctls `TlsClientProtocol` 层补 ECH 钩子；4) 与 Go 版抓包对拍（ClientHello 字节对比）；5) ECH 降级测试（服务端不支持 ECH → 回退标准 TLS）；6) GCM 连续 3 次 ECH 失败 → 禁用 5 分钟；7) 真机验证：ECH 启用 + ECH 降级路径 |
| **退出准则** | 1) JUnit：ECHConfigList 编解码（与 Go golden vector 对拍）；HPKE Seal/Open（RFC 9180 测试向量）；2) 集成测试：ECH 启用 → 连接成功；ECH 服务端关闭 → 客户端降级标准 TLS 并记录日志；3) 抓包验证：ClientHello 含 ECH 扩展，accept_confirmation 正确 |
| **预估工作量** | 15–25 人天（取决于 bctls vendor 难度） |
| **依赖** | Phase 1 + Phase 1.5；ECH 拍板决策 |

### Phase 3：一致性回归 + 性能调优

| 项 | 内容 |
|---|---|
| **目标** | 全面回归测试 + 性能对标 + 边界修复 |
| **范围** | Go 测试向量全量对拍；真机 Perfetto 分析；背压/fast retry/hot pair/relay 评分边界回归；SOCKS5 突发并发（softLimitWait 100ms）；长稳测试（6h+） |
| **具体工作** | 1) Go 侧导出全量 golden vectors（152 个测试函数 → JSON 向量文件）；2) Java JUnit 跑同一批向量；3) 真机 speedtest 对比（Go APK vs Java APK）；4) Perfetto trace 分析 GC 停顿；5) 长稳混合流量测试（视频+上传+小包）；6) 修复不一致项；7) 路由绕过规则边界（geoip/geosite/bypass_rules）回归 |
| **退出准则** | 1) 全量 golden vector 通过（100%）；2) 真机 speedtest 差异 <15%；3) 6h 长稳无 GC 尖峰 >30ms / 无内存泄漏；4) 所有 02-protocol-spec 26 条验收基线逐条通过 |
| **预估工作量** | 10–15 人天 |
| **依赖** | Phase 1 + Phase 1.5 + Phase 2 |

### Phase 4（可选）：纯 Java TUN 转发器

| 项 | 内容 |
|---|---|
| **目标** | 替换 C hev-socks5-tunnel，实现纯 Java 数据面 |
| **前置条件** | Phase 3 真机基准达标；TCP 状态机模块独立通过 TCP 一致性测试（RFC 793/1122/6675） |
| **范围** | `TunForwarder`（纯 Java TUN→SOCKS5）；`TcpSessionManager`（用户态 TCP：SYN/ACK/重传/窗口/SACK/拥塞）；`UdpSessionManager`（UDP NAT）；`MappedDns`（198.18.0.2 ↔ 240.0.0.0/8 双向映射） |
| **预估工作量** | 30–45 人天（含 TCP 正确性验证） |
| **退出准则** | 独立 TCP 一致性测试（与内核 TCP 对拍）；真机 speedtest 与 C 版差异 <10%；移除 C 库后 CI 不再需要 NDK |

### 工作量汇总

| 阶段 | 人天 | 里程碑 |
|---|---|---|
| Phase 0 | 3–5 | 工程骨架 + CI 测试流 |
| Phase 1 | 15–20 | GCM 链路可用（无 ECH） |
| Phase 1.5 | 20–25 | x-tunnel 链路可用（无 ECH） |
| Phase 2 | 15–25 | ECH 完整交付 |
| Phase 3 | 10–15 | 一致性回归 + 性能调优 |
| Phase 4（可选） | 30–45 | 纯 Java TUN（取决于拍板） |
| **合计（P0–P3）** | **63–90** | **≈ 3–4.5 人月** |
| **合计（P0–P4）** | **93–135** | **≈ 4.5–6.5 人月** |

---

## 4. 测试策略

### 4.1 Go 测试 → JUnit 映射

| Go 测试文件 | 行数 | Java 测试类 | 移植方式 |
|---|---|---|---|
| `gcm/protocol/message_test.go` | ~120 | `GcmMessageTest` | **直接移植**：编解码向量 → JUnit `@ParameterizedTest` |
| `gcm/pool/connection_test.go` | ~300 | `ConnectionPoolTest` | **重写**：Mock WS + 状态机测试 |
| `gcm/pool/stream_manager_test.go` | ~200 | `StreamManagerTest` | **直接移植**：位图分配/流状态机 |
| `gcm/relay/manager_test.go` | ~150 | `RelayManagerTest` | **直接移植**：评分/负载均衡 |
| `xtunnel/protocol/*_test.go` | ~200 | `XtProtocolTest` | **直接移植**：8B 编解码向量 |
| `xtunnel/pool_test.go` | ~400 | `ClientPoolTest` | **重写**：背压/帧聚合/fast retry |
| `xtunnel/socks5_test.go` | ~200 | `XtSocks5ServerTest` | **重写**：SOCKS5 握手 + UDP ASSOCIATE |
| `shared/dns/*_test.go` | ~300 | `DoHClientTest`/`DnsCacheTest` | **直接移植**：DNS 编解码 + 缓存 TTL |
| `shared/routing/matcher_test.go` | ~250 | `MatcherTest` | **直接移植**：IP/域名匹配 |
| `shared/socks5/server_test.go` | ~150 | `Socks5ServerTest` | **重写**：Mock WS 后端 |
| 其他（ech/config 等） | ~100 | 各自 Test 类 | 视情况直接移植或重写 |

**直接移植**：协议编解码、评分算法、数据结构逻辑——Go 测试的断言条件可直译为 JUnit `assertEquals`。  
**重写**：涉及 goroutine/channel/WS 的集成测试——Java 版用 MockWebServer + ExecutorService 模拟并发。

### 4.2 协议一致性测试设计

**思路：Go 侧在 GHA 上生成黄金向量 → Java 单测跑同一批向量。**

1. **Go 侧生成脚本**：在 `golib/` 下新增 `cmd/export-vectors/` 工具，把 Go 测试的编解码用例序列化为 JSON：
   ```json
   {
     "gcm_encode": [
       {"streamId": 1, "type": 0, "data": "example.com:443|", "expect": "01006578616d706c652e636f6d3a3434337c"},
       ...
     ],
     "xtunnel_encode": [
       {"type": 1, "connId": "uuid-xxx", "meta": [0, "1.2.3.4:443"], "payload": "...", "expect": "01..."}
       ...
     ]
   }
   ```
   GHA 中 `go run cmd/export-vectors/main.go > vectors.json && upload-artifact`。

2. **Java 侧消费**：`@MethodSource` 或 `@ParameterizedTest` 读取 `vectors.json`，逐条 encode/decode 校验。

3. **集成测试**：MockWebServer 模拟 GCM worker 和 x-tunnel server，验证完整握手时序（SOCKS5→CONNECT→CONNECTED→DATA→CLOSE）。

4. **真实服务器手工集成清单**（02-protocol-spec §3 全部 26 条基线逐条验证）：
   - GCM：URL 格式（fallbackip 不编码）、Host/UA 头、TLS 1.3 SNI、帧格式、CONNECT 载荷、心跳时序
   - XT：URL 格式（client_id/ch_id 编码）、Token 子协议、8B 头、通道选择、背压、Hot Pair

### 4.3 三层测试架构

```
┌─────────────────────────────────────────────────────┐
│  Layer 3: 真机验收                                    │
│  - VPN 建立 → 浏览器/APP 可用                         │
│  - speedtest 上下行                                   │
│  - 6h+ 长稳（内存/GC/温度）                           │
│  - Perfetto trace                                    │
│  - 网络切换/息屏重连                                  │
└───────────────────────┬─────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────┐
│  Layer 2: 集成测试 (GHA JVM)                          │
│  - MockWebServer 模拟 server                          │
│  - 完整 SOCKS5→协议→WS 握手                           │
│  - 背压触发/降级路径                                   │
│  - fast retry 退避序列                                │
│  - ECH 降级路径                                       │
│  - 路由绕过规则                                       │
└───────────────────────┬─────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────┐
│  Layer 1: 单元测试 (GHA JVM)                          │
│  - 协议编解码（golden vectors）                        │
│  - 数据结构（位图/缓存/评分）                          │
│  - 状态机（流状态/连接状态）                           │
│  - 背压阈值/定时器                                    │
│  - DNS 编解码 + SVCB 解析                            │
└─────────────────────────────────────────────────────┘
```

### 4.4 性能基准方案（引用 05 调研）

**GHA JVM 参考基准**（JMH，定位相对开销）：
- IPv4/TCP/UDP 头部解析吞吐（ops/µs）
- GCM 2B + XT 8B 帧编解码吞吐（msg/s）
- SOCKS5 回环中继（loopback MBps）
- 背压队列入队/记账（ops/ms）

**真机基准**（人工/脚本执行）：
- speedtest（Go APK vs Java APK 同设备同节点）
- Perfetto 60s 全速下载 trace（GC 停顿/每核 CPU%）
- 100ms 采样 PPS/吞吐/队列长度
- 6h+ 混合流量长稳

**达标阈值**（05 调研 §9）：
- 下行 ≥ Go 版 0.7×
- 平均单核 CPU < 80%
- GC 尖峰 < 30ms
- 丢包率 < 2%
- 内存 < 200MB

---

## 5. 一致性验收清单

> 所有"服务器与用户可见"必须一致的行为列表，供最终验收对照。基于 02-protocol-spec §3 的 26 条基线扩展。

### A. 协议字节（服务器可见）

| # | 行为 | GCM/XT | 一致性要求 |
|---|---|---|---|
| 1 | GCM WS URL = `wss://<WorkerHost>/<UserID>[?fallbackip=<ProxyIP>]`，fallbackip **不 URL 编码** | GCM | 逐比特一致 |
| 2 | GCM HTTP 头：`Host: <WorkerHost>` + Chrome/109 Edge UA 写死字符串 | GCM | 字符串精确匹配 |
| 3 | GCM 直连 TCP→WorkerHost；中转→relay 但 Host/SNI=WorkerHost | GCM | 一致 |
| 4 | XT WS URL = `wss://<server>?client_id=<..>&ch_id=<N>`，两参数 URL 编码，ch_id 从 1 起 | XT | 一致 |
| 5 | XT Token 经 WS 子协议传输；401→中止 | XT | 一致 |
| 6 | GCM TLS MinVersion=TLS1.3；ECH 失败→回退标准 TLS | 两协议 | 一致 |
| 7 | GCM SOCKS5 无鉴权，无论 methods 回 `{05 00}` | GCM | 一致 |
| 8 | GCM 帧 = 2 字节头 `[streamID:1][type:1][data]`，帧边界由 WS 消息界定 | GCM | 逐字节一致 |
| 9 | CONNECT 载荷 = ASCII `host:port\|`（IPv6 `[v6]:port\|`，port 十进制） | GCM | 逐字节一致 |
| 10 | CONNECTED/CLOSE 无 data；DATA 原样二进制 | GCM | 一致 |
| 11 | 先注册 handler 再发 CONNECT；乐观回 SOCKS5 success 后等 CONNECTED | GCM | 时序一致 |
| 12 | XT 帧 = 8 字节大端头 `[t:1][len(id):1][len(meta):2][len(payload):4]` + id + meta + payload | XT | 逐字节一致 |
| 13 | TCPConnect meta=[IPStrategy][target]，payload=首包；connID=UUIDv4 文本（36 字符小写 hex） | XT | 一致 |
| 14 | SelectUplink meta=BE32 通道号；SelectDownlink meta=BE32 通道号 | XT | 一致 |
| 15 | ConnStatus meta[0]=0(OK)/1(ERR) | XT | 一致 |
| 16 | Backpressure meta[0]=0/1/2（Normal/SlowDown/Pause） | XT | 一致 |
| 17 | 背压队列上限 8MB，写队列超时 500ms，Pause 等待 ≤3s 后降级 SlowDown | XT | 数值一致 |
| 18 | prebind：MsgPrebindRequest(0x10) + connID=`prebind-<uuid>` + target=`x-tunnel.prebind`；强制 uplink≠downlink | XT | 一致 |
| 19 | UDP：UDPConnect 广播；UDPData meta=服务端回显目标；UDPClose 关联 | XT | 一致 |
| 20 | TCPData 可聚合（同 connID、meta 空、总长 ≤256KB） | XT | 一致 |
| 21 | WS 保活：GCM Ping 15s/Pong 3s 超时退休；XT Ping 5s/读超时 15s | 两协议 | 数值一致 |
| 22 | Shutdown 发 WS Close(1000) 帧 | XT | 一致 |
| 23 | GCM CONNECT 握手等待 TunnelTimeout（1m） | GCM | 一致 |
| 24 | GCM 读 deadline = 心跳间隔+超时（18s） | GCM | 一致 |
| 25 | XT SOCKS5 回 `{05 01...}`（失败）或 `{05 07...}`（命令不支持） | XT | 一致 |
| 26 | 路由绕过→本地直连；GCM 回 `{05 00}`；XT HTTP CONNECT 回 200 | 两协议 | 一致 |

### B. 内部逻辑（功能一致性）

| # | 行为 | 一致性要求 |
|---|---|---|
| 27 | 连接池维护循环：1s/5s/10s/15s/1min（GCM）| 间隔一致 |
| 28 | ECH 连续 3 次失败→禁用 5 分钟→刷新配置 | 语义一致 |
| 29 | Relay 评分=延迟+失败×500；负载均衡 Top5 加权随机 | 算法一致 |
| 30 | Stream AIMD 窗口：初值 1MB/64KB/4MB，Congestion→减半/+8KB | 算法一致 |
| 31 | DNS 缓存 TTL 5min，cleanup 1min | 数值一致 |
| 32 | DNS 预热 40 域名，并发 8，总超时 15s | 参数一致 |
| 33 | DoH 3 服务器轮转，每服务器 2 次重试，Google /resolve 特判 | 逻辑一致 |
| 34 | Hot Pair：1 对默认，30s 刷新，prebind 3s 超时，槽位 01-08 | 参数/状态机一致 |
| 35 | Fast retry：100ms+rand(300ms)，窗口 1s，连失 3 退出；指数 3s×1.5→60s；20 次慢速 | 序列一致 |
| 36 | SOCKS5 下行队列 64 槽 + 2s 超时；上行/下行缓冲 32KB | 参数一致 |
| 37 | xtunnel writeWorker TCPData 聚合 maxAgg=256KB | 一致 |
| 38 | xtunnel UDP 拦截端口默认 [443] | 一致 |
| 39 | 运行时日志格式 `[HH:mm:ss] [LEVEL] [scope] message`，2000 行/256KB | 格式/容量一致 |

### C. 用户可见行为

| # | 行为 | 一致性要求 |
|---|---|---|
| 40 | Profile 编辑页：GCM 字段（WorkerHost/PrefIp/UserId/FallbackIp/DisableEch/DisableIpv6Route）+ XT 字段（ServerAddr/Token/Connections/...）| UI 不变 |
| 41 | 高级参数（backpressure_limit MB/超时秒/count/端口列表）| UI 不变 |
| 42 | 日志展示（RuntimeLogActivity）：trim + 原样显示 | 格式不变 |
| 43 | 状态通知（STATUS_STARTING/STARTED/STOPPED/ERROR）| 不变 |
| 44 | 配置导出 URI（gcm:// / ech:// / xtunnel://）| 语义不变 |
| 45 | APK 体积 2–5MB universal（vs 当前 30–80MB 4 ABI）| 预期改善 |

---

## 6. CI 计划

### 6.1 GHA Workflow 草图

#### `build-debug.yml`（重构后）

```yaml
name: Build Debug
on:
  push:
    branches: [main, develop, 'feat/*', 'fix/*']
  workflow_dispatch:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v3
        with: { distribution: temurin, java-version: 17, cache: gradle }
      - uses: android-actions/setup-android@v3
      # ✅ 移除：hev clone / Go setup / gomobile
      - name: Unit tests
        run: ./gradlew testDebugUnitTest --no-daemon
      - name: Lint
        run: ./gradlew lintDebug --no-daemon

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v3
        with: { distribution: temurin, java-version: 17, cache: gradle }
      - uses: android-actions/setup-android@v3
      # ✅ 移除：hev clone / Go setup / gomobile
      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon
      - name: Upload universal APK
        uses: actions/upload-artifact@v4
        with:
          name: x-client-debug-universal
          path: app/build/outputs/apk/debug/app-debug.apk
```

**关键变化**：
- ❌ 移除 `git clone --recursive hev-socks5-tunnel`
- ❌ 移除 `setup-go` / `gomobile init` / `gomobile bind`
- ❌ 移除 `check AAR exists` 步骤
- ✅ 新增 `testDebugUnitTest` 步骤
- ✅ 产物从 4 个 per-ABI APK → 1 个 universal APK
- 构建时间预估：从 ~10–15 分钟缩减至 ~4–6 分钟

#### `test.yml`（新增，Phase 0 引入）

```yaml
name: Tests
on:
  push:
    branches: [main, develop, 'feat/*', 'fix/*']
  pull_request:
    branches: [main]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v3
        with: { distribution: temurin, java-version: 17, cache: gradle }
      - uses: android-actions/setup-android@v3
      - name: Run JUnit tests
        run: ./gradlew testDebugUnitTest --no-daemon
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: app/build/reports/tests/
```

#### `generate-vectors.yml`（Phase 1 引入，Go 侧黄金向量）

```yaml
name: Generate Test Vectors
on:
  workflow_dispatch:  # 或 push 到 golib/ 时触发

jobs:
  export:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v6
        with: { go-version: '1.25' }
      - name: Export vectors
        run: cd golib && go run cmd/export-vectors/main.go > ../app/src/test/resources/vectors.json
      - name: Upload vectors
        uses: actions/upload-artifact@v4
        with:
          name: test-vectors
          path: app/src/test/resources/vectors.json
```

#### `release.yml`（重构后）

```yaml
name: Release
on:
  push:
    tags: ['v*']
  workflow_dispatch:

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v3
        with: { distribution: temurin, java-version: 17, cache: gradle }
      - uses: android-actions/setup-android@v3
      # ✅ 移除：hev clone / Go setup / gomobile
      - name: Run tests
        run: ./gradlew testDebugUnitTest --no-daemon
      - name: Build release APK
        run: ./gradlew assembleRelease -PVERSION_CODE=... -PVERSION_NAME=...
      - name: Sign APK
        # ... 同现有签名步骤
      - name: Create Release
        # ... 同现有 release 步骤，但产物为单个 universal APK
```

**APK 体积变化**：

| 指标 | 当前 | Java 化后 |
|---|---|---|
| 产物数量 | 4 个 per-ABI APK | 1 个 universal APK |
| 单个 APK 大小 | 8–20MB | 2–5MB |
| 总下载体积 | 30–80MB | 2–5MB |
| 含 native .so | 是（Go runtime + hev） | 否（纯 Java） |
| 构建时间 | ~10–15 分钟 | ~4–6 分钟 |

---

## 附录 A：Go→Java 并发翻译速查

| Go 代码模式 | Java 翻译 |
|---|---|
| `go func() { ... }()` | `executor.submit(() -> { ... })` |
| `ch := make(chan T, cap)` | `new LinkedBlockingQueue<>(cap)` |
| `ch <- v` | `queue.put(v)` 或 `queue.offer(v)` |
| `v := <-ch` | `queue.take()` 或 `queue.poll(timeout)` |
| `select { case v := <-ch1: ... case <-time.After(d): ... }` | `poll(timeout)` on combined queue; 或 `CompletableFuture.anyOf()` |
| `mu.Lock(); defer mu.Unlock()` | `lock.lock(); try { ... } finally { lock.unlock(); }` |
| `atomic.LoadInt64(&x)` | `atomicLong.get()` |
| `atomic.CompareAndSwapInt32(&s, 0, 1)` | `atomicInteger.compareAndSet(0, 1)` |
| `sync.Once { fn() }` | `atomicBoolean.compareAndSet(false, true)` + `fn()` |
| `time.AfterFunc(d, fn)` | `scheduler.schedule(fn, d, MILLISECONDS)` |
| `ctx, cancel := context.WithCancel(parent)` | `CancellationToken` + `cancel.set()` |
| `defer close(ch)` | `try { ... } finally { ch.close(); }` 或 `scheduler.schedule(ch::close, d)` |
| `for v := range ch { ... }` | `while ((v = ch.poll()) != null) { ... }` 或阻塞 `while` + `take()` |

## 附录 B：Go 死代码排除清单

| 文件 | 行数 | 排除原因 | 迁移动作 |
|---|---|---|---|
| `gcm/pool/quality_monitor.go` | 256 | gcm/backend.go 从未 New/Start | 不迁移（或定义空接口占位） |
| `gcm/pool/proxy_transport.go` | 386 | EnableDoHProxy 恒 false | 不迁移 |
| `shared/config/flags.go` | 428 | urfave/cli v3 CLI-only | 不迁移 |
| `shared/config/loader.go` | 141 | yaml.v3 CLI-only | 不迁移 |
| `Logger.FileLogger` | ~100 | Android 恒 EnableLogFile=false | 不迁移 |

**迁移后净代码量**：Go 非测试 14,113L - 排除 ~1,311L = ~12,802L → 预估 Java 14,500–18,000L（含 Java 惯用冗余 + 注释）。

## 附录 C：关键风险矩阵

| 风险 | 等级 | 缓解策略 | 所属阶段 |
|---|---|---|---|
| ECH 不可用导致功能降级 | 🟠 中 | Phase 1 不含 ECH（标准 TLS 1.3 可用）；Phase 2 vendor bctls 补丁 | P1/P2 |
| TUN 转发器 TCP 正确性 | 🔴 高 | **保留 C hev-socks5-tunnel 过渡** | P4 才消除 |
| GC 停顿导致延迟尖峰 | 🟠 中 | 热路径零分配 + byte[] 池 + 预分配缓冲 | P1/P1.5 |
| xtunnel ClientPool 复杂度（1674L） | 🟠 中 | 逐函数对照移植 + 全量 golden vector + 背压专项测试 | P1.5 |
| OkHttp WS relay 端口替换适配 | 🟡 低 | 自定义 SocketFactory，已知方案 | P1 |
| minSdk 24 TLS 1.3 覆盖 | 🟡 低 | BC bctls 同时解决 ECH + TLS 1.3（Phase 2）；Phase 1 可接受部分设备 TLS 1.2 | P2 |
| CI hev clone ref 漂移 | 🟡 低 | Java 化后自然消除 | P0 |
| 路由数据文件 assets 加载延迟 | 🟡 低 | 后台线程预加载 + 缓存 | P1 |

---

*报告生成：基于 Phase 1 六份调研报告的综合分析与架构设计。所有引用路径相对仓库根 /opt/projects/x-client。本文件为唯一允许创建/修改的文件。*
