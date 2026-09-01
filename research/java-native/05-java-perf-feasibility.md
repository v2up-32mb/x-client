# 05 · 纯 Java 数据面在 Android 上的性能可行性调研

> 调研子代理报告 · 面向「用原生 Java 完整实现 golib + hev-socks5-tunnel 功能组合」的总目标。
> 本文件只做可行性评估与风险分析，不含任何实现代码；本地零构建（规则 1 已遵守）。

---

## 0. 结论（TL;DR）

**纯 Java 能跑通"TUN 包处理 + 本地 SOCKS5/HTTP 代理 + GCM/xtunnel 协议栈"，在吞吐上大概率能追平 Go 版真机水平（移动端 20–100+ Mbps 场景）；但"性能"不是最大的可行性风险，最大的风险是《逻辑与正确性》：**

1. **TUN 是"IP 包"流，不是 TCP 流**。Android `VpnService` 建立的是 `IFF_TUN` 接口，应用读到的是裸 IP 包。要让它像普通网络一样工作，必须在用户态**终结 TCP**（回 ACK、处理重传/窗口/SACK）。C 侧正是靠内嵌 **lwIP 完整 TCP/IP 协议栈**（约 1000+ 行内核级状态机）完成的。Java 若想等价，要么自己实现一个正确、可收敛的 TCP（重传/拥塞/窗口/SACK 全都要，正确性极难），要么保留 C 转发器。**这一项决定"纯 Java 数据面"成败，实现量约 3000–5000 行且是最高风险区。**

2. **TUN fd 的 Java 读写是可行的**：`ParcelFileDescriptor.getFileDescriptor()` → `FileInputStream/FileOutputStream`；`java.nio Selector` **无法**注册裸 fd，但 Android 自 API 21 提供 **`android.system.Os.poll(StructPollfd[], int)`**，可直接对 tun fd 做可等待的 poll（`setBlocking(false)` 配合）；无 JNI 即可高效等待。

3. **每包系统调用数与 C 持平**：tun fd 一次 `read()` 只返回**一个 IP 包**（无批量 read 红利），输入侧 1 次 poll+1 次 read/包，输出侧 1 次 write/包——与 C 的 `hev_tunnel_read/write` 相同。

4. **纯 Java 的主要性能成本在"每包对象分配→GC 停顿"与"每连接线程模型"**，而非解析本身（IP/UDP/TCP 头部解析在 ART 上为纳秒级）。用"字节数组池 + 每包零分配 + 单读线程 + NIO Selector 管理 SOCKS 连接 + direct ByteBuffer（按需）"可把 GC 压力压到可接受。

5. **业内无纯 Java 大规模先例**：主流 Android VPN 客户端（shadowsocks-android、v2rayNG、NekoBox、Outline）数据面全部用 C/Go/Rust 原生核；连 hev 作者自己的 SocksTun 也只是"Java UI + C JNI 核"。这不是"做不到"的判决，但印证纯 Java 数据面在 Android 是**非主流、高风险**路线。

6. **可达标条件（量化建议）**：若把性能目标定为 **下行≤150 Mbps / 并发≤2000 TCP 流 / 每包 CPU 摊销 <~10µs**，纯 Java 方案可行；若目标 **>300 Mbps 或 3000+ 并发流或要求极低电量开销**，则应保留 C/Go 原生数据面（或 C TUN 转发器 + Java 协议栈的过渡方案）。最终只能靠**真机**验证（见 §6）。

---

## 1. 现状数据面特征（代码证据）

### 1.1 组件分工

- **C 层**：`hev-socks5-tunnel`（CI 阶段 `git clone` 到 `app/src/main/jni`，本地无源码，本文按 GitHub master 源码分析）。职责：读 VpnService 的 TUN fd 上的裸 IP 包，做**用户态 TCP/IP 终结**，再把每个连接经 loopback 转给本地 SOCKS5/HTTP。
- **Go 层**：`golib/`（14,113 行非测试 Go，见 `find golib -name '*.go' | grep -v _test | wc -l`）。职责：本地 SOCKS5/HTTP 监听 + GCM/xtunnel 协议栈（WS+ECH+DoH+连接池+relay+背压）。
- **Android 层**：`TProxyService.java` 建立 TUN（`builder.establish()` 返回 `ParcelFileDescriptor`），`setBlocking(false)`、`setMtu(prefs.getTunnelMtu())=8500`（`app/src/main/java/com/x/client/app/TProxyService.java:192-193`、`Preferences.java:390-392`），把 `tunFd.getFd()` 交给 C `TProxyStartService`。

### 1.2 hev-socks5-tunnel（C）结构（GitHub heiher/hev-socks5-tunnel master）

- **线程/任务模型 = 协作式任务系统**（hev-task），非每包一线程：
  - `task_lwip_io`（`hev-socks5-tunnel.c` `lwip_io_task_entry`）：单循环 `hev_tunnel_read(tun_fd, mtu, ...)` → `netif->input()` 交给 lwIP。
  - `task_lwip_timer`（`lwip_timer_task_entry`）：周期 `tcp_tmr()`（TCP_TMR_INTERVAL=250ms）驱动 TCP 状态机/超时。
  - `task_event`：关停事件。
  - 每个 TCP/UDP 会话一个协作任务（`tcp_accept_handler`/`udp_recv_handler` → `hev_task_new(stack_size)`），栈默认 86016 字节（`hev-config-const.h` `TASK_STACK_SIZE`，x-client 配置 `task-stack-size=81920`，`TProxyService.java:241-244`）。
- **TUN 读**：`hev_tunnel_read`（`hev-tunnel.h`）`pbuf_alloc(PBUF_RAW, mtu, PBUF_RAM)` + `hev_task_io_readv(fd, iov, 2,...)`——**每包一次 readv，返回一个 IP 包**（≤mtu=8500）。
- **TUN 写**：`netif_output_handler` → `hev_tunnel_write` → `writev(fd, iov, ≤512)`（pbuf 链）。
- **TCP 缓冲**：每会话 ring buffer `tcp_buffer_size=65536`（64KB，`hev-config.c:480`）；`tcp_splice_b` 从 SOCKS fd `readv` → `tcp_write`；`tcp_splice_f` 把 lwIP 出队 pbuf（≤64 个）`writev` 到 SOCKS fd 并 `tcp_recved` 回 ACK 窗口。
- **UDP**：`UDP_BUF_SIZE=1500`、`UDP_POOL_SIZE=512`（`hev-config-const.h`）。
- **关键点**：C 层在用户态实现了**完整 lwIP TCP/IP**（IP 分片 `ip4_frag/ip6_frag`、TCP 状态机、重传、窗口、ND6）。`hev_tunnel_read` 每包在堆上 `pbuf_alloc`（有 lwIP 内存池，非每包 malloc 都触发）。

### 1.3 Go golib 数据面

- **shared/socks5/server.go**（GCM 路径）：
  - `acceptLoop` 每连接 `go s.handleConnection(conn)`（协程/连接）；`SetNoDelay(true)`；读写 deadline 10s。
  - 每隧道方向 `buf := make([]byte, 32*1024)`（`server.go:425`）+ `io.Copy` 双向（`server.go:580-591`），两个 goroutine/隧道。
  - 下行 `downstream := make(chan []byte, downstreamQueueSize=64)`（`server.go:290`）、`downstreamQueueTimeout=2s`；`localClientWriteTimeout=10s`（`server.go:31-33`）。
  - GCM wire 协议 2 字节头 `[STREAM_ID:1][TYPE:1]`（`gcm/protocol/message.go`），pool 为连接池+多路复用流。
- **xtunnel（pool.go / socks5.go / config.go）背压参数（本次关注重点）**：
  - 全局字节预算 `DefaultBackpressureLimitBytes = 8 << 20`（8MB，`config.go:81`，曾从 16MB 回退）。
  - 单通道写队列 `writeQueueSize = 16384`（`pool.go:396`），会话级 `chan writeJob`。
  - `WriteQueueWaitTimeout = 500ms`（`config.go:101`，曾 100ms），超时返回「缓冲区拥堵」并释放 `reserveQueueBytes`。
  - `waitForBackpressure()` Pause 3s 降级 → SlowDown（`pool.go:1585-1595`）；`getBackpressureDelay()` 减速（`pool.go:1609-1616`）。
  - **TCPData 帧聚合**：`writeWorker` 把同 `connID` 的连续 TCPData 合并为一帧，`maxAgg = ReadBufferSize*4 = 256KB`（`pool.go:730-776`），显著减少 WS 帧数。
  - xtunnel socks5.go：TCP 中继 `buf := make([]byte, 32*1024)`（`socks5.go:366`）、UDP `64*1024`（`socks5.go:463`）。
- **真机调参历史（progress.md / tasks.md，v1.1.9）**：
  - 问题：speedtest **上传**断连——`asyncWriteDirect` 写队列满 → 超时 → `handleSOCKS5Connect` 直接 return（defer Close）→ speedtest 报网络错误。
  - 调参：写队列 4096→16384、等待 100ms→500ms、加 3s Pause 降级；客户端背压 8MB；服务端 1MB→32MB。
  - **含义**：Go 版在真机 speedtest 上传（典型 10–50 Mbps）时能把 8MB 全局写队列 + 16384 槽写满，说明数据面吞吐富余、瓶颈在链路/回程；背压机制是真机能触发的（非摆设）。
  - 另：SOCKS5 并发连接受突发尖峰误拒（progress.md 阶段14，加 100ms 软等待窗口）；GCM 用 256KB 滑动窗口流控（`gcm/pool/stream_manager.go:11-14`）。

---

## 2. Android VpnService 纯 Java 包处理（网络调研 + 源码）

> 参考：Android 官方 `VpnService` / `VpnService.Builder` 文档（developer.android.com/reference/android/net/VpnService [URL-1]）：establish() 返回 ParcelFileDescriptor；"packets are always started with IP headers"；`setBlocking(boolean)` "Sets the VPN interface's file descriptor to be in blocking/non-blocking mode"；`setMtu(int)` 设 MTU（默认 1500）。

### 2.1 读取 TUN fd 的三条 Java 路径

1. **`FileInputStream/FileOutputStream` 包装 `ParcelFileDescriptor.getFileDescriptor()`**（`java.io.FileDescriptor`）：走 JNI 直达 `read()/write()` 系统调用。**这是最直接、每包一次 syscall、开销最小的方式。**
2. **`FileChannel`**（`new FileInputStream(fd).getChannel()`）：`FileChannelImpl` 对 tun fd 的 read 仍是一次一包；但 **tun fd 无法作为 `java.nio.SelectableChannel` 注册进 `Selector`**（Selector 只接受 `SocketChannel/ServerSocketChannel/DatagramChannel`）。FileChannel 对 O_NONBLOCK fd 读返回 0（不抛 EAGAIN），因此**不能依赖 FileChannel 做非阻塞事件通知**。用途受限。
3. **`android.system.Os.poll(StructPollfd[] fds, int timeoutMs)`**（Linux poll(2)，Android API 21+，官文档 [URL-2][URL-3]）：**可以让纯 Java 对裸 tun fd 做可阻塞/超时的可等待**。这是纯 Java 高效 TUN 读循环的关键 API。

**推荐纯 Java 读循环**：
```java
StructPollfd p = new StructPollfd();
p.fd = tunFd.getFileDescriptor();
p.events = (short) OsConstants.POLLIN;
Os.poll(new StructPollfd[]{p}, -1 /* 或 ms */);        // 阻塞等待可读
if ((p.revents & OsConstants.POLLIN) != 0) {
    int n = tunIn.read(buf);                            // 一次读一个 IP 包
}
```
配合 `setBlocking(false)`（沿用现状）与 `Os.poll`。若不想用 `android.system`，可用阻塞模式 `setBlocking(true)` + 专用读线程（阻塞 read），但会牺牲 `Os.poll` 的超时/可打断能力，且关停时需守护 fd 关闭来打断阻塞读。

### 2.2 批量读：能否一次 read 读多包？

- **Linux tun 每 read 返回一个 IP 包**（packet 模式），与 C 侧 `hev_tunnel_read` 的"每包一次 readv"结论一致。**没有"一次 read 多包"的红利**（批量读红利只存在于 AF_PACKET/PACKET_MMAP 等，不适用 VpnService 的 tun fd）。
- 因此"批量读"的设计只能体现在：一次 poll 唤醒后**连续多次 read 直到 EAGAIN**，把多个包在下游（写 WS 前）聚合成批，减少上下文切换与 WS 帧数（Go 侧 `TCPData 聚合`已是同一思路）。

### 2.3 每包 syscall 分析（Java vs C）

| 环节 | C | 纯 Java |
|---|---|---|
| TUN 输入等待 | poll（hev-task epoll） | `Os.poll` 或阻塞 read |
| TUN 输入读 | 1× readv/包 | 1× read/包 |
| 头部解析 | lwIP 内存操作 | 字节数组索引/移位 |
| SOCKS 上行/下行 | 每连接 readv/writev | 每连接 Socket read/write（NIO 或阻塞线程） |
| TUN 输出写 | 1× writev(≤512 iov)/包 | 1× write/包 |

结论：**路径上的 syscall 数 Java 与 C 等价**（tun 读/写各自 1 次/包），性能差异主要来自解析的每包 CPU 成本、对象分配/GC、线程调度，而非系统调用次数。

---

## 3. Java 字节头解析与 NIO 技术要点

- **推荐每包零分配解析**：用**可复用的 `byte[]`** 直接以索引访问（`(b[i]&0xff)` 取值），16/32 位用移位拼装；或用**一个可复用 `ByteBuffer.wrap(buf)` 实例**反复 `position/limit` 定位（`ByteBuffer.wrap` 每包新建一个对象，避免；用一个持久化 ByteBuffer + 手动 reslice）。`asReadOnlyBuffer()` **每包会分配视图对象，避免在热路径使用**。
- **绝对 get 技巧**：`ByteBuffer.get(i)`/`getInt(i)` 是边界检查的绝对访问，比"读后改 position"少一次状态变更；对 20/40 字节头部每包约 5–15 次绝对访问，ART 上纳秒级——不是瓶颈。
- **避免 String 中间表示**：IP 地址/域名只在建连握手/日志处转 String；数据面热路径用 `byte[]` + 预分配小对象。IPv6 解析 40 字节头同理，注意扩展头跳过。
- **microbenchmark 参考量级（JVM/ART 训练数据经验，需 GHA JMH 验证）**：单次 20 字节 IPv4 头解析/校验 ~ 20–80ns；`System.arraycopy`/`Arrays.copyOfRange` 拷贝 1KB ~ 50–150ns；一次字节翻转排序/位操作 ~ 1–3ns/op。这些相对 1.5–8.5KB 的包传输延迟可忽略。

### 3.1 NIO direct 是否值得

- **数据面热路径大量小包时，建议用堆内 `byte[]`（池化）**：分配/读写开销低，无需管理 native 内存生命周期，且 `SocketChannel.read(ByteBuffer)` 用 direct 与否对 loopback 中继吞吐影响有限。
- direct `ByteBuffer` 的优势在**大块连续传输 + 避免 GC 拷贝**（如 256KB 聚合写）；代价是 native 显式分配/回收与池管理复杂。**建议：池化的堆 byte[] 为主，聚合写/大帧可选 direct。** 过度依赖 direct 增加正确性与内存管理风险，且 ART 上堆 byte[] 已足够跑移动 VPN 流量。

---

## 4. 代理吞吐参考（网络调研）

- Go 版真机调参记录（§1.3）显示其在 speedtest 上传（移动/家庭宽带 10–100 Mbps）下能打满写队列并触发背压，说明 Go 数据面吞吐富余、单个手机 CPU 核并非 100% 受限。
- **先例调研（GitHub 检索）**：
  - `heiher/sockstun`（742★，语言标 Java）：**数据面仍用 hev-socks5-tunnel C JNI 子模块**（`app/src/main/jni/hev-socks5-tunnel`），Java 只做 UI/Service——作者验证过"Java-only 不值得/没做"。
  - `xjasonlyu/tun2socks`（5469★，Go + gVisor TCP 栈）、`OutlineFoundation/outline-go-tun2socks`、`eycorsican/go-tun2socks`——纯 Go 用户态 TCP 栈先例（性能好但用 gVisor 重量级栈）。
  - shadow-android / v2rayNG / NekoBox / Outline：数据面全 C/Go/Rust 原生核。
  - **未检索到**在 Android 上以**纯 Java**实现生产级 tun2socks（TUN→TCP 终结→SOCKS）且规模化的公开项目（GitHub code search 在当前未认证环境受限，见 openQuestions）。
- **Java SOCKS5 代理吞吐参考**：常规 JVM 桌面 NIO/Socket 中继可达 GB/s 级线性吞吐、数十万 msg/s；**ART（armv8）按 ~10–40× 折减**，简单 TCP 中继仍在数百 MB/s 理论区间；但移动 VPN 受"每包对象分配 + GC + 线程调度 + 链路 MTU/ 回程"约束，**真机上稳定几十 Mbps 到 150 Mbps 是务实预期**（此区间与 Go 版手机场景重叠）。

---

## 5. ART 运行时特性（决定性的 Java 侧约束）

- **GC**：ART 是**分代** GC（young/老代）。Young（并发拷贝）暂停通常 **1–4ms**；老代/整堆 GC 暂停 **10–30ms 甚至更高**（取决于存活与堆大小）。每包分配驱动 GC 频率：10k pps × 每包 2–3 个对象 + 临时缓冲 → 每秒几万次分配 + 缓冲抖动，若不池化会频繁触发 young GC，出现周期性延迟尖峰（丢包/卡顿感）。
- **缓解**：字节数组池 + 每包零分配（对象在池中复用）、预分配大读缓冲、避免在高频路径创建 `String/ByteBuffer/Integer` 装箱、用 `ByteArrayOutputStream`/池取代 String 拼接。**目标：热路径零分配**。
- **JIT/编译**：ART 分层编译（interpreter → profiling → AOT/JIT）；release 构建经 **AOT（DEX→OAT）**，热路径已编译，无冷启动 JIT 惩罚；但高度内联/分支预测仍靠 profile 化。对稳态吞吐影响小，确定性比 JVM 桌面更可控。
- **线程数**：无硬性 16k/进程上限；该数字源于默认栈大小 × 地址空间的反推。Java 线程默认 native 栈 8MB 虚址（提交即用），大量 `Thread-per-connection` 在几千连接时即触碰内存/调度墙。**建议：上限 ≤数百常驻线程**，SOCKS 连接用 **NIO Selector（单/少线程）** 而非每连接一线程；tun 读单线程；每通道 WS 写可复用短句柄池。
- **电池/持续高 CPU**：Java 比 C 增加分配+GC+调度开销，长时间满载会放大耗电与发热；池化+零分配+低延迟 GC 可把增量压到小比例。**这是纯 Java 相对原生核最明显的隐藏成本。**

---

## 6. 逐环节开销预测与瓶颈点（Java 数据面）

| 环节 | 每包成本预估 | 瓶颈/风险 |
|---|---|---|
| TUN 读循环（Os.poll+read） | 1 poll + 1 read ≈ 1–3µs | 低；正确使用 Os.poll 是关键 |
| IP/UDP/TCP 头部解析（零分配） | 20–80ns | 低；用索引/移位，勿 String |
| **用户态 TCP 终结（回 ACK/重传/窗口/SACK）** | lwIP 在 C 亦 ~远超解析 | **最高风险**：正确性+实现量大（见 §7 结论） |
| TCP 连接表维护（NIO 通道复用） | 每连接哈希查表 ~50–200ns | 中低；HashMap/数组桶即可 |
| SOCKS5 握手（每连接） | 数十 µs | 低；仅首包/连接 |
| WS 帧编解码（xtunnel 8B 头 / GCM 2B 头） | 每帧 ~50–150ns | 低；零分配 + 聚合写 |
| 池写队列（16384 槽 + 8MB 字节预算 + 500ms 等待 + Pause 3s） | 每 job 入队/记账 | **中**：Java 对象入队会增加 GC/内存，需池化 job；背压语义改动前与 Go 对齐 |
| 背压（ByteBuffer 引用排队，不复制载荷） | 低 | 队列只存引用，勿 copy 载荷 |

**瓶颈排序**：用户态 TCP 终结（正确性/复杂度）> GC 停顿（分配未池化时）> 写队列对象开销 > 解析本身。吞吐上限主要由 **WS+TLS 与回程** 决定；纯 Java 的 tun→socks 中继在移动带宽下**通常不是第一瓶颈**，除非把每包分配和线程模型写坏。

---

## 7. 风险清单与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 用户态 TCP 状态机正确性（回 ACK/重传/拥塞/SACK/IP 分片） | 🔴 高 | 认真评估；reuse 成熟算法（参考 go-tun2socks/gVisor 语义）；或**保留 C TUN 转发器过渡**；大量协议级集成测试 |
| GC 停顿导致的延迟尖峰/丢包 | 🟠 中 | 热路径零分配 + byte[]/job 池 + 预分配大缓冲；年轻代并发收集参数校调 |
| 每连接一线程致线程数/内存墙 | 🟠 中 | NIO Selector 单/少线程；连接表哈希；限定并发上限 |
| 8MB/16384 写队列在 Java 的对象与内存开销 | 🟠 中 | 槽位存池化引用，不复制载荷；预留 8MB 预算语义与 Go 对齐 |
| 持续 CPU / 电池 / 发热 | 🟡 中低 | 零分配 + 低 GC；实测 CPU% 与温度 |
| 与 Go 版对外协议/背压实测不一致 | 🟡 中 | 语义级对照测试（5 组背压阈值、Pause 降级） |
| 小包高 PPS（ACK 风暴）时 GC/线程抖动 | 🟡 低中 | 小包聚合写 + 合并帧（复用 Go 的 TCPData 聚合思路） |

---

## 8. 基准测试方案

> 规则：本地无构建、无真机（一切构建在 GitHub Actions）。

### 8.1 GHA 上的 JVM 参考基准（JMH 单元测试，跑在 Actions 的 JVM host，不构建 Android）
在 `golib` 同级新增 `jmh/`（或独立 Gradle 子工程），提交触发 Actions，用 host JDK（17）跑：
- **包解析吞吐**：纯 Java 解析 IPv4/IPv6/TCP/UDP 头部（预置字节数组，measure ops/µs），确认零分配版本达到预期（对比 String/ByteBuffer 版本）。
- **WS 帧编解码吞吐**：GCM 2 字节头 + xtunnel 8 字节头 的 encode/decode，measure msg/s。
- **SOCKS5 代理回环吞吐**：JVM 内回环（loopback）中继 `client ↔ java socks5 server ↔ echo`，测 MBps/msgps；附 TCPData 聚合前后的帧数对比。
- **背压队列模拟**：入队/记账基准，确认 16384 槽 + 8MB 闭环在 Java 上不额外抖动。

> 注意：这些是 **JVM 参考值**，只用于定位相对开销；**绝对达标与否必须真机**（ART 与手机 CPU/内存/网络差异大）。

### 8.2 真机基准步骤清单（部署后人工/脚本执行）
1. **Perfetto/Systrace**：抓 60s 全速下载/上传 trace，看 `ProcessRenderState`/`sched`/`GC` 停顿、`art` GC 事件、每核 CPU%；确认无 >30ms GC 尖峰。
2. **speedtest 脚本**：同一台设备/同一节点，分别跑 Go 版 APK 与 Java 版 APK（其余设置一致：MTU 8500、8MB/16384/500ms/Pause 3s、同一 wss 服务端），记录去程/回程吞吐、失败次数、延迟抖动。
3. **每 100ms 采样统计**：`Os.poll` 循环内每 100ms 统计 PPS/吞吐/队列长度/背压降级次数，落盘对比两版。
4. **长稳测试**：≥6–12 小时混合流量（视频+上传+小包心跳）观察内存（无泄漏）、GC 次数、电池温度。
5. **半关闭/超时/突发并发回归**：移植 Go 版已修的边界（SOCKS5 突发软等待、Pause 3s 降级、重连后旧 worker 不抢包）逐一验证。

---

## 9. 结论：纯 Java 是否可达标

- **性能目标定义**（移动 VPN 务实值）：下行 **20–150 Mbps**、上行对称、并发 **≤2000 TCP 流**、小包延迟抖动 <30ms、长期 CPU 平均单核 <80%、内存 <200MB、可连续满载 ≥6h 不异常。Go 版真机调参记录表明它在手机带宽上"数据面富余、背压可触发"（§1.3），故目标对齐 Go 版场景是合理的。
- **判定**：
  - 若只要求"追平 Go 版真机移动带宽"（≤150 Mbps、≤2000 流）→ **纯 Java 可达**，但前提是：(a) 用户态 TCP 正确性达标（这是真正的硬门槛），(b) 热路径零分配 + 单读线程 + NIO Selector，(c) 背压/聚合语义与 Go 对齐，并经 §8 真机基准验证。
  - **若吞吐目标 >300 Mbps 或并发 >3000 流或要求极低电量** → 纯 Java 风险显著上升（GC/调度成为瓶颈），建议保留原生数据面。
  - **过渡方案（建议采纳）**：在纯 Java 数据面未通过真机基准前，**保留 C TUN 转发器（hev-socks5-tunnel）作为过渡**，仅把"本地 SOCKS5/HTTP + 协议栈"迁到 Java——这样先把最大风险（用户态 TCP）留在已验证的 C 栈，Java 只承担 SOCKS/协议/背压，性能与正确性都可控；纯 Java 全面替换留待基准证明后再决策。
- **兜底量化开关**：真机基准若出现 **下行 < 目标 0.7× 或平均单核 CPU >80% 或 >2% 丢包/GC 尖峰>30ms**，即切换回 C TUN 转发器 + Java 协议栈的过渡路线（Java 仍需保住协议栈可达标）。
- **为何只能真机验证**：ART（armv8）的 GC/编译/调度与 JVM host 差异大，GHA 上无法跑真机；吞吐/延迟/G C 参数只能靠真机 Perfetto + speedtest 定夺。本地无构建、无设备，故本报告给出的是**架构层面可行性 + 可执行的验证路径**，而非硬数字承诺。

---

## 10. 现场调研工具可用性说明

- 本机工具集未提供 `web_search`/`fetch_content`；改用 `bash + curl`（经出网代理）抓取：Android 官方 VpnService/Builder/Os/StructPollfd 文档、GitHub 仓库列表与源码（hev-socks5-tunnel 全量 C 源码已拉取到 /tmp/hev 分析）。
- GitHub code search API 在未认证环境下被限流/空结果，未能穷尽"纯 Java tun2socks"检索（见 openQuestions）。部分 JS 渲染页面（developer.android 部分、StackOverflow、Baeldung）抓不到正文，已据可抓取的官方 API 摘要 + 项目源码 + Rust 面训练知识做分析，并在 §5 注明 ROM 的 ART 数值为经验区间。

**来源 URL 摘要**：
- [URL-1] https://developer.android.com/reference/android/net/VpnService
- [URL-2] https://developer.android.com/reference/android/system/StructPollfd
- [URL-3] https://developer.android.com/reference/android/system/Os （`poll(StructPollfd[], int timeoutMs)`）
- [URL-4] https://github.com/heiher/hev-socks5-tunnel （主执行 hev-socks5-tunnel.c / hev-tunnel.h / hev-config.c 等）
- [URL-5] https://github.com/heiher/sockstun （Java UI + C JNI 核先例）
- [URL-6] https://github.com/xjasonlyu/tun2socks 、OutlineFoundation/outline-go-tun2socks 、shadowsocks/shadowsocks-android、2dust/v2rayNG（原生核先例）
