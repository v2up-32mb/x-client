# 视觉系统与 Material 规范调研报告（Visual / UI Research）

> 任务：为 X Client（Android 多协议 VPN 客户端，XML View 体系）的 Design System 建设提供视觉方向调研依据与初始 token 建议。
> 阶段：Web Research / Visual。本文档仅记录调研结论，不改动任何代码。
> 所有官方结论均附可访问 URL；本地数据集结果注明「来源：本地 ui-ux-pro-max 数据集」；无法核实的来源一律不引用。标注「提案」的数值仅供 Design System 阶段定稿参考，非官方值。

---

## 0. 本项目视觉现状速览（作为调研输入）

来源：`app/src/main/res/`、`app/build.gradle`、布局/Java 代码扫描（2026-09 现状）。

| 项 | 现状 |
| --- | --- |
| Material 库 | `com.google.android.material:material:1.9.0`；appcompat 1.6.1、recyclerview 1.3.2 |
| 主题 | `Theme.MaterialComponents.DayNight.NoActionBar`（M2 语义），`values-night/styles.xml` 覆盖状态栏颜色 |
| 品牌色 | 双蓝并存：toolbar `#0BA3F3`、FAB `#2B63A5`（`values/colors.xml`，无 night 覆盖） |
| 硬编码 Material 色板 | 布局/Java 中 `#4CAF50`(×4)、`#2196F3`(×4)、`#FF9800`(×3)、`#F44336`(×2)、`#9C27B0`(×3)、`#009688`(×1)、`#FFFFFF`(×17+) 等，未走主题属性 |
| 单位问题 | 布局存在 px 硬编码（5px/20px/50px/100px）；状态栏色 `#FFEFEFEF`（light）/`#FF121212`（night）硬编码在 styles.xml |
| 语义色 | 连接状态色散落在代码（绿/橙/红硬编码），无统一语义 token |

---

## 1. 来源清单（均已于本次调研实际抓取验证）

### 官方规范（Material Design 3 / m3.material.io）
> 注：m3.material.io 为 JS 渲染站点，本次经文本渲染代理（r.jina.ai）抓取并逐条核对正文，以下 URL 即原始页面地址。

1. Color roles（颜色角色定义与配对规则）：https://m3.material.io/styles/color/roles
2. Color system / dynamic color（动态色与语义色，含「语义色可不随动态色变化」结论）：https://m3.material.io/styles/color/system
3. Typography — type scale tokens（五档 15 样式、Major Second 比例、emphasized 样式）：https://m3.material.io/styles/typography/type-scale-tokens
4. Shape — shape scale tokens（圆角档位；2025-05 M3 Expressive 更新记录）：https://m3.material.io/styles/shape/shape-scale-tokens
5. Elevation（z 轴高度与色彩表达）：https://m3.material.io/styles/elevation

### 官方实现文档（MDC-Android @ tag 1.12.0）
6. Getting started（M2→M3 主题映射表、渐进迁移清单）：https://github.com/material-components/material-components-android/blob/1.12.0/docs/getting-started.md
7. Color theming（M3 颜色角色 ↔ Android attr 完整对照表、dynamic colors 用法）：https://github.com/material-components/material-components-android/blob/1.12.0/docs/theming/Color.md
8. Typography theming（15 个 `textAppearance*` attr 及默认字号/字重）：https://github.com/material-components/material-components-android/blob/1.12.0/docs/theming/Typography.md
9. Shape theming（`ShapeAppearance.Material3.Corner.*` 档位与 dp 值）：https://github.com/material-components/material-components-android/blob/1.12.0/docs/theming/Shape.md
10. Dark theme（elevation overlay 与 tonal surface 的关系、相关 attr）：https://github.com/material-components/material-components-android/blob/1.12.0/docs/theming/Dark.md

### 版本与发布记录（GitHub Releases，正文已核对）
11. MDC-Android 1.5.0 release（**Material 3 主题/组件首次引入**，2022-01-13）：https://github.com/material-components/material-components-android/releases/tag/1.5.0
12. MDC-Android 1.9.0 release（**新增 tonal surface 色 attr 与 fixed accent 色 attr**，2023-05-04）：https://github.com/material-components/material-components-android/releases/tag/1.9.0
13. MDC-Android 1.10.0 release（**compileSdkVersion 提升至 34**，2023-10-05）：https://github.com/material-components/material-components-android/releases/tag/1.10.0
14. MDC-Android 1.12.0 release（当前最新 stable，minSdk 19+，Slider/ProgressIndicator 无文本对比度 a11y 修复，2024-05-02）：https://github.com/material-components/material-components-android/releases/tag/1.12.0

### 一手数据（本次直接从官方制品提取）
15. **material-1.12.0.aar**（https://dl.google.com/android/maven2/com/google/android/material/material/1.12.0/material-1.12.0.aar ，本次下载并解包核对）：M3 baseline 全部颜色角色的精确 hex（`m3_sys_color_light_*` / `m3_sys_color_dark_*` → `m3_ref_palette_*`）、`TextAppearance.Material3.*` 实际定义。本报告第 6 节官方值均出自此制品。
16. AndroidX Compose M3 `TypeScaleTokens.kt`（与 XML 同源 token，用于补行高值）：https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/TypeScaleTokens.kt

### Android 开发者官网
17. Dark theme 应用指南（DayNight、系统开关、`colorSurface`/`colorOnSurface` 建议）：https://developer.android.com/develop/ui/views/theming/darktheme

### 本地数据集（ui-ux-pro-max skill，`python3 .agents/skills/ui-ux-pro-max/scripts/search.py`）
18. `--design-system -p "X Client"`（检索词 "vpn network privacy utility tool"）→ 推荐 Pattern「Trust & Authority」、Style「Minimalism & Swiss Style」、色板「Shield dark + connected green」（Primary `#1E3A5F` + Accent `#22C55E`）、字体 Inter。
19. `--domain color`（检索词 "connection status color semantics success warning error"）→ 状态页/监控类产品的状态色惯例：绿色系表成功（`#16A34A`/`#22C55E`）、红色系表故障/危险（`#DC2626`/`#EF4444`）。
20. `--domain ux`（检索词 "touch target thumb zone accessibility status indicator feedback"）→ Android 触控目标 48dp（High severity）、提交类操作必须 Loading→Success/Error 反馈。
21. `--domain typography`（检索词 "developer tool utility dashboard clean technical"）→ 技术工具类字体方向：Inter / Sans+Mono 组合。
22. MDC-Android getting-started（master 分支，用于确认 1.13/Expressive 需要 compileSdk 35+）：https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md

---

## 2. M3 颜色体系与 XML 落地（含 material 库版本兼容性结论）

### 2.1 M3 颜色角色模型（官方）

M3 把界面用色定义为「角色（roles）」而非分散的色值，核心分组如下（来源 [1][7]）：

| 分组 | 角色 | 用途（官方定义摘要） |
| --- | --- | --- |
| Primary | `primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer` | 最高强调：FAB、高强调按钮、激活态；container 用于 FAB 等关键组件的底色 |
| Secondary | `secondary` / `onSecondary` / `secondaryContainer` / `onSecondaryContainer` | 次强调：低调填充、tonal button |
| Tertiary | `tertiary` / `onTertiary` / `tertiaryContainer` / `onTertiaryContainer` | 平衡 primary/secondary 的对比强调（如输入框高亮） |
| Error | `error` / `onError` / `errorContainer` / `onErrorContainer` | 错误态（如密码错误），语义色 |
| Surface | `surface` / `onSurface` / `onSurfaceVariant` + 5 档 `surfaceContainer`（lowest/low/container/high/highest） | 中性背景/卡面；官方默认映射：导航区→`surfaceContainer`，FAB/对话框→`surfaceContainerHigh` |
| Outline | `outline` / `outlineVariant` | 描边与分隔；`outlineVariant` 是低强调版 |
| Inverse | `inverseSurface` / `inverseOnSurface` / `inversePrimary` | 反色区（Snackbar 等） |
| Fixed（1.9.0+） | `colorPrimaryFixed` 等 | 浅/深色模式下保持不变的强调色 |

关键规则（来源 [1]）：
- 「On」角色专用于其配对色之上的文字/图标；「Variant」是低强调替代。
- 官方通过算法配对保证 **角色配对间至少 3:1 对比度**；错误地混用角色（如 `primary` 底配 `onSurface` 文字）会破坏可读性。
- **语义色（如成功/错误）可被设为不随动态色变化**（来源 [2] 原文："Specific colors, such as semantic colors, can be set to not dynamically change"）——这是 VPN 状态色设计的关键依据。

### 2.2 XML（View 体系）落地方式

所有 M3 组件样式（`Widget.Material3.*`）引用 `Theme.Material3` 下的颜色主题属性，直接在主题中覆写 attr 即可全局生效（来源 [7]）。attr 与角色的对照（完整表见 [7]，此处列本项目将用到的核心项）：

| 角色 | Android attr（XML 直接引用） |
| --- | --- |
| Primary 系 | `colorPrimary` / `colorOnPrimary` / `colorPrimaryContainer` / `colorOnPrimaryContainer` / `colorPrimaryInverse` / `colorPrimaryFixed` |
| Secondary 系 | `colorSecondary` / `colorOnSecondary` / `colorSecondaryContainer` / `colorOnSecondaryContainer` |
| Tertiary 系 | `colorTertiary` / `colorOnTertiary` / `colorTertiaryContainer` / `colorOnTertiaryContainer` |
| Error 系 | `colorError` / `colorOnError` / `colorErrorContainer` / `colorOnErrorContainer` |
| Surface 系 | `colorSurface` / `colorOnSurface` / `colorOnSurfaceVariant` / `colorSurfaceContainer(-Low/-High/-Highest/-Lowest)` / `colorSurfaceInverse` / `colorOnSurfaceInverse` / `colorOutline` / `colorOutlineVariant` |
| 背景 | `android:colorBackground` / `colorOnBackground` |

**结论：XML View 体系可完整落地 M3 角色体系，无需 Compose。** 组件层直接引用 `?attr/colorPrimary` 等即可实现「组件禁止裸 hex」的本地规则。

### 2.3 material 库版本兼容性结论（对 1.9.0 与 1.12.0）

| 版本 | 与本项目相关的事实 | 来源 |
| --- | --- | --- |
| 1.5.0（2022-01） | **M3 主题/组件/动态色首次发布**（`Theme.Material3.*`、`DynamicColors`） | [11] |
| 1.9.0（2023-05）= **当前使用** | 已包含全套 `Theme.Material3.*`；本次发布**新增** tonal surface 色 attr（`colorSurfaceContainer*` 等）与 fixed accent 色 attr（`colorPrimaryFixed` 等）。即：**1.9.0 已支持 M3 全部颜色角色 attr，可直接以 M2 主题 + M3 attr 渐进接入** | [12] |
| 1.10.0（2023-10） | 库 compileSdkVersion 提升到 34 → **消费方需 compileSdk 34** | [13] |
| 1.12.0（2024-05）= 建议目标 | 最新 stable；minSdk 降至 19（本项目 24 ✓）；需 compileSdk 34（本项目 34 ✓）；包含 Slider/ProgressIndicator 无文本对比度 a11y 修复 | [14] |

**结论：**
1. **1.9.0 即可开始 M3 化**（M3 主题、颜色角色、动态色均可用），无需先升库。
2. **建议在 Design System 阶段升级到 1.12.0**：与本项目 compileSdk 34 / minSdk 24 完全兼容，获得 a11y 修复；1.13+（Expressive）需要 compileSdk 35（[22] 主分支文档），超出本项目 targetSdk 34，不跟进。
3. 升级 1.9.0→1.12.0 对颜色/字体 attr 无破坏性变更（1.9.0 已含全部所需 attr；1.12.0 未移除 M2 attr）。

### 2.4 M2→M3 迁移要点（XML）

**路径 A（推荐）：整主题切换。** 官方给出主题父子关系映射（来源 [6]）：

| M3 | 对应 M2 |
| --- | --- |
| `Theme.Material3.DayNight.NoActionBar` | `Theme.MaterialComponents.DayNight.NoActionBar`（本项目现用父主题，一一对应） |
| `Theme.Material3.DayNight` | `Theme.MaterialComponents.DayNight` |
| `Theme.Material3.DynamicColors.DayNight` | M2 无对应（M3 专属） |

注意：M3 无 `Theme.MaterialComponents.DayNight.DarkActionBar` 对应物（M3 的顶栏用组件级 `MaterialToolbar` + surface 角色上色，而非 DarkActionBar 主题变体）——对本项目无影响（已是 NoActionBar + 独立 toolbar 背景）。

**路径 B（渐进）：保留 M2 主题、手动补 M3 attrs。** 官方明确支持：在 `Theme.MaterialComponents` 下逐项添加 M3 attr（`colorPrimaryContainer`、`colorSurfaceContainer`、`textAppearanceTitleMedium`、`shapeAppearanceCornerMedium` 等完整清单见 [6]），否则遇到 `ThemeEnforcement` 报错。适合灰度改造，但双体系并存期容易出现「两套蓝色」类不一致——与本次要修的双蓝问题同源。

**attr 级主要差异（来源 [6][7][8][9]）：**
- 颜色：M2 的 `colorPrimaryVariant` / `colorSecondaryVariant` 在 M3 中无对应，被 **Container 角色**（`colorPrimaryContainer` 等）取代；M3 新增 tertiary、surface container、fixed、inverse 等角色。
- 字体：M2 `textAppearanceHeadline1..6 / Subtitle1..2 / Body1..2 / Caption / Button / Overline`（共 12+）→ M3 `Display/Headline/Title/Body/Label` 大/中/小 15 样式（见 §3.1）。
- 形状：M2 `shapeAppearanceSmall/Medium/LargeComponent` → M3 `shapeAppearanceCorner*`（ExtraSmall..ExtraLarge，见 §3.2）。
- 组件默认样式名空间：`Widget.MaterialComponents.*` → `Widget.Material3.*`（M3 主题下 `<Button>` 等会自动膨胀为 Material 组件，见 [6]）。

### 2.5 Dynamic Color（Material You）在 minSdk 24 项目的可选支持策略

- 机制（来源 [7]）：`DynamicColors.applyToActivitiesIfAvailable(app)` —— 仅当运行在 **Android 12（API 31）+** 且用户壁纸提供动态色时生效；低版本**自动回退**到主题自带 baseline 配色，**不影响 minSdk 24**。
- 也可用主题覆盖层 `ThemeOverlay.Material3.DynamicColors.DayNight`（来源 [7]）。
- 细粒度控制：`DynamicColorsOptions` + `OnPreApplyColorListener` 可按 Activity 决定是否启用（来源 [7]）。
- 配套 API：`MaterialColors.harmonizeWithPrimary()` 可把自定义语义色向 primary 调和（来源 [7]）。

**对本项目的策略建议（提案）：**
- Dynamic Color 与「固定品牌蓝」在产品诉求上互斥：VPN 工具的状态色语义（绿=已连接等）必须稳定，且品牌信任感依赖一致的蓝色。因此**建议默认不开启** Dynamic Color；如后续要支持，做成「设置项 + 仅作用于中性 surface 色、状态色用不随动态变化的固定值」的受限模式（依据 [2] 语义色可不随动态色变化的规则）。
- 若不开启，升级 1.12.0 的意义不受影响（a11y 修复与 token 完整度仍受益）。

---

## 3. M3 Typography / Shape / Spacing 与本项目映射

### 3.1 Typography：M3 五档 15 样式（官方值，来源 [8][15][16]）

下表为 **material 1.12.0 AAR 内 `TextAppearance.Material3.*` 实际字号/字重** + AndroidX TypeScaleTokens 行高（XML 样式不显式设行高，行高取自同源 token）：

| 样式（attr 后缀） | 字号 | 行高 | 字重 | 典型用途（官方定义摘要） |
| --- | --- | --- | --- | --- |
| DisplayLarge | 57sp | 64 | Regular | 超大展示（本项目基本不用） |
| DisplayMedium | 45sp | 52 | Regular | — |
| DisplaySmall | 36sp | 44 | Regular | — |
| HeadlineLarge | 32sp | 40 | Regular | — |
| HeadlineMedium | 28sp | 36 | Regular | — |
| HeadlineSmall | 24sp | 32 | Regular | — |
| TitleLarge | 22sp | 28 | Regular | 页面大标题 / 大按钮文字 |
| TitleMedium | 16sp | 24 | **Medium** | 列表主标题、卡片标题 |
| TitleSmall | 14sp | 20 | **Medium** | 小标题、次级标题 |
| BodyLarge | 16sp | 24 | Regular | 长文正文 |
| BodyMedium | 14sp | 20 | Regular | 常规正文、次级信息 |
| BodySmall | 12sp | 16 | Regular | 辅助说明 |
| LabelLarge | 14sp | 20 | **Medium** | 按钮、状态标签 |
| LabelMedium | 12sp | 16 | **Medium** | 小标签、徽标 |
| LabelSmall | 11sp | 16 | **Medium** | 最小标注 |

规格说明（来源 [3]）：整个字阶以 **Major Second（1.125）比例、14 为基准字号** 构建；M3 另有 emphasized 样式（同尺寸不同字重/字形），主要用于编辑性强调，本项目第一阶段可不用。

**本项目映射建议（满足本地 SKILL「≤4 字号 / 2 字重」约束，提案）：**
- 选用 4 档：`TitleLarge`（22/28, Regular）、`TitleMedium`（16/24, Medium）、`BodyMedium`（14/20, Regular）、`LabelLarge`（14/20, Medium）。
- 2 字重 = Regular 400 + Medium 500，恰与 M3 baseline 一致，**无需引入额外字重文件**（Roboto/sans-serif 系统字体即含 400/500）。
- 所有 `android:textSize`/`textAppearance` 硬编码改为引用上述主题 attr；日志页等距文本可保留系统 monospace（属功能文本，不占 4 档名额）。
- 本地数据集（来源 [21]）也支持「技术工具类用单一无衬线 + 高字重对比」的方向；Inter 为 Web 侧字体，Android 侧直接用系统 Roboto（即 M3 默认 plain/brand 字体）零成本对齐，不建议打包自定义字体。

### 3.2 Shape：圆角档位（官方实现值，来源 [9]）

material **1.12.0**（XML）实现的 `ShapeAppearance.Material3.Corner.*`：

| 档位 | 圆角 |
| --- | --- |
| None | 0dp |
| ExtraSmall | 4dp |
| Small | 8dp |
| Medium | 12dp |
| Large | 16dp |
| ExtraLarge | 28dp |
| Full | 50%（组件短边一半） |

注意（来源 [4]）：M3 Expressive（2025-05）规格将 large 提到 20dp、extra large 提到 32dp、新增 48dp——但 **1.12.0 XML 实现仍是 16/28**，本项目按 1.12.0 实现值执行即可（升级 Expressive 需 compileSdk 35+，见 [22]，超出范围）。

**本项目映射建议（提案）：** 卡片/对话框 `Large`(16dp)；按钮/FAB/输入框 `Medium`(12dp)～`Full`；二维码扫描遮罩、日志块 `Small`(8dp)；chips `Small`(8dp)。全部通过 `shapeAppearanceCorner*` attr 引用，禁止布局内裸 `cornerRadius`。

### 3.3 Spacing：8pt 网格与本项目现状

- 官方组件的 padding/margin 均以 4dp 为最小步进（4/8/12/16/24/32…），这是 8pt 网格在 Android dp 体系下的落地方式（Material 组件默认值均为 4 的倍数；可对照 [15] AAR 中组件 dimens）。
- 本地 SKILL 已规定 8pt 网格；现状问题：布局存在 px 硬编码（5px/20px/50px/100px），必须全部换算为 dp 并对齐网格（px→dp 按 3x 屏估算时 5px≈2dp、20px≈7dp 等，建议直接就近对齐 4/8）。
- 触控目标：本地数据集（来源 [20]）与 Android 官方一致——**Android 为 48dp**（高于本地 SKILL 的 ≥44dp 底线，按平台取 48dp）。
- 拇指区：主连接操作保持在屏幕下半部（本地 SKILL 规则 + [20] thumb zone 条目），现状 FAB/主按钮布局在 Design System 阶段按此复核。

---

## 4. Dark theme 规范要点

来源：[10]（MDC Dark.md）、[17]（developer.android.com）、[1]（surface 角色）、[15]（night baseline 值）。

1. **主题结构**：以 `DayNight` 主题 + `values-night` 资源限定符承接系统夜间模式（[17]）；本项目已有此结构，方向正确。
2. **背景用深灰不用纯黑**：M3 baseline 暗色 `surface` = `#141218`、`background` = `#141218`（来源 [15] AAR 实测值）；官方理由：深灰比纯黑更能呈现阴影层次、减少浅色文字的视觉疲劳（[10][17]）。**现状 night 状态栏 `#FF121212` 属 M2 时代惯用值，建议随 M3 化改为 `?attr/colorSurface`（#141218）。**
3. **M2 机制——elevation overlay**：半透明 `colorPrimary` 叠加在 surface 上，按海拔提升亮度；attr 为 `elevationOverlayEnabled` / `elevationOverlayColor`（默认 `colorPrimary`），适用组件清单见 [10]。
4. **M3 机制——tonal surface**：**M3 组件已用 tonal surface 色系统取代 elevation overlay**（[10] 原文注明）；层级改由 5 档 `surfaceContainer*` 表达（dark baseline：lowest `#0F0D13` → low `#1D1B20` → container `#211F26` → high `#2B2930` → highest `#36343B`，来源 [15]），另有 `surfaceTint` 默认指向 `colorPrimary`。**自定义悬浮层级界面（底部弹层、悬浮状态卡）建议直接用 `colorSurfaceContainerHigh` 而不是叠加透明色。**
5. **品牌色暗色适配**：dark 模式下 `primary` 用亮 tone（baseline 为 tone80，`#D0BCFF` 一类的浅亮色），`onPrimary` 用深 tone（tone20）——即「浅色模式深填充/暗色模式浅填充」的翻转（[1][7]）。这正是双蓝在暗色下需要重新算 tone 的原因（见 §5）。
6. **文字/图标**：优先 `?attr/colorOnSurface` / `colorOnSurfaceVariant`；系统级兜底为 `?android:attr/textColorPrimary` 与 `?attr/colorControlNormal`（[17]）。现状 `secondary_text` 的 light/dark 双值（`#6B7280`/`#B8BDC7`）应被 `onSurfaceVariant` 角色吸收。
7. **状态栏/导航栏**：跟随 surface 角色并配 `windowLightStatusBar` 切换（[17] 原则是避免硬编码浅色主题专用色）。

---

## 5. 视觉方向：VPN/隐私工具的视觉惯例 + primary 色去留（方案 A/B）

### 5.1 VPN/隐私工具类 App 的视觉惯例（依据汇总）

> 以下结论中，①来自本地数据集（[18][19]），②来自 M3 官方语义（[1][2]），③为基于上述依据的产品推理（已标注）。

1. **信任优先于花哨**：本地数据集对 "vpn network privacy utility tool" 的首选 Pattern 即 **Trust & Authority**（信任与权威），Style 推荐 **Minimalism & Swiss**（克制、高对比、留白），并给出「Shield dark + connected green」配色方向（[18]）。
2. **状态色语义是核心视觉语言**：状态页/监控类产品的通行语义为 绿=正常/已连接（`#16A34A`/`#22C55E`）、红=故障/错误（`#DC2626`/`#EF4444`）、中性灰=停用（[19]）。M3 侧对应规则：错误态有官方 `error` 角色组；语义色必须**不随主题/动态色漂移**（[2]）。③ 推论：连接态四态建议固定为——已连接=绿、连接中=品牌蓝（进行中动效）、已断开=中性（onSurfaceVariant 层级）、错误=红；橙色仅作警告（流量/电量类提醒），不作连接态。
3. **图标语义一致性**：③ 盾牌/钥匙/锁（隐私）、电源/开关（连接）、信号/节点（线路）应各自成族并统一描边风格（本地 SKILL「组件禁止裸 hex」同样适用于图标 tint——一律 `?attr/colorOnSurfaceVariant` / `colorPrimary` 引用）。
4. **触控与反馈**：主操作 48dp 触控 + 提交类操作 Loading→Success/Error 状态反馈为高优先级准则（[20]），对应「一键连接」按钮的按压/连接中/已连接三态设计。

### 5.2 方案 A：保留品牌蓝 `#0BA3F3` 作为 primary（统一掉 FAB 深蓝）

**做法：** 以 `#0BA3F3` 为种子色，按 M3 角色体系派生浅/暗两套方案（seed → primary/primaryContainer/surface 等），`fab_background`、Java/Layout 中的 `#2196F3` 等全部并入同一角色。

**理由（依据）：**
- M3 官方迁移路径即「以品牌色生成整套 scheme」（[6] 推荐 Material Theme Builder 基于 brand colors 生成 M3 主题）；从现有品牌色出发是成本最低、不损失品牌认知的合规路径。
- 浅蓝调（高明度低饱和偏青蓝）在工具类界面中传达「轻快、在线」；M3 算法保证派生角色间的对比度达标（[1] 角色配对 ≥3:1）。
**对比度校验（本次按 WCAG 相对亮度公式实测计算）：**

| 组合 | 对比度 | 结论 |
| --- | --- | --- |
| 现状 `#0BA3F3` + 白字 | **2.78:1** | **不达标**（低于角色配对底线 3:1 [1] 与正文 4.5:1）→ 亮蓝不能直接作 primary 填充 |
| 现状 `#2B63A5` + 白字 | 6.12:1 | 达标（深蓝作为填充反而合格，但品牌上双蓝并存问题仍在） |
| 提案 light primary `#0873AC` + 白字 | 5.17:1 | 达标 |
| 提案 dark primary `#7FD0FA` + `#002F44` | 8.26:1 | 达标 |
| 提案 success/warning 配对（light/dark 共 4 组） | 5.43:1 ～ 10.15:1 | 达标 |

这条实测数据是「方案 A 必须做 tone 深化、顶栏回归中性色」的直接量化依据：问题不在品牌色相，而在「亮蓝+白字」这个组合不可用。

**风险与对策：** 浅色模式 primary 取品牌蓝的**深化 tone（提案 `#0873AC`，≈tone40）**；`#0BA3F3` 本色降级为「连接中/品牌点缀」用途（容器色/进度色）；暗色模式取其亮 tone（提案 `#7FD0FA`，≈tone80）。具体 tone 值由 Design System 阶段用 Material Theme Builder 生成定稿（本报告 §6 先给提案值）。

### 5.3 方案 B：以深蓝 `#2B63A5`（FAB 蓝，或再加深）重定 primary

**做法：** primary 统一为深蓝（`#2B63A5` 或其深化 tone），toolbar 不再使用亮蓝底，改为 surface + onSurface 的中性顶栏（M3 默认做法，[1] surface 容器规则）。

**理由（依据）：**
- 深蓝与「安全/专业/可信」的行业联想一致；本地数据集中 Trust & Authority 方向的推荐 primary 即深蓝 `#1E3A5F`，多个工程/工具类色板亦以深蓝为主色（[18]）。
- 深色 primary 在浅色 surface 上白字对比度天然充裕（M3 baseline primary40 tone 的设计意图，[1][15]），暗色模式翻转到亮 tone 后同样成立。
- 中性顶栏更贴近 M3 默认形态（顶栏用 `surface` 角色，非品牌色块），长期与 M3 组件生态一致。
- 风险与对策：品牌识别从「亮蓝」转向「深蓝」，且亮蓝 `#0BA3F3` 若保留需明确其新角色（如连接中动效色），否则又出现第三种蓝。

### 5.4 推荐及说明

**推荐方案 A（保留 `#0BA3F3` 为种子，primary 按角色取 tone）**，理由按权重排序：
1. **可访问性可解**：A 的对比度问题可通过 tone 调整一次性解决（M3 生成式方案的核心能力，[1][6]），而 B 的品牌损失不可逆。
2. **成本与一致性**：A 把现存两种蓝收敛为「一个种子色 + 角色派生」，直接消灭双蓝问题（现状问题的根因是「无角色体系」而非「色相选错」）。
3. **先例**：M3 官方推荐流程就是从品牌色生成（[6]），无需重定品牌。
- 采用 A 时仍吸收 B 的两点精华：① 顶栏不再满铺品牌色（`#0BA3F3` 保留给连接按钮/进度/焦点等高强调处，顶栏回归 surface 中性色）——这一步同时消除「toolbar 蓝 vs FAB 蓝」并排冲突；② 深蓝 `#2B63A5` 退役或降级为 pressed/emphasis 状态层。
- 若品牌方坚持「顶栏必须有品牌色块」，则按 B 的对比度逻辑执行（品牌色块上用 onPrimary 白字 + 深化 tone），此时 A/B 的差异仅在顶栏，其余角色体系完全相同。

---

## 6. 初始 token 建议表（提案，最终由 Design System 阶段定稿）

### 6.1 官方 baseline 参照值（直接从 material 1.12.0 AAR 提取，来源 [15]）

供定稿时对照的 M3 官方 baseline（紫色系，仅作结构与对比参照，本项目不直接采用）：
light：primary `#6750A4` / onPrimary `#FFFFFF` / primaryContainer `#EADDFF` / onPrimaryContainer `#21005D`；surface `#FFFBFF` / onSurface `#1D1B20`；surfaceVariant `#E7E0EC` / onSurfaceVariant `#49454F`；outline `#79747E` / outlineVariant `#CAC4D0`；error `#B3261E` / onError `#FFFFFF` / errorContainer `#F9DEDC` / onErrorContainer `#410E0B`。
dark：primary `#D0BCFF` / onPrimary `#381E72` / primaryContainer `#4F378B` / onPrimaryContainer `#EADDFF`；surface `#141218` / onSurface `#E6E0E9`；surfaceVariant `#49454F` / onSurfaceVariant `#CAC4D0`；outline `#938F99` / outlineVariant `#49454F`；error `#F2B8B5` / onError `#601410`；surfaceContainer 系 lowest `#0F0D13` / low `#1D1B20` / container `#211F26` / high `#2B2930` / highest `#36343B`。

### 6.2 X Client 初始 token 建议（三层，方案 A 落地；数值为提案）

**第一层 primitive（种子与语义原色）**

| token | 值 | 说明 |
| --- | --- | --- |
| `brand_seed` | `#0BA3F3` | 既有品牌蓝（toolbar 蓝），作 scheme 种子 |
| `brand_deep` | `#2B63A5` | 既有 FAB 深蓝 → 降级为 pressed/emphasis 来源 |
| `semantic_success` | `#22C55E`（dark 容器用 `#146C43` 系） | 本地数据集工具类通用成功绿 [19] |
| `semantic_warning` | `#FF9800`（现有） | 保留作警告原色，后续按 tone 校准 |
| `semantic_danger` | `#B3261E` / dark `#F2B8B5` | 直接采用 M3 官方 error 值 [15] |

**第二层 semantic（颜色角色 → 语义名 → light/dark 建议值）**

> 定稿方法建议：以 `brand_seed` 输入 Material Theme Builder 生成正式值；下表为手工提案，均已按「浅色 primary 取深化 tone、暗色取亮 tone」原则给出，风险点为对比度需逐对校验（≥3:1，[1]）。

| 语义名 | M3 角色 / attr | Light（提案） | Dark（提案） | 主要用途 |
| --- | --- | --- | --- | --- |
| `color/primary` | `colorPrimary` | `#0873AC`（seed 深化 tone≈40） | `#7FD0FA`（seed 亮化 tone≈80） | 主按钮填充、连接中状态、焦点 |
| `color/onPrimary` | `colorOnPrimary` | `#FFFFFF` | `#002F44`（深青蓝） | primary 上文字/图标 |
| `color/primaryContainer` | `colorPrimaryContainer` | `#CDE9FF`（seed tone≈90） | `#0B4A6E`（seed tone≈30） | 「连接中」底色、选中态容器 |
| `color/onPrimaryContainer` | `colorOnPrimaryContainer` | `#00243A` | `#CDE9FF` | 同上文字 |
| `color/success`（扩展角色） | 自定义（参照 tertiary 用法） | `#146C43` | `#6FDFA0` | **已连接**、导入成功 |
| `color/onSuccess`（扩展角色） | 自定义 | `#FFFFFF` | `#00290F` | success 上文字 |
| `color/warning`（扩展角色） | 自定义 | `#9A5B00` | `#FFB86B` | 流量/证书类警告 |
| `color/error` | `colorError` | `#B3261E` | `#F2B8B5` | **错误**、失败提示（官方值） |
| `color/onError` | `colorOnError` | `#FFFFFF` | `#601410` | error 上文字（官方值） |
| `color/errorContainer` | `colorErrorContainer` | `#F9DEDC` | `#8C1D18` | 错误横幅底色（官方值） |
| `color/onErrorContainer` | `colorOnErrorContainer` | `#410E0B` | `#F9DEDC` | 官方值 |
| `color/background` | `android:colorBackground` | `#FFFBFF` | `#141218` | 窗口背景（官方 baseline 中性值） |
| `color/surface` | `colorSurface` | `#FFFBFF` | `#141218` | 卡面/顶栏（官方值） |
| `color/surfaceContainer` | `colorSurfaceContainer` | `#F3EDF7` | `#211F26` | 导航区/顶栏（官方值） |
| `color/surfaceContainerHigh` | `colorSurfaceContainerHigh` | `#ECE6F0` | `#2B2930` | 悬浮层/FAB/对话框（官方值） |
| `color/onSurface` | `colorOnSurface` | `#1D1B20` | `#E6E0E9` | 主文字（官方值） |
| `color/onSurfaceVariant` | `colorOnSurfaceVariant` | `#49454F` | `#CAC4D0` | 次文字/**已断开**图标（官方值） |
| `color/outline` | `colorOutline` | `#79747E` | `#938F99` | 输入框描边（官方值） |
| `color/outlineVariant` | `colorOutlineVariant` | `#CAC4D0` | `#49454F` | 分隔线（官方值） |

> 说明：`background/surface/surfaceContainer*/error*` 直接采用官方 baseline 中性值（不随品牌色改变，属 M3 设计意图）；品牌相关角色（primary 系）与扩展状态色为提案值，均已按 WCAG 公式实测达标（详见 §5.2 对比度校验表），**定稿时建议再以 Material Theme Builder 全套复核后替换**。success/warning 为 M3 之外的扩展角色——按 [2] 的规则，它们属于「不随动态色/主题漂移的语义色」，实现上可与动态色脱钩。

**第三层 component（组件映射约束，示例）**

| 组件 | 约束（全部经第二层角色引用） |
| --- | --- |
| 主连接按钮（CIP 核心组件） | 常态：`primary` 填充 + `onPrimary` 文字；连接中：`primaryContainer` + 进度色 `primary`；已连接：`success` + `onSuccess`；错误：`errorContainer` + `onErrorContainer`；触控 ≥48dp、位于拇指区 |
| FAB | `primaryContainer` + `onPrimaryContainer`（M3 FAB 官方默认映射，[1]）；圆角 `ExtraLarge` |
| 顶栏 | `surface`（或 `surfaceContainer`）+ `onSurface`，状态栏同步 `?attr/colorSurface`——移除硬编码 `#0BA3F3`/`#FFEFEFEF`/`#FF121212` |
| 列表项/卡片 | 底 `surfaceContainer(Low)`，标题 `TitleMedium`，副文 `BodyMedium` + `onSurfaceVariant`，分隔线 `outlineVariant` |
| 日志页 | 等宽文本沿用系统 monospace，底 `surfaceContainerLowest`，ERROR 行 `error`、WARN 行 `warning` |
| 空态/加载态 | 图标 `onSurfaceVariant`，主文 `onSurface`，操作按钮 `TextButton`（`primary`）；loading 用进度指示器（升级 1.12.0 后获得对比度修复，[14]） |

### 6.3 状态色语义总表（提案）

| 连接状态 | 颜色 | 图形语言 |
| --- | --- | --- |
| 已连接 | `success`（绿） | 实心/点亮 |
| 连接中 | `primary`（品牌蓝）+ 动效 | 呼吸/进度 |
| 已断开 | `onSurfaceVariant`（中性） | 描边/熄灭 |
| 错误 | `error`（红） | 实心 + 图标（warning icon） |
| 警告（非连接态） | `warning`（橙） | 仅横幅/角标，不用于连接按钮 |

依据：[19]（状态色惯例）+ [2]（语义色稳定规则）+ [1]（error 官方角色）。此表应作为图标与文本用色的唯一依据，替换现有 Java/Layout 中散落的 `#4CAF50/#FF9800/#F44336` 硬编码。

---

## 7. 调研结论小结（供 Design System 阶段执行）

1. **无需 Compose/无破坏性升级即可 M3 化**：当前 material 1.9.0 已支持全部 M3 颜色角色 attr；建议顺手升级 1.12.0（compileSdk 34 / minSdk 24 完全兼容），主题父级一步切到 `Theme.Material3.DayNight.NoActionBar`。
2. **token 体系按官方角色建三层**：primitive（品牌种子+语义原色）→ semantic（M3 角色，light/dark 成对）→ component（组件映射）；组件层只允许引用 `?attr/*`，消灭裸 hex 与 px。
3. **Typography 收敛为 4 档 2 字重**（TitleLarge / TitleMedium / BodyMedium / LabelLarge），与 M3 字阶完全对齐，不引入自定义字体。
4. **Shape 用 1.12.0 实现值**（4/8/12/16/28/50%），spacing 全部对齐 4dp 步进，触控目标 48dp。
5. **Dark theme 走 M3 tonal surface**：中性色取官方 baseline（`#141218` 系），弃用 elevation overlay 与 `#FF121212`；语义状态色跨主题固定。
6. **视觉方向采用方案 A**：`#0BA3F3` 保留为品牌种子；浅色模式 primary 用深化 tone，顶栏回归中性 surface，FAB 深蓝降级为状态色——双蓝问题由角色体系一次性解决。
7. **Dynamic Color 默认不开**（minSdk 24 兼容性无问题，但与状态色稳定性诉求冲突）；如开启，仅中性 surface 参与动态化。
8. 后续需用 Material Theme Builder（[6] 官方工具）以 `#0BA3F3` 为种子生成正式方案，替换 §6.2 中所有「提案」值并做对比度验收。
