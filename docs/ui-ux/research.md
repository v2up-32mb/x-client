# X Client UI/UX 研究综合报告（Research Synthesis）

> 汇总自三份一手研究：`ui-inventory.md`（Project Architect，代码级盘点）、`research-ux.md`（UX Researcher，32 个来源含 6 个竞品仓库源码级证据）、`research-visual.md`（Visual Researcher，含 material-1.12.0.aar 一手解包数据）。
> 本文是决策层摘要；证据细节与完整来源请查阅上述三份原始文档。

---

## 1. 当前产品 UI/UX 问题（代码级证据）

| # | 问题 | 证据 |
|---|------|------|
| P-1 | 品牌双蓝并存：toolbar `#0BA3F3` vs FAB `#2B63A5` | `values/colors.xml` |
| P-2 | 布局/Java 大量硬编码 Material 色板：`#4CAF50`×4、`#2196F3`×4、`#FF9800`×3、`#9C27B0`×3、`#F44336`×2、`#FFFFFF`×17+ | `activity_profile_list.xml:42-43`、`item_profile_swipe.xml:94-127`、`ProfileListActivity.java:222-232` 等 |
| P-3 | `#0BA3F3` 亮蓝 + 白字对比度仅 2.78:1，不达标（角色配对底线 3:1） | research-visual §5.2 实测 |
| P-4 | px 单位硬编码：`appitem.xml` 100px 图标、50px 文字，触控目标与密度不匹配 | `appitem.xml:14,18` |
| P-5 | Profile 列表无 empty 态，首启用户面对空白屏 | `activity_profile_list.xml` |
| P-6 | 连接状态仅靠按钮变色 + Toast，无进度指示、无错误恢复路径 | `ProfileListActivity.updateStartButton` |
| P-7 | VPN 运行中限制操作仅 Toast 提示，无视觉 disabled | `ProfileEditActivity`、`item_profile_swipe` |
| P-8 | 字号/字重不统一：列表主标题 16sp bold vs 14sp bold | `item_profile.xml` vs `item_profile_swipe.xml` |
| P-9 | 部分控件缺 contentDescription（FAB 等）；RadioButton 无语义标签 | inventory 各节 |
| P-10 | Manifest 声明不存在的 `.MainActivity`，存在崩溃路径 | `AndroidManifest.xml` |
| P-11 | 设置页单屏堆叠全部项，无分组、无 summary 当前值 | `activity_settings.xml` |
| P-12 | 状态栏颜色硬编码 `#FFEFEFEF`/`#FF121212`，M2 时代做法 | `values[-night]/styles.xml` |

## 2. 用户流程问题

来自 `user-flows.md`（8 条旅程全量分析）：

1. **首启**：空列表无引导，新增后直接进入无说明的编辑页
2. **导入**：扫码失败无重试提示；手动输入无格式校验反馈
3. **连接主流程**：启动超时 60s 期间无进度反馈；错误只走 Toast；提示文案中文硬编码
4. **编辑**：字段校验错误信息分散、非就近显示；VPN 运行中禁用无视觉区分
5. **分应用**：无 empty 态；px 硬编码；无"已选 N 个"确认上下文
6. **设置**：改动生效条件（需重启 VPN）无提示；开关实时生效无反馈
7. **日志**：仅服务存活时可获取，长日志卡顿无分页

## 3. 信息架构问题

- 主页 = 列表 + 独立启动按钮 + FAB 三块并存，**主操作不唯一**（违背 M3 "一个屏幕一个最重要操作"，m3.material.io/components/floating-action-button/guidelines）
- 设置无分组层级，11+ 项堆叠单屏（违背 AOSP 10–15 项规范，source.android.com/docs/core/settings/settings-guidelines）
- 分应用代理页缺少模式语义说明（排除 vs 包含）
- 错误路径信息架构缺失：无诊断入口、无错误详情携带能力

## 4. 竞品研究（摘要）

6 个真实竞品的**源码级**调研（v2rayNG、Clash Meta for Android、WireGuard Android、Tailscale、Cloudflare 1.1.1.1、Outline）：

| 模式 | 竞品做法 | 对 X Client 的意义 |
|------|---------|------------------|
| 主页大状态卡片 | CMFA `LargeActionCard`：全宽卡片，未连接="Stopped/Tap to start"，运行中换色+流量文案（design_main.xml） | 推荐：全宽状态卡片替代"按钮+FAB"双操作 |
| 状态双通道 | 颜色+动词化文案（Outline 五态进行时/完成时）；v2rayNG 把连接按钮绿改橙引发用户抗议（issue #2531） | **绿=已连接的用户心智不可动摇** |
| Profile 行活数据 | CMFA 行含 name/type/active/流量/到期；v2rayNG 延迟测试→数字上屏→按延迟排序 | 行结构：名称+协议徽标+状态 |
| 分应用四件套 | WireGuard Exclude/Include 双 Tab+计数确认+全选；v2rayNG 搜索+反选+剪贴板导入导出 | X Client 分应用页需补齐 |
| 设置分组子页 | CMFA 拆 4 子页；AOSP：标题+当前值 summary 成对显示 | 设置页分组化 |
| 空态指引 | WireGuard 空列表文案"Add a tunnel using the button below"直指 FAB | 空态必须指向下一步动作 |
| 错误恢复 | Outline 失败文案含"自查网络+截图错误详情求助"；v2rayNG 仅 Toast 失败被用户诟病（issue #1818） | 错误条+重试+查看日志 |

## 5. Android / Material 设计规范（关键结论）

- **M3 颜色角色体系可在 XML View 完整落地**（无需 Compose）：material 1.9.0 已含全部 M3 角色 attr；**建议升 1.12.0**（compileSdk 34/minSdk 24 完全兼容，含 a11y 修复；1.13+ 需 compileSdk 35 不跟进）
- 主题映射一一对应：`Theme.MaterialComponents.DayNight.NoActionBar` → `Theme.Material3.DayNight.NoActionBar`
- **Typography**：M3 五档 15 样式（1.12.0 AAR 实测值）；本项目收敛为 4 档 2 字重：TitleLarge(22/28)/TitleMedium(16/24,Medium)/BodyMedium(14/20)/LabelLarge(14/20,Medium)
- **Shape**（1.12.0 实现值）：ExtraSmall 4dp / Small 8dp / Medium 12dp / Large 16dp / ExtraLarge 28dp / Full 50%
- **Spacing**：4dp 最小步进（8pt 网格）；触控目标 Android 平台为 **48dp**
- **Dark theme**：M3 tonal surface（dark baseline surface `#141218`）取代 elevation overlay 与 `#FF121212`；硬编码颜色是官方暗色主题首要禁忌
- **Dynamic Color**：API 31+ 可用、minSdk 24 自动回退；但与"状态色必须稳定"的产品诉求冲突，**默认不开启**
- **语义色可不随主题/动态色漂移**（m3.material.io/styles/color/system 原文）→ 状态四态色跨主题固定的依据

## 6. Common Patterns（跨竞品共性）

P1 主页单一主操作大按钮/大卡片 · P2 状态=颜色+动词文案双通道 · P3 主页绑定当前配置上下文 · P4 延迟测试+排序 · P5 Profile 行信息密度（名称+类型+活数据） · P6 分应用=搜索+全选/反选+模式+计数 · P7 设置按域拆分分组 · P8 空态=下一步指引 · P9 导入多通道入口 · P10 瞬时提示(Snackbar)与常驻状态分层

## 7. Recommended Patterns（本项目采纳）

| 采纳项 | 依据 |
|--------|------|
| 全宽状态卡片主页（四态：断开/连接中/已连接/错误） | CMFA C2a + M3 主操作唯一 S1 |
| 状态色语义表：绿=已连接、品牌蓝+动效=连接中、中性=断开、红=错误、橙仅警告 | C1b 用户心智 + 本地数据集状态色惯例 |
| Profile 行：名称+协议徽标+服务器地址（可开关显示）+当前项高亮 | CMFA C2b + 现有 show_server_addr 功能 |
| 滑动操作按钮改语义 token（编辑=中性/置顶=主色/删除=error） | P2 现状四色硬编码 |
| FAB 唯一化 + 导入多通道菜单（扫码/手动） | M3 S1 + v2rayNG C1f |
| 分应用四件套 + 48dp 行高 + "已选 N 个应用"确认区 | WG C3a + v2rayNG C1d |
| 设置分组 + summary 当前值 + 主题三选项默认跟随系统 | AOSP S12 + S9 |
| 编辑页 TextInputLayout 就近校验 | NN/g S13 |
| 空态/错误/加载组件化（EmptyStateView 等） | WG C3b + M3 S7 |
| 错误恢复路径：错误条+重试+查看日志+复制详情 | Outline C5a + v2rayNG #1818 反例 |
| 顶栏回归中性 surface（品牌蓝不再满铺） | M3 surface 角色 + 对比度实测 |
| 不引入底部导航栏（目的地<3 个） | M3 S3（3–5 个同级目的地才用 nav bar） |

**暂缓项（记录不实施）**：延迟测试/排序（需业务层新增能力，涉及 golib 接口，超出本次 UI 重构边界；作为后续机会点记录于 redesign-plan Open Questions）；订阅管理（产品无此功能）。

## 8. Anti-patterns（本项目须规避）

A1 单屏多竞争主操作 · A2 违背已建立的颜色语义（绿=连接） · A3 硬编码颜色 · A4 失败只弹 Toast 无后续路径 · A5 单屏设置超载/分组含糊 · A6 裸空白无指引空态 · A7 px 单位与 <48dp 触控目标

## 9. Opportunity（差异化/改进机会）

O1 状态色系统化 · O2 主页大卡片+实时副文案（当前 Profile 名） · O3 Profile 行活数据 · O4 测延迟（暂缓，见上） · O5 分应用四件套 · O6 设置分组化 · O7 三态组件化 · O8 错误恢复路径 · O9 全量 token 化+暗色安全 · O10 contentDescription 全覆盖

## 10. 最终 UI/UX 原则（本次重构最高准则）

1. **信任优先**：VPN 是安全工具，视觉克制、状态明确、对比度达标高于装饰性
2. **状态即语言**：连接状态 = 颜色 + 图标 + 动词化文案三通道，四态色板全 App 唯一
3. **主操作唯一**：每屏一个最重要的动作；主页 = 连接
4. **一切用色经角色**：组件层禁止裸 hex/px；light/dark 成对定义
5. **空态是指引，错误是路径**：每个列表有空态文案指向动作；每个失败有重试/详情出口
6. **48dp 与标签**：触控目标 ≥48dp；交互图标必须有 contentDescription
7. **不为重构而重构**：符合规范的实现保留（如 RuntimeLogActivity 的三态已达标）
8. **业务逻辑 ≈ 稳定**：UI 层大改，广播协议/Go 接口/存储结构不动

---

### 关键决策记录（事实 / 观点 / 采用）

| 议题 | 事实 | 不同观点 | 采用 | 理由 |
|------|------|---------|------|------|
| material 版本 | 1.9.0 已含 M3 attr；1.12.0 兼容且含 a11y 修复；1.13+ 需 compileSdk 35 | — | 升 1.12.0 | 零破坏 + 修复 |
| M2→M3 路径 | 整主题切换 vs 渐进补 attr 均官方支持 | 渐进期双体系并存易再现"双蓝" | 整主题切换 | 单次到位，消除不一致根源 |
| 品牌色 | `#0BA3F3`+白字 2.78:1 不达标；`#2B63A5`+白字 6.12:1 达标 | 方案 A 保品牌蓝派生 vs 方案 B 深蓝重定 primary | **方案 A**：`#0BA3F3` 作种子，light primary 用深化 tone `#0873AC`，dark 用亮 tone `#7FD0FA` | 品牌保留 + 对比度可解；吸收 B 的"顶栏回归中性" |
| 主页形态 | CMFA 大卡片 vs v2rayNG FAB+底栏 | 两种均为主流 | 全宽状态卡片 | 与现有列表页一体化成本可控、四态语义最清晰 |
| Dynamic Color | API31+ 可用、低版本自动回退 | 开启会漂移品牌/状态色 | 默认关闭 | 状态色稳定性优先 |
| 底部导航 | M3 要求 3–5 个同级目的地 | — | 不引入 | 目的地不足且重要性不等 |
