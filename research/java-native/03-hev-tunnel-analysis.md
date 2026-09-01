# hev-socks5-tunnel 与 Android TUN 转发链路调研：Java TUN 转发器需求规格

> 阶段：Phase 1 并行深度调研；任务：hev-tunnel-analysis。
> 结论前置，证据随附。代码证据基于分析时克隆的源码（main 分支 HEAD，hev-socks5-tunnel v2.17.1 版本常量），行号为近似值，以函数名与代码摘录为准。
> 来源 URL：https://github.com/heiher/hev-socks5-tunnel （主仓库，.gitmodules 声明 submodule：third-part/hev-task-system、third-part/yaml、third-part/lwip、src/core→hev-socks5-core）；https://github.com/heiher/hev-socks5-core ；https://github.com/heiher/lwip （lwipopts.h）；https://github.com/heiher/hev-task-system 。
> 工具可用性说明：read 可用；bash/fetch_content/web_search 在本环境不可用（bash 在执行早期命令后失效）；write 工具不可用，报告无法落盘，完整报告随 structured_output 返回，fileWritten=false。

## 0. 结论（先结论）

1. **hev-socks5-tunnel 不是简单 TUN→SOCKS5 逐包转发器，而是一个嵌入了完整用户态 TCP/IP 协议栈（lwIP，hev 维护的 fork）的 tun2socks 实现**。TCP 握手、重传、窗口/拥塞、IP 分片与重组、UDP NAT 全部由 lwIP 在单线程协同调度（hev-task-system，epoll reactor）内完成；每个 TCP/UDP 流对应一个独立任务（task），该任务先与本地 Go SOCKS5 服务器握手，再做数据 splice。
2. **Android 侧 TUN 为裸 IP 包、无 4 字节 per-packet 头**（hev-tunnel-linux.h 的 HEV_TUNNEL_GENERIC 路径直接 read/write 裸包）；Java 通过 ParcelFileDescriptor.getFd() 把已有 tun fd 传入，C 侧用 ioctl(FIONBIO) 置非阻塞。
3. **Java 化必须在用户态用 Java 重写 lwIP 级别的 TCP/IP 协议栈**（TCP 状态机/序列号/重传/窗口/RTT/IP 分片重组/校验和生成/IPv6/NDP 等），这是本任务最重的工作量与最高风险点；逐连接"软件 SOCKS5 客户端+内核 Socket"的廉价方案不成立，因为 TUN 上收到的原始 IP 包必须由实现方亲自回复 SYN-ACK/ACK，内核 TCP 不会代表我们应答这些包。
4. **与 Go 侧协议配对存在三处已证实的不兼容/缺口**：(a) GCM 后端（golib/shared/socks5/server.go）只实现 CONNECT(0x01)，UDP ASC(0x03)/FWD UDP(0x05) 一律回 0x07 并断开 → 当前 GCM 协议下 UDP 根本不工作；(b) x-tunnel 后端（golib/xtunnel/socks5.go）支持 CONNECT 与 UDP ASSOCIATE(0x03)，但不支持 FWD UDP(0x05) → 配置 udp:'tcp'（UDP-in-TCP 模式）与两个 Go 后端均不兼容；(c) 默认 udp:'udp'（UDP-in-UDP，标准 SOCKS5 UDP 帧）仅在 x-tunnel 后端可用。Java 版 TUN 转发器的对外协议必须以这些既有 Go 后端为准，而不是以 hev-socks5-core 的全部能力为准。
5. **Java 实现最大的技术障碍之一是 fd 语义**：纯 Java 无法把已有 tun fd 注册为 java.nio SelectableChannel（SelectableChannel 没有接受外部 fd 的公开构造），非阻塞 select/poll 驱动的 TUN 读写要么引入极少量 NDK shim（仅 fd 桥接），要么退化为阻塞 IO + 每线程读（吞吐和唤醒延迟显著劣化）。

## 1. CI/构建集成（步骤 1）

证据（见 .github/workflows/build-debug.yml 与 release.yml）：
- 两个 workflow 都执行 `git clone --recursive https://github.com/heiher/hev-socks5-tunnel app/src/main/jni`，**未指定 ref/branch/tag**（使用默认分支 main 的最新提交），且是 `--recursive` 递归拉 submodule（yaml/lwip/hev-task-system/hev-socks5-core）。→ Java 移植基线存在漂移风险（openQuestions #1）。
- env `ANDROID_NDK_VERSION: 26.3.11579264`；app/build.gradle 配 `ndkVersion "26.3.11579264"`、`externalNativeBuild { ndkBuild { path "src/main/jni/Android.mk" } }`（经典 ndkBuild 而非 CMake）、4 个 ABI（armeabi-v7a/arm64-v8a/x86/x86_64）、`splits.abi` 各自出 APK。
- defaultConfig 中 externalNativeBuild 参数：`APP_CFLAGS+=-DPKGNAME=com/x/client/app -ffile-prefix-map=...`、`APP_LDFLAGS+=-Wl,--build-id=none`。PKGNAME 是 JNI_OnLoad RegisterNatives 的类路径前缀（见后）。
- Android.mk（上游仓库）产出两个模块：`hev-socks5-tunnel`（BUILD_SHARED_LIBRARY，`-DENABLE_LIBRARY`）与 `hev-socks5-tunnel-bin`（独立可执行）；静态依赖 LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system。Application.mk：APP_OPTIM=release、APP_PLATFORM=android-29、APP_ABI 四架构、-O3、clang。构建产物：so 打包进 APK 各 ABI 目录，Java 侧 `System.loadLibrary("hev-socks5-tunnel")` 加载；xclient.aar 由 gomobile bind 独立产出至 app/libs/xclient.aar（与 hev 无关）。

## 2. hev-socks5-tunnel 逐项分析（步骤 2）

### 2.1 总体架构
- src/hev-main.c：`hev_socks5_tunnel_main` → config 解析 → `hev_task_system_init()`（epoll 事件循环）→ `lwip_init()` → `hev_socks5_tunnel_init(tun_fd)` → `hev_socks5_tunnel_run()` → `hev_task_system_run()`（阻塞直到 quit）。
- src/hev-socks5-tunnel.c：
  - `gateway_init()`：创建 lwIP netif（`netif_add_noaddr`，output=netif_output_v4/v6_handler），设置 loopback 地址，`netif_set_flags(NETIF_FLAG_PRETEND_TCP|PRETEND_UDP)`（hev fork 扩展：协议栈"假装"发出包，靠对端 TCP 重传兜底，本地不做队列）；`icmp` 配置为 reply 时加 `NETIF_FLAG_PRETEND_ICMP`。创建全局监听 tcp_pcb（`tcp_bind(NULL,0)` → `tcp_listen` → `tcp_accept(tcp_accept_handler)`）和 udp_pcb（`udp_recv(udp_recv_handler)`），作为所有隧道流量的入口。
  - 三个固定任务：`task_event`（停止信号 socketpair）、`task_lwip_io`（TUN 读）、`task_lwip_timer`（tcp_tmr 等周期定时，优先级 1）；每建立一个 TCP/UDP 会话再 spawn 一个任务（`tcp_accept_handler`/`udp_recv_handler` 里 `hev_task_new(stack_size)` + `hev_task_run`）。

### 2.2 TUN 读写
- fd 来源与模式：src/hev-socks5-tunnel.c `tunnel_init()`：`extern_tun_fd >= 0` 时（Android/JNI 模式）直接 `ioctl(extern_tun_fd, FIONBIO, nonblock=1)` 设为**非阻塞**，不再打开 /dev/net/tun；只有独立二进制模式（fd=-1）才走 hev-tunnel-linux.c 的 `hev_tunnel_open("/dev/net/tun", IFF_TUN|IFF_NO_PI)` + SIOCSIFMTU/SIOCSIFADDR 等 ioctl。
- 包格式：hev-tunnel.h 中 Linux 平台分支（HEV_TUNNEL_GENERIC，由 hev-tunnel-linux.h 定义）`hev_tunnel_read` 直接 `pbuf_alloc(PBUF_RAW, mtu)` 后单次 `hev_task_io_read(fd, buf->payload, buf->len, yielder, data)`——**无 4 字节 AF 头**；`hev_tunnel_write` 单 pbuf 时直接 `write(fd, payload, len)`（链式 pbuf 用 writev，最多 512 个 iov）。README 未专门说明，实现层面确认 Android VpnService TUN 是裸 IP 包（与 macOS utun 需要 4 字节头不同——那是 HEV_TUNNEL_GENERIC_HEAD 分支）。
- 批量/缓冲：每次循环读一个包（无批量读）；读缓冲= MTU（配置 8500）；`lwip_io_task_entry`：`buf = hev_tunnel_read(...)` → 成功则 `netif->input(buf, netif)`（ip_input 进入 lwIP）。**EAGAIN 语义**：hev-task-io 在非阻塞 fd 上遇 EAGAIN 时调用 yielder（`task_io_yielder` → `hev_task_yield(WAITIO)` 挂起，epoll 等待 POLLIN），且 `READ_ONCE(run)==0` 时立即返回 -1 退出循环；EINTR 由 hev-task-io 内部重试。
- 写失败：`netif_output_handler` 对 EAGAIN 返回 `ERR_WOULDBLOCK`（lwIP 在 PRETEND 模式下等价于丢弃，依靠对端 TCP 重传；UDP 直接丢失），其他错误 LOG_W 后返回 `ERR_IF` 丢弃。
- MTU 与分片：mtu=8500 直接作为 lwIP 的 netif MTU；lwIP 配置 IP_FRAG=1（出向超 MTU 分片，IP_FRAG_USES_STATIC_BUF=0 每次分配）、IP_REASSEMBLY=1（入向重组，hev/lwipopts.h：`MEMP_NUM_REASSDATA=1`，同一时刻只能有一个 IPv4 重组在途；IPv6 由 ip6_reass_tmr 处理）。

### 2.3 TCP
- 无显式连接表：lwIP 内部 tcp_pcb 以 (local_ip,local_port,remote_ip,remote_port) 四元组索引（tcp.c 中 `tcp_bind/tcp_listen/tcp_accept` 流程 + `pretend_netif_idx` 限定本 netif）；`MEMP_NUM_TCP_PCB=4096`、`MEMP_NUM_TCP_SEG=8192`（并发连接与段池上限）。
- 新连接：`tcp_accept_handler`（三次握手由 lwIP 完成，SYN-ACK 由 PRETEND 模式直接出 TUN）→ 创建 HevSocks5SessionTCP + 新 task → `hev_socks5_session_task_entry` → `hev_socks5_session_run`（src/hev-socks5-session.c）：先 `hev_socks5_client_connect(127.0.0.1:1080)`（10s connect timeout，见 hev-socks5-misc.c `connect_timeout=10000`）→ 可选 username/password 认证 → `hev_socks5_client_handshake`（写入 [VERSION=5, NMETHODS=1, METHOD]；标准模式读回 method，需 USER 时再走 RFC1929 creds；最后发 CONNECT 请求并读响应）→ `splicer`。
- 双向 splice（src/hev-socks5-session-tcp.c `hev_socks5_session_tcp_splice`）：
  - TUN→SOCKS5（上行 fwd_f）：lwIP `tcp_recv_handler` 把收到的 pbuf 追加到 `self->queue` 并唤醒任务；任务用 `writev(fd, iov[64])` 写 SOCKS5 socket，写成功后 `tcp_recved(pcb, s)`（缩小通告窗口=背压）+ 释放 queue 头。EAGAIN→返回 0 等 POLLOUT；其他错误→终止。
  - SOCKS5→TUN（下行 fwd_b）：`hev_ring_buffer_alloca(tcp_buffer_size)`（默认 65536，hev-config.c 会 clamp 到 `TCP_SND_BUF`=65528）从 socks fd `readv` 入 ring buffer，再 `tcp_write(pcb,...)+tcp_output` 送进 lwIP；`tcp_sent_handler` 里 `hev_ring_buffer_read_release(len, ...)` 回收。
  - 半关闭：对端 FIN → `self->pcb_eof=1` → queue 清空后 `shutdown(fd, SHUT_WR)`；SOCKS5 对端 EOF → ring buffer 清空后 `tcp_shutdown(pcb, 0, 1)`。
  - RST/异常：`tcp_err_handler` 置 `pcb=NULL` 并 terminate；terminate 会 `hev_socks5_set_timeout(0)` + `hev_task_wakeup`。
  - 空闲超时：`tcp_read_write_timeout=300000`（5 分钟无双向 IO 即终止，hev-socks5-misc.c `hev_socks5_task_io_yielder` 里 `hev_task_sleep(timeout)` 超时返回 -1）。
  - MSS：无逐流 clamp，统一 `TCP_MSS=8191`（lwipopts.h），MTU 8500 下 8191+IP+TCP 头 < 8500，无需钳制。
  - 端口/fd 上限：`max_session_count`（默认 0=不限；>0 时 `hev_socks5_tunnel_insert_session` 超限先 terminate 最旧会话）；`limit-nofile` 默认 65535（set_rlimit）。x-client 配置均未设置该项/该节 → 默认不限。
- 会话移除：session 结束时 `hev_socks5_tunnel_delete_session` + `tcp_abort`（session-tcp.c destruct 中 `tcp_recv(...,NULL); tcp_sent(NULL); tcp_err(NULL); tcp_abort(pcb)`）。

### 2.4 UDP
- 入口：lwIP 收到 UDP 包若无匹配 pcb，hev fork 的 udp.c（`udp_new_port` 贪心分配源端口，变体见 udp.c `local_port==0` 时 `port=udp_new_port()`）为每个唯一流 (src_ip,src_port,dst_ip,dst_port) 建 pcb 并分配**新映射源端口**——即应用层 NAT（README 称 Fullcone NAT：映射在会话存活期保持，回复可从任何远端进入）。`MEMP_NUM_UDP_PCB=1024`。
- `udp_recv_handler`（tunnel 层）为每个新 pcb 创建 HevSocks5SessionUDP + 任务。会话类型由配置 `socks5.udp` 决定：'udp'→ `HEV_SOCKS5_TYPE_UDP_IN_UDP`（标准 UDP ASSOCIATE，cmd=3）；'tcp'→ `HEV_SOCKS5_TYPE_UDP_IN_TCP`（扩展 FWD UDP，cmd=5）。
- 会话 splice（src/hev-socks5-session-udp.c `hev_socks5_session_udp_splice` + hev-socks5-core/src/hev-socks5-udp.c）：
  - 帧格式（UDP-in-UDP）：`[RSV=0x00 00(2B)][FRAG=0x00(1B)][ATYP(1B)][ADDR][PORT(2B,BE)][DATA]`（sendmmsg_udp 中 datlen/hdrlen 置 0）。UDP-in-TCP 帧：`[datlen(2B,BE)][hdrlen(1B)=3+addrlen][ATYP][ADDR][PORT][DATA]`（sendmmsg_tcp）。**注意：这是 C 作为 SOCKS5 客户端发给服务器 / 从服务器收到的 wire 格式，必须与 Go 后端配对**（见第 5 节）。
  - fwd_f（TUN→代理）：lwIP `udp_recv_handler`（session 内）把 pbuf 与目的地址（`pcb->local_ip/local_port`，即 app 发往的目的）打包成 `HevSocks5UDPFrame` 入 `frame_list`，`frames>UDP_POOL_SIZE(512)` 时**新包直接丢弃**（背压）；任务以 `hev_socks5_udp_sendmmsg` 批量（`udp-copy-buffer-nums` 默认 10×1500B）发出。
  - fwd_b（代理→TUN）：从 SOCKS5 UDP 控制 socket `recvmmsg`（UDP-in-UDP 未 associate 时先 connect 到首个来源=服务器 BND.ADDR，`udp_associated`），解析帧头得到回复源地址；**若该流的目标是 NAME（域名反查的假 IP），用会话启动时记录的 `self->addr/self->port`（=假 IP）替换回复源**（因为代理回包带的是真实远端 IP）；随后 `udp_sendfrom(pcb, saddr, port)` 写回 TUN，由 lwIP 根据 pcb 映射回 app 的 (src_ip,src_port)。
  - 地址类型：`hev_socks5_addr_from_lwip`（src/misc/hev-utils.c）对 IPv4 先做 `hev_mapped_dns_lookup`（仅当命 mapdns 网络）→ NAME，否则 IPV4/IPV6。回复帧解析只接受 IPV4/IPV6（`hev_socks5_addr_into_lwip` 对 NAME 返回 -1，但 NAME 场合已由 self->addr 覆盖）。
  - 超时：`udp-read-write-timeout=60000`（60s 无流量会话销毁，pcb 同时 udp_remove → 映射释放）。
  - 广播/组播/回环：无特殊处理——走普通 pcb 逻辑；VpnService 不注入 lo 流量，lwIP 里 netif 设了 loopback 地址但实际无回环转发。

### 2.5 DNS（mapped DNS）
- 配置（x-client TProxyService.writeTProxyConfig 生成，remoteDns=true 默认开启）：`mapdns: address: 198.18.0.2, port: 53, network: 240.0.0.0, netmask: 240.0.0.0, cache-size: 10000`。
- 拦截（src/hev-socks5-tunnel.c `udp_recv_handler` 开头的判断）：发往 198.18.0.2:53 的 IPv4 UDP 交给 `dns_recv_handler` → `hev_mapped_dns_handle`（src/hev-mapped-dns.c）：
  - 解析 DNS 头 (id/fl/qd/an/ns/ar)，要求 qd≤32；对每个 A 类查询（QTYPE==1 && QCLASS==1）把域名放入 LRU 缓存（红黑树 + 双链表，容量 10000，超限淘汰最旧），分配索引 idx（24 位，因为 netmask=240.0.0.0 → `idx = ip & ~mask`；要求 max ≤ ~mask 即在 240.0.0.0/8 范围内），**伪造 A 记录回复 IP=`240.0.0.0|idx`（240.x.y.z）**；不回 AAAA。设置 QR=1、RA=1（`fl | 0x8000 | ((fl&0x100)>>1)`），an=ipn。
  - 回复 `udp_sendfrom(pcb, b, local_ip, local_port)`。
- 反向：TCP/UDP 会话构造目标地址时，`hev_socks5_addr_from_lwip` 对命中的 240.0.0.0/8 假 IP 调用 `hev_mapped_dns_lookup` → 得到原域名 → SOCKS5 请求/帧用 **ATYP=NAME**；Go 服务器端用 DoH 解析。→ 这是 DNS 防泄漏/域名路由的关键：**Java 版必须完整实现该 mapdns 双向映射**。

### 2.6 ICMP 与其他协议
- 配置默认 `icmp: off`（x-client 不写该字段）→ 不回复 echo（README："Optional local ICMP Echo"）。回复模式（预制 ICMP 应答）由 `NETIF_FLAG_PRETEND_ICMP` 控制。
- 非 TCP/UDP/ICMP 的 IP 协议（如 SCTP/ESP 等）：lwIP 无 raw pcb → 静默丢弃。ICMPv6 NDP 由 lwIP nd6 处理（`nd6_tmr` 每 4 个 tcp_tmr tick，`LWIP_IPV6=1`）。

### 2.7 统计
- `hev_socks5_tunnel_stats(tx_packets, tx_bytes, rx_packets, rx_bytes)`（src/hev-socks5-tunnel.c，API 文档见 README）：tx=从 TUN 读出（应用上行、发往代理），rx=写入 TUN（代理下行）；统计变量为该进程全局，`hev_socks5_tunnel_fini()` 时清零。
- x-client 的 TProxyService.java 声明了 `TProxyGetStats()`（第 52 行）但**全仓 grep 无任何调用点**——统计值当前未被消费。

### 2.8 线程模型与生命周期
- src/hev-jni.c：JNI_OnLoad 里 `RegisterNatives`（PKGNAME/CLSNAME 由 -DPKGNAME=com/x/client/app + CLSNAME 默认 TProxyService 拼出）；`native_start_service` 持 mutex 判断 `is_running`，malloc ThreadData{config_path, fd}，`pthread_create` 一个 work_thread 跑 `hev_socks5_tunnel_main`（阻塞到 quit）。
- `native_stop_service`：`hev_socks5_tunnel_quit()` → `hev_socks5_tunnel_stop()`（src/hev-socks5-tunnel.c）：置 tsync，向 event_fds[1] 写 1 字节 → `event_task_entry` 读到后 `WRITE_ONCE(run,0)`，遍历 session_set 全部 `hev_socks5_session_terminate`，然后 `hev_task_join(lwip_io)` + `hev_task_join(lwip_timer)`；随后 pthread_join(work_thread)。JNI 层的 is_running/thread_joinable 状态机保证幂等。
- `native_is_running`：atomic 读 is_running（启动后 1，线程退出时置 0）。
- 运行时三个任务 + N 个会话任务全部跑在同一个 work_thread 上（协同式、仅 yield 点切换），`hev_task_mutex` 是会话任务与 lwip_io/timer 之间保护 pcb 结构的协同锁。
- `hev_socks5_tunnel_fini`：`tsync & SYNC_WAIT` 自旋等待 500us 后清理 mapper/lwip/tunnel/统计。

### 2.9 Android 特有 syscall / 系统依赖
- ioctl(FIONBIO)（设 tun 非阻塞）；setsockopt(IPV6_V6ONLY=0)（hev-socks5-misc.c `hev_socks5_socket`：所有出向 socket 建为 AF_INET6 双栈）；setsockopt(SO_RCVBUF=524288)（UDP 控制 socket）；SO_MARK（仅配置了 socks5.mark 才设，x-client 未用）；TCP_FASTOPEN_CONNECT（仅配置 tcp-fastopen 才设，未用）；signal(SIGPIPE,SIG_IGN)。
- 事件循环：hev-task-system 的 **epoll** reactor（src/kern/io/hev-task-io-reactor-epoll.h），配合 epoll_ctl ADD/MOD。**无 io_uring**；无对 tun 的二次 ioctl（MTU/地址/路由全由 Android VpnService.Builder 管）。

## 3. TProxyService.java 逐函数分析（步骤 3）

（app/src/main/java/com/x/client/app/TProxyService.java，731 行）
- `buildVpnInterface(prefs)`（L88-135）：`setBlocking(false)`；`setMtu(prefs.getTunnelMtu()=8500)`；IPv4 时 `addAddress(198.18.0.1, 32)` + `addRoute("0.0.0.0", 0)` + （remoteDns=false 且 dnsIpv4 非空时）addDnsServer；IPv6 且未 disable 时 `addAddress(fc00::1, 128)` + `addRoute("::",0)` + dnsIpv6；remoteDns=true（默认）时 `addDnsServer(getMappedDns()=198.18.0.2)` 作为唯一 DNS；global 时只排除自身包名（`addDisallowedApplication`），per-app 时 `addAllowedApplication` 逐个包名；`setSession` 名称 IPv4/IPv6/Global/per-App。
- `writeTProxyConfig(prefs)`（L137-172）：写入 `getCacheDir()/tproxy.conf`，字段逐行：
  - `misc.task-stack-size` = getTaskStackSize()=81920（注：hev-config.c 会 clamp 到至少 20480+max(tcp_buffer,udp_copy_buf)=20480+65528≈86008，81920 略低于 → 实际生效 86008+）；
  - `tunnel.mtu` = 8500；
  - `socks5.port` = getSocksPort() 默认 1080；`socks5.address` = '127.0.0.1'（getSocksAddress() 硬编码）；`socks5.udp` = getUdpInTcp()?tcp:udp → **默认 'udp'**（getUdpInTcp() 硬编码 false）；
  - 可选 `udp-address`（getSocksUdpAddress()="" → 不写）、`username/password`（均为空 → 不写）；
  - remoteDns=true 时追加 `mapdns`（address 198.18.0.2 / port 53 / network 240.0.0.0 / netmask 240.0.0.0 / cache-size 10000）。
- 启动时序 `startVpn()`（L70-111）：buildVpnInterface → `builder.establish()` 得 tunFd → writeTProxyConfig → `startProxy(prefs)`（调 `Xclient.startSocksProxy("127.0.0.1:1080", protocol, paramsJSON, true)` 启动 Go 侧监听，先于 C 隧道）→ `TProxyStartService(configPath, tunFd.getFd())` → `Thread.sleep(200)` 后 `TProxyIsRunning()` 校验 → 注册屏幕/网络回调 → 发 STATUS_STARTED → `monitorNativeTunnel(prefs)`。
- `monitorNativeTunnel`（L431-449）：单线程每秒 `TProxyIsRunning()`，false → `failStartup("hev-socks5-tunnel 意外停止")`（含 Xclient.stopSocksProxy + 关 fd + stopSelf）。
- 停止路径 `cleanupRuntime()`（L455-484）：置 runtimeRunning=false → 注销回调 → `TProxyStopService()`（join native 线程）→ `Xclient.stopSocksProxy()` → `tunFd.close()`；`onDestroy` 最后 `Process.killProcess(myPid())` 结束 :vpn 进程（保证下个会话全新进程）。
- 网络切换：`registerDefaultNetworkCallback` → onAvailable/onLost 判定 defaultNetwork 变化 → `scheduleReconnect` 延时 300ms 调 `Xclient.reconnect(reason)`（只重建 Go 侧连接，TUN 隧道不重启）；屏幕 OFF/ON 超 60s 也会触发重建。

## 4. Java TUN 转发器行为规格（步骤 4）

### 4.1 逐包处理流程（每包必经）
1. 从 tun fd 读原始 IP 包（缓冲 ≥ MTU 8500；非阻塞语义 = NIO/Selector 或 shim 的 POLLIN 驱动，EAGAIN 等价于"等可读"，退出条件等价 run==0）。
2. 版本判定 IPv4/IPv6 → 校验和（**发送路径计算**；入向校验按 C 一致地跳过以保持兼容与性能，留 openQuestions #4）。
3. IPv4 分片：MF/offset 判定 → 入向重组缓冲（内存上限等价 MEMP_NUM_REASSDATA=1）+ 重组超时 timer；出向若 IP+TCP 总长>MTU 则分片。
4. 协议分发：
   - TCP：五元组查连接表；无 → 建 TCP 会话（对 app 先回 SYN-ACK，起 3-way 握手、重传/窗口逻辑），会话连 127.0.0.1:1080 做 SOCKS5 CONNECT(0x01，目标=原始目的地址(IPV4/IPV6/NAME))，成功后 splice；有 → 按 seq 递交/乱序缓冲，回 ACK，管理窗口通告与零窗口，出向数据按发送队列 + RTO 重传、RST/FIN 语义与 4.3 一致。
   - UDP：四元组查 NAT 表；无 → 分配映射源端口并建 SOCKS5 UDP ASSOCIATE(0x03) 会话（当前兼容目标），有 → 直接转发；回复路径按会话记录的目标（NAME 流用假 IP 覆盖源地址）改写后写回 TUN。
   - 目的为 240.0.0.0/8 → 反查域名得 NAME，否则 IPV4/IPV6。
   - ICMP：off → 不回复 echo；可生成/透传 ICMP unreachable 语义可由实现简化（C 默认不做主动探测）。
   - 其他协议：丢弃。
5. 出向写 TUN：组帧（IP+TCP/UDP+校验和）→ 超 MTU 分片 → 写 tun fd；EAGAIN → 排队等 POLLOUT（TCP 靠会话发送队列，UDP 可丢）。
6. 会话/映射生命周期：TCP 5 分钟空闲、UDP 60 秒空闲（或实现内统一 TTL=60s，与 C 的 udp 超时一致）、映射释放时若 UDP 会话仍关联需先发 UDP 关闭语义（供上层/Go 回收）。

### 4.2 数据结构建议
- TCP 连接表：`ConcurrentHashMap<FlowKey5, TcpSession>`；FlowKey=(srcIp4/6, srcPort, dstIp, dstPort, proto)。TcpSession 含：发送缓冲（ring buffer，65536→65528 对齐）、接收重传队列、发送队列、seq/ack 状态、RTT/RTO、FIN/RST 标志、SOCKS5 通道状态。
- UDP NAT 表：`ConcurrentHashMap<FlowKey5, UdpSession>`；UdpSession 含映射源端口、目标地址（含 NAME 反查结果）、帧队列（上限 512 帧）、TTL（60s，LRU 维护）。映射端口分配器（复用检测）。
- mapdns：`ConcurrentHashMap<String 域名, Integer idx>` + 反向 `idx→域名`，LRU 容量 10000，idx 24 位（与 netmask 240.0.0.0 匹配 `ip & ~mask`）。
- 事件循环：单调度线程 + Selector（或 shim 桥接 fd）+ 会话线程池/虚拟线程（模拟 hev-task 协同调度，注意与 C 相同的"单写者"锁语义）。

### 4.3 边界情况清单（必须覆盖）
连接 reset（RST 乱序/带数据 RST 的处理与终止）；重传（超时重传、快速重传、Timestamps 可选）；乱序/丢包（对端重传、seq 空洞缓冲）；半关闭（FIN 与 SHUT_WR 双向、EOF 后残留数据、pcb_eof）；窗口为 0 与窗口更新；对端 SOCKS5 服务器拒绝（0x07 等—会话终止且不回数据）；CONNECT 握手期间 app 数据/后续包到达（lwIP 会在 pcb 建立前缓冲 SYN 之后的数据段？——C 由 lwIP 收队列兜底；Java 需同样在会话建立前缓冲）；UDP NAT 过期（映射释放后到达的回复丢包）；UDP 帧队列溢出（>512 丢弃，与 C 一致）；IPv6（源地址选择、扩展头、分片头、NDP NA/NS 最小应答）；DNS（仅 A 记录、qd>32 拒绝、域名长度 ≤255）；ICMP（默认丢弃 echo 请求，不生成 unreachable）；IP 分片重组超时；EAGAIN/背压（TUN 写满、SOCKS5 socket 写满、GC 停顿导致 RTO 波动）；fd 耗尽（连接数上限策略：max-session-count 语义=0 不限，或两表上限 4096 TCP/1024 UDP 对齐 lwIP 池）；会话数超限时终止最旧（与 `hev_socks5_tunnel_insert_session` 一致）。

### 4.4 Java 相对 C 的行为差异表（示意）
| 维度 | C (hev + lwIP + hev-task) | Java 实现 | 影响 |
|---|---|---|---|
| 事件循环 | epoll（hev-task-system） | java.nio Selector（底层 epoll）/ 或 shim | 语义近似；Selector 每次 key 处理粒度不同 |
| fd 非阻塞 | FIONBIO + 每 fd 一个 epoll 条目 | 纯 Java 无法注册外部 fd；需 shim 或阻塞 IO 线程 | **最大技术风险**（见结论 5） |
| EAGAIN 语义 | 写：ERR_WOULDBLOCK→PRETEND 丢弃；读：yield 等待 | NIO write 定长或 0 字节、selector 重试；读需显式 poll | 需自行实现与 PRETEND 等价的丢包策略 |
| checksum | 出向算，入向不验 | 可完全一致（算发送、验可选） | 性能差异可忽略 |
| 内存 | pbuf 池 + hev_malloc | byte[] 池 + GC 停顿 | 需对象池化，注意 GC 抖动对 RTO/重传的影响 |
| 线程 | 单线程协同 + 真线程 1 个 | 调度线程 + 会话线程池/虚拟线程 | JVM 线程切换开销 > 协同切换，会话多时注意 |
| 定时器 | tcp_tmr 250ms 周期 | ScheduledExecutor / 自旋 deadline 堆 | 精度与唤醒开销差异 |
| NAT 端口 | lwIP udp_new_port 贪心 | 自实现分配器 | 端口范围/复用策略需对齐 |
| fd 传递 | JNI int fd 直用 | PFD.getFd() + IO 桥 | java.io 无写超时语义，需要包装 |

### 4.5 性能热路径
- 上行每包：read(TUN) → 拷贝入 pbuf/byte[] →（IPv4 重组判定）→ 哈希查会话 → 会话接收队列 seq 处理 → 回 ACK/触发 SOCKS5 写（写杯：writev 到 loopback socket）→ 出向 ACK/数据 write(TUN)。
- 下行每包：read(SOCKS5 socket) → ring buffer → TCP 段化（MSS 8191）→ checksum →（>MTU 分片）→ write(TUN)。
- C 版优化点（Java 须参照）：入向不校验 checksum；单线程无锁（协同锁仅保护 pcb）；每包单次 read/write 无批量；writev 一次多段。Java 建议：byte[] 池、会话哈希 O(1)、避免每包 allocation（直接 ByteBuffer 复用）、checksum 用纯 Java 查表实现（Java 无 SSE 通道，本项为纯开销）。

## 5. SOCKS5 客户端行为与 Go 侧 server.go 配对（步骤 5）

C 客户端（hev-socks5-core）：
- 版本 5；无认证方法列表 `[5,1,0]`（无 user/pass）或 `[5,1,2]`（有）；服务器回 0x00 或 0x02，RFC1929 creds 仅在被要求时发送（hev-socks5-client.c `hev_socks5_client_handshake_standard`）。
- 命令：TCP=CONNECT(0x01)；UDP-in-TCP=FWD UDP(0x05)；UDP-in-UDP=UDP ASC(0x03)（hev-socks5-client.c `hev_socks5_client_write_request`）。
- 请求 ATYP：目标 IPv4/IPv6/域名（mapdns 反查）；响应 BND.ADDR 只接受 IPv4/IPv6（hev-socks5-client.c `hev_socks5_client_read_response`）；UDP 会话须解析 BND.ADDR 并 `connect()` 到该 UDP 中继（client-udp.c `hev_socks5_client_udp_set_upstream_addr`）。

Go 侧配对结果：
- **GCM 后端 golib/shared/socks5/server.go**（630 行）：
  - `handleAuth`（L87）：读 [ver,nmethods]+methods，**无条件回 `[0x05,0x00]`（NONE）且从不读 creds**。若 C 配了 user/pass，会在服务器表意 NONE 时跳过 creds（C 端判断 method==0 不再走 RFC1929），协议仍兼容；服务器端无 USER 选择逻辑。
  - `handleRequest`（L109-242）：`header[1] != cmdConnect` → 回 `[0x05,0x07,...]`（cmd not supported）后断开。→ **UDP ASC(0x03)/FWD UDP(0x05) 全部被拒**。ATYP 支持 1(IPv4)/3(域名，EnableDoH 时用 dnsCache.ResolveAny 预解析)/4(IPv6)。
  - `createTunnel`：CONNECT 请求后**乐观立即回 10 字节成功 `[0x05,0x00,0x00,0x01,0,0,0,0,0,0]`**；`createDirectTunnel` 直连同样 10 字节。与 C 客户端响应解析（4 字节头 + 按 atype 6/18 字节地址）兼容。
- **x-tunnel 后端 golib/xtunnel/socks5.go**（624 行）：
  - auth：无密码回 `[0x05,0x00]`；有密码回 `[0x05,0x02]` 并走 RFC1929（handleSOCKS5UserPassAuth）。
  - `handleSOCKS5` 命令分发：0x01 CONNECT ✓；**0x03 UDP ASSOCIATE ✓**（`handleSOCKS5UDP`：ListenUDP 绑定配置 host:0，回 BND.ADDR=实际监听地址（IPv4 优先 `[5,0,0,1,ip4...,port]`），然后 `udpAssociation.loop` 解析标准帧 `parseSOCKS5UDPPacket`（要求 RSV+FRAG=0、ATYP 1/3/4），锁定首包源 `clientUDPAddr` 后经 x-tunnel 通道转发，回复由 `buildSOCKS5UDPPacket` 组同格式帧写回）；**0x05（FWD UDP）不识别 → 回 0x07**。
- 结论：**C 客户端"标准 UDP ASSOCIATE(0x03)+SOCKS5 UDP 帧"行为仅在 x-tunnel 后端成立；"UDP-in-TCP(0x05)"无任何 Go 后端支持；GCM 后端无任何 UDP 支持。** Java TUN 转发器应实现与 C 完全一致的 SOCKS5 客户端线协议（V5/NONE 认证/CONNECT+UDP-ASC/三 ATYP/10 字节响应/标准 UDP 帧/对 NAME 流的假 IP 覆盖），对外兼容性即自动满足；UDP 会话的"关联失败即丢弃"语义（0x07 后关闭会话）也必须保留，使 GCM 协议下行为与现状一致（UDP 静默不可用）。

## 6. 附录
- 源码版本：hev-socks5-tunnel v2.17.1（src/hev-config-const.h：MAJOR 2 / MINOR 17 / MICRO 1）；hev-socks5-core（README：IPv4/IPv6、CONNECT、UDP ASSOCIATE、FWD UDP、user/pass）；lwip fork（lwipopts.h 如上）；hev-task-system（epoll reactor）。
- x-client 侧关键文件：app/src/main/java/com/x/client/app/TProxyService.java；golib/shared/socks5/server.go；golib/xtunnel/socks5.go；golib/android.go（StartSocksProxy 分发 gcm/xtunnel）；.github/workflows/*.yml；app/build.gradle。
- 未决事项与基线确认项汇总见 openQuestions。