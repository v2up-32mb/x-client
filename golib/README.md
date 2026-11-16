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
# 以下命令仅供 GitHub Actions；禁止在本地执行
# AAR 产物（供 Android 层使用）
cd golib
go get golang.org/x/mobile/bind
gomobile bind -target=android -androidapi=24 -o ../app/libs/xclient.aar

# Go 侧单测（同样只能在 GitHub Actions 执行）
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

### 旧操作 JSON 请求与结果（`fetch/test`，保持兼容）

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
来源抓取与测速是独立操作；本轮不改变第三方 `list` 显式 HTTP(S) URL 的抓取策略，也不额外测速来源站。

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
- `download_url` 仅允许 HTTPS（含自定义非标准端口），显式 `http://` 在任务输入校验时拒绝；
  测速 session 也拒绝明文 HTTP 拨号，不按 80/443 等端口猜测 scheme，不自动改写用户 URL。
- `protocol` 为 `gcm/xtunnel`，只标识参考协议，不发送 token/userid，不进行 WS/认证。
- `sample_limit` 1..4096，仅用于 CIDR 新抽样；手动/列表候选全参与。前缀 round-robin 分配额度、
  每段分层抽样，IPv6使用有界大整数主机位运算，不展开地址空间。前缀多于额度时显式警告覆盖不足。
- 输入候选、前缀各不超过4096，合并抽样后总候选也不得超过4096；超过报错，不静默截断。
  解析正文最多10000行，超出给出错误行。请求 JSON 最大4MiB，拒绝未知字段。
- 并发 1..32，默认8；下载永远串行，`download_top` 0..5；每 IP最多5秒/20MiB，正文总100MiB。
  总任务30分钟上限。下载上限之外还有连接、TLS、trace、DoH及HTTP头等流量开销。
- `download_top=0` **不发起下载**；`recommend_count=0` **不自动推荐**，适合保存前的少量
  实际目标兼容性检查。仍做真实 TLS/HTTPS trace、10次TCP、5次HTTPS，不用测速站成功替代目标可用性。

快照保留字段为 `id/operation/state/phase/completed/total/network_id/started_at/updated_at/`
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

兼容语义：`http_success/http_attempts/http_median_ms/http_jitter_ms` 字段名称及 phase 枚举保持不变，
这些统计实际是 **HTTPS trace 请求**的成功数、尝试数、延迟与波动；Android 应显示“HTTPS验证/延迟”，
不能因字段名前缀而标为明文 HTTP。目标 trace、预热和5次请求在本轮前已经使用 TLS；本轮收紧的是
原先允许显式 HTTP 的自定义下载入口，并修正日志措辞，不删减证书、CF trace 或延迟检查。

### 测量、取消与安全边界

- 连通阶段独立 TCP+验证证书的 TLS+HTTPS trace（状态码200），校验 trace 的 `ip/h/colo`；CF-RAY仅补充
  机房代码。普通网页、403、HTML、无有效trace/colo的目标标为失败/不适用，不冒充任意 XT 可用。
- 通过连通检查的候选做 **10次独立 TCP socket**，成功延迟和失败分开；不是 ICMP 丢包。
  初始 TLS 时间单列 `tls_ms`。再做一次验证 trace 预热，随后 **5次完整小HTTPS请求**，记录成功数、
  成功请求中位数和 max-min 波动。只有观察到连接复用才可按已建连接解释；无法全程复用时快照
  明确警告“含重连的总请求延迟”。连通检查/预热不混入5次请求成功计数。
- 所有目标 HTTPS 证书始终校验，XT的 `insecure` 不传入这里。保留 URL scheme、Host、SNI、
  path/query，**仅把 TCP 目的地址换为 Candidate 的明确 IP+port**，不按端口猜 TLS。
  目标 trace 的连通、预热及5次延迟请求均拒绝所有重定向，包括同源跳转和 HTTPS→HTTP 降级。
- ECH直接使用 Go TLS 配置，独立实现任务 context-bound 的 RFC8484 DNS wire POST、HTTPS(type65)
  ECH key5解析；空ECH域名恢复 `cloudflare-ech.com`，显式空DoH按既有后端语义处理：GCM复用
  `xshared` 内置服务器列表，XT使用 `https://v.recipes/dns-query`；未指定选项仍保持原默认值。
  按用户/内置 DoH、Cloudflare DoH、Google DoH 顺序，每次最多4秒，再 UDP
  `8.8.8.8:53` 最多2秒，总准备15秒。校验DNS响应ID/问题/rcode/截断和记录归属。
  不调用共享manager的 background-context 获取，不启动刷新；配置缺失/获取失败/ECH拒绝均明确
  标 fallback并回退**仍验证证书**的标准TLS，不回退明文 HTTP；UDP DNS 辅助查询不是 HTTP 测速。
  只记录服务模式HTTPS记录和响应内CNAME链，需要另行解析的HTTPS alias-mode暂走明确回退，
  不递归无限查询。
- 下载拒绝所有重定向（比仅禁止跨域更严格），拒绝非2xx、空正文、压缩、HTML/JSON/XML错误页；
  要求明确二进制 Content-Type（octet-stream/binary/zip/image），自定义不合格URL显示不可用。
  只统计正文读取窗口的 MiB/s，峰值来自≥500ms窗口、不用于主排名；<1秒为short，不自动推荐。
  读出的错误正文也计入预算但不给速度；已知 Content-Length提前 EOF为失败。时长截止可形成部分
  正文样本，用户取消则永不当成功。正文每次读取即累计到任务快照；取消后的清理只结算实际
  `download_bytes/download_seconds`（不重复计费），当前下载标 `failed`，不恢复速度/成功/推荐。
- 排序先成功次数/稳定性，再真实HTTPS延迟；相近组（锚点+max(20ms,20%)）比较有效下载，
  性能接近时优先官方来源。采用锚点分组避免模糊比较违反排序传递性。推荐只取10/10 TCP、5/5 HTTPS
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
RFC8484/UDP/ECH拒绝回退、非标准TLS端口/SNI/Host、证书失败、真实连接复用、HTTPS错页、取消阻塞读取、
正文budget/short/峰均速、推荐不足、阶段进度、并发上限、历史保留和晚结果隔离；另覆盖纯Go DNS
bootstrap/UDP与TCP framing/解析阶段取消清理、site-local混合答案拒绝，以及正文部分读取后的取消计量。
HTTPS回归还覆盖显式明文下载在输入/拨号边界被拒绝、目标请求与下载拒绝跳转降级、ECH回退后的
Host/SNI/候选端口和证书失败；新增用例仅落盘，须由本轮 CI 实际执行后才有通过证据。
测试使用注入 transport/dial/probe/clock 与本地 `httptest` / UDP / `net.Pipe`，无需访问外网；
生产JSON API没有私网旁路、替换证书或假探测入口。

**本地仅允许 gofmt/静态检查。** Go测试（含 `-race ./...`）、Android入口的真实 `gomobile bind`
以及Android编译均须由GitHub Actions执行；本实现落盘不代表这些验证已通过。

### v2：冻结会话、逐 IP 流水线、手动下载 wire

四个函数签名不变。新增 `operation=prepare/probe/download`；旧 `fetch/test` 不使用会话，
旧 test 的 **20MiB/5秒/原 URL/阶段屏障/推荐算法**保持不变。新 UI 不应通过旧 test 绕过手动额度。
以下是实际实现的 JSON 契约，不是 Android 可自行补全的字段建议。所有新选项也受严格白名单约束。

#### 会话和请求约定

| 字段 | 新操作含义 |
|---|---|
| `session_id` | 首次 prepare 省略，Go 返回32位十六进制不透明 ID；后续必须回传。新扫描省略此字段，替换活跃扫描，不会重置下载账本 |
| `download_round_id` | **首次进程内建立账本可省略**，此后所有新操作均须回传 Go 返回的 ID；旧 ID/遗漏/Go已失账明确拒绝 |
| `refresh_ech` | 默认 false；仅 prepare 可设 true，强制刷新本次 options 对应 ECH 配置；不变更候选、已完成证明/时间或账本 |
| `new_download_round` | 默认 false；仅 prepare 可设 true，调用方必须已取得用户明确的新增100MiB批准；产生新 round ID，不清候选/成绩/ECH |
| `probe_role` | 默认 `reference`；probe可设 `validation` 对其它实际target检查。prepare可携带同一字段说明准备目的；download只能reference |
| `candidates` | 首次 prepare 合并物化；probe为冻结队列中最多N个候选；download恰好一个。后续 prepare必须省略/空数组 |
| `prefixes` | 仅首次 prepare使用；probe/download及既有session的prepare拒绝非空prefixes |
| `options` | 所有新操作都必须包含实际target的 `target_host`，其余按下例/旧默认补齐；不要把50MiB/10秒参数回传到prepare/probe（这两个使用旧通用参数校验，但不下载） |

`probe/download` 中 Candidate 可只传 `address/source_id/source_kind`（仍需合法来源）；Go核对规范化
IP+port确实属于会话，再使用**冻结的完整来源/备注**，不接受调用方改变候选归属、提交valid/score证明。
推荐直接复制 prepare 返回的 Candidate。全部候选（显式+CIDR抽样去重）整轮最多4096，不能拆批添加。
`sample_limit`默认256只限制CIDR生成配额；`probe_concurrency`默认8、最大32且每批不超过N。
队列优先级为 legacy > manual > third_party > official-v4 > official-v6；混合来源取最高优先级，
保留 `source_ids/source_kinds/comment`。同级按数值IP（IPv4在前）、端口、稳定来源ID排列。
同地址来自多个明确来源时主来源提升到最高优先级；旧独立Parse API仍保留首来源合并语义。

**首次prepare（新账本，无ECH的最小示例，可直接提交）：**

```json
{
  "operation":"prepare","network_id":"wifi-generation-1",
  "candidates":[{"address":"1.1.1.1:443","source_id":"paste","source_kind":"manual","comment":"手动备注"}],
  "prefixes":[{"cidr":"104.16.0.0/13","port":443,"source_id":"cf-v4","source_kind":"official"}],
  "options":{"target_host":"node.example.com","target_port":443,"protocol":"gcm","enable_ech":false,"sample_limit":256,"probe_concurrency":8}
}
```

Start返回task ID，轮询到 `worker_done=true` 后保存 `session_id/download_round_id/candidates`。
prepare内部只物化队列、准备ECH，不探测候选；`completed/total`成功时都是冻结候选数量，**不表示已probe**。
新扫描时同样提交上例候选/前缀、**附带当前download_round_id**，省略session_id；可改变network/target，
旧session随后失效，已有下载正文用量继承。未知session不能用prepare暗中重建。

以下请求中的 `SESSION` / `ROUND` 必须替换为上次快照返回值；不把这些示例占位值当有效 ID。

**reference逐IP探测（无下载/无推荐，完成后不允许再次reference重测同IP）：**

```json
{
  "operation":"probe","session_id":"SESSION","download_round_id":"ROUND","network_id":"wifi-generation-1","probe_role":"reference",
  "candidates":[{"address":"1.1.1.1:443","source_id":"paste","source_kind":"manual"}],
  "options":{"target_host":"node.example.com","target_port":443,"protocol":"gcm","enable_ech":false,"probe_concurrency":8,"download_top":0,"recommend_count":0}
}
```

每worker独立完成 trace/TLS/证书 → 10次独立TCP → 一次HTTPS预热+5次HTTPS请求，完成一个立即发布
`probe_complete/score_ready`。前步失败短路也是完整失败；没有所有候选同阶段屏障。
整体 `phase=connectivity`直到done，真实逐IP阶段在 `result.probe_phase`，`completed/total`仅为本批完整IP数。
UI按冻结队列聚合整个UISession进度；Go快照副本中完整合格按未舍入score降序，待测/在测随后，失败最后，
同组同分用上述候选顺序。排序不移动内部worker索引，不影响身份或下载。

**统一刷新/过期维护（下面options启用ECH，实际会话首次prepare也应使用同一reference配置）：**

```json
{
  "operation":"prepare","session_id":"SESSION","download_round_id":"ROUND","network_id":"wifi-generation-1","refresh_ech":true,
  "options":{"target_host":"node.example.com","target_port":443,"protocol":"gcm","enable_ech":true,"ech_domain":"cloudflare-ech.com","dns_server":"https://doh.pub/dns-query"}
}
```

使用 `refresh_ech=false` 或省略，表示统一前台缓存维护：仅缺失/过期时查询，未过期或fallback直接命中。
这不是改变reference目标的入口：reference在首次prepare冻结。切换reference/ECH/DNS参数需要新扫描；
在既有会话prepare另一配置仅为validation准备配置，不会替换reference。每次只准备一个options配置组。

**用户已确认的新100MiB轮（只改变下载轮）：**

```json
{
  "operation":"prepare","session_id":"SESSION","download_round_id":"ROUND","network_id":"wifi-generation-1","new_download_round":true,
  "options":{"target_host":"node.example.com","target_port":443,"protocol":"gcm","enable_ech":false}
}
```

保存返回的新 `download_round_id` 后再执行后续任务。新轮旧结果/候选/已完成数/目标证明均不清空。
若选用ECH且此时配置过期，prepare仍会进行同一统一维护；想强制重查另设refresh_ech，不会后台刷新。

**其它实际target兼容复核（包括XT最终高级覆盖参数，不发送Token/UserId）：**

```json
{
  "operation":"prepare","session_id":"SESSION","download_round_id":"ROUND","network_id":"wifi-generation-1","probe_role":"validation",
  "options":{"target_host":"xt.example.com","target_port":8443,"protocol":"xtunnel","enable_ech":true,"ech_domain":"cloudflare-ech.com","dns_server":"https://v.recipes/dns-query"}
}
```

```json
{
  "operation":"probe","session_id":"SESSION","download_round_id":"ROUND","network_id":"wifi-generation-1","probe_role":"validation",
  "candidates":[{"address":"1.1.1.1:443","source_id":"paste","source_kind":"manual"}],
  "options":{"target_host":"xt.example.com","target_port":8443,"protocol":"xtunnel","enable_ech":true,"ech_domain":"cloudflare-ech.com","dns_server":"https://v.recipes/dns-query","probe_concurrency":8,"download_top":0,"recommend_count":0}
}
```

同ECH配置跨目标复用缓存；目标proof不会复用。Go对reference强制target_key匹配，对validation必须是
其它target；相同target应直接复用本次完整reference证明。validation也运行完整无下载流程，输出
`result_kind=validation`和独立target_key，不更新reference已完成/下载资格。它的score仅描述该target，
**不得并入reference排名**。Go不缓存profile×4096矩阵：只保留一份最多4096的reference完成/合格布尔索引；
其它target证明仅存在最近3个任务快照（每probe至多32条）。Android需要按最终target/network/选择版本核对并持久化真实结果，
不是传valid=true给Go。`target_key`为Go生成的SHA256指纹，不是签名/令牌；关联必须连同session_id和network_id。

**download-only（必须先取消扫描并等待worker_done，再调用；完成后仍由UI保持暂停）：**

```json
{
  "operation":"download","session_id":"SESSION","download_round_id":"ROUND","network_id":"wifi-generation-1",
  "candidates":[{"address":"1.1.1.1:443","source_id":"paste","source_kind":"manual"}],
  "options":{"target_host":"node.example.com","target_port":443,"protocol":"gcm","enable_ech":false,
    "download_bytes_per_ip":52428800,"download_seconds":10,"download_url":"https://speed.cloudflare.com/__down?bytes=52428800"}
}
```

该操作三项下载参数省略时同样是 **50MiB/10秒/新URL**；旧test默认仍是20MiB/5秒。
上限分别为1..52428800字节、1..10秒，可自定义HTTPS URL。只允许本会话完整合格的reference候选，
只发下载HTTPS，不重跑trace/10TCP/5HTTPS、不推荐、不更新reference TLS/ECH/score/tested_at/证明。
Go会核对下载options的reference target_key，即使实际请求Host/SNI为download_url中的测速站。
若remaining不足50MiB，只读剩余额度；remaining为0启动前明确拒绝，绝不自动追加。

#### 完整快照/结果示例及字段定义

下面为**合成的wire格式说明**，数值不是实测或测试通过证据。ID/指纹以说明字符串代替实际生成值；
字段结构和名称与实现一致。首次prepare完成快照（例中只有1候选、未启用ECH）：

```json
{
  "worker_done":true,"session_id":"SESSION","target_key":"TARGET_SHA256","probe_role":"reference",
  "ech":{"key":"ECH_SHA256","state":"disabled","prepared_at":0,"expires_at":0,"cache_hit":false,"error":""},
  "download_round_id":"ROUND","download_round_limit":104857600,"download_round_used":0,"download_round_remaining":104857600,"download_reserved_bytes":0,
  "id":"TASK_PREPARE","operation":"prepare","state":"completed","phase":"done","completed":1,"total":1,
  "network_id":"wifi-generation-1","started_at":1730000000000,"updated_at":1730000000010,"download_bytes":0,
  "warnings":[],"error":"",
  "candidates":[{"address":"1.1.1.1:443","ip":"1.1.1.1","port":443,"comment":"手动备注","source_id":"paste","source_kind":"manual","source_ids":["paste"],"source_kinds":["manual"]}],
  "prefixes":[],"errors":[],"results":[]
}
```

逐IP probe完整结果快照（请求含一个IP；一批多个IP也可在 `state=running,worker_done=false` 时出现完整结果）：

```json
{
  "worker_done":true,"session_id":"SESSION","target_key":"TARGET_SHA256","probe_role":"reference",
  "ech":{"key":"","state":"disabled","prepared_at":0,"expires_at":0,"cache_hit":false,"error":""},
  "download_round_id":"ROUND","download_round_limit":104857600,"download_round_used":0,"download_round_remaining":104857600,"download_reserved_bytes":0,
  "id":"TASK_PROBE","operation":"probe","state":"completed","phase":"done","completed":1,"total":1,
  "network_id":"wifi-generation-1","started_at":1730000001000,"updated_at":1730000002000,"download_bytes":0,
  "warnings":["TCP成功率不是ICMP丢包；HTTPS trace成功不代表WebSocket/认证成功；本次probe不下载/不推荐"],"error":"",
  "candidates":[{"address":"1.1.1.1:443","ip":"1.1.1.1","port":443,"comment":"手动备注","source_id":"paste","source_kind":"manual","source_ids":["paste"],"source_kinds":["manual"]}],
  "prefixes":[],"errors":[],
  "results":[{
    "address":"1.1.1.1:443","ip":"1.1.1.1","port":443,"comment":"手动备注","source_id":"paste","source_kind":"manual","source_ids":["paste"],"source_kinds":["manual"],
    "probe_complete":true,"score_ready":true,"score_version":1,"score":98.7,
    "score_breakdown":[{"key":"tcp_success","points":30,"max_points":30},{"key":"http_success","points":30,"max_points":30},{"key":"tcp_latency","points":14.25,"max_points":15},{"key":"http_latency","points":14.55,"max_points":15},{"key":"http_jitter","points":9.9,"max_points":10}],
    "probe_phase":"done","na_reason":"","result_kind":"reference","target_key":"TARGET_SHA256","download_ech_status":"disabled",
    "status":"valid","error":"","colo":"HKG","tcp_measured":true,"http_measured":true,
    "tcp_success":10,"tcp_attempts":10,"tcp_min_ms":20,"tcp_max_ms":30,"tcp_avg_ms":25,"tls_ms":35,
    "http_success":5,"http_attempts":5,"http_median_ms":30,"http_jitter_ms":5,
    "download_mib_s":0,"download_peak_mib_s":0,"download_bytes":0,"download_seconds":0,"download_state":"untested",
    "ech_status":"disabled","recommended":false,"tested_at":1730000002000
  }]
}
```

`Result`新增字段：

- `probe_complete`：本IP流程正常处理结束（含失败短路）。取消partial始终false；不会晚复活。
- `score_ready/score_version/score/score_breakdown`：未完成不出分；完整失败也ready/version1，总分强制0，
  仍显示已测子项贡献。五项固定数组，key为tcp_success/http_success/tcp_latency/http_latency/http_jitter；
  公式分别为30×TCP成功/10、30×HTTPS成功/5、15×clamp(1−TCP均值/500)、15×clamp(1−HTTPS中位数/1000)、
  10×clamp(1−HTTPS波动/500)。clamp范围0..1；内部不舍入，UI可保留一位小数。
  缺指标不给0ms奖励；TLS/ECH/来源/下载均不加减基础分。
- `tcp_measured/http_measured`：至少一次相应成功测量，辅助区分未测与真正零耗时；统计attempt/success同时核对。
- `probe_phase`：connectivity/stability/latency/done；未probe/下载样本可为空。
- `na_reason`：尚未执行、后续未测、前步失败跳过、partial取消重试，或仅下载说明；失败主因仍在error。
- `result_kind`：reference/validation/download；旧test为空。新proof使用前须明确匹配reference或需要复核的validation。
- `target_key`：目标host（小写去尾点）、实际port、protocol、enable_ech、规范ECH域名及实际DoH顺序的指纹；
  不含下载URL/抽样数/并发数。只可与相同session/network下结果比较。download的Result此字段为空，不携带probe证明。
- `download_ech_status`：下载站实际disabled/used/fallback，绝不覆盖参考 `ech_status`。
- 旧数字零值、旧status/download_state/ech_status枚举保持；`recommended`新操作恒false。
  未知colo在wire仍可为空，UI严格显示 `UKN`（不能用UKN伪造CF trace通过）。

**下载完成的完整快照示例：**

```json
{
  "worker_done":true,"session_id":"SESSION","target_key":"TARGET_SHA256","probe_role":"reference",
  "ech":{"key":"","state":"disabled","prepared_at":0,"expires_at":0,"cache_hit":false,"error":""},
  "download_round_id":"ROUND","download_round_limit":104857600,"download_round_used":52428800,"download_round_remaining":52428800,"download_reserved_bytes":52428800,
  "id":"TASK_DOWNLOAD","operation":"download","state":"completed","phase":"done","completed":1,"total":1,
  "network_id":"wifi-generation-1","started_at":1730000003000,"updated_at":1730000010000,"download_bytes":52428800,
  "warnings":[],"error":"",
  "candidates":[{"address":"1.1.1.1:443","ip":"1.1.1.1","port":443,"comment":"手动备注","source_id":"paste","source_kind":"manual","source_ids":["paste"],"source_kinds":["manual"]}],
  "prefixes":[],"errors":[],
  "results":[{
    "address":"1.1.1.1:443","ip":"1.1.1.1","port":443,"comment":"手动备注","source_id":"paste","source_kind":"manual","source_ids":["paste"],"source_kinds":["manual"],
    "probe_complete":false,"score_ready":false,"score_version":0,"score":0,
    "score_breakdown":[{"key":"tcp_success","points":0,"max_points":30},{"key":"http_success","points":0,"max_points":30},{"key":"tcp_latency","points":0,"max_points":15},{"key":"http_latency","points":0,"max_points":15},{"key":"http_jitter","points":0,"max_points":10}],
    "probe_phase":"","na_reason":"仅下载；参考成绩与证明未修改","result_kind":"download","target_key":"","download_ech_status":"disabled",
    "status":"untested","error":"","colo":"","tcp_measured":false,"http_measured":false,
    "tcp_success":0,"tcp_attempts":0,"tcp_min_ms":0,"tcp_max_ms":0,"tcp_avg_ms":0,"tls_ms":0,
    "http_success":0,"http_attempts":0,"http_median_ms":0,"http_jitter_ms":0,
    "download_mib_s":10,"download_peak_mib_s":12,"download_bytes":52428800,"download_seconds":5,"download_state":"ok",
    "ech_status":"disabled","recommended":false,"tested_at":0
  }]
}
```

Android只合并该Result中的 `download_bytes/download_seconds/download_mib_s/download_peak_mib_s/download_state/download_ech_status`，
按address关联；**不得用整个Result替换reference记录**。取消时 `state=cancelled`、sample failed、速度0，
先收到worker_done=false，再true；正文/seconds/round_used仍可在false期间单调增长，最终取true时结算。
下载失败原因在warnings；没有新下载状态枚举。重复下载仅替换展示样本，账本仍累计所有调用。

#### ECH/取消/账本接入规则（必读）

- `worker_done`是新增布尔，所有操作（含旧fetch/test）都有。只有主流程全部worker返回、取消上下文、
  关闭存活连接并等待Transport脱离请求的拨号/TLS清理后才true。state终态不是资源退出凭据；
  false期间下一次Start返回busy。取消后仅下载累计计量和最终worker_done可更新，禁止晚probe成功。
- `ech`仅公开准备状态：`key/state/prepared_at/expires_at/cache_hit/error`。state为disabled/ready/fallback，
  不与每IP的ech_status混淆。ready不代表ECH已被目标接受；仍逐IP核真实TLS ECHAccepted。
  prepared_at/expires_at Unix毫秒；fallback的expires_at=0表示同UISession不逐批重试，需显式refresh。
  成功按DNS HTTPS及相关CNAME TTL最小值，封顶10分钟；不能提供TTL的内部提供者保守5分钟。
  TTL=0不复用，后续probe明确prepare_required，不擅自延长DNS有效期。
- 一个活跃session中最多8配置组/512KiB正文，超限按最早准备配置淘汰（同时间key稳定打破平局）；
  probe/download缓存缺失/过期均返回prepare_required，绝不自动联网刷新或清队列/账本。
  key按会话内network、规范ECH域名、实际DoH顺序隔离；不同target可共享同组公开配置，配置bytes只存Go。
  取消准备不缓存，新session/新round也不发布半初始化状态；刷新失败明确替换为fallback，保留队列/旧成绩时间。
- 每轮 `download_round_limit=104857600`。`download_round_used`包含本轮所有download任务实际读取正文，
  含失败、取消、错误正文前缀；`download_round_remaining=limit-used`。`download_bytes`仅当前任务消费。
  `download_reserved_bytes`为本任务开始时允许最多读取的额度（min(request,remaining)），结束仍保留，便于核对；
  非download为0。Go单任务槽保证无并发超支；正文读取受该上限和10秒context双限制。
- Android须在Start前按自己已持久化round/余额**先预留**，然后匹配返回round_id及reserved，
  最终仅worker_done=true时用round_used及本任务实际正文结算，不在cancel返回时释放预留。
  进程死亡/Go丢账时持久预留保守保留或拒续，不能用空ID另开session规避。
  Go不能读取Android持久库：新进程第一笔不带round的prepare意味着新账本，调用方必须阻止把遗留账本当首次。
- 明确错误前缀：`busy`、`unknown_session`、`ledger_lost`、`ledger_mismatch`、`network_mismatch`、
  `target_mismatch`、`candidate_mismatch`、`already_complete`、`reference_required`、`prepare_required`、`budget_exhausted`。
  输入错误通常Start立即抛异常；首次prepare合并超4096/准备错误可在task failed/error中返回。不要自动重试新session/新额度。
- 本实现校验调用方network_id归属，不提供系统网络绑定或VPN判定。切网/后台必须Android主动Cancel；
  Android还须核对selection/profile最终参数/网络/上下文/revision，不能拿旧快照批准新选择或自动重连VPN。

新增 `session_test.go/score_test.go` 覆盖wire默认/拒绝未知字段、来源优先与整轮4096、逐IP完成/partial重试、
无下载probe、目标隔离、跨任务/新扫描/显式新轮/失账、download-only和取消结算、ECH缓存/TTL/CNAME/取消/隔离/上界、
score缺项/失败0/稳定排序；旧取消快照回归仅允许新增worker_done的最终false→true。
这些用例和全部旧测试**仅已写入，未在本地运行**；必须由后续GitHub Actions实际编译、`go test -race`及gomobile绑定验收。
