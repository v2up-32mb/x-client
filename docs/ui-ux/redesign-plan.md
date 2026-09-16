# X Client UI/UX 重构实施计划（Redesign Plan）

> 依据：`research.md`（综合研究）及其引用的三份一手研究。本文是执行蓝图，所有数值以本文为准；如与 research 提案冲突，以本文定稿值为准。

---

## 1. Current Problems

见 `research.md` §1–§3（12 项代码级问题 + 7 类流程问题 + 4 类 IA 问题）。核心归纳：
**无 token 体系（双蓝+六色乱用+px）→ 主操作不唯一 → 三态缺失 → 错误无路径 → a11y 缺口。**

## 2. Design Goals

1. 建立三层 Design Token（primitive→semantic→component），组件层零裸 hex/零 px
2. 主页改造为"状态卡片"模式，连接四态视觉语言全 App 统一
3. 全部列表/流程补齐 empty/loading/error/disabled 态
4. Light/Dark 双主题经 M3 角色体系天然一致
5. 触控目标 48dp、contentDescription 全覆盖、对比度全部达标（≥3:1 配对 / 4.5:1 正文）
6. 业务逻辑零变更：广播协议、Go 接口、SharedPreferences 结构、URI 格式不动

## 3. UX Principles

见 `research.md` §10（8 条：信任优先/状态即语言/主操作唯一/一切用色经角色/空态是指引/48dp 与标签/不为重构而重构/业务逻辑稳定）。

## 4. Information Architecture

```
ProfileListActivity（主页=连接+配置管理）
├─ 状态卡片（四态）           ← 新：主操作唯一
├─ 当前配置上下文区           ← 新：Profile 名+服务器地址（尊重 show_server_addr 开关）
├─ Profile 列表（当前项高亮）
│   └─ 空态 EmptyStateView    ← 新：指向 FAB
├─ FAB（唯一）→ 菜单：手动添加 / 扫码导入
└─ Toolbar 溢出菜单：设置 / 运行日志 / 主题切换
ProfileEditActivity（按协议分组字段 + 就近校验）
SettingsActivity（分组 Section：通用/代理绕过/ECH 与 DNS/日志；每项带 summary）
AppListActivity（搜索+全选/反选+已选计数+48dp 行+加载/空态）
RuntimeLogActivity（保留现有三态，补过滤与操作语义）
CustomCaptureActivity（主题化遮罩色）
```

## 5. Navigation Strategy

- **不引入底部导航**（目的地 <3 个同级，M3 nav-bar 不适用；research-ux §6.1）
- 保持 Activity 栈 + Toolbar 返回；主题切换入口收敛到设置页（现 Toolbar 菜单图标按钮保留但补 contentDescription）
- `setDefaultNightMode` 触发的 Activity 重建：切换仅发生在设置页根部，状态丢失风险最小化（官方 S9 注）

## 6. Design System（定稿值）

### 6.1 颜色（M3 角色，`values/colors.xml` + `values-night/colors.xml` 成对）

**Primitive 层**（命名 `p_*`，禁止直接用于组件）：

| token | light | dark |
|---|---|---|
| `p_brand_seed` | `#0BA3F3`（品牌种子，保留） | 同 |
| `p_success` | `#146C43` | `#6FDFA0` |
| `p_warning` | `#9A5B00` | `#FFB86B` |

**Semantic 层**（M3 角色 attr 定向覆盖，组件只允许引用本层）：

| 角色（attr） | light | dark | 用途 |
|---|---|---|---|
| `colorPrimary` | `#0873AC` | `#7FD0FA` | 主按钮、连接中、焦点（实测对比度 5.17:1 / 8.26:1） |
| `colorOnPrimary` | `#FFFFFF` | `#002F44` | |
| `colorPrimaryContainer` | `#CDE9FF` | `#0B4A6E` | 选中态容器、FAB 底 |
| `colorOnPrimaryContainer` | `#00243A` | `#CDE9FF` | |
| `md_success`（自定义角色，static 双主题同源） | `#146C43` | `#6FDFA0` | **已连接** |
| `md_onSuccess` | `#FFFFFF` | `#00290F` | success 上文字 |
| `md_successContainer` | `#DCFCE7` | `#0B3D24` | 已连接卡片底 |
| `md_warning` | `#9A5B00` | `#FFB86B` | 警告（非连接态） |
| `colorError` / `colorOnError` | `#B3261E` / `#FFFFFF` | `#F2B8B5` / `#601410` | 官方值 |
| `colorErrorContainer` / `colorOnErrorContainer` | `#F9DEDC` / `#410E0B` | `#8C1D18` / `#F9DEDC` | 错误横幅 |
| `android:colorBackground` / `colorSurface` | `#FFFBFF` | `#141218` | 官方 baseline |
| `colorSurfaceContainer` | `#F3EDF7` | `#211F26` | 顶栏/卡面 |
| `colorSurfaceContainerHigh` | `#ECE6F0` | `#2B2930` | 对话框/悬浮 |
| `colorOnSurface` / `colorOnSurfaceVariant` | `#1D1B20` / `#49454F` | `#E6E0E9` / `#CAC4D0` | 主/次文字 |
| `colorOutline` / `colorOutlineVariant` | `#79747E` / `#CAC4D0` | `#938F99` / `#49454F` | 描边/分隔 |

> success/warning 系列为扩展角色（M3 规则允许语义色不随主题漂移；此处仍给 night 值以优化暗色对比度）。primary 系提案值对比度已实测达标（research-visual §5.2），实现后用 CI 构建产物人工复核。

### 6.2 Typography（4 档 2 字重，M3 attr）

`textAppearanceTitleLarge`(22/28/400) · `textAppearanceTitleMedium`(16/24/500) · `textAppearanceBodyMedium`(14/20/400) · `textAppearanceLabelLarge`(14/20/500)。系统 Roboto，不打包字体。等宽场景（日志/URI）用 `monospace`（功能文本，不占档）。

### 6.3 Shape（1.12.0 实现值）

卡片/对话框 `Large 16dp`；按钮/输入框 `Medium 12dp`；chip/日志块 `Small 8dp`；FAB `ExtraLarge 28dp`。经 `shapeAppearance*` attr，布局禁止裸 radius。

### 6.4 Spacing（4dp 步进）

`spacing_xs=4 / sm=8 / md=12 / lg=16 / xl=24 / xxl=32`（dimens.xml）。触控目标 ≥48dp。px 全部换算对齐（5px→4dp、20px→20dp、50px→48dp 文字改 14sp、100px 图标→48dp）。

### 6.5 状态色语义总表（全 App 唯一依据）

| 状态 | 底色 | 文字/图标 | 图形语言 |
|---|---|---|---|
| 已连接 | `md_successContainer` / 强调 `md_success` | `md_onSuccess` 系 | 实心 + check 图标 |
| 连接中 | `colorPrimaryContainer` | `colorOnPrimaryContainer` | 进度指示 + 品牌蓝 |
| 已断开 | `colorSurfaceContainer` | `colorOnSurfaceVariant` | 描边/熄灭 |
| 错误 | `colorErrorContainer` | `colorOnErrorContainer` | 实心 + error 图标 |
| 警告 | `md_warning` 仅横幅/角标 | — | 不用于连接按钮 |

## 7. Component Strategy

新建 shared 组件（`app/src/main/java/com/x/client/app/ui/` 包 + `res/layout/`）：

| 组件 | 形态 | 用于 |
|---|---|---|
| `ConnectionStatusCard`（自定义 View） | 全宽 MaterialCardView，四态色/文案/图标切换 + 连接中进度 | 主页 |
| `EmptyStateView`（自定义 View） | 图标+标题+说明+可选主按钮 | Profile 列表/分应用/日志 |
| `ErrorBanner`（layout include） | errorContainer 横幅 + 重试/详情按钮 | 主页错误态 |
| 样式族 `Widget.XClient.*` | Button/OutlinedButton/TextButton/EditText(TextInputLayout)/Card/ListItem/Chip | 全部页面 |
| 状态色 selector | `ColorStateList` 资源 | 滑动按钮等 |

**写入规则**：`ThemeManager`、`Preferences`、`TProxyService`、广播 action/extra、URI 解析、SwipeRevealLayout 核心逻辑 = **禁改区**；确需触碰须在 PR 描述记录原因。

## 8. Screen-by-Screen Changes

### 8.1 主页 ProfileListActivity
- **Current**：Toolbar 蓝底 + 列表 + 绿色 btn_start + FAB，无空态，状态=按钮变色
- **Problem**：P-1/P-2/P-5/P-6，主操作不唯一
- **Research Evidence**：CMFA LargeActionCard（C2a）；M3 主操作唯一（S1）；空态指引（C3b）；绿=连接心智（C1b）
- **New Design**：顶部状态卡片（四态，见 §6.5）点击=连接/断开；卡片副文案=当前 Profile 名+服务器地址（`show_server_addr` 关闭时 `••••••`）；列表当前项 primaryContainer 高亮；FAB 唯一（primaryContainer 底色 per M3 映射）；空态 EmptyStateView；错误态 ErrorBanner（重试+查看日志）；Toolbar 改 surface 底+onSurface 前景
- **Implementation Plan**：ConnectionStatusCard 新组件 → 布局重组（status_card + recycler + empty_state + fab）→ ProfileListActivity 移除 btn_start 与动态 setBackgroundTintList，接入卡片 → item_profile/item_profile_swipe token 化 → 删除 Manifest `.MainActivity` 残留（独立 commit）
- **Validation**：四态各一张截图（light/dark）+ 空态 + 连接失败态；CI 编译通过；广播状态流转回归（STARTING/STARTED/ERROR/STOPPED）

### 8.2 ProfileEditActivity
- **Current**：ScrollView + EditText/CheckBox/Spinner，按钮四色硬编码，校验=Toast
- **Problem**：P-2/P-7，错误非就近
- **Research Evidence**：NN/g 就近原则（S13）；AOSP 标题+值
- **New Design**：协议 Spinner 保留；每协议字段组用 TextInputLayout（outline 样式）包 EditText，校验错误 `setError` 就近显示；保存/导入按钮统一 `Widget.XClient.Button`（primary）与 TextButton；进阶参数折叠区标题 LabelLarge；VPN 运行中禁用态给整组 alpha+enabled 视觉
- **Implementation Plan**：布局逐字段迁移 TextInputLayout（保持 android:id 与 Preferences key 不变）→ 校验逻辑从 savePrefs 中搬到字段级（仅 UI 反馈，规则不变）
- **Validation**：GCM/X-Tunnel 两组字段截图；空必填触发 inline error；保存流程回归

### 8.3 SettingsActivity
- **Current**：单屏 11+ 项堆叠，紫色 `#9C27B0` 分应用按钮
- **Problem**：A5 反模式
- **Research Evidence**：AOSP 10–15 项/分组/summary（S12）；CMFA 分组（C2c）
- **New Design**：分组 Section 头（LabelLarge + onSurfaceVariant）：通用（主题三选，默认跟随系统）/ 代理绕过（端口、bypass 规则、分应用入口）/ ECH 与 DNS / 日志；每项标题+当前值 summary；入口行统一 ListItem 样式
- **Implementation Plan**：布局重排为 Section 结构（仍为普通控件，不引入 preference 库——避免新增依赖与存储变更）→ 保存校验逻辑不变
- **Validation**：light/dark 截图；端口校验/bypass 校验回归

### 8.4 AppListActivity
- **Current**：ListView + px 硬编码，无加载/空态，无计数
- **Problem**：P-4/P-5；A7
- **Research Evidence**：WG AppListDialogFragment 四件套（C3a）
- **New Design**：顶部"已选 N 个应用"计数条 + 保存按钮（拇指区）；搜索框保留；行高 ≥48dp、图标 48dp、文字 14sp/16sp token；加载中进度条；空结果"未找到匹配应用"；全选/反选入溢出菜单
- **Implementation Plan**：appitem.xml 重写（dp/sp）→ activity_app_list.xml 加计数条/加载/空态 → AppListActivity 补加载态与计数逻辑（选择数据仍为包名列表，存储不变）
- **Validation**：500+ 应用可滚动流畅；筛选空态截图；选择→保存→重进回显回归

### 8.5 RuntimeLogActivity
- **Current**：已有 loading/empty 态（达标），monospace，复制按钮
- **Problem**：仅样式未 token 化；缺 ERROR/WARN 行级着色
- **Research Evidence**：保留达标实现（原则 7）；日志语义色（research-visual §6.2）
- **New Design**：日志底色 surfaceContainerLowest token；ERROR/WARN 行色 token；操作按钮统一样式
- **Implementation Plan**：仅样式层改动，逻辑不动
- **Validation**：日志渲染回归 + light/dark 截图

### 8.6 CustomCaptureActivity + ScannerOverlayView
- **Current**：遮罩/边角色 Java 硬编码
- **Problem**：不随主题（扫码页恒暗可接受，但取值应 token 化）
- **Research Evidence**：A3 硬编码禁忌
- **New Design**：遮罩色、框色、扫描线色改为可配置 attr（默认保持现值，dark 语义），文案白字保留
- **Implementation Plan**：ScannerOverlayView 增加 TypedArray 读取 → styles 定义 `ScannerOverlay` 样式
- **Validation**：扫码功能回归（需真机/CI 截图为辅）

### 8.7 dialog_export（导出二维码/URI）
- **New Design**：AlertDialog 用 M3 主题默认；QR ImageView 补 contentDescription；按钮规范化
- **Validation**：导出流程回归

## 9. Technical Strategy

- **依赖**：material 1.9.0 → **1.12.0**（唯一新增依赖级变更；无新库引入）
- **主题**：`Theme.Material3.DayNight.NoActionBar` 单一定义（values + values-night 仅颜色差异），状态栏 `?attr/colorSurface` 动态
- **包结构**：新增 `ui/`（组件）与 `ui/widget/`（自定义 View）；Activity 不挪包（避免 manifest 改动面扩大）
- **禁改区**：见 §7 写入规则；SharedPreferences key、广播 action、`golib` 接口全部不动
- **构建**：全程 GitHub Actions `build-debug.yml`（workflow_dispatch）；本地只做静态检查（grep 裸 hex/px）

## 10. Migration Strategy

1. 先 Design System（theme/colors/typography/shape/dimens + 1.12.0 升级）→ CI 验证编译与全页回归（M3 主题下旧组件自动映射 Widget.Material3.*，需逐一核对）
2. 再 shared components → 主页 → 其余页
3. 每步独立 commit；每步 CI 绿后才进下一步
4. 旧 token（toolbar_background/fab_background/secondary_text/divider/code_background）保留一个过渡期，引用迁完后删除

## 11. Testing Strategy

- **编译**：CI build-debug（4 ABI）
- **静态**：自写 grep 脚本检查 res/layout 与 java 中裸 hex / px（禁则清单）
- **回归清单**（每屏）：启动、连接/断开流转、导入（URI/扫码）、编辑保存、设置保存、分应用选择、日志获取、主题切换（三模式）、旋转重建
- **视觉 QA**：四态 × light/dark 关键截图清单（§8 各 Validation 项）；无多模态输入时以布局审查 + CI 产物人工核对代替
- **A11y**：grep contentDescription 覆盖率；触控目标尺寸清单核对

## 12. Risks

| 风险 | 缓解 |
|---|---|
| M3 主题下旧 M2 组件默认样式漂移（按钮高度/形状变化） | 升级后先 CI 构建全页人工核对；必要时为个别组件显式 `style="@style/Widget.Material3.*"` |
| 1.12.0 与 appcompat 1.6.1 组合 | 官方兼容（minSdk 19/compileSdk 34）；CI 验证 |
| ProfileListActivity 重构面大（含导入/QR/广播） | 分两个 commit：先 token 化/主题迁移（逻辑零改动），再状态卡片重组 |
| ThemeManager 切主题重建 Activity 状态丢失 | 现状已存在；重构不恶化，且入口收敛到设置页 |
| SwipeRevealLayout 触摸行为在换色后回归 | 不改其逻辑，仅换子按钮配色；回归清单覆盖滑动操作 |
| `.MainActivity` 残留清理 | 无引用即安全删除；独立 commit 便于回滚 |

## 13. Open Questions

1. 延迟测试/按延迟排序（O4）：需 golib 暴露测速能力，是否立项？（本计划不含）
2. 导入"从剪贴板"通道（P9）是否需要？（本计划不含，记录）
3. 分应用代理默认模式语义（排除 vs 包含）当前实现为哪种？若为包含模式，UI 文案需按现行为描述（待实现时确认 Preferences.getApps 语义）
4. 俄语翻译新增 key 的翻译来源？（新增字符串先中文+英文，ru 留待补翻）

## 14. Implementation Order

```
C1  build(deps): material 1.9.0 -> 1.12.0                     [CI]
C2  feat(theme): M3 theme + 三层 color/typography/shape/spacing token   [CI]
C3  refactor(ui): shared 组件（ConnectionStatusCard/EmptyStateView/ErrorBanner + Widget.XClient.* 样式族）
C4  refactor(home): ProfileList 状态卡片化 + 列表 token 化 + 空态/错误态   [CI]
C5  fix(manifest): 移除 .MainActivity 残留                     [CI]
C6  refactor(edit): ProfileEdit TextInputLayout 就近校验        [CI]
C7  refactor(settings): 分组 + summary                          [CI]
C8  refactor(applist): 48dp + 计数 + 加载/空态                  [CI]
C9  refactor(logs+scanner+dialog): token 化收尾                 [CI]
C10 chore(cleanup): 删除废弃 token/资源；全量静态禁则检查通过
```

每步提交信息遵循 Conventional Commits（仓库既有规范）。
