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
| `ProfileEditActivity` | 编辑配置：按协议（GCM / X-Tunnel）切换字段组，`TextInputLayout` 就近校验 | 主页点击 / 滑动菜单 |
| `SettingsActivity` | 全局设置：通用 / 网络 / 代理绕过 / ECH 与 DNS 四分组，每项带当前值 summary | 主页菜单 |
| `AppListActivity` | 分应用代理：搜索、全选/反选、已选计数确认条、加载/空态 | 设置页入口行 |
| `RuntimeLogActivity` | 运行日志查看：等级过滤语义着色（ERROR 红 / WARN 橙）、复制 | 主页菜单 / 错误卡「查看日志」 |
| `CustomCaptureActivity` | 二维码扫描（`DecoratedBarcodeView` + 自定义遮罩） | 添加菜单「扫描二维码」 |

## 核心组件

| 组件 | 说明 |
|---|---|
| `ui/ConnectionFab` | VPN 连接核心控制（`bottom|end` 最底层 FAB）。五态状态机：`DISCONNECTED`（电源/中性）→ `CONNECTING`（主色容器 + `CircularProgressIndicator`）→ `CONNECTED`（success 绿 + ✓）→ `DISCONNECTING`（compact 进度圈，不展开）→ `ERROR`（errorContainer + !，compact 常驻）。tap = 连接/断开；长按 = morph 展开状态卡；节点名/错误信息单行定速滚动（40dp/s），**滚完即自动收折**；展开态 tap 仅折叠 |
| `ui/EmptyStateView` | 空态视图（图标 + 标题 + 说明 + 主按钮），用于 Profile 列表、分应用搜索、日志 |
| `SwipeRevealLayout` | Profile 行滑动揭示菜单（置顶 / 编辑 / 删除 / 分享 / 复制），语义背景色区分操作 |
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

**所有 Go / AAR / APK 编译和测试均走 GitHub Actions**（本地仅允许静态检查）：

| 工作流 | 触发 | 说明 |
|---|---|---|
| `build-aar.yml` | main 推送（`golib/**`）/ 手动 | gomobile AAR 构建，预热默认分支缓存 |
| `release.yml` | `v*` tag / 手动 | tag=构建+签名+发版；手动=release 类型 CI 构建验证（unsigned，不发版） |
| `check-keystore.yml` | 手动 | 验证签名密钥 secrets |

## 全局优选 IP（首版）

入口：主页「更多 → 优选 IP」或节点编辑页的「管理全局优选 IP」。`PreferredIpActivity`
提供来源 / 测试 / 已选三个页签，沿用现有 Material 3 语义色与触控尺寸。

- 官网 IPv4 默认入口、官网 IPv6 可选（检查本机直连 IPv6 地址），`776771.xyz/all.txt`
  仅预置，不会自动抓取；支持其他 HTTPS 列表、粘贴、UTF-8 TXT（2MiB）和旧节点入口收集。
  解析通过固定 Go JSON API，错误行显示原文及行号，必须明确确认只导入有效行。
- 测试使用实际参考 Profile 的最终目标 / ECH / DNS；XT 高级 JSON 先合并。
  默认 CIDR 抽样256、并发8（最多32），推荐最多3个。前5个顺序下载，每IP最多
  5秒/20MiB，总正文最多100MiB，移动/计费网络再次确认。探测及ECH/DNS有额外开销。
- 真实任务每500ms在工作线程查询：`startPreferredIPTask` / `getPreferredIPTask` /
  `cancelPreferredIPTask`，列表解析使用 `parsePreferredIPList`。UI不生成假进度。
  首版必须断开所有可检测VPN；不支持VPN中物理网络桥接。旋转保留控制器和taskId，
  离开页面/后台取消，网络切换取消并标旧结果；进程死亡后不自动启动新任务。
- 保存前每批最多3个已选IP，对需要接管的实际Profile目标做无下载兼容检查
  (`download_top=0`, `recommend_count=0`)。本轮保存检查对相同最终目标/ECH/DNS复用完整兼容结果
  （包括失败项）；不以原测速推荐替代最新检查。确认页列出接管范围、例外、XT最终连接数乘法。
  每个节点联网前独立校验高级JSON、入口冲突、最终目标和合并后的连接数；坏节点留独立并列出
  具体原因，不阻断正常节点。XT连接数沿用编辑页的1–16，GCM沿用1–64；接受Go支持的整数标量/
  整数字符串，不用 `getInt()` 截断小数。取消/网络变化仍中止整轮，零兼容节点不替换旧池。
  仅HTTPS/CF trace通过，不等同WebSocket升级/认证或完整VPN速度。
- 应用私有 `preferred-ip-v1.json` 由UI通过 `AtomicFile` 写入，含 schema/revision、来源快照、
  候选、已选、最近测试、模式映射、按实际目标的兼容地址和接管前模式。保存使用revision
  比较；UI与`:vpn`均先对永久独立的 `preferred-ip-v1.lock` 取OS `FileLock`，再执行完整
  读取/恢复/写入事务（包括API 24的 `.bak` 协议）。`openRead()`只在锁内恢复，写入先fsync，
  模式复制/删除/恢复与池保存使用同一协议；内部 `readLocked` 不重复取锁，避免同进程重叠锁。
  不删除/替换锁文件，不依赖SharedPreferences多进程缓存。损坏库不覆盖；服务保留原入口并记录原因。
- 编辑页支持跟随 / 独立 / 域名默认。原 `PrefIp` / `XtRelayNodes` 保留；GCM
  `FallbackIp` 不参与。未知目标不接管；目标变更使验证失配，运行参数回到原值，需重新验证。
  XT高级JSON含`relay_nodes`时强制独立，可明确确认删除该覆盖键；其他未知高级键不因编辑丢失。
  编辑/冲突删除/URI导入/保存共用高级JSON草稿，旋转后仍保留；导入XT链接前列出并确认删除
  `server_addr/token/relay_nodes/connections` 等链接字段的旧高级覆盖（ECH域名/DNS恢复全局设置），
  取消不修改表单。完整URI缺少或留空 `ip/relay_nodes` 均清空旧入口。首次导入且没有明确模式时
  保持隐式默认，不伪造用户独立选择；池未启用时用原入口，池启用后也须该节点自己的验证及确认。
  替换已明确配置模式的节点仍保存为独立URI快照；原入口单独保存，不被全局池改写。
- `PreferredIpResolver`统一模式优先级，服务在高级JSON合并后应用池，分享URI导出当时有效
  入口快照（无备注/继承绑定）。`Preferences.forProfile(id)`提供不改变当前Profile指针的
  独立访问器，编辑、导入、分享和遍历验证不再通过持久切换当前节点读取其他节点。
- 更新来源或取消测试不修改已选池。全局保存/禁用均下次连接生效，不自动重连；
  「恢复原配置 / 禁用全局」恢复接管前模式，仍保留池与原IP。复制/删除节点同步处理模式。

验证边界：本地仅进行了源码/XML/资源引用静态检查。新AAR API绑定、Android编译、真机的
深浅主题/字体放大/横屏/网络切换/VPN检测和跨进程文件读写需集成后在GitHub Actions及设备验证。

### 优选 IP 审查修复：静态回归场景

以下是源码路径检查与后续设备验收输入，**不是已运行的Android测试**，不引入测试依赖。
本地只能运行下方Python源码断言；真正的并发时序、Activity旋转/对话框及协议运行结果需CI/设备验证。

| 场景/输入 | 应保持的结果与检查位置 |
|---|---|
| API 24写者在 `startWrite` 后暂停，VPN读者读取；另测写者进程死亡并遗留 `.bak` | `PreferredIpStore.withLock` 在存在性判断之前取独立文件锁；读者不能打开在写base；进程死亡释放锁后 `readLocked/openRead` 恢复完整旧版本。现代系统 `.new` 恢复亦在锁内。 |
| `setMode/copyMode/removeMode`、保存池、禁用恢复，与另一进程读者交错；陈旧revision提交 | 内部只调用 `readLocked/writeLocked`，不嵌套 `withLock`；CAS与写后读取在同一锁内。禁用/池保存均经 `write`；陈旧提交报错，不能覆盖新模式。 |
| 旧高级JSON为 `{"server_addr":"wss://old.example","token":"old","relay_nodes":"1.1.1.1:443","connections":8,"custom_key":"keep"}`，导入 `xtunnel://new.example?token=new` | 弹窗列出前4个覆盖键；取消时表单/草稿/模式均不变。确认后保存实际server/token来自链接，relay为空，connections为默认3，`custom_key`仍保留；高级表单其他修改仍可保存。 |
| 删除高级relay冲突→普通高级字段编辑→导入第二个链接→旋转→保存；或退出不保存 | 所有删除只改 `xtAdvancedDraft`；保存不重读旧JSON复活覆盖键。旋转恢复草稿、模式与URI原地址标记，退出未保存不改旧JSON。没有明确模式且未改模式的首次导入保持隐式默认，不伪造independent。 |
| 旧 `PrefIp/XtRelayNodes` 非空，导入GCM/XT链接：不含入口参数，或 `ip=` / `relay_nodes=` | 两种形式都无条件写空输入框，保存为空入口；XT高级relay覆盖必须先确认删除。GCM出口FallbackIp不参与本修复。 |
| 混合节点：损坏高级JSON、`relay_nodes`覆盖、错误target类型/非法端口，另有正常节点 | `prepareSave` 的逐节点try/catch覆盖这些检查；坏节点保持原入口且scope含具体原因，无探测；不把自动失败持久化为用户明确independent；正常节点继续校验和保存。全坏时保留旧池并报告各节点原因。 |
| XT高级connections取 `null/true/{}/[]/"abc"/"3.5"/3.5/0/-1/17`；有效取 `1/16/"3"/3.0` | 前组拒绝且不探测，后组得到1/16/3/3；检查在base+advanced合并后、任何缓存复用及 `run` 前。GCM范围1–64，乘法使用已验证整数。 |
| 显式default的ws:// XT节点保留旧relay，另一个正常节点保存池 | 不参与接管的节点在目标/连接数检查前跳过，保留default，下次仍传空入口；高级relay冲突仍须单独处理。 |
| 编辑页重建后扫码结果先于优选库加载返回；加载中再旋转 | URI暂存于 `pendingPreferredImport` 和savedState，加载成功后导入；手动导入按钮加载完成前禁用，不静默丢弃结果。 |
| 空ECH域名/DoH设置，或XT高级JSON显式空值 | 最终参数共用 `resolveDefaults`：域名恢复内置默认，XT空DoH恢复v0.3.4默认，GCM空DoH仍表示共享内置列表；测试/分享/服务一致。 |
| 双栈多地址、多DNS；合法长列表URL | 完整描述/URL分别生成带前缀SHA-256 ID，长度小于256字节；原URL保持，网络比较共用同一函数，不直接截断。 |
| 参数异常时/探测途中/缓存复用结束时取消或切网；预览后修改connections为非法 | 各catch和循环边界仍检查generation/network；整轮中止，不生成/提交新池；`commitPreview`再次检查最终连接数。 |

从仓库根目录执行（仅检查源码结构/XML/资源，不编译、不执行Android/Go代码）：

```sh
python3 app/src/static_checks/preferred_ip_feedback.py
git diff --check
```

### 首次试用反馈修复（11412ad 基线）

静态确认的路径与限制：

- `ProfileEditActivity` 原先把首次 URI 导入一律记成明确独立；`ProfileListActivity` 在池已启用时
  也把新导入节点记成独立。`prepareSave` 正确跳过明确独立节点，因此此组合可导致保留旧入口。
  新导入现在和新建节点一样保留隐式模式，只有验证完成并确认保存后才能应用；没有迁移历史
  `independent`，因为旧数据无法区分用户意图。旧节点若已明确独立，需用户在编辑页选择跟随。
- 原先保存预览把参数错误/不兼容节点写成 `independent`，下一次预览又把自动失败当作用户设置跳过。
  现在失败仅产生诊断、没有验证则实际保留原入口，不写入伪造的明确模式。显式独立/default、
  高级 `relay_nodes` 冲突、未知CF目标仍不会被强行接管。
- `Resolver.apply` 原本已经在高级JSON后取已选与验证地址交集；未发现“已保存且匹配的global
  仍必然使用旧6IP”的源码证据。新增 `Decision` 由这一实际修改路径返回采用状态、原因与最终数量，
  编辑页、已选页和服务日志复用它。三IP×最终connections=3应输出9条**配置**通道；不声称已连接或认证。
  没有用户当时日志，不能断言手机上旧6IP就是上述某一条分支所致。
- UI区分选择草稿、完整验证但未确认、已确认保存及当前已保存的下次启动参数；正在运行VPN不重连。
  兼容报告独立于 `last_test`，逐IP列出各实际节点（目标/端口/ECH/DNS）、有效/失败/未测与具体原因。
  全失败仍展示诊断并禁止保存；部分成功按各节点的真实子集接管。无下载检查的 `recommended=false`
  不作失败依据。同一检查轮只复用完整同目标结果，不把某目标失败扩散到其他目标。
- 取消/离开、网络、选择、节点参数（包括最终connections）或池revision变化会使报告过期、移除预览。
  提交前再校验generation/network、完整节点签名和选择，并保留原AtomicFile CAS。
  新页面会话不把保存历史称为刚完成验证；已保存池的实际参数摘要仍可查看。
- 一个公共固定高度日志框位于三个页签外：竖屏160dp/横屏96dp，最多160行/24KiB，外部超长行截断。
  真实阶段/进度、下载正文、URL、警告、错误与取消转成可读事件；累计warnings按序号增量消费，
  相同快照不反复输出，不显示原始JSON。仅原在底部且未手动触摸时跟随；旋转保留历史滚动位置。
- 第三方来源按内部完整SHA256 ID映射URL；多来源分别显示，缺记录显示“原URL映射已缺失”，
  不回退随机码。第三方COMMENT只隐藏展示；混合来源备注仅从纯manual源记录恢复，避免合并碎片泄漏。
  手工备注、解析及内部comment未删除。UI统一“HTTPS验证/请求延迟”；Go字段仍为 `http_*`。

新增 `app/src/androidTest/java/com/x/client/app/PreferredIpFeedbackTest.java` 为无框架依赖的设备断言集，
通过 `run(Context)` 供后续CI/设备harness调用（未接入自动runner，不增加依赖）：覆盖3/9接管、部分子集、禁用/独立/default/目标变更/高级冲突、
0-download非推荐仍有效、URL多来源/备注、日志去重和界限。**仅编写，未本地运行或编译，也未运行CI。**
静态脚本通过只说明源代码守卫/XML/词法括号成立，不证明Android编译或设备行为。

仍需新CI验证AAR API/Android构建，并在设备检查首导入→选择3IP→验证→确认→下次连接9条参数、
全失败及部分兼容、取消/切网/参数变更、长URL、大字体/暗色/横屏、日志手动历史滚动和旋转。
首次183916a包的用户反馈不能用旧11412ad CI成功记录替代本轮验证。
