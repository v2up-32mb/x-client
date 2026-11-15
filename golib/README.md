# golib — X Client Go 核心库

`golib` 是 X Client 的多协议 VPN 核心，以 Go module `xclient` 组织，通过
[`gomobile bind`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) 编译为
`app/libs/xclient.aar`，供 Android 层（`app/`）调用。

- Go 1.25.5（`go.mod` 指令）
- 入口：`android.go`（package `xclient`，gomobile thin wrapper）
- 协议栈与共享能力全部下沉至外部库（commit 伪版本随发版升级为 tag 引用）：
  - [`github.com/v2up-32mb/gcm`](https://github.com/v2up-32mb/gcm) —— GCM 协议（protocol/pool/relay + `NewStreamDialer` 适配器）
  - [`github.com/v2up-32mb/xtunnel`](https://github.com/v2up-32mb/xtunnel) —— X-Tunnel 协议（Client + `ProxyDialer`/`Reconnect`）
  - [`github.com/v2up-32mb/xshared`](https://github.com/v2up-32mb/xshared) —— SOCKS5/HTTP 代理服务器 + config/dns/ech/logger/routing
- 协议适配层保留两个薄 backend：`gcm/backend.go`、`xtunnel/backend.go`（paramsJSON 解析 + 库装配）；
  `ipopt/` 独立实现全局优选 IP 前台任务

## 目录结构

```
golib/
├── android.go            # gomobile 入口：API 导出 + 协议分发（newBackend）
├── gcm/
│   └── backend.go        # GCM 后端：paramsJSON → gcm 库装配（SOCKS5 + StreamDialer + bypass Option）
└── xtunnel/
    ├── backend.go        # X-Tunnel 后端：paramsJSON → xtunnel 库装配（SOCKS5 + ProxyDialer + bypass Option）
    └── log.go            # 后端日志器（xshared logger）
```

## 公共 API（Android 侧调用）

Android 侧统一通过 `xclient.Xclient`（gomobile 绑定类）调用，`android.go` 导出：

| API | 说明 |
|---|---|
| `StartSocksProxy(listenAddr, protocol, paramsJSON, verbose)` | 在 `listenAddr` 启动本地代理；`protocol` 取 `"gcm"`（默认，空值向后兼容）或 `"xtunnel"`；`paramsJSON` 为协议参数对象 |
| `StopSocksProxy()` | 停止当前代理并释放资源 |
| `Reconnect(reason)` | 按指定原因重连当前后端（xtunnel 走库 `reconnectCh` 强制重建通道） |
| `NotifyNetworkChanged()` | 通知网络切换（触发后端自适应重连） |
| `SetTimeZone(tz)` | 设置日志时间戳时区（跟随 Android 系统时区） |
| `ValidateBypassRules(rules)` | 校验路由绕过规则（换行分隔，见下） |
| `AppendRuntimeLog(scope, message)` | 写入内存环形日志缓冲 |
| `GetRuntimeLogs()` | 读取本次运行日志全文 |

### 协议参数（paramsJSON 字段）

**GCM**（`TProxyService.buildGCMParams` 组装，17 项）：

```
worker_host, ws_conn, relay_ips, user_id, proxy_ip,
ech_domain, ech_dns, enable_ech, disable_ipv6_route,
enable_dns_warmup, bypass_private, bypass_geoip_cn,
bypass_geosite_cn, bypass_rules, enable_dynamic_pool, dynamic_pool_max,
log_level
```

**X-Tunnel**（基础 15 项 + 高级参数可覆盖）：

```
server_addr (wss:// 必填), token, connections, client_id,
relay_nodes (逗号分隔), enable_ech, ech_domain, dns_server,
insecure, enable_hot_pair, hot_pair_count, log_level,
bypass_private, bypass_geoip_cn, bypass_geosite_cn, bypass_rules
```

高级参数（`XT_ADVANCED_PARAMS` 透传，毫秒/字节整数，0 或缺省用默认值）：

```
backpressure_limit, write_queue_wait_timeout, dial_timeout, handshake_timeout,
read_timeout, write_timeout, ping_interval, reconnect_delay, connect_timeout,
max_socks5_connections, udp_blocked_ports
```

> 键集由 `gcm/backend.go` 与 `xtunnel/backend.go` 的 `Param*` 常量定义，与 Android 侧逐键对齐；
> 变更任一侧必须同步另一侧。

## 协议速查

### GCM（2 字节头）

```
[STREAM_ID:1][TYPE:1][可选 DATA]
TYPE = 0 CONNECT    DATA = ASCII "host:port|"
TYPE = 1 CONNECTED  无 DATA
TYPE = 2 DATA       DATA = 任意二进制
TYPE = 3 CLOSE       无 DATA
```

- WebSocket 连接：`wss://<workerHost>/<userID>?fallbackip=<出口IP列表>`
- 多路复用：256 流/WS，共享连接池；选路 = Relay 评分 + 负载均衡
- 协议无 UDP：SOCKS5 UDP ASSOCIATE 请求由共享服务器回复 `0x07`

### x-tunnel（8 字节头）

- 头部：`connID` + `msgType`（8 字节），每通道独立 connID 空间
- 选路 = 通道竞争 + Hot Pair 预绑定；UDP ASSOCIATE 经 `ProxyDialer()`（端口拦截 +
  IPStrategy 过滤）；Fast Retry、背压控制在库内

## ECH / DoH 回退链（共享）

```
DoH 多服务器 fallback → UDP DNS (8.8.8.8:53) → 标准 TLS 1.3
```

ECH 配置项（`ech_domain` / `ech_dns`）对两种协议共用；配置懒加载（首次拨号按需获取，
不阻塞后端启动）。

## 路由绕过规则语法

`bypass_rules` 以换行分隔，每行一条，支持三种形式（可通过 `validateBypassRules` 校验）：

```
192.168.1.0/24          # CIDR 网段
example.com             # 域名（含子域）
full:api.example.com    # 全匹配域名
```

内置开关：`bypass_private`（本地/局域网）、`bypass_geoip_cn`、`bypass_geosite_cn`。
匹配器由 backend 构建（`routing.NewMatcher`），经 `WithBypassMatcher` 注入共享 SOCKS5 服务器。

## 数据流

```
┌─────────────────────────────────────────────┐
│ Android 应用（main 进程）                     │
│   ProfileListActivity / ProfileEditActivity  │
└──────────────┬──────────────────────────────┘
               │ Intent ACTION_CONNECT / 本地广播 ACTION_STATUS
┌──────────────▼──────────────────────────────┐
│ TProxyService（:vpn 进程，VpnService）        │
│   └─ TUN 接口                                │
└──────────────┬──────────────────────────────┘
               │ hev-socks5-tunnel（NDK）
┌──────────────▼──────────────────────────────┐
│ golib：xshared SOCKS5 服务器                 │
│   └─ newBackend(protocol)                    │
│       ├─ gcm     → gcmlib.NewStreamDialer    │
│       └─ xtunnel → xtlib.ProxyDialer()       │
└─────────────────────────────────────────────┘
```

## 构建与验证

```bash
# AAR 产物（供 Android 层使用）
cd golib
go get golang.org/x/mobile/bind
gomobile bind -target=android -androidapi=24 -o ../app/libs/xclient.aar

# Go 侧单测（本地可验证；Android APK 编译一律走 GitHub Actions）
go test ./... -race
```

> 完整应用构建与发布流程见仓库根目录 `README.md`；模块约定见根目录 `AGENTS.md`。

## 全局优选 IP：独立前台任务（`ipopt`）

新增 `ipopt/`，不依赖 `activeBackend`，不修改 GCM/XT 协议评分、重试或运行中连接。
`ipopt_android.go`（按固定文件名为 Android 平台入口）仅暴露 gomobile 支持的字符串/基本类型：

| Go API / Java 下驼峰 | 返回 |
|---|---|
| `ParsePreferredIPList(content, sourceID, sourceKind)` / `parsePreferredIPList` | `(string, error)`：解析 JSON，不联网 |
| `StartPreferredIPTask(requestJSON)` / `startPreferredIPTask` | `(string, error)`：立即返回不透明任务 ID；运行/清理中返回 `busy` |
| `GetPreferredIPTask(taskID)` / `getPreferredIPTask` | `(string, error)`：JSON 快照，未知/过期 ID 报错 |
| `CancelPreferredIPTask(taskID)` / `cancelPreferredIPTask` | 幂等取消，无返回值 |

Java 捕获带 `error` 方法的异常；建议后台线程每 500ms 轮询。Android 必须阻止本应用及
其他可检测 VPN 混测，并在退到后台、离开页面、网络切换时取消（旋转可保留任务 ID）。
Go 不提供物理 `Network` 绑定桥，不自行断开 VPN，不把 `network_id` 当作系统路由绑定。

### JSON 请求与结果

纯文本格式为 `IP:PORT#COMMENT` / `[IPv6]:PORT#COMMENT`，端口 1..65535，支持 UTF-8
BOM、CRLF、空行；只在 `source_kind=legacy` 时允许裸 IP 默认 443。域名、CIDR、无效行
不会冒充手动候选。按规范化 IP+port 合并备注、`source_ids` / `source_kinds`；地址中不含备注。
纯文本解析保留文档示例地址，实际网络任务拒绝非公网地址；第三方抓取同时给出被过滤行号。

```json
{"candidates":[{"address":"1.1.1.1:2008","ip":"1.1.1.1","port":2008,"comment":"备注","source_id":"manual","source_kind":"manual","source_ids":["manual"],"source_kinds":["manual"]}],"errors":[{"line":2,"message":"错误原因","text":"原行"}]}
```

抓取任务：

```json
{"operation":"fetch","network_id":"opaque-net-id","source":{"id":"cf-v4","url":"https://www.cloudflare.com/ips-v4","kind":"cidr4"}}
```

`cidr4/cidr6` 只允许对应 CF 官方完整 URL；`list` 一律标记 `third_party`，不会因来源 ID
看起来像官方就升格。IPv6官方地址为 `https://www.cloudflare.com/ips-v6`；第三方 URL
仅由调用方明确选择后抓取，没有自动订阅。抓取 15s/2MiB，最多 3 次安全重定向，禁止
HTTPS 降级及私网目标；官方还禁止转到其他域名/路径。非 2xx、HTML、非 UTF-8、压缩正文
均失败，空有效列表失败；混合有效/错误行保留错误明细，调用方须确认后导入有效部分。

测试任务（数值省略使用默认值；显式 0 仅在允许关闭的参数中具有关闭含义）：

```json
{
  "operation":"test","network_id":"opaque-net-id",
  "candidates":[{"address":"1.1.1.1:443","ip":"1.1.1.1","port":443,"comment":"","source_id":"manual","source_kind":"manual"}],
  "prefixes":[{"cidr":"104.16.0.0/13","port":443,"source_id":"cf-v4","source_kind":"official"}],
  "options":{
    "target_host":"node.example.com","target_port":443,"protocol":"gcm",
    "enable_ech":true,"ech_domain":"cloudflare-ech.com","dns_server":"https://doh.pub/dns-query",
    "sample_limit":256,"probe_concurrency":8,"recommend_count":3,"download_top":5,
    "download_bytes_per_ip":20971520,"download_seconds":5,
    "download_url":"https://speed.cloudflare.com/__down?bytes=20971520"
  }
}
```

- `target_host/target_port` 必须取目标 Profile 的**最终有效参数**，包括 XT 高级 JSON 覆盖；
  `target_host` 不带 scheme/端口/路径。目标 trace 固定 HTTPS `/cdn-cgi/trace`。
- `protocol` 为 `gcm/xtunnel`，只标识参考协议，不发送 token/userid，不进行 WS/认证。
- `sample_limit` 1..4096，仅用于 CIDR 新抽样；手动/列表候选全参与。前缀 round-robin 分配额度、
  每段分层抽样，IPv6使用有界大整数主机位运算，不展开地址空间。前缀多于额度时显式警告覆盖不足。
- 输入候选、前缀各不超过4096，合并抽样后总候选也不得超过4096；超过报错，不静默截断。
  解析正文最多10000行，超出给出错误行。请求 JSON 最大4MiB，拒绝未知字段。
- 并发 1..32，默认8；下载永远串行，`download_top` 0..5；每 IP最多5秒/20MiB，正文总100MiB。
  总任务30分钟上限。下载上限之外还有连接、TLS、trace、DoH及HTTP头等流量开销。
- `download_top=0` **不发起下载**；`recommend_count=0` **不自动推荐**，适合保存前的少量
  实际目标兼容性检查。仍做真实 TLS/trace、10次TCP、5次HTTP，不用测速站成功替代目标可用性。

快照字段固定为 `id/operation/state/phase/completed/total/network_id/started_at/updated_at/`
`download_bytes/warnings/error/candidates/prefixes/errors/results`，所有数组空值为 `[]` 而不是
`null`，时间为 Unix 毫秒。`state=queued/running/completed/cancelled/failed`，阶段为
`fetch/connectivity/stability/latency/download/done`；进度只代表当前真实阶段，阶段切换重置计数。
抓取结果填 `candidates` 或 `prefixes`；测速保留全部成功、失败、取消前尚未测的候选。

每个 Result 包含 Candidate 字段及：

```text
status, error, colo, tcp_success, tcp_attempts, tcp_min_ms, tcp_max_ms, tcp_avg_ms,
tls_ms, http_success, http_attempts, http_median_ms, http_jitter_ms,
download_mib_s, download_peak_mib_s, download_bytes, download_seconds,
download_state, ech_status, recommended, tested_at
```

`status=untested/valid/failed`，`download_state=untested/ok/short/failed`，
`ech_status=disabled/used/fallback`。未测数字0必须结合状态解释，不能展示为真实0延迟/速度。

### 测量、取消与安全边界

- 连通阶段独立 TCP+验证证书的 TLS+HTTP 200 trace，校验 trace 的 `ip/h/colo`；CF-RAY仅补充
  机房代码。普通网页、403、HTML、无有效trace/colo的目标标为失败/不适用，不冒充任意 XT 可用。
- 通过连通检查的候选做 **10次独立 TCP socket**，成功延迟和失败分开；不是 ICMP 丢包。
  初始 TLS 时间单列 `tls_ms`。再做一次验证 trace 预热，随后 **5次完整小HTTP请求**，记录成功数、
  成功请求中位数和 max-min 波动。只有观察到连接复用才可按已建连接解释；无法全程复用时快照
  明确警告“含重连的总请求延迟”。连通检查/预热不混入5次请求成功计数。
- 所有目标 HTTPS 证书始终校验，XT的 `insecure` 不传入这里。保留 URL scheme、Host、SNI、
  path/query，**仅把 TCP 目的地址换为 Candidate 的明确 IP+port**，不按端口猜 TLS。
- ECH直接使用 Go TLS 配置，独立实现任务 context-bound 的 RFC8484 DNS wire POST、HTTPS(type65)
  ECH key5解析；按用户 DoH、Cloudflare DoH、Google DoH 顺序，每次最多4秒，再 UDP
  `8.8.8.8:53` 最多2秒，总准备15秒。校验DNS响应ID/问题/rcode/截断和记录归属。
  不调用共享manager的 background-context 获取，不启动刷新；配置缺失/获取失败/ECH拒绝均明确
  标 fallback并回退**仍验证证书**的标准TLS。只记录服务模式HTTPS记录和响应内CNAME链，
  需要另行解析的HTTPS alias-mode暂走明确回退，不递归无限查询。
- 下载拒绝所有重定向（比仅禁止跨域更严格），拒绝非2xx、空正文、压缩、HTML/JSON/XML错误页；
  要求明确二进制 Content-Type（octet-stream/binary/zip/image），自定义不合格URL显示不可用。
  只统计正文读取窗口的 MiB/s，峰值来自≥500ms窗口、不用于主排名；<1秒为short，不自动推荐。
  读出的错误正文也计入预算但不给速度；已知 Content-Length提前 EOF为失败。时长截止可形成部分
  正文样本，用户取消则永不当成功。正文每次读取即累计到任务快照；取消后的清理只结算实际
  `download_bytes/download_seconds`（不重复计费），当前下载标 `failed`，不恢复速度/成功/推荐。
  HTTPS自定义非标准端口和HTTP显式scheme均按URL处理。
- 排序先成功次数/稳定性，再真实HTTP延迟；相近组（锚点+max(20ms,20%)）比较有效下载，
  性能接近时优先官方来源。采用锚点分组避免模糊比较违反排序传递性。推荐只取10/10 TCP、5/5 HTTP
  且有效目标结果；启用下载时还须下载`ok`，不足推荐数不凑数。短样本、失败项不自动推荐。
- 每个任务/请求有 context和期限，UDP deadline+取消关闭，TLS HandshakeContext，HTTP body随取消
  关闭。额外绑定 HTTP Transport可能脱离单次请求的 dial context到真实任务，关闭 idle transports。
  默认不读取代理环境变量；DNS全部解析地址均须公网（包括拒绝 IPv6 site-local `fec0::/10`），
  随后拨号字面IP，避免DNS rebinding或混合私网答案。辅助URL域名使用私有 `PreferGo` resolver，
  通过字面公网 `1.1.1.1:53` bootstrap（UDP，截断时TCP），不修改 `net.DefaultResolver`、不调用
  Android `getaddrinfo`，也不递归使用受保护拨号。每次解析最多5秒且受更短请求期限约束，DNS socket
  有 deadline并随 context 取消实际关闭；同步 `LookupHost` 等待 A/AAAA 交换退出。公网53端口被阻断
  时解析会明确失败，不回退系统DNS；IPv6-only且无IPv4可达能力的网络也可能无法访问此bootstrap。
- 仅1个真实运行/清理中的任务，最近3个已结束快照；取消立即锁定终态、清除推荐，晚结果不能覆盖。
  任务还跟踪 Transport 脱离请求返回的拨号及存活连接，关闭/等待实际I/O清理后才释放运行槽；连接
  正常关闭即从跟踪中移除，关闭与取消回调共享幂等关闭路径。
  网络 ID只是结果归属，旧网络/部分结果的UI标记、已选池和原子存储由Android管理；本包不自动保存已选。

### 测试与交付验证

`ipopt/*_test.go` 包含解析、规范化/来源合并/边界、真实CIDR范围与抽样上限、私网过滤、来源抓取、
RFC8484/UDP/ECH拒绝回退、非标准TLS端口/SNI/Host、证书失败、真实连接复用、HTTP错页、取消阻塞读取、
正文budget/short/峰均速、推荐不足、阶段进度、并发上限、历史保留和晚结果隔离；另覆盖纯Go DNS
bootstrap/UDP与TCP framing/解析阶段取消清理、site-local混合答案拒绝，以及正文部分读取后的取消计量。
测试使用注入 transport/dial/probe/clock 与本地 `httptest` / UDP / `net.Pipe`，无需访问外网；
生产JSON API没有私网旁路、替换证书或假探测入口。

**本地仅允许 gofmt/静态检查。** Go测试（含 `-race ./...`）、Android入口的真实 `gomobile bind`
以及Android编译均须由GitHub Actions执行；本实现落盘不代表这些验证已通过。
