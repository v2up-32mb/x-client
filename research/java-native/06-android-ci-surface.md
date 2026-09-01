# 06 Android 集成面与 CI 构建链路调研报告

> 调研子代理：android-ci-surface  
> 日期：2026-09-01  
> 仓库：`v2up-32mb/x-client`  
> 分支：`main`

## 调研工具说明

可用工具：`read`、`bash`（只读命令）、`write`（限本文件）。

> **⚠ 不可用**：`web_search`、`fetch_content` 不在本任务工具列表中，所有论断均基于仓库内代码直接取证，无外部资料来源。

---

## 0. 结论先行

**Android app 的集成面高度集中且边界清晰**，这是 Java 化项目的核心利好：

1. **Go AAR 调用仅 7 处**，集中于 `TProxyService.java`（5 处）、`XclientApplication.java`（1 处）、`SettingsActivity.java`（1 处），共涉及 6 个静态方法。
2. **hev-socks5-tunnel C 库调用仅 4 个 native 方法**（`TProxyStartService/Stop/IsRunning/GetStats`），全部在 `TProxyService.java` 中；其中 `TProxyGetStats` 为死代码——**无人消费其返回值**。
3. **推荐方案 A：保留 `xclient.Xclient` Java 类（静态 facade）**，方法签名与现有 AAR 完全一致，app 调用点**零改动**。
4. **CI 变化巨大但正向**：去掉 Go/gomobile/NDK/jni clone 四个步骤，构建时间预计缩减 50%+；APK 从 4 个 per-ABI 变为 1 个 universal，体积缩减 60-80%。
5. **核心风险点**：`tproxy.conf` 写入 + TUN fd 读写（hev 包转发职责）的 Java 化实现是性能与正确性难点（由 03 子代理专门评估）；`:vpn` 多进程 + `MODE_MULTI_PROCESS` 坑必须在 Java 化后保持行为不变。

---

## 1. Xclient API 调用面穷举

### 1.1 完整调用点（rg `Xclient\.` app/src/main/java/）

| 文件 | 行号 | 调用 | 调用时机 |
|---|---|---|---|
| `TProxyService.java` | L289 | `Xclient.startSocksProxy(listenAddr, protocol, paramsJSON, true)` | VPN 启动线程（`startProxy` → `startVpn`） |
| `TProxyService.java` | L366 | `Xclient.setTimeZone(TimeZone.getDefault().getID())` | 服务 `onCreate` + 时区变更广播 |
| `TProxyService.java` | L465 | `Xclient.stopSocksProxy()` | `cleanupRuntime`（停止/异常恢复） |
| `TProxyService.java` | L644 | `Xclient.reconnect(reason)` | 网络变化/息屏重连（`scheduleReconnect` 线程） |
| `TProxyService.java` | L663 | `Xclient.appendRuntimeLog("AndroidVPN", message)` | 全部运行时事件（约 18 处调用） |
| `TProxyService.java` | L672 | `Xclient.getRuntimeLogs()` | `sendRuntimeLogs`（RuntimeLogActivity 触发） |
| `XclientApplication.java` | L13 | `xclient.Xclient.setTimeZone(...)` | App 启动（`Application.onCreate`） |
| `SettingsActivity.java` | L166 | `Xclient.validateBypassRules(bypassRules)` | 用户保存全局设置时校验 |

**共 7 个唯一调用点，涉及 6 个 API 方法**。`Xclient.notifyNetworkChanged()` 在 Go 侧有定义但 app 未使用（app 直接调用 `reconnect`）。

### 1.2 Go 侧 Android API 签名（`golib/android.go`）

```go
func StartSocksProxy(listenAddr, protocol string, paramsJSON string, verbose bool) error  // L49
func StopSocksProxy()                                                                     // L185
func Reconnect(reason string)                                                             // L202
func NotifyNetworkChanged()                                                               // L197（app 未调用）
func SetTimeZone(tz string)                                                               // L119
func ValidateBypassRules(rules string) error                                              // L180
func AppendRuntimeLog(scope, message string)                                              // L214
func GetRuntimeLogs() string                                                              // L219
```

gomobile bind 生成的 `xclient.Xclient` Java 类会为每个 Go 导出函数生成一个同名静态方法（含 `static native` 实现）。Java 版需写出等价静态方法。

---

## 2. 参数映射表

### 2.1 GCM 协议参数（`TProxyService.buildGCMParams` L297-325）

`Preferences` getter → JSON key → Go 消费点（`gcm/backend.go buildConfig`）：

| JSON Key | 来源 (Preferences) | 默认值 | 类型 | Go Config 字段 / 验证 |
|---|---|---|---|---|
| `worker_host` | `getWorkerHost()` | `""` (必填) | string | `c.WorkerHost`（空则 error）；去除 `wss://`/`https://` 前缀和尾 `/`（`normalizeWorkerHost`） |
| `ws_conn` | `getWsConn()` | `3` (1-64 clamp) | int | `c.MinPoolSize` / `c.MaxPoolSize`（动态池） |
| `relay_ips` | `getPrefIp()` | `""` | string(逗号分隔) | `c.RelayIPs []string` |
| `user_id` | `getUserID()` | `""` | string | `c.UserID`（WS 路径 wss://host/&lt;userID&gt;） |
| `proxy_ip` | `getFallbackIp()` | `""` | string | `c.ProxyIP`（fallbackip 查询参数 + 拨号） |
| `ech_domain` | `getEchDomain()` | `"cloudflare-ech.com"` (全局) | string | `c.ECHDomain`（空则不预取） |
| `ech_dns` | `getEchDns()` | `"https://doh.pub/dns-query"` (全局) | string | `c.DoHUrl`（空则用内置备用列表） |
| `enable_ech` | `!getDisableEch()` | `true` | bool | `c.EnableECH` |
| `disable_ipv6_route` | `getDisableIpv6Route()` | `false` | bool | **仅类型校验，由 VPN 层处理路由** |
| `enable_dns_warmup` | `getEnableDnsWarmup()` | `false` | bool | `c.EnableDNSWarmup` |
| `bypass_private` | `getBypassPrivate()` | `false` | bool | `routing.NewMatcher` 构造参数 |
| `bypass_geoip_cn` | `getBypassGeoIpCn()` | `false` | bool | `routing.NewMatcher` 构造参数 |
| `bypass_geosite_cn` | `getBypassGeoSiteCn()` | `false` | bool | `routing.NewMatcher` 构造参数 |
| `bypass_rules` | `getBypassRules()` | `""` | string | `routing.NewMatcher` 手动规则 |
| `enable_dynamic_pool` | `getEnableDynamicPool()` | `false` | bool | `c.EnableDynamicPool` |
| `dynamic_pool_max` | `getDynamicPoolMax()` | `16` (1-64 clamp) | int | `c.DynamicPoolMaxSize` |
| `log_level` | `getLogLevel()` | `"INFO"` | string(DEBUG/INFO/WARN/ERROR) | `c.LogLevel`（`config.ParseLogLevel`） |

> **注**：`JSONObject.put(key, boolean)` 输出无引号布尔值（`true`/`false`），`put(key, int)` 输出无引号数字。Go `parseParamsJSON` 统一转为字符串，兼容无引号标量。Java 版 `org.json.JSONObject.toString()` 序列化行为一致，不需要改动。

### 2.2 X-Tunnel 协议参数（`TProxyService.buildXtunnelParams` L327-362）

| JSON Key | 来源 (Preferences) | 默认值 | 类型 | Go Config 字段 (`xtunnel/config.go`) |
|---|---|---|---|---|
| `server_addr` | `getXtServerAddr()` | `""` (必填) | string | `c.ServerAddr`（空/error；须 wss:// 或 ws://） |
| `token` | `getXtToken()` | `""` | string | `c.Token` |
| `connections` | `getXtConnections()` | `3` (1-16 clamp) | int | `c.Connections` |
| `relay_nodes` | `getXtRelayNodes()` | `""` | string(逗号分隔) | `c.RelayNodes` |
| `enable_ech` | `!getXtDisableEch()` | `true` (Go DefaultConfig) | bool | `c.EnableECH`（Go 侧非空覆盖默认 true） |
| `ech_domain` | `getEchDomain()` | `"cloudflare-ech.com"` (全局) | string | `c.ECHDomain` |
| `dns_server` | `getEchDns()` | `"https://doh.pub/dns-query"` (全局) | string | `c.DNSServer` |
| `insecure` | `getXtInsecure()` | `false` | bool | `c.InsecureSkipVerify` |
| `enable_hot_pair` | `getXtEnableHotPair()` | `false` | bool | `c.EnableHotPair` |
| `hot_pair_count` | `getXtHotPairCount()` | `1` (1-8 clamp) | int | `c.HotPairCount` |
| `log_level` | `getLogLevel()` | `"INFO"` | string | `logLevelFromParams` |
| `bypass_private` | `getBypassPrivate()` | `false` | bool | `routing.NewMatcher` |
| `bypass_geoip_cn` | `getBypassGeoIpCn()` | `false` | bool | `routing.NewMatcher` |
| `bypass_geosite_cn` | `getBypassGeoSiteCn()` | `false` | bool | `routing.NewMatcher` |
| `bypass_rules` | `getBypassRules()` | `""` | string | `routing.NewMatcher` |

**高级参数 JSON 覆盖**（`Prefs.getXtAdvancedParams()` → `buildXtunnelParams` L352-362 合并逻辑）：

| Advanced Key | 语义 | 单位 | Go 字段 |
|---|---|---|---|
| `backpressure_limit` | 全局写队列背压阈值 | 字节 | `c.BackpressureLimitBytes`（默认 8MB） |
| `write_queue_wait_timeout` | 写队列满等待超时 | 毫秒 | `c.WriteQueueWaitTimeout`（默认 500ms） |
| `dial_timeout` | 拨号超时 | 秒（→ms） | `c.DialTimeout`（默认 3s） |
| `handshake_timeout` | 握手超时 | 秒 | `c.HandshakeTimeout`（默认 5s） |
| `read_timeout` | 读超时 | 秒 | `c.ReadTimeout`（默认 15s） |
| `write_timeout` | 写超时 | 秒 | `c.WriteTimeout`（默认 5s） |
| `ping_interval` | Ping 间隔 | 秒 | `c.PingInterval`（默认 5s） |
| `reconnect_delay` | 重连延迟 | 秒 | `c.ReconnectDelay`（默认 1s） |
| `connect_timeout` | 本地 SOCKS5 等待远端建链超时 | 秒 | `c.ConnectTimeout`（默认 15s） |
| `max_socks5_connections` | SOCKS5 最大并发连接数 | 数量 | `c.MaxSOCKS5Connections`（默认 1024） |
| `udp_blocked_ports` | UDP 拦截端口列表 | 逗号分隔 | `c.UDPBlockedPorts`（默认 443） |

> **注**：Go 侧还有 `ParamClientID = "client_id"` 参数，Android 端**不发送**（由 Go 自动生成 UUID）。Java 版同理，无需显式传入。

### 2.3 高级参数 UI 对应（`ProfileEditActivity.java` L436-488）

编辑页 UI 字段 → `collectXtAdvancedParams()` JSON → Go `buildConfig()` 逐 key 读取。注意 `backpressure_limit` 在 UI 中以 MB 为单位显示（`n * 1048576L`），Go 侧接收字节数。超时类字段以秒输入，Go 侧接收毫秒——由 `putTimeoutSeconds` 转换。

---

## 3. native 调用面（hev-socks5-tunnel）

### 3.1 四个 JNI 方法（`TProxyService.java` L49-52）

```java
private static native boolean TProxyStartService(String configPath, int fd);  // L49
private static native boolean TProxyStopService();                             // L50
private static native boolean TProxyIsRunning();                               // L51
private static native long[]   TProxyGetStats();                               // L52（声明但**无调用点**）
```

通过 `System.loadLibrary("hev-socks5-tunnel")` 加载（L68）。

### 3.2 TProxyGetStats：死代码

`rg TProxyGetStats app/src/main/java/` 仅命中声明（L52），**返回值 `long[]` 从未被读取**。hev C 库提供了 stat 信息，但 Android app 完全未消费。Java 版无需复刻，除非未来需求（如通知栏流量统计 UI）。

### 3.3 monitorNativeTunnel（`TProxyService.java` L422-437）

```java
private void monitorNativeTunnel(Preferences prefs) {
    new Thread(() -> {
        while (!stopping && runtimeRunning) {
            Thread.sleep(1_000); // 1 秒轮询周期
            if (!stopping && !TProxyIsRunning()) {
                appendRuntimeLog("hev-socks5-tunnel 意外停止");
                failStartup(prefs, new IllegalStateException("hev-socks5-tunnel 意外停止"));
                return;
            }
        }
    }, "gcm-vpn-monitor").start();
}
```

- **周期**：1 秒
- **判定**：`!stopping && !TProxyIsRunning()` — C 进程意外退出
- **失败处理**：调用 `failStartup` → 日志 + `sendStatus(STATUS_ERROR)` + `cleanupRuntime`（关 tun fd + Go 代理）
- **Java 版改造**：若 hev 完全 Java 化，改为监控 Java 健康状态（如定时检测底层 WebSocket 活跃连接数），或改为更轻量的 watchdog。

### 3.4 tproxy.conf（`TProxyService.writeTProxyConfig` L238-264）

hev C 库读取的 YAML 配置文件内容：

```yaml
misc:
  task-stack-size: 81920          # prefs.getTaskStackSize()，固定值
tunnel:
  mtu: 8500                       # prefs.getTunnelMtu()，固定值
socks5:
  port: <1080>                    # prefs.getSocksPort()
  address: '127.0.0.1'            # prefs.getSocksAddress()，固定值
  udp: 'udp'                      # prefs.getUdpInTcp()（false=udp）
  username: ''                    # 固定空（已废弃）
  password: ''                    # 固定空（已废弃）
  # udp-address: ''               # 条件字段（当前 getSocksUdpAddress 始终空）
  # username/password:            # 条件字段（仅非空时写入）
mapdns:
  address: 198.18.0.2             # 固定值（仅 remoteDns=true，当前始终 true）
  port: 53
  network: 240.0.0.0
  netmask: 240.0.0.0
  cache-size: 10000
```

> hev C 库用此文件配置本地 SOCKS5 监听参数和 TUN 隧道 MTU。若全部 Java 化（Java SOCKS5 + Java TUN 转发），此配置文件**不再需要**。

---

## 4. 运行时日志链路

### 4.1 scope 取值

`rg 'appendRuntimeLog("AndroidVPN"' app/` — **全部调用使用固定 scope `"AndroidVPN"`**（`TProxyService.java` L663）。Go 侧各协议后端用 `logger.GetLogger("System")`/`"Client"`/`"Pool"` 等，但这些 scope 只出现在 Go 日志系统内部，与 Android 运行日志 buffer 分离。

### 4.2 Go logger 缓冲语义（`golib/shared/logger/logger.go`）

```go
const (
    maxRuntimeLogLines = 2000
    maxRuntimeLogBytes = 256 * 1024  // 256 KB
)
```

- **环形覆盖**：新行写入时若 `lines >= 2000` 或 `bytes + newLine > 256KB`，逐条移除最旧行直到容量内（L35-43）
- **快照格式**：`strings.Join(s.lines, "\n")`（换行分隔，无尾换行）（L47）
- **UTF-8 安全**：超长行按 UTF-8 字符边界截断（`truncateUTF8`，L15-19）
- **单行格式**：`[HH:mm:ss] [LEVEL] [scope] message`（L71）；LEVEL 为 D/I/W/E
  ```
  [14:32:15] [I] [AndroidVPN] VPN 与本地隧道已启动
  [14:32:15] [I] [System] 启动 GCM: Worker=xxx...
  ```
- **清空时机**：每次 `backend.Start` 开头调用 `logger.ClearRuntimeLogs()`，即**每次 VPN 启动清空**（per-session 日志）。
- **线程安全**：`sync.RWMutex` 保护

### 4.3 Android 侧日志展示链路

1. `RuntimeLogActivity.onCreate` → 向 `TProxyService` 发送 `ACTION_REQUEST_RUNTIME_LOGS` Intent（L96-105）
2. `TProxyService.onStartCommand` 收到 → 调用 `Xclient.getRuntimeLogs()` → `sendBroadcast(ACTION_RUNTIME_LOGS)` 广播带上 `EXTRA_RUNTIME_LOGS` 字符串（L669-679）
3. `RuntimeLogActivity.logReceiver` 收到 → `renderLogs(logs)` → `textLogs.setText(currentLogs)` 原样展示（L137-147）
4. **无解析**：UI 只做 trim + 数行数（`\n` 计数）；但用户 copy 出去后可能依赖格式，**Java 版必须保持 `[HH:mm:ss] [I] [scope] message` 格式对齐**

### 4.4 Java 版适配要求

- Ring buffer（`ArrayList` + 两个计数器 或 `CircularBuffer` 实现），容量 2000 行 / 256KB
- 每行 append 时调用 `DateTimeFormatter.ofPattern("HH:mm:ss")`（或预编译 `SimpleDateFormat`，注意线程安全）
- 线程安全：`ReentrantReadWriteLock`
- `ClearRuntimeLogs()` 调用时机保持不变（VPN start 时）

---

## 5. 网络/电源/时区联动

### 5.1 networkCallback（`TProxyService.java` L480-533）

```java
registerDefaultNetworkCallback(new NetworkCallback() {
    onAvailable(Network) { ... }   // L483
    onLost(Network) { ... }        // L498
    // 未 override onCapabilitiesChanged
});
```

- **触发条件**：`onAvailable`：默认网络首次可用或**切换**（判断 `defaultNetwork != null && !defaultNetwork.equals(network)`）；`onLost`：当前默认网络丢失
- **防抖**：300ms（`scheduleReconnect` L633 Thread.sleep(300)）
- **行为**：调用 `Xclient.reconnect(reason)` → Go 侧重建 WebSocket 连接（不重建 TUN）
- **Java 版**：完全在 Java 侧（`TProxyService`），与 Go 无关，保持不变

### 5.2 screenReceiver（`TProxyService.java` L555-615）

```java
Intent.ACTION_SCREEN_OFF → 记录 screenOffElapsedRealtime = SystemClock.elapsedRealtime()
Intent.ACTION_SCREEN_ON  → 计算息屏时长；若 ≥ 60s → scheduleReconnect()
```

- **阈值**：`SCREEN_RECONNECT_THRESHOLD_MS = 60_000L`（L32）
- **语义**：息屏 60 秒以上 → 可能 TCP 被运营商/NAT 超时清理 → 主动重建连接
- **创建时初始化**：若注册时屏幕已熄灭（`!powerManager.isInteractive()`），立即记录 start time（L604-607）
- **Java 侧**：纯 Android 框架回调，无 Go 依赖

### 5.3 timeZoneReceiver（`TProxyService.java` L373-396）

```java
Intent.ACTION_TIMEZONE_CHANGED → syncSystemTimeZone()
```

`syncSystemTimeZone`（L364-369）：
```java
Xclient.setTimeZone(java.util.TimeZone.getDefault().getID());
```

- **用途**：同步 Android 系统时区到 Go 运行时 `time.Local`，让日志时间戳 `15:04:05` 显示为系统时区
- **Java 版**：若日志格式用 Java 本地时间，则 `setTimeZone` **变为空操作**（可保留方法但方法体为空或删除）
- **双重调用**：`XclientApplication.onCreate`（主进程）和 `TProxyService.onCreate`（:vpn 进程）都调用 setTimeZone——保证两个进程的日志时区对齐（Java 化后此冗余调用保留无害）

### 5.4 Java 侧 vs Go 侧归属

| 职责 | 当前归属 | Java 化后 | 说明 |
|---|---|---|---|
| 网络切换检测 | Java（NetworkCallback） | Java | 不变 |
| 息屏重连 | Java（BroadcastReceiver） | Java | 不变 |
| 时区同步 | Java → Go (`setTimeZone`) | Java 自用或删除 | `time.Local` 等价于 JVM 系统时区 |
| reconnect 调度 | Java（`scheduleReconnect` 300ms 防抖） | Java | 不变 |
| 连接池重建 | Go（`pool.Reconnect`） | Java | 核心数据面逻辑（由其他子代理评估） |

---

## 6. 构建链路

### 6.1 debug 构建（`.github/workflows/build-debug.yml`）

**触发条件**：仅 `workflow_dispatch`（手动触发）

**构建步骤**（按执行顺序）：

1. Checkout
2. `setup-java@v3` — JDK 17（temurin），带 gradle cache
3. `setup-android@v3`（无额外包）
4. `git clone --recursive https://github.com/heiher/hev-socks5-tunnel app/src/main/jni` — **未固定 ref/branch**（取默认分支 tip）
5. 检查 `app/libs/xclient.aar` 是否存在 → 若不存在：
   6. `setup-go@v6` — Go 1.25
   7. `go install gomobile@latest` + `gomobile init`
   8. `cd golib && gomobile bind -target=android -androidapi=24 -o ../app/libs/xclient.aar`
9. 检查/生成 Gradle Wrapper
10. `./gradlew assembleDebug`
11. 上传 4 个 per-ABI APK（armeabi-v7a / arm64-v8a / x86 / x86_64）

**注意**：
- `ANDROID_NDK_VERSION: 26.3.11579264` 环境变量已声明但**未在任何 step 中显式使用**——实际由 `app/build.gradle` 的 `ndkVersion "26.3.11579264"` 控制（AGP 自动安装）
- hev-socks5-tunnel clone **未锁定 commit/tag**，不同时间构建可能拿到不同版本（可复现性风险）

### 6.2 release 构建（`.github/workflows/release.yml`）

**触发条件**：`push: tags: ['v*']` + `workflow_dispatch`

**额外步骤**（相对 debug）：
- `fetch-depth: 0`（全量 git 历史，用于版本号计算）
- 从 tag 提取 `VERSION_NAME`，`git rev-list --count HEAD` 得 `VERSION_CODE`
- `setup-android@v3` 额外安装 `build-tools;29.0.3 platforms;android-29`
- `./gradlew assembleRelease -PVERSION_CODE=... -PVERSION_NAME=...`
- `r0adkll/sign-android-release@v1` 签名（使用 secrets：SIGNING_KEY、ALIAS、KEY_STORE_PASSWORD、KEY_PASSWORD）
- 重命名：`*-unsigned-signed.apk` → `x-client-*-signed.apk`
- 生成 Release Notes（按 Conventional Commits 分类：feat/fix/refactor/perf/docs/build/chore）
- `softprops/action-gh-release@v1` 上传全部 APK + 创建 Release

### 6.3 app/build.gradle 关键配置

```groovy
compileSdkVersion 34
ndkVersion "26.3.11579264"
minSdkVersion 24
targetSdkVersion 34
abiFilters "armeabi-v7a", "arm64-v8a", "x86", "x86_64"
externalNativeBuild { ndkBuild { path "src/main/jni/Android.mk" } }
splits { abi { enable true; universalApk true } }  // 已包含 universal!
```

依赖项：
```groovy
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.9.0'
implementation 'com.google.zxing:core:3.5.3'
implementation 'com.journeyapps:zxing-android-embedded:4.3.0'
implementation files('libs/xclient.aar')  // ← Java 化后删除
```

> **重要发现**：`splits.abi` 中已设置 `universalApk true`，所以当前 CI 已经产出 universal APK，只是**没有上传**（CI 只上传 4 个 per-ABI 分包）。

### 6.4 .gitignore 注意

```
app/libs/gcm.aar   # 只忽略 gcm.aar（旧模块名）
app/src/main/jni/  # 忽略 hev clone 目录
```

`xclient.aar` 不在 `.gitignore` 中，但 `app/libs/` 目录本身不存在于仓库中（CI 动态创建）——当前没有 AAR 入库风险。Java 化后可将 `app/libs/xclient.aar` 从 gitignore 中清除（不存在的东西）。

### 6.5 无单元测试 CI

- `app/build.gradle` **无 `testImplementation` 依赖**（无 JUnit）
- `app/src/test/` 和 `app/src/androidTest/` 目录**不存在**
- CI 中**无 `test` / `testDebugUnitTest` 步骤**
- Go 侧有 27 个测试文件（约 2930 行），运行 `go test ./...` 但不在 Android CI 中

### 6.6 Gradle 配置细节

| 配置 | 值 | 说明 |
|---|---|---|
| AGP | `8.11.1`（`buildscript.dependencies`） | |
| Gradle Wrapper | `9.2.1`（`gradle-wrapper.properties`） | |
| JDK | 17（`JAVA_HOME=/usr/lib/jvm/java-17-openjdk`） | |
| configuration-cache | `true`（`gradle.properties`） | 意味着构建缓存对 AAR 变更敏感 |

---

## 7. 输出：Java 化方案与改动清单

### 7.1 API 兼容层方案对比

#### 方案 A：保留 `xclient.Xclient` Java 类（推荐 ✅）

**做法**：删除 `app/libs/xclient.aar` 依赖，改在 `app/src/main/java/xclient/Xclient.java` 创建**普通 Java 类**，静态方法签名与 gomobile 生成的完全一致：

```java
package xclient;

public class Xclient {
    public static error StartSocksProxy(String listenAddr, String protocol, String paramsJSON, boolean verbose) { ... }
    public static void StopSocksProxy() { ... }
    public static void Reconnect(String reason) { ... }
    public static void SetTimeZone(String tz) { ... }
    public static error ValidateBypassRules(String rules) { ... }
    public static void AppendRuntimeLog(String scope, String message) { ... }
    public static String GetRuntimeLogs() { ... }
}
```

**优点**：
1. **app 代码零改动**：`TProxyService.java`、`XclientApplication.java`、`SettingsActivity.java` 的全部 7 个调用点保持原样。
2. **参数 JSON 格式完全一致**：`buildGCMParams` / `buildXtunnelParams` 输出的 JSON key/value 无需修改，Go 和 Java 后端可使用完全相同的解析逻辑，支持 A/B 对照测试。
3. **广播协议不变**：`ACTION_STATUS`、`ACTION_RUNTIME_LOGS`、`EXTRA_STATUS`、`EXTRA_RUNTIME_LOGS` 字符串全部不变，UI 无需适配。
4. **`:vpn` 多进程架构不变**：TProxyService 的 `android:process=":vpn"` 与 `MODE_MULTI_PROCESS` SharedPreferences 的设计约束（`onDestroy` 的 `killProcess`）不需要改动。
5. **gomobile 生成的 Java 方法本来就是静态的**，替换为等价静态 Java 方法对调用方透明。

#### 方案 B：重构 app 调用点

**做法**：创建新 facade（如 `com.x.client.app.proxy.ProtocolManager`），修改所有调用点。

**缺点**：
- 收益极小（仅 7 处调用），但需要改 3 个核心文件
- 增加回归风险（TProxyService 的生命周期很复杂）
- 参数 JSON 格式可能改动，增加协议不兼容风险

**结论**：方案 A 几乎在所有维度优于 B，唯一潜在问题是包名 `xclient.Xclient`（gomobile 遗留），但这是最小的代价。

### 7.2 app 侧改动清单（按文件）

#### `app/build.gradle` — **改动量：约 30-40 行**

| 改动 | 原因 | 行数 |
|---|---|---|
| 删除 `ndkVersion "26.3.11579264"` | 无需 NDK | -1 |
| 删除 `ndk { abiFilters ... }` | 无 native | -3 |
| 删除 `externalNativeBuild { ndkBuild { ... } }` | 无 jni | -4 |
| 删除 `splits { abi { ... } }` 全块 | 只产出一个 universal APK | -8 |
| 删除 `implementation files('libs/xclient.aar')` | AAR 依赖改为 source dependency | -1 |
| 新增 `implementation` 或直接依赖 source（通过 `:xclient` module 或 `sourceSets.main.java.srcDirs += "$rootDir/golib/xclient-java/src"`） | 纯 Java Xclient 类 + 协议栈 | +2-5 |
| 可选：新增 `testImplementation 'junit:junit:4.13.2'` | 支持单元测试 | +1 |

#### `app/src/main/java/xclient/Xclient.java` — **新建：约 150-250 行**

- gomobile 类的纯 Java 替身：8 个静态方法（包括 `NotifyNetworkChanged` 即使 app 不调用也保留，保持 Go → Java 接口对称）
- 内部委托给新的 Java protocol 层（`com.x.client.proxy.GcmBackend` / `XtunnelBackend`）
- `SetTimeZone` 可保留（如果 Java 日志格式用 `ZoneId.of(tz)` 输出）或变为空操作
- `AppendRuntimeLog` / `GetRuntimeLogs`：ring buffer 实现（从 `logger.go` 移植语义）

#### `app/src/main/java/com/x/client/app/TProxyService.java` — **改动量：约 40-60 行**

| 改动 | 原因 | 行数 |
|---|---|---|
| 删除 `private static native ... TProxyStartService/Stop/IsRunning/GetStats` | JNI 方法移除 | -4 |
| 删除 `static { System.loadLibrary("hev-socks5-tunnel"); }` | native 库移除 | -1 |
| 改写 `startVpn()`：删除 `TProxyStartService(configPath, fd)` 调用 + 200ms sleep + `TProxyIsRunning()` 检查 | Java 版 TUN 转发由 Java 代码直接启动（如 `startTunForwarder(tunFd, socksAddr)`），无需独立进程轮询 | ~+10 行新逻辑 |
| 改写 `monitorNativeTunnel`：改为监控 Java protocol 健康状态（如 `Xclient.isRunning()` 或定期检查连接池状态） | `TProxyIsRunning()` 已移除 | ~+10 行 |
| 删除 `writeTProxyConfig` 方法（整个方法 L238-264） | hev C 库不再读取配置文件 | -25 |
| 删除 `cleanupRuntime` 中的 `TProxyStopService()` 调用 | C 库移除 | -3 |
| **保留**所有其他逻辑不变：`networkCallback`、`screenReceiver`、`timeZoneReceiver`、`scheduleReconnect`、`sendStatus`、通知构建 | 这些都是纯 Android 逻辑，无 Go/hev 依赖 | 0 |

#### `app/src/main/java/com/x/client/app/XclientApplication.java` — **改动量：0 行**

保持不变。`xclient.Xclient.setTimeZone(...)` 调用自动切到新的 Java 实现。

#### `app/src/main/java/com/x/client/app/SettingsActivity.java` — **改动量：0 行**

`Xclient.validateBypassRules(bypassRules)` 调用保持不变。

#### `app/src/main/java/com/x/client/app/ProfileListActivity.java` — **改动量：0 行**

无任何 Xclient 或 native 调用。

#### `app/src/main/java/com/x/client/app/ProfileEditActivity.java` — **改动量：0 行**

仅读写 SharedPreferences 和 UI。

#### `app/src/main/java/com/x/client/app/Preferences.java` — **改动量：0 行**

SharedPreferences 封装不变。

#### `app/src/main/java/com/x/client/app/ServiceReceiver.java` — **改动量：0 行**

#### `app/src/main/java/com/x/client/app/RuntimeLogActivity.java` — **改动量：0 行**

广播协议不变，展示逻辑不变。

#### `app/src/main/AndroidManifest.xml` — **改动量：0 行**

`:vpn` 进程配置保留；FOREGROUND_SERVICE_SPECIAL_USE 权限保留。

#### `app/libs/` 目录 — **删除**

移除 `xclient.aar`（不存在于仓库但 CI 动态生成；Java 化后 CI 不再生成）。

**总结**：**app/ 下仅 3 个文件需要改动**（`app/build.gradle`、新建 `Xclient.java`、`TProxyService.java`），其余 11 个 Java 文件 + AndroidManifest.xml 保持原样。

### 7.3 CI 重构设计

#### 新 `build-debug.yml` 草图

```yaml
name: Build Debug

on:
  push:
    branches: [main, develop, 'feat/*', 'fix/*']
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    env:
      JAVA_HOME: /usr/lib/jvm/java-17-openjdk
      JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
      ANDROID_HOME: /usr/local/lib/android/sdk
      ANDROID_SDK_ROOT: /usr/local/lib/android/sdk

    steps:
    - uses: actions/checkout@v4

    - uses: actions/setup-java@v3
      with:
        distribution: temurin
        java-version: 17
        cache: gradle

    - uses: android-actions/setup-android@v3

    # ✅ 移除：hev-socks5-tunnel clone（不再需要）
    # ✅ 移除：Go setup / gomobile init / gomobile bind（不再需要）
    # ✅ 移除：xclient.aar 检查（不再需要）

    - name: Run unit tests (JVM)
      run: ./gradlew testDebugUnitTest --no-daemon

    - name: Build debug APK
      run: ./gradlew assembleDebug --no-daemon

    - name: Upload universal debug APK
      uses: actions/upload-artifact@v4
      with:
        name: x-client-debug-universal
        path: app/build/outputs/apk/debug/app-universal-debug.apk
```

#### 新 `release.yml` 变化

- 同样移除 Go/gomobile/hev clone 步骤
- 构建命令不变：`./gradlew assembleRelease -PVERSION_CODE=... -PVERSION_NAME=...`
- 签名不变
- **产物**：单个 `x-client-*-signed.apk`（universal）；Release Notes 中删除"请根据设备架构下载"说明

#### 是否保留 4 ABI 拆分？

**不需要**。纯 Java/字节码无 ABI 依赖，`universal.apk` 即可覆盖所有架构。当前 `splits.abi` 配置在 Java 化后应**完全移除**（`enable false` 或删除整个块）。移除后：
- `gradle assembleDebug` 只产出 `app-debug.apk`（或 `app-universal-debug.apk`，取决于是否有 splits 块残留）
- 体积更小（无重复 dex 镜像）

#### 单元测试 CI

建议在 `build-debug.yml` 中加入 `testDebugUnitTest` 步骤（已写入草图）。测试范围：
- **协议一致性测试**（`app/src/test/java/`）：JUnit 4 + Mock WebSocket 服务端，验证参数 JSON 构建与解析的往返一致性
- **GCM 编解码测试**：2 字节头（STREAM_ID + TYPE）的序列化/反序列化
- **背压阈值/连接池状态机测试**：移植 Go 侧关键逻辑的边界测试
- **routing.Matcher 测试**：从 Go `routing` 包移植 geoip/geosite 判断测试（需嵌入相同 `geoip_cn.txt` / `geosite_cn.txt` 数据文件，Java 版放 `app/src/main/assets/` 或 compile-time `@RawRes`）

### 7.4 APK 体积与启动变化预估

#### 体积

| 项目 | 当前（Go/Hev native） | Java 化后 | 说明 |
|---|---|---|---|
| Go runtime + 协议栈 .so | ~5-15MB/ABI（gomobile `libgojni.so` + `libxclient.so` 等） | 0 | 移除 |
| hev-socks5-tunnel .so | ~300-800KB/ABI | 0 | 移除 |
| 4 ABI * 上述 .so | ~20-60MB（4 个 APK 各含） | 0 | 移除 |
| Java 新增 dex | 0 | ~0.6-1.2MB | 1.5-2.5 万行 Java 代码估算 |
| APK 总计（4 ABI debug） | ~30-80MB（4 个 APK 各 8-20MB） | **~2-5MB**（单个 universal） | 裁减 60-85% |

> **注**：`gomobile bind` 生成的 .so 大小主要来自 Go 运行时（含 GC、调度器、runtime 包、`sync` 包等）和 `gorilla/websocket`、`golang.org/x/net` 等网络库。纯 Java 版用 Java 原生 WebSocket + OkHttp（或 Java 内置 `java.net.http.WebSocket`），无 GC 双重开销。

#### 启动时间

- **当前**：`System.loadLibrary("hev-socks5-tunnel")` JNI 加载 + Go 运行时初始化（~100-400ms，含 goroutine scheduler boot、zoneinfo 加载、`_ "time/tzdata"` 嵌入数据）
- **Java 化后**：Java 类首次加载 + classloader 加载（~10-50ms）
- **净效果**：VPN 建立路径中 `startVpn` 的 native 初始化阶段缩减 ~100-300ms

#### 运行时内存

- **Go runtime**：基础 ~3-8MB 堆（含 GC）+ 每连接 buffer
- **Java 版**：JVM 堆 + Java 对象，预计相当或略小（取决于 GC 收集频率）
- **无双重 GC**：当前架构是 JVM GC + Go GC 同时运行；Java 化后只有 JVM GC

### 7.5 风险清单

| 风险 | 严重性 | 说明 | 缓解措施 |
|---|---|---|---|
| **AAR 移除后 gradle cache 残留** | 低 | `files('libs/xclient.aar')` 删除后，`configuration-cache=true` 下旧缓存条目可能冲突 | 首次 CI 运行时执行 `./gradlew clean`；或 bump version 触发完全重构建 |
| **`:vpn` 进程行为变化** | 中 | Java 化不应改变 `TProxyService` 的 `android:process=":vpn"` + `onDestroy` 末尾 `killProcess` 设计；若误删除 `killProcess`，旧多进程 SharedPreferences 缓存坑会复现（注释 L135-138） | 代码审查：TProxyService.onDestroy 末尾 killProcess 必须保留；不可将 TProxyService 移到主进程 |
| **System.loadLibrary 移除后 NDK 残余配置报错** | 中 | 若只删 `loadLibrary` 但未同步删除 `app/build.gradle` 中的 `ndk`/`externalNativeBuild`/`splits.abi` 块，gradle 会在无 `app/src/main/jni/Android.mk` 时直接报错 | 必须同时删除所有 NDK 相关配置块（见改动清单） |
| **hev TUN fd 转发 Java 化的性能风险** | 高 | hev C 库的 `TProxyStartService` 接收 TUN fd 并启动 C 轮询循环，高效处理 MTU 8500 包；Java 版需用 `FileInputStream(fd)` + `Selector` 或专用线程池实现同等吞吐；O_NONBLOCK 语义需手动管理 | 由 03 子代理评估；可考虑 JNI thin wrapper（非 hev 但自写轻量 JNI 转发器）作为性能降级方案 |
| **路由数据文件移植** | 中 | `geoip_cn.txt`（5822 行）+ `geosite_cn.txt`（6410 行）Go 用 `//go:embed` 嵌入；Java 版需放 `assets/` 或 raw resource；解析逻辑（`parseIPData` / `parseDomainData`）需从 Go 移植 | 由 01 子代理盘点解析格式；这两文件格式（IP CIDR / domain suffix）纯文本可直接移植 |
| **AGP 版本兼容** | 低 | AGP `8.11.1` + Gradle `9.2.1` 是较新版本；删除 `splits.abi` 后默认产出单 APK 行为可能与旧版本有差异；需验证 `applicationId` / `namespace` 不变 | 首次构建后人工确认 APK 签名/manifest 符合预期 |
| **CLAUDE.md 与实际 CI 触发条件不一致** | 低 | CLAUDE.md 声称"推送 main/develop/feat/* 分支触发" debug 构建，但实际 `build-debug.yml` 只有 `workflow_dispatch` | 需确认是否要补齐 push 触发（功能问题，不影响 Java 化） |
| **hev clone ref 不固定** | 中（已存在，Java 化后消失） | 当前 `git clone` 未锁定 commit/tag，不同时间构建可能获取不同 hev 版本 | Java 化后此风险自然消除；若保留 C 库方案，应锁定 tag |
| **Go 1.25 依赖** | 低（Java 化后消失） | CI 使用 Go 1.25（setup-go@v6）；Java 化后不再需要 Go 环境 | — |
| **多进程 SharedPreferences 重启后状态** | 低 | `onDestroy` 的 `killProcess` 确保 VPN 进程每次启动时从磁盘重新读取 SharedPreferences，避免旧缓存覆盖主进程设置；Java 化不动此层则不受影响 | 代码审查确认 |

---

## 附录 A：Go 测试覆盖率（27 个测试文件，约 2930 行）

`find golib -name '*_test.go' | wc -l` → 27

这些 Go 测试是**Java 化后移植 JUnit 的主要来源**，特别是：
- GCM 二进制编解码（`protocol/message.go`）
- 连接池状态机与背压（`gcm/pool/`、`xtunnel/client.go`）
- 路由匹配（`routing/matcher.go`）
- DNS 解析/缓存（`dns/`）
- SOCKS5 协议解析（`socks5/`）

## 附录 B：XtAdvancedParams UI 字段对照（`ProfileEditActivity.java` L436-488）

UI 输入 → JSON key → Go 消费（`xtunnel/backend.go buildConfig`）：

| UI 控件 | JSON key | UI 单位 | Go 接收单位 | Go 默认值 |
|---|---|---|---|---|
| `edittext_xt_adv_backpressure` | `backpressure_limit` | MB | 字节 (int) | 8MB (8388608) |
| `edittext_xt_adv_write_queue_wait` | `write_queue_wait_timeout` | ms | 毫秒 (int) | 500ms |
| `edittext_xt_adv_dial_timeout` | `dial_timeout` | 秒 | 毫秒 (time.Duration) | 3000ms |
| `edittext_xt_adv_handshake_timeout` | `handshake_timeout` | 秒 | 毫秒 | 5000ms |
| `edittext_xt_adv_read_timeout` | `read_timeout` | 秒 | 毫秒 | 15000ms |
| `edittext_xt_adv_write_timeout` | `write_timeout` | 秒 | 毫秒 | 5000ms |
| `edittext_xt_adv_ping_interval` | `ping_interval` | 秒 | 毫秒 | 5000ms |
| `edittext_xt_adv_reconnect_delay` | `reconnect_delay` | 秒 | 毫秒 | 1000ms |
| `edittext_xt_adv_connect_timeout` | `connect_timeout` | 秒 | 毫秒 | 15000ms |
| `edittext_xt_adv_max_socks5` | `max_socks5_connections` | 数量 | int | 1024 |
| `edittext_xt_adv_udp_ports` | `udp_blocked_ports` | 逗号分隔 | []int | [443] |

---

## 附录 C：全仓库 Xclient 调用点完整列表

```
app/src/main/java/com/x/client/app/TProxyService.java:289: Xclient.startSocksProxy(...)
app/src/main/java/com/x/client/app/TProxyService.java:366: Xclient.setTimeZone(...)
app/src/main/java/com/x/client/app/TProxyService.java:465: Xclient.stopSocksProxy()
app/src/main/java/com/x/client/app/TProxyService.java:644: Xclient.reconnect(reason)
app/src/main/java/com/x/client/app/TProxyService.java:663: Xclient.appendRuntimeLog("AndroidVPN", message)
app/src/main/java/com/x/client/app/TProxyService.java:672: Xclient.getRuntimeLogs()
app/src/main/java/com/x/client/app/XclientApplication.java:13: xclient.Xclient.setTimeZone(...)
app/src/main/java/com/x/client/app/SettingsActivity.java:166: Xclient.validateBypassRules(bypassRules)
```

**共 8 处（6 个唯一方法）**。无其他文件引用 `xclient.Xclient`。
