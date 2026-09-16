# X Client UX 竞品调研报告（Web Research）

> 调研角色：UX Researcher ｜ 日期：2026-09
> 调研对象：v2rayNG、Clash Meta for Android (CMFA)、WireGuard Android、Tailscale Android、Cloudflare 1.1.1.1、Outline Client
> 目标：为 X Client（多协议 VPN 客户端：Profile 管理 + 一键连接 + 分应用代理 + 扫码导入 + 设置 + 日志）的 UI 重构提供有据可查的模式、最佳实践与反模式。

---

## 1. 调研方法与来源清单

### 1.1 方法

- 直接抓取各竞品 GitHub 仓库的 README、wiki、源码（布局 XML / Kotlin UI / 字符串资源 / issue 讨论），以**源码事实**而非截图印象为证据；
- 抓取 m3.material.io 内容接口（FAB、Extended FAB、Navigation bar、Snackbar、Progress indicator、Switch、Color system、Dynamic color 八个页面）与 developer.android.com / source.android.com 官方设计文档；
- 辅以 Nielsen Norman Group 等可用性研究机构的公开文章；
- 每条关键结论均标注来源 URL；无法找到可靠来源的结论标注 **[推断]**。

### 1.2 来源清单

**官方规范（Material / Android）：**

| # | 来源 | URL |
|---|------|-----|
| S1 | M3 FAB guidelines（"FAB 用于屏幕最重要操作"、单屏禁多 FAB、尺寸与色彩） | https://m3.material.io/components/floating-action-button/guidelines |
| S2 | M3 Extended FAB guidelines（需要文字标签理解动作时用 extended FAB） | https://m3.material.io/components/extended-fab/guidelines |
| S3 | M3 Navigation bar guidelines（3–5 个顶级目的地、固定位置、仅紧凑/中等窗口） | https://m3.material.io/components/navigation-bar/guidelines |
| S4 | M3 Color system（26+ 色彩角色、内置暗色方案、三档对比度） | https://m3.material.io/styles/color/system/overview |
| S5 | M3 Dynamic color（用户壁纸取色 vs 内容取色的适用场景） | https://m3.material.io/styles/color/dynamic-color/overview |
| S6 | M3 Snackbar guidelines（不打断用户、dismissive/non-dismissive、inverse surface 配色） | https://m3.material.io/components/snackbar/guidelines |
| S7 | M3 Progress indicators（线性/圆形、所有实例统一配置、end stop 提升可感知性） | https://m3.material.io/components/progress-indicators/guidelines |
| S8 | M3 Switch guidelines（"开关是调整设置的最佳方式"、on/off 必须一眼可辨） | https://m3.material.io/components/switch/guidelines |
| S9 | Android 官方暗色主题（DayNight、禁止硬编码颜色、三选项默认跟随系统） | https://developer.android.com/develop/ui/views/theming/darktheme |
| S10 | Android 动态取色实现（Material 3 dynamic color / dynamic colors API） | https://developer.android.com/develop/ui/views/theming/dynamiccolor |
| S11 | AndroidX Settings 指南（Preference 库、key 唯一、指向 AOSP 设置设计规范） | https://developer.android.com/develop/ui/views/components/settings |
| S12 | AOSP Settings 设计规范（单屏 10–15 项上限、常用在上、标题+状态值、页面类型） | https://source.android.com/docs/core/settings/settings-guidelines |
| S13 | NN/g 错误信息指南（就近显示、红/高对比惯例、"识别-诊断-恢复"） | https://www.nngroup.com/articles/error-message-guidelines/ |

**竞品一手证据（GitHub 源码/文档）：**

| # | 来源 | URL |
|---|------|-----|
| C1 | v2rayNG 仓库（README + wiki） | https://github.com/2dust/v2rayNG |
| C1a | v2rayNG `MainBottomBar.kt`（连接 FAB + 状态底栏源码） | https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainBottomBar.kt |
| C1b | v2rayNG issue #2531 "New UI"（连接按钮绿→橙的用户抗议） | https://github.com/2dust/v2rayNG/issues/2531 |
| C1c | v2rayNG issue #1818 "start service failure"（仅 Toast 报错用户无法自助） | https://github.com/2dust/v2rayNG/issues/1818 |
| C1d | v2rayNG `PerAppProxyActivity.kt` / `PerAppProxyViewModel.kt`（分应用搜索/全选/反选/导入导出） | https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/java/com/v2ray/ang/ui/perappproxy/PerAppProxyActivity.kt |
| C1e | v2rayNG `MainViewModel.kt`（延迟测试结果、按结果排序、连接信息检测） | https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/java/com/v2ray/ang/ui/main/MainViewModel.kt |
| C1f | v2rayNG `strings.xml`（状态 Toast、订阅后自动测速/排序/删除、导入菜单文案） | https://github.com/2dust/v2rayNG/blob/master/V2rayNG/app/src/main/res/values/strings.xml |
| C1g | v2rayNG wiki "Mode"（VPN 模式 vs Proxy only 模式取舍说明） | https://github.com/2dust/v2rayNG/wiki/Mode |
| C2 | CMFA 仓库 | https://github.com/MetaCubeX/ClashMetaForAndroid |
| C2a | CMFA `design_main.xml`（LargeActionCard 大卡片启停 + 状态换色 + 流量文案） | https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/design/src/main/res/layout/design_main.xml |
| C2b | CMFA `adapter_profile.xml`（Profile 行字段：name/type/active/流量/到期/更新时间） | https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/design/src/main/res/layout/adapter_profile.xml |
| C2c | CMFA `SettingsDesign.kt` + `SettingsActivity.kt` 等（设置分 4 个子页） | https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/design/src/main/java/com/github/kr328/clash/design/SettingsDesign.kt |
| C2d | CMFA `design_access_control.xml`（分应用页含搜索入口 + 菜单） | https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/design/src/main/res/layout/design_access_control.xml |
| C3 | WireGuard Android 仓库 | https://github.com/wireguard/wireguard-android |
| C3a | WG `AppListDialogFragment.kt`（Exclude/Include 双 Tab、按名排序、计数确认、全选按钮、加载与错误处理） | https://github.com/wireguard/wireguard-android/blob/master/ui/src/main/java/com/wireguard/android/fragment/AppListDialogFragment.kt |
| C3b | WG `tunnel_list_fragment.xml` + `strings.xml`（列表 + 创建 FAB + 空态占位文案） | https://github.com/wireguard/wireguard-android/blob/master/ui/src/main/res/layout/tunnel_list_fragment.xml |
| C4 | Tailscale Android 仓库 | https://github.com/tailscale/tailscale-android |
| C4a | Tailscale `MainView.kt`（顶部开关行 + 状态文本 + 健康警告图标入口 + 进行中禁用开关） | https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/ui/view/MainView.kt |
| C4b | Tailscale `HealthView.kt`（健康警告列表、严重度配色、空态） | https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/ui/view/HealthView.kt |
| C5 | Outline Client 仓库 | https://github.com/OutlineFoundation/outline-apps |
| C5a | Outline `original_messages.json`（连接五态文案、失败文案含"截图错误详情发给服务商"、诊断链接、自动重连说明） | https://github.com/OutlineFoundation/outline-apps/blob/master/client/resources/original_messages.json |
| C6 | Cloudflare 1.1.1.1 官方文档（Android 设置：大开关 "Toggle the WARP button to Connected"、模式在设置中选择、默认 WARP） | https://developers.cloudflare.com/1.1.1.1/setup/android/ |

> 说明：m3.material.io 页面为 Angular SPA，本次通过其内容接口 `_dsm/content/m3/{version}/{fileId}.json` 获取与页面等价的官方内容 JSON；developer.android.com / source.android.com 通过抓取页面正文提取。上述 URL 均为可公开访问的规范页面。

---

## 2. Common Patterns（共性模式）

### P1. 主页"单一主操作"模式：大按钮/大卡片占据视觉中心，一切围绕连接状态
- **描述**：首页不做信息分发，核心是一个不可错过的大尺寸启停控件，其余内容按需展示。
- **证据**：
  - CMFA：主页第一元素是全宽 `LargeActionCard`，未连接显示 "Stopped / Tap to start"，运行中变为 "Running / 已转发流量"，背景色随状态切换（`colorClashStarted`/`colorClashStopped`），图标从 `not_interested` 变为 `check_circle` — C2a
  - 1.1.1.1：官方文档表述为单个大开关 "Toggle the WARP button to Connected" — C6
  - Tailscale：主页顶部是带 `TintedSwitch` 的 ListItem，下方直接展示状态文本（Connected/Disconnected 等）— C4a
  - Outline：单一服务器大圆钮 + Connected/Connecting…/Disconnected/Disconnecting… 四态 — C5a
- **来源**：C2a、C4a、C5a、C6

### P2. 连接状态 = 颜色语义 + 动词化文案 双通道传达
- **描述**：所有竞品用"状态色 + 状态词 + 附加信息"三层传达连接状态，且状态控件本身换色/换图标。
- **证据**：
  - CMFA 大卡片：色块 + 图标 + "Running/Stopped" + 副文案（流量或"tap to start"）— C2a
  - v2rayNG：FAB 容器色随 `isRunning` 切换（active 色与 inactive 明暗两套），图标 play↔stop — C1a
  - Outline：Disconnecting/Connecting 用进行时态文案，区别于终态 Connected/Disconnected — C5a
- **注意**：色彩语义不可轻易更改 — v2rayNG 把连接按钮从绿色改成橙色后，用户在 issue #2531 明确抗议"用户习惯绿色代表已连接"（C1b）。
- **来源**：C1a、C1b、C2a、C5a

### P3. 主页同时承载"当前配置"上下文（连接不脱离列表）
- **描述**：多配置 VPN 的主页连接动作始终与"当前选中的配置"绑定，用户能在首页看到将用哪个配置连。
- **证据**：
  - v2rayNG：主页 = 服务器列表 + 底部状态栏（显示状态文本，点按=测当前配置延迟）+ FAB 启停，连接动作直接作用于列表中当前选中项 — C1a
  - CMFA：主页显示当前 profile 名称与所属模式（`profileName`、`mode` 变量绑定进 `design_main.xml`）— C2a
- **来源**：C1a、C2a

### P4. 延迟测试 + 按延迟排序 + 失效自动清理
- **描述**：多节点客户端标配"一键测全部延迟"→ 数字显示在每行 → 支持按延迟排序，甚至订阅更新后自动执行"测速-排序-删除失效节点"流水线。
- **证据**：
  - v2rayNG `MainViewModel.kt`：批量测速结果流式写入每行 `testDelayMillis`，提供 `SortByTestResults` 动作；连接测试还带出口 IP/国家识别 — C1e
  - v2rayNG `strings.xml`：`Auto test after updating subscription`、`Auto sort after testing`、`Auto delete invalid config after testing`、`Enable speed display`、可配置 `Real delay test URL` — C1f
- **来源**：C1e、C1f

### P5. Profile 行信息密度：名称 + 类型/协议 + 动态指标 + 生命周期信息
- **描述**：多配置列表行在名称之外，展示协议/类型标签、测速结果或流量、到期与更新时间等"活数据"，并用高亮标记当前激活项。
- **证据**：
  - CMFA `adapter_profile.xml`：`profile.name`、`profile.type`（未保存时显示"未保存"前缀）、`profile.active`、上传/下载流量、`profile.expire`、`profile.total`、`profile.updatedAt` — C2b
  - v2rayNG：行内显示名称 + 延迟数字（测试后），按订阅分组分页（MainGroupTab / MainServerPager）— C1e
- **来源**：C2b、C1e

### P6. 分应用代理 = 搜索 + 全选/反选 + 模式（排除/包含）+ 计数确认
- **描述**：应用动辄数百个，行业惯例是：顶部搜索框过滤；提供全选/反选；明确"排除模式/包含模式"两种语义；确认按钮动态显示"将影响 N 个应用"。
- **证据**：
  - WireGuard Android `AppListDialogFragment`：TabLayout 两个 Tab（Exclude/Include），列表按应用名大小写不敏感排序，底部确认按钮用复数资源显示 "Exclude N applications"/"Include N applications"，中性按钮 "Toggle all" 一键全选/反选，仅列出持有 INTERNET 权限的应用，加载中有 `progress_bar` — C3a
  - v2rayNG `PerAppProxyActivity`：搜索（`filterApps`）+ `selectAll` + `invertSelection` + 选区从剪贴板导入/导出 + 排序时已选中项置顶 + bypass 模式开关 + `isLoading` 加载态 — C1d
  - CMFA 分应用页（Access Control）：顶栏含搜索入口与菜单 — C2d
- **来源**：C3a、C1d、C2d

### P7. 设置按"域"拆子页，每个子页内再用分组
- **描述**：功能多的 VPN App 把设置拆成顶层分类入口（列表）→ 子页（可滚动的 preference 分组），而非单屏堆叠。
- **证据**：
  - CMFA：设置主页只有 4 个入口 — App（应用）/ Network（网络）/ Override（覆写）/ Meta Feature（内核高级功能），各自独立 Activity — C2c
  - AOSP 设置规范：单屏 10–15 项封顶、常用项放顶部、需要多入口的设置做成独立页后从多处链接 — S12
  - v2rayNG：设置项按功能命名分组（自动测速类、Mux 类、测速 URL 类等）— C1f
- **来源**：C2c、S12、C1f

### P8. 空态 = 明确的下一步动作指引
- **描述**：列表为空时不只说"没有数据"，而是告诉用户怎么创造第一条数据，且指向屏幕上的具体控件。
- **证据**：
  - WireGuard Android：隧道列表空态占位文案为 "Add a tunnel using the button below"（直接指向屏幕下方创建 FAB）— C3b
  - Tailscale HealthView：无警告时显示"无健康问题"的空态而非空白 — C4b
  - v2rayNG 分应用页：`isLoading` 时展示加载，避免空列表误读 — C1d
- **来源**：C3b、C4b、C1d

### P9. 导入配置的多通道入口（二维码 / 剪贴板 / 文件 / 按协议手动）
- **描述**：多协议客户端的"添加配置"菜单按导入来源组织，手动添加按协议类型逐项列出。
- **证据**：v2rayNG `strings.xml`：`Import from QR code` / `Import from Clipboard` / `Import from a local file` / `Add [VMess]`、`Add [VLESS]`、`Add [Shadowsocks]`、`Add [Trojan]`、`Add [Hysteria2]`… 按协议逐一列出 — C1f
- **来源**：C1f

### P10. 状态轻提示（Toast/Snackbar）+ 常驻状态展示分层
- **描述**：瞬时事件（启动中/成功/失败）用一闪而过的轻提示，持续状态（当前连接、流量）放常驻区域，两者职责分离。
- **证据**：
  - v2rayNG：`Starting service`/`Stopping service`/`Service started successfully`/`Failed to start service` 走 Toast；持续状态由底栏 `displayText` 常驻展示 — C1f、C1a
  - M3 Snackbar 规范：Snackbar 用于不打断的信息，分 dismissive（自动消失）与 non-dismissive（须用户处理），置于底部 — S6
- **来源**：C1a、C1f、S6

---

## 3. Best Practices（最佳实践）

### B1. 主操作唯一：单屏最多一个 FAB / 一个大卡片
M3 规范明确："Use a FAB for the most important action on a screen; it appears in front of all other content"、"Don't display multiple FABs on a single screen"（单屏 3 个 FAB 会让人分不清主操作）。若动作需要文字才能理解，用 extended FAB（"Use instead of FAB when label text is needed to understand action"）。
**来源**：S1、S2

### B2. 暗色模式禁止硬编码颜色，一律走主题属性
Android 官方暗色主题文档："Avoid using hardcoded colors or icons intended for use under a light theme. Use theme attributes or night-qualified resources instead."，重点使用 `?android:attr/textColorPrimary`、`?attr/colorControlNormal`，以及 MDC 的 `?attr/colorSurface`/`?attr/colorOnSurface`。**来源**：S9

### B3. 主题切换提供三选项且默认"跟随系统"
官方推荐选项集：Light / Dark / System default（"the recommended default option"）；API 31+ 用 `UiModeManager#setApplicationNightMode`，API 30 及以下用 `AppCompatDelegate.setDefaultNightMode()`（会自动重建已启动的 Activity，需要处理状态保存）。**来源**：S9

### B4. 开关状态必须一眼可辨，且是"调整设置"的首选控件
M3 Switch："Switches are the best way to let people adjust settings"、"Make sure the switch's selection (on or off) is visible at a glance"。连接类 App 的启停若用 Switch，须保证 on/off 视觉差异显著（M3 的轨道加宽、handle 图标皆为此设计）。**来源**：S8

### B5. 错误信息就近展示、用约定俗成的红色/高对比样式、支撑"识别-诊断-恢复"
NN/g：错误提示应出现在错误源附近（proximity），使用加粗、高对比、红色等约定视觉；遵循启发式 #9 "Help Users Recognize, Diagnose, and Recover from Errors"。**来源**：S13

### B6. 错误恢复要给出可执行路径：诊断信息可带走 + 兜底入口
Outline 的失败文案模板："Failed to connect. Please check your internet connectivity, then screenshot the error details and send them to your access key provider." —— 同时给出"自查网络"动作与"带错误详情求助"路径；另有独立帮助主题列表（"I am having trouble connecting to my Outline VPN server" 等）。Tailscale 把所有健康问题集中成 Health 警告列表（按严重度着色），主页只放一个小图标入口。**来源**：C5a、C4b

### B7. 加载状态统一风格并覆盖慢路径
M3 Progress indicators："Use the same configuration for all instances of a process"（同一流程的加载指示器视觉一致）；WireGuard 分应用列表加载期间显示 `progress_bar` 而非空白列表。**来源**：S7、C3a

### B8. 设置项标题 + 当前状态值成对出现
AOSP 设置规范："Make your settings' titles brief and meaningful… Below the title, show the status to highlight the value of the setting"（标题下用 summary 显示具体当前值），标题避免"General settings"这类模糊词、避免 set/change/manage 等空动词。**来源**：S12

### B9. 无障碍标签与触达性
v2rayNG 连接 FAB 为 start/stop 图标提供动态 `contentDescription`（`acc_start`/`acc_stop`），底栏状态文本同样加语义描述（C1a）；Tailscale 在切换进行中禁用开关防止双击（"Disable switch if toggle is in progress"，乐观 UI + 防抖）（C4a）。触控目标遵守 Material ≥48dp 的组件默认尺寸 **[推断：各竞品未文档化具体值，遵循 M3 组件默认]**。

---

## 4. Anti-patterns（反模式）

### A1. 单屏多个竞争性主操作
M3 明确以 3 个 FAB 同屏为反例："A screen with 3 FABs makes it hard to tell what the primary action should be." 对 VPN 主页而言，启停控件应唯一。**来源**：S1

### A2. 违背用户已建立的颜色语义
v2rayNG 新 UI 将连接按钮由绿改橙，用户抗议"Users are accustomed to seeing green for connection"，担心橙色被误读（issue #2531）。教训：连接/成功=绿、警告=橙、错误=红是 VPN 用户的心理模型，改动需渐进或保留状态色语义。**来源**：C1b

### A3. 硬编码颜色（尤其给暗色模式埋雷）
官方将 hardcoded colors 列为暗色主题首要禁忌（S9）。X Client 现状（`#4CAF50`/`#FF9800`/`#2196F3`/`#F44336` 散落于布局与 Java）正属此类，重构时应以语义 token 替代（对照 S4 的 26+ 色彩角色）。

### A4. 失败只弹一条 Toast，无后续路径
v2rayNG "start service failure" 仅 Toast 报错，用户在 issue #1818 里只能求助论坛（C1c）。对照 B5/B6：错误应可识别（视觉强调）、可诊断（详情/日志入口）、可恢复（重试/修复建议）。Toast 适合"已开始启动"这类过程通知，不适合终态失败。

### A5. 单屏设置项超载、分组含糊
AOSP："Showing more than 10–15 items can be overwhelming"，且应按频率排序、限制单屏项数（S12）。将全部设置堆在一个屏幕或用"General/Advanced"等模糊分组是反模式。

### A6. 空列表无指引（裸空白）
WireGuard 空隧道列表仍给出 "Add a tunnel using the button below"（C3b）；反例是仅显示空白 RecyclerView，让新用户在空屏上寻找入口。

---

## 5. Opportunities（X Client 改进/差异化机会）

结合 X Client 现状（品牌双蓝并存；布局/Java 硬编码 Material 色板；px 硬编码；缺 empty/loading 态；部分控件缺 contentDescription；无 ViewModel；Material Components 1.9.0 XML Views），按"竞品已验证 + X Client 缺失"列出机会点：

| # | 机会点 | 依据（竞品做法 → X Client 现状） |
|---|--------|-------------------------------|
| O1 | **连接状态色彩语义系统化**：定义 connecting/connected/disconnected/error 四态色板（成功/进行/中性/危险），一处定义、主页与状态栏共用，杜绝"双蓝并存 + 绿橙蓝红乱用" | CMFA 状态换色（C2a）、v2rayNG #2531 教训（C1b）、X Client 现状 |
| O2 | **主页大卡片模式 + 实时副文案**：未连接="已断开/点击连接"，连接中/已连接时显示当前 Profile 名与状态；主操作唯一化 | CMFA LargeActionCard（C2a）、Tailscale 状态行（C4a）、M3 主操作唯一（S1） |
| O3 | **Profile 行"活数据"与默认选中高亮**：名称 + 协议徽标 + 延迟（测后）+ 当前激活高亮 + 滑动操作保留 | CMFA 行字段（C2b）、v2rayNG 延迟显示（C1e） |
| O4 | **一键测延迟 + 按延迟排序 + 失效标记**：首版可不做自动删除，但"测试→数字上屏→可排序"闭环对多配置管理价值最高 | v2rayNG（C1e、C1f） |
| O5 | **分应用代理页补齐四件套**：搜索框、全选/反选、排除-包含模式说明、确认区显示"已选 N 个应用"；列表加载态 | WG（C3a）、v2rayNG（C1d）、CMFA 搜索（C2d） |
| O6 | **设置页分组化 + summary 显示当前值**：按 通用/网络/分应用/日志与诊断/关于 分组，项数控制在 AOSP 建议内 | AOSP（S12）、CMFA 子页制（C2c） |
| O7 | **empty/loading/error 三态组件化**：统一 EmptyStateView（插画+一句话+主按钮）与加载占位，先覆盖 Profile 列表、分应用列表、日志三个高频空屏 | WG 空态（C3b）、M3 加载（S7）、X Client 现状"缺三态" |
| O8 | **错误恢复路径**：连接失败时从 Toast 升级为可停留的错误条（Snackbar non-dismissive 或卡片）→"查看日志/重试/复制错误详情" | Outline 错误文案（C5a）、NN/g（S13）、v2rayNG #1818 反例（C1c） |
| O9 | **色板 token 化 + 暗色安全**：以语义 token（surface/onSurface/primary/error…）替换全部裸 hex，天然修复 values-night 一致性；沿用 DayNight + ThemeManager 时注意 activity 重建 | S4、S9、X Client 架构笔记（ThemeManager 切换需重启 Activity） |
| O10 | **contentDescription 全覆盖**：连接按钮（随状态切换文案）、扫码、滑动操作按钮等 | v2rayNG FAB 语义（C1a）、X Client 现状 |

---

## 6. Recommended Patterns for X Client（按页面建议）

> 标注：〔S*〕=官方规范来源，〔C*〕=竞品来源（编号见 1.2），〔推断〕=基于上述证据的推论，未找到直接来源。

### 6.1 主页（连接页）
- **主操作唯一**：采用"大状态卡片"或"FAB + 状态底栏"二选一，不做两套并存。大卡片更符合 CMFA/1.1.1.1 的"一眼可点"模型（C2a、C6）；FAB+底栏更接近 v2rayNG 且与现有 ProfileList 一体化成本低（C1a）。〔推断〕在 XML Views + Material 1.9 约束下，推荐**全宽状态卡片**（占位大、语义清晰、易做四态色），点击即切换连接。
- **状态机四态**：`DISCONNECTED / CONNECTING / CONNECTED / ERROR`，配色 = error 红、connecting 用主品牌蓝 + 进度指示（S7）、connected 用语义成功色（绿色系，尊重 C1b 的用户心智）、disconnected 用中性 surface。
- **副文案承载上下文**：卡片副文本显示"当前 Profile 名"或"已连接 · 已转发流量"式信息（C2a）；连接中显示"正在连接…"（C5a 进行时态文案）。
- **错误态不使用 Toast 终结**：ERROR 态显示在卡片内或下方错误条，附"重试"与"查看日志"动作（B5、B6、C5a）。
- **顶部工具栏**：保留应用名 + 扫码导入入口；图标按钮补 contentDescription（C1a）。
- **不引入底部导航栏**：X Client 页面数（主页/日志/设置/分应用）少于 5 且重要性不等，M3 要求 nav bar 用于 3–5 个同级顶级目的地，且少于 3 个目的地时应用 tabs（S3）。〔推断〕保持现有 Toolbar + 溢出菜单/侧栏即可。

### 6.2 Profile 列表
- **行结构**（对照 C2b、C1e）：主文本=Profile 名称；次文本=协议标签 + 延迟数字（未测显示占位"—"；测试后上屏）；激活项左侧高亮条或整行容器色 primary-container 化〔推断，M3 色彩角色 S4〕。
- **默认选中**：始终有一行处于"当前选中"高亮，主页连接动作作用于它（C1a、C2a 的当前 profile 概念）。
- **滑动操作**：保留现有 SwipeRevealLayout 模式（编辑/删除/置顶），但按钮色改语义 token（编辑=中性、置顶=主色、删除=error 红）〔推断，X Client 现状是四色硬编码〕。
- **添加入口**：右下 FAB（唯一），点开"扫码 / 剪贴板 / 文件 / 手动"菜单（P9，C1f）；手动添加按协议分子菜单。
- **列表操作**：溢出菜单提供"测试全部延迟/按延迟排序"（C1e）。
- **空态**：文案指向 FAB（"点击右下角按钮添加第一个配置"），复用 EmptyStateView（C3b、O7）。

### 6.3 Profile 编辑页
- **协议切换动态字段**：按协议显隐字段组（X Client 已有手工显隐逻辑，保留但收敛为"每协议一个字段组"的单一映射表）〔推断〕。
- **校验错误就地显示**：字段下方红字 + 红描边（TextInputLayout.error），而非保存时全局 Toast（S13 就近原则）。
- **保存后返回列表并刷新**，新配置出现在列表且给予位置反馈〔推断〕。

### 6.4 设置页
- **分组子页制**：顶层设置列表分组为 通用（主题/语言）/ 网络 / 分应用代理入口 / 日志与诊断 / 关于；每组在顶层屏内用 preference category 分隔，若单项超过 10–15 则拆子页（S12、C2c）。
- **每项带 summary 当前值**：如"主题 → 跟随系统"、"分应用代理 → 已排除 12 个应用"（S12、B8）。
- **主题切换三选项**：浅色/深色/跟随系统，默认跟随系统（S9）；因 `setDefaultNightMode` 会重建 Activity，切换入口尽量放在设置页根部减少状态丢失（S9 注）。
- **开关类设置用 Switch**（S8），数值类用滑杆/对话框，避免自造控件。
- **日志与诊断组**：提供"导出日志/清除日志"与"连接信息测试"入口（C1f 的测速 URL、C5a 的诊断路径）。

### 6.5 分应用代理页
- **四件套**：搜索框（常驻或顶栏展开）+ 全选/反选（溢出菜单或底部条）+ 模式说明（排除模式/包含模式，用 Switch 或分段控件明确当前语义）+ 底部确认区显示"已选 N 个应用"（C3a 计数按钮、C1d、C2d）。
- **默认语义**：建议默认"排除模式"（大部分用户只想排除银行/本地类 App）〔推断：未找到统计来源，基于 WG 默认 Exclude Tab（C3a initialExcluded=true）与 CMFA 惯例〕。
- **列表排序**：按应用名字母序，已选中的排前面（C3a 排序、C1d 选中置顶）。
- **加载态**：应用枚举期间显示居中进度条（C3a progress_bar），完成后才显示列表。
- **空/失败态**：无结果时显示"未找到匹配应用"空态；权限异常时错误文案 + 重试（C3a 的 error→toast 可升级为页面内提示）。

### 6.6 日志页
- **常驻操作**：过滤（按级别）、复制全部、分享、清空（X Client 已有 RuntimeLogActivity，UI 上补齐操作语义）〔推断，参照常见日志页与 C1f 无直接来源〕。
- **与错误路径联动**：主页 ERROR 态的"查看日志"直达本页；日志条目支持长按复制单条（C5a 的"错误详情可带走"思路）。
- **空态**："暂无日志，连接后将在此显示运行记录"。

### 6.7 空态 / 错误态 / 加载态（横切组件）
- **EmptyStateView**：图标/插画 + 一句话说明 + 主行动按钮（指向创建/导入），用于 Profile 列表、分应用搜索无结果、日志空（C3b、O7）。
- **错误呈现分级**：
  1) 过程性提示 → Snackbar dismissive（"已开始连接"）（S6、C1f）；
  2) 失败 → 页面内错误条/卡片（non-dismissive Snackbar 或卡片），含错误摘要 + "重试" + "查看日志" + "复制详情"（S6、S13、C5a）；
  3) 网络 本身离线 → 全局提示条，网络恢复后自动消失〔推断，Tailscale Health 思路 C4b〕。
- **加载指示统一**：全 App 只用一种进度视觉（圆形，MDC 1.9 的 CircularProgressIndicator），列表加载用顶部 linear 或居中圆形（S7）。
- **色彩语义 token**：success/warning/error/info 四语义色 + surface/onSurface/primary 系统角色，全部经 values/values-night 定义，Java/布局禁止裸 hex（S4、S9、A3）。

---

### 附：与 X Client 硬约束的对照速查

| 建议 | X Client 落地约束 |
|------|------------------|
| 大状态卡片、四态色 | Material Components 1.9.0（Material 2 语义）：用 MaterialCardView + 自定义 stateColor，不引 M3 组件 |
| 主题三选项默认跟随系统 | 已有 ThemeManager（AppCompatDelegate），需处理 Activity 重建（S9） |
| 空态/加载组件化 | XML Views：EmptyStateView/LoadingLayout 自定义 View 即可，无需 Compose |
| 分应用四件套 | 现有 SwipeRevealLayout 与 RecyclerView 均可承载，搜索用顶栏 SearchView |
| 无构建环境 | 本报告仅为设计依据，不涉及构建验证 |
