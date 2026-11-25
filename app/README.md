# app — X Client Android 应用

`app/` 是 X Client 的 Android 模块：多协议 VPN 客户端 UI 与服务层。
协议核心由 `golib/`（Go，module `xclient`）提供，经 `gomobile bind` 编译为
`app/libs/xclient.aar`（CI 生成，不入库）。

- Java 17，单 Activity 栈 + XML Views（无 Compose）
- minSdk 24 / targetSdk 34，支持 4 种 ABI（armeabi-v7a / arm64-v8a / x86 / x86_64）
- 主题：Material 3（`Theme.XClient`，DayNight），三层设计 token

## 屏幕清单

| 屏幕（Activity） | 职责 | 入口 |
|---|---|---|
| `ProfileListActivity` | 主页：Profile 列表 + `Connection FAB` 连接/断开 + 添加/导入（扫码 / 手动）+ 主题切换菜单 | 启动器 |
| `ProfileEditActivity` | 编辑配置：按协议（GCM / X-Tunnel）切换字段组，`TextInputLayout` 就近校验 | 主页卡片次级菜单 / 长按 |
| `SettingsActivity` | 全局设置：通用 / 网络 / 代理绕过 / ECH 与 DNS 四分组，每项带当前值 summary | 主页菜单 |
| `AppListActivity` | 分应用代理：搜索、全选/反选、已选计数确认条、加载/空态 | 设置页入口行 |
| `RuntimeLogActivity` | 运行日志查看：等级过滤语义着色（ERROR 红 / WARN 橙）、复制 | 主页菜单 / 错误卡「查看日志」 |
| `CustomCaptureActivity` | 二维码扫描（`DecoratedBarcodeView` + 自定义遮罩） | 添加菜单「扫描二维码」 |

## 核心组件

| 组件 | 说明 |
|---|---|
| `ui/ConnectionFab` | VPN 连接核心控制（`bottom|end` 最底层 FAB）。五态状态机：`DISCONNECTED`（电源/中性）→ `CONNECTING`（主色容器 + `CircularProgressIndicator`）→ `CONNECTED`（success 绿 + ✓）→ `DISCONNECTING`（compact 进度圈，不展开）→ `ERROR`（errorContainer + !，compact 常驻）。tap = 连接/断开；长按 = morph 展开状态卡；节点名/错误信息单行定速滚动（40dp/s），**滚完即自动收折**；展开态 tap 仅折叠 |
| `ui/EmptyStateView` | 空态视图（图标 + 标题 + 说明 + 主按钮），用于 Profile 列表、分应用搜索、日志 |
| `Profile 卡片 / PopupMenu` | Material 3 风格 Profile 列表卡片与次级弹出菜单（连接/断开、设为当前、编辑、复制、分享导出、删除），支持卡片右侧 `>` 按钮与长按触发 |
| `ScannerOverlayView` | 扫码遮罩自定义绘制（颜色可经 `app:scanner*` 属性配置） |
| `Preferences` | SharedPreferences 集中访问（Profile 列表 / 全局设置 / 主题模式），`MODE_MULTI_PROCESS` |
| `ThemeManager` | 主题切换（浅色 / 深色 / 跟随系统，默认跟随系统），经 `AppCompatDelegate.setDefaultNightMode` |
| `TProxyService` | VPN 前台服务（`specialUse`，独立 `:vpn` 进程）：TUN 接口 + hev-socks5-tunnel 转发 |

## 服务与进程模型

```
main 进程                          :vpn 进程
ProfileListActivity  ──CONNECT──▶  TProxyService（VpnService + TUN）
        ◀──── ACTION_STATUS ────  sendBroadcast(STATUS_STARTING/STARTED/ERROR/STOPPED)
        ──REQUEST_RUNTIME_LOGS──▶  ──ACTION_RUNTIME_LOGS──▶（2s 超时兜底）
```

- `TProxyService` 组装协议参数并调用 `xclient.Xclient.startSocksProxy(...)`；
  `VPN 正在运行` 时主页禁止切换/编辑/删除当前配置
- 网络切换由 `ConnectivityManager.NetworkCallback` 自动重连

## 主题系统与设计 token

```
app/src/main/res/values[-night]/
├── colors.xml    # primitive（p_brand_seed）→ semantic（x_*：M3 角色；md_*：扩展状态色）
└── styles.xml    # Theme.XClient（Material3.DayNight.NoActionBar）+ Widget.XClient.* 组件族
```

**规范约定（写 UI 必须遵守）**：

1. 组件层取色只允许 `?attr/colorPrimary` 等主题角色，禁止裸 hex；light/dark 在
   `values` / `values-night` 成对定义（**styles 是整体重声明，双份都要改**）
2. 间距 4dp 步进（`@dimen/spacing_*`）；字号走 `textAppearance`（4 档 2 字重）
3. 触控目标 ≥48dp；交互图标必须有 `contentDescription`
4. 状态色语义全 App 唯一：绿=已连接、品牌蓝=连接中、中性=断开、红=错误、橙仅警告
5. 自定义动画需显式处理 `ANIMATOR_DURATION_SCALE`（reduced-motion 跳切）

## 核心功能说明

### 多协议 Profile

- 每个 Profile 独立选择 `Protocol`（`gcm` / `xtunnel`），决定字段组与后端分发
- 导入通道：`gcm://` / `ech://`（兼容）/ `xtunnel://` URI 解析、二维码扫描
- 导出：生成协议 URI + 二维码，一键复制

### 代理绕过

- 内置开关：本地/局域网、GeoIP:CN、GeoSite:CN
- 手动规则：CIDR / 域名 / `full:` 全匹配（换行分隔），保存前经核心库校验
- 运行中的 VPN 锁定全局设置与当前 Profile，UI 提供视觉 disabled 状态

### 运行日志

- 内存环形缓冲，等级可调（下次启动 VPN 生效）
- 时间戳跟随 Android 系统时区，运行中改时区即时生效

## 协议细节与数据流

协议格式、参数字段、ECH/DoH 回退链与数据流图见 **[`golib/README.md`](../golib/README.md)**。

## 构建

**构建和 Go 核心测试走 GitHub Actions；Android App 行为由用户在设备上手工验证**（本地不运行项目构建）：

| 工作流 | 触发 | 说明 |
|---|---|---|
| `build-aar.yml` | main 推送（`golib/**`）/ 手动 | gomobile AAR 构建，预热默认分支缓存 |
| `release.yml` | `v*` tag / 手动 | tag=构建+签名+发版；手动=Go `-race` 测试 + `gomobile`/API 检查 + 签名 `.debug` APK 构建（不运行 App 测试，不发版） |
| `check-keystore.yml` | 手动 | 验证签名密钥 secrets |

## 全局优选 IP v2（Android 实现；行为需设备手工验证）

入口仍为主页「更多 → 优选 IP」及节点编辑页「管理全局优选 IP」。原生 Java / Material 3，
`TabLayout + ViewPager/PagerAdapter` 两页「来源 / 测试」，不新增依赖。没有已选页、优选日志框、
推荐按钮或确认保存流程；`PreferredIpLog` 保留为兼容工具，但页面不调用它。

### 会话与线程边界

- `PreferredIpRuntime` 在 UI 进程保留一个不持有 Activity 的 Controller。离页、后台和旋转都请求
  真实暂停；同进程返回且上下文有效后，用户可继续。切页（点击/横滑）不取消。进程死亡仅恢复
  已选/历史/账本，不恢复半个网络任务，也不自动启动任务。没有优选后台 Service。
- `control` 串行执行状态转换，`networkIO` 单独做阻塞 Start/Get，`canceller` 是独立快速取消通道，
  `selectionIO` 做短落盘事务，`sourceIO` 做文本/TXT解析和来源存储，`historyIO` 做独立历史写入。
  轮询/下载不会排在复选框或取消前；取消在 Start 尚未返回时也会被返回后的检查补发。
- 只有一个 Go 网络任务槽（fetch / prepare / probe / validation / download 共用）。
  `state=cancelled` **不是**释放凭据：匹配 task/session/network 的 `worker_done=true` 后，
  完成结算才释放。Get 异常/失去终态时继续取消并保守占槽，不猜测退出或启动第二个任务。
- `scanEpoch`、`selectionVersion`、根库 `revision` 独立。选择/来源落盘不会误杀扫描。
  实际参考节点最终参数、网络、抽样/并发变化使成绩成为历史并要求重新开始；节点全集/最终高级
  参数另以不透明摘要校验验证提交。高级 Token/UserId 原 JSON 不进入历史或 Go 测试请求。
- 首次 `prepare` 由 Go 一次冻结全部候选；Java 不抽样、不改来源优先级。`probe` 每批最多 N
  （默认8，上限32）；完整成功/失败都保留，partial 在退出后回待测，继续只派发未完成地址。
  `sample_limit=256` 仅为 CIDR 配额，整轮4096上限由 Go 判断。无自动推荐、自动勾选或扫描下载。
- 首次统一 ECH prepare，后续批次复用。`prepare_required` 只串行维护一次、重派一次；仍不可用
  （如 TTL=0）就停止并提示。不同验证目标先 prepare；Go 规范化 key 若等同参考，复用参考证明，
  不发送相同 target 的 validation。不存在缓存错误后悄悄新建会话/额度的重试分支。

### 冻结证明上下文（切网竞态修复）

- 私有不可变 `ScanContext` 冻结 network ID、scan epoch、参考 Profile ID/最终参数摘要、context key、
  公共 target/options JSON（含抽样/并发）；首次 prepare 返回后仅补齐 Go session ID/reference target key。
  通过 volatile 指针安全发布，结果 `resultContexts[address]`、每个扫描 Job 和待下载意图都持有该对象。
  新扫描清空结果及其归属；原会话的新下载轮/ECH维护不替换归属。私有对象不含明文凭据。
- `networkChanged` 在短锁内先递增 epoch、更新网络，再走独立 Cancel、排队 invalidate。
  `eligible` **正向**核对象身份、epoch、网络（已观察网络及实时 NetworkAccess）、当前参考参数摘要、
  非空session、地址/结果归属、reference target key、完整/score-ready/v1/合格；不靠 `!stale` 猜当前。
  因而 invalidate 尚未执行也不能新增；A→B→A 的字符串虽恢复，epoch 不恢复。迟到 invalidate
  不会把用户已明确启动的较新扫描标旧。切tab/checkbox/Store revision 不增加扫描代次。
- 请求写 proof 自己的 network/session；Job 拷贝请求并继承 proof epoch，维护/重派不读取当前代次
  给旧请求换标签。响应核 task/operation/role/network/session，reference 还核 snapshot/row target key。
  下载派发前、持久预留后的 Start 前再检查；被切网拒绝且尚未 Start 的预留走原未入场释放路径。
  已入场旧 Job 仍按原 task/network/session 等 worker_done 并结账，不能以失效为由丢弃下载账本。
- 新增 intent 的资格检查与 Store mutation 内检查持有同一个 proof；取消旧 checked 不受此门槛限制。
  reference复用、异target prepare/probe及其回调也携原 proof。验证提交前和 Store mutation 内首尾
  再核 proof、内存 selectionVersion、最终 Profile 集合/参数；锁内核派发时的持久 selection_version，
  并逐 Profile 核最新 following/mode/实际target、最新selected交集。仍以最新revision作CAS，不覆盖旧整库。
  落盘 network_id/session_id/context_key 来自 proof，而非通过检查时重新采样的网络/会话。
- 此修复只拒绝过期**新提交**，不在切网时改 selected/validations/modes/enabled 或删除历史有效池，
  不自动重连。独立 canceller 不拿 Store 锁；contextLock 不包围 Store/Go I/O。历史快照也始终写测量
  所属的冻结网络/会话。50MiB/10秒、100MiB显式追加、六字段merge、worker_done单槽及有界ECH维护不变。

### 列表与交互

测试页是一份 RecyclerView（设置是可滚动 header，不会在大字体时挤掉列表）：
最左独立48dp复选框 / Location（未知严格 `UKN`） / 可换行 IP:PORT / 右侧一位小数评分。
点击其它行区只展开/收起，checkbox 只保存选择。IPv6 的展示层加零宽断行机会，内部身份仍是
Go 返回的完整规范地址，不截端口。语义 Material 3 颜色、无固定行高、无水平文字滚动。

- 按未舍入 score 降序，未测在中、完整失败在末；同分保持 Go 原队列。未测/旧上下文显示 `—`，
  完整失败显示0；不把 `recommended=false` / 没下载当失败。
- 约500ms聚合；Controller 后台排序，独立 differ 后台计算 DiffUtil，主线程差量通知。
  地址是 identity，稳定 ID 不使用 position/hash 碰撞；绑定前移除 checkbox listener，操作核对
  holder 当前地址。触摸/拖动/TalkBack 聚焦时只更新原顺序的内容，延后位置改变，恢复可见地址锚点。
- 展开状态、设置、选择和滚动位置按地址/会话保留。详情按需在后台生成（不是每500ms生成
  4096×节点详情），展示真实 TCP/TLS/HTTPS/ECH、五项评分、N/A原因、来源URL、手动备注、
  测量时间/网络、下载独立样本和每个实际 Profile 的采用/不采用状态。
- 官网v4/v6、主动第三方HTTPS、粘贴/TXT（2MiB）、旧入口收集均保留。可编辑手动来源/第三方URL、
  刷新/删除/开关；失败保旧快照。编辑不暗改冻结队列，需重新开始。停用/删除来源中的已选仍可见
  可取消；第三方COMMENT仅隐藏，混合来源从纯手动记录恢复真实手动备注。

### 勾选、真实验证与服务安全

勾选不是“直接把 IP 宣称有效”：

1. 新增需当前 reference 的完整、合格、score-ready、版本1、target/session/network上下文匹配结果。
   历史已勾选（即使失败/旧网络/来源停用）仍可取消。
2. 独立短事务在永久 FileLock 内读取最新revision、只改 selected intent / selection_version，
   清掉该地址旧验证以防取消再勾选复活旧网络proof；失败界面回到落盘状态。
3. 同真实目标复用当前完整 reference。其它 following 目标在暂停期用同一网络槽无下载验证；
   不把 validation 结果放进参考排名。提交再次核对选择版本（内存与持久）、最终 Profile 参数/
   集合、冻结证明上下文及当前网络/scan epoch，且在最新revision上只合并实际通过地址，不写旧整库快照。
   持久 selection_version 在派发验证前冻结，不等结果回来后再取新版本。
4. 至少一个合法 following 实际目标通过才首次启用。隐式模式保持隐式；显式 independent/default
   和高级 relay_nodes 冲突不强制迁移。验证失败不删历史有效池；切网也不自动销毁用户已批准的池。
   验证去重/说明缓存有8192项硬上限，避免无界 profile×4096矩阵；超限停止并提示。
5. `PreferredIpResolver.apply` / Service / URI 路径保留：base → XT高级JSON → mode →
   selected∩当前实际target验证子集。原 PrefIp/XtRelayNodes 和 GCM FallbackIp 不改；取消最后
   一个自动停用。更多菜单可明确禁用/允许验证后启用。任何保存都不重连正在运行的VPN。

### 下载预算与持久化

- 行内测速先真暂停等退出，然后仅该一个 IP `operation=download`。最多 **50MiB / 10秒**，
  `https://speed.cloudflare.com/__down?bytes=52428800`；结束保持暂停。计费网络显式确认。
  只merge `download_bytes/download_seconds/download_mib_s/download_peak_mib_s/download_state/download_ech_status`
  六字段，参考 TLS/ECH/proof/score/tested_at 不变。
- **prepare/probe** 使用旧通用20MiB/5秒选项且 download_top/recommend_count=0；不错误地把新下载
  的50MiB/10秒交给 Go 旧通用参数校验。旧公开 fetch/test / 四个 gomobile API 签名未改。
- `PreferredIpLedger` 在 Go Start 前独立 Store 事务预留 min(50MiB,left)；仅 worker_done 后按
  同round、相同reservation、`新累计used=旧used+本任务bytes` 单调核对结算，取消/失败也计。
  同步 Start 拒绝且未拿到taskId意味着未入场，可释放该次预留；Get失败/不匹配不会释放。
- 根库 `download_ledger` 保存 id/used/reserved/owner/previous。换任务、暂停/继续、新扫描都携带
  原round ID；进程变化、失账、不符、残留预留都拒绝悄悄新建100MiB。只有更多菜单明确确认新轮
  才允许追加；新进程在确认后可省略遗失的Go round ID，旧账本留 previous（有界一份）。同进程
  无法核对的Go轮ID拒绝继续，需重启并再次明确确认，不猜余额。
- 根库继续 schema1、16MiB、AtomicFile + 永久 preferred-ip-v1.lock（API24 .bak 恢复也在锁内）。
  `PreferredIpStore.update` 是短read/latest-revision/CAS，不嵌套锁、不做网络。
- `PreferredIpHistory` 独立 AtomicFile + 永久 history lock：最近完整 `preferred-ip-complete.json`
  和当前 `preferred-ip-checkpoint.json` 各最多16MiB/4096条。旧 last_test **先转存**公共字段白名单，
  再CAS成轻量引用；失败保原库。批次完成/暂停才写历史，不按500ms全量写。超限明确提示历史未
  完整保存，不静默截断，也不回滚用户已保存池。

### App 手工验证

GitHub Actions 手动 CI 构建（`.debug` 包）执行 `go test -race`、`gomobile`（未命中缓存时重新绑定）与 JSON API 签名检查，以及 `com.x.client.app.debug` APK 构建和签名校验，不运行 Android Instrumentation / App 模拟测试。所有 App 行为验证均由用户在设备上手工完成；当前仓库不保留模拟网络的 `androidTest` 套件、Instrumentation runner 或对应报告 parser。CI 成功只代表上述构建、签名与 Go 检查通过，不代表 App 行为已通过测试。
