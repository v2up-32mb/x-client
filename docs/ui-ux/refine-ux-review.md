# UX Review：主页精修（VPN FAB 化 + 菜单语义色）

> 分支 `refactor/ui-ux-redesign` ｜ 阶段：UX Review（实现前评审，只输出建议，不改代码）
> 输入：`ProfileListActivity.java`、`ui/ConnectionStatusCard.java`、`ui/ErrorBanner.java`、
> `activity_profile_list.xml`、`item_profile_swipe.xml` + `ProfileAdapter.java`、
> `values[-night]/colors.xml`、`values[-night]/styles.xml`、`SwipeRevealLayout.java`、
> `TProxyService.java` 状态机、redesign-plan §6/§8.1、qa-report.md
> 性质：精修（refinement），保留现有 M3 Design System，不重构架构。

---

## 0. 结论速览

| # | 议题 | 结论 |
|---|---|---|
| 1 | FAB 交互 | **tap=连接/断开（单击直达），长按=展开信息卡**；TalkBack 走"双击并按住"+带标签辅助动作，被动信息位兜底 |
| 2 | 动画方案 | **单自定义 View（MaterialCardView 基类）+ ValueAnimator morph**；拒绝 Fragment/共享元素/MotionLayout；折叠=定时为主 + 点按/滚动/换配置/onPause 多兜底 |
| 3 | 五态映射 | 沿用 §6.5 状态语言：断开=surfaceContainer、连接/断开中=容器色+`CircularProgressIndicator`、已连=successContainer、错误=errorContainer |
| 4 | 顶部结构 | **Toolbar 即全部**；不设被动指示器，用"resume 自动滚动到当前项"替代；列表高亮足够 |
| 5 | 菜单语义色 | share=primaryContainer、copy=surfaceContainerHigh、edit=**新增 tertiaryContainer（teal 系）**、delete=**保持实心 colorError** |
| 6 | 无障碍 | 5 套 contentDescription + liveRegion + 颜色非唯一通道（图标形状）+ TalkBack 下冻结自动折叠 + 76dp 定高在 fontScale 2.0 会裁切（须 wrap/minHeight） |
| 7 | 回归风险 | 错误链路最大风险点：展开 Error 卡必须内嵌"重试/查看日志"，否则能力降级；DISCONNECTING 无广播支撑需乐观态+超时兜底；Handler 回调须随态作废 |

---

## 1. Recommended VPN FAB interaction

**唯一推荐：tap = 连接/断开主操作（单击直达，与现状态卡心智一致）；长按 = 展开信息卡（查看连接信息）。**

理由：

- **断开/连接是本页主操作且频率最高**。"点击已连接时先展开再断开"会把主操作变成两步——每次断开都要先看完一遍动画再点第二次，效率与心智双输。且断开 VPN 并非破坏性操作（不丢数据、可随时重连），不需要二次确认式的保护；用动画做"假确认"反而让用户怀疑"我是不是没点上"。
- **长按作为"查看信息"是低频、可发现的折中**。展开卡的核心价值是状态变化的即时反馈（morph 自动触发），而非常驻面板；长按只是"错过自动展开后的补看"路径，低频操作放长按成本可接受。
- **连接中/错误态的 tap 语义不悬空**：`vpnStarting` 时 tap 忽略（现有防抖保留）；ERROR 态 tap = 折叠展开卡（不做动作，见 §7），重试走卡内按钮，避免误触重连。

**TalkBack 下"查看信息"的触达路径（三层兜底）：**

1. **标准长按手势**：双击并按住 = long-press。FAB 必须置 `longClickable=true`，`AccessibilityNodeInfo` 自然暴露长按。
2. **带标签的辅助动作**：仅靠"双击并按住"用户无法预知会发生什么，建议在 `onInitializeAccessibilityNodeInfo` 注册 `AccessibilityAction` 并以"查看连接信息"命名（TalkBack 动作菜单直接可读），而非裸 ACTION_LONG_CLICK。
3. **被动信息位兜底（结构性解法）**：当前配置名/服务器地址在列表行中常驻（`text_profile_name`/`text_server_addr`），TalkBack 焦点扫列表即可读全，**不依赖瞬时展开卡**——这是第 4 节"列表高亮足够"结论的 a11y 依据。

展开卡本身：`focusable=true`、文本容器设 `accessibilityLiveRegion="polite"`，TalkBack 用户双击 FAB 触发连接时，展开卡的"正在连接…"会被自动播报；同时 **TalkBack（`isTouchExplorationEnabled`）或卡片持有 a11y 焦点期间冻结自动折叠计时器**，否则 2~3.5s 的停留窗口对探索模式用户太短，信息卡会在焦点到达前消失。

---

## 2. Animation strategy

**唯一推荐：单个自定义 View（`MaterialCardView` 子类，含 compact 图标层 + expanded 文本层）+ `ValueAnimator` 做 morph。**

### 2.1 方案取舍

| 方案 | 判定 | 理由 |
|---|---|---|
| **自定义 View + ValueAnimator（宽度/高度/圆角/内容交叉淡入）** | ✅ 采用 | 零新增依赖；morph 目标是**原位同一个 view**，不需要跨容器；duration 可编程缩放，reduced-motion 直接跳切最易实现；与现有 XML View 栈、Popup、SwipeRevealLayout 无耦合 |
| MotionLayout | ❌ | 单一两段式过渡用不上约束集的表达力；reduced-motion 与"终态到达时内容原位换装"这类运行时分支在 MotionScene 里写反而绕；调试成本不值 |
| Fragment 共享元素 / ContainerTransform | ❌ | 面向"跨屏幕/跨容器"的转场；本例 FAB→卡是同一位置自变形，引入 Transition 体系只为一个 morph 属于重型方案，还会牵动主页无 Fragment 的现状 |
| `ExtendedFloatingActionButton.extend()` | ❌ | 高度固定 48dp、不 morph 圆角、无 per-state 停留控制，不满足 56→76dp + 28→16dp 的规格 |

实现要点：

- **尺寸**：`ValueAnimator` 插值 `layoutParams.width/height`（56dp→300dp、56→76dp），每帧 `requestLayout`；300dp 需用 `min(300dp, 屏宽-2×16dp)` 封顶（小屏兜底）。
- **圆角**：`MaterialCardView.setRadius(float)` 逐帧插值 28→16dp，官方 API，无需自绘 `ShapeAppearanceModel`。
- **内容**：compact 图标层 alpha 1→0（~120ms）与 expanded 文本层 alpha 0→1 + `translationX` 12dp→0（~150ms，延迟 ~80ms 启动）交叉；整体 expand 250ms（FastOutSlowIn）、collapse 220ms（AccelerateDecelerate），落在 200-300ms 规格带内。
- **reduced-motion**：读 `Settings.Global.ANIMATOR_DURATION_SCALE`，为 0 时跳过全部动画直接切终态（内容也直接显示，不做淡入）；自定义动画不会自动豁免系统动画缩放，必须显式处理。
- **CONNECTING 是否自动折叠**：**推荐"展开中不倒计时，终态到达时原位换内容并按终态计时"**——tap 后展开"正在连接…"，若 1.5s 内就返回 STARTED，卡片原位淡换为"已连接"并按 success 停 2s 再折叠；若迟迟不返回则保持展开进度态（折叠后再为 STARTED 二次展开会造成"折叠-展开"抖动）。若实现期坚持统一 dwell，则 connecting dwell 取 3s、终态到达时允许二次展开，作为降级路径。
- **终态 dwell**：success 2.0s（规格 1.5-2.5s 中值）、error 3.5s（规格 2.5-4s 偏长，因错误文案更长，见 §7）。

### 2.2 collapse 触发时机

**定时为主，多兜底**：① 终态 dwell 到期；② 展开期间用户 tap FAB（仅折叠，不触发连接/断开）；③ 列表开始拖动滚动（`SCROLL_STATE_DRAGGING`）——与现有"滚动关闭滑动菜单"（`closeAllItems`）完全同构，用户已建立"一滚全收起"的心智；④ 切换当前配置；⑤ `onPause`。

**不采用"点击外部折叠"**：需要全屏触摸拦截层，与 RecyclerView 的滚动/滑动展开（SwipeRevealLayout 自己消费横向手势）事件流冲突，且与 App 内既有"滚动即收起"模式不一致。定时已覆盖"用户去看列表"的场景。

### 2.3 与列表滚动/SwipeRevealLayout 的冲突

- **事件层无冲突**：展开卡覆盖在 FrameLayout 顶层（elevation 高于 RecyclerView），只消费落在自身 bounds 内的触摸；SwipeRevealLayout 在 item 内部处理手势，互不越界。
- **视觉遮挡**：展开后左下角覆盖 ~100dp 高度，列表 `paddingBottom=88dp` 下会压住最后一行的一部分。自愈机制已足：dwell ≤3.5s + 滚动即折叠。**不建议**动态改 padding（会引起整列 re-layout 跳动）；QA 真机若发现遮挡影响操作，再考虑把 paddingBottom 提到 96dp。
- **同一帧并发动画**：滑动菜单关闭（SwipeRevealLayout 自身 250ms ValueAnimator）与 FAB collapse 可能同帧发生，两者操作不同 view 树分支，无线程/测量冲突，无需协调。

---

## 3. Connection state visualization（compact FAB 五态映射）

沿用 §6.5 状态色语义总表（全 App 唯一依据），FAB 是该表的紧凑载体，**不引入新语义**：

| 状态 | 底色 | 图标 | 图标 tint | contentDescription |
|---|---|---|---|---|
| DISCONNECTED | `?attr/colorSurfaceContainer` | `ic_power` | `?attr/colorOnSurfaceVariant` | 连接 VPN |
| CONNECTING | `?attr/colorPrimaryContainer` | `CircularProgressIndicator`（indeterminate） | `?attr/colorPrimary` | 正在连接，请稍候 |
| CONNECTED | `@color/md_success_container` | `ic_check_circle` | `@color/md_success` | 断开 VPN |
| DISCONNECTING | `?attr/colorSurfaceContainer` | `CircularProgressIndicator` | `?attr/colorOnSurfaceVariant` | 正在断开，请稍候 |
| ERROR | `?attr/colorErrorContainer` | `ic_error` | `?attr/colorOnErrorContainer` | 连接失败，双击重试 |

设计依据与关键决定：

- **DISCONNECTED 用中性 surfaceContainer 而非 primaryContainer（蓝）**：Add FAB 已是 primaryContainer，若 VPN FAB 断开态也用蓝，两个 56dp 蓝 FAB 并排 = "孪生按钮"歧义；且 §6.5 已定义"已断开=描边/熄灭"心智（原状态卡同理）。中性熄灭 → 交互后变彩，状态对比更强。
- **CONNECTED 用 successContainer + md_success 图标**：与原状态卡逐字对齐（绿=已连接心智，redesign-plan C1b）。
- **DISCONNECTING 为"乐观本地态"**：`TProxyService` 只有 `STATUS_STARTING/STARTED/ERROR/STOPPED` 四广播，**没有 STOPPING**；`stopVpn()` 同步置 `enable=false` 后异步等 `STATUS_STOPPED`。因此断开中只能由 UI 本地进入，且必须带 3s 超时兜底（见 §7）。底色选 surfaceContainer（中性"退场"）而不用蓝：蓝已绑定"正在建立"的前向语义。
- **进度指示选型**：**用 `CircularProgressIndicator`（material 1.12.0 自带，24-32dp，叠放在 FAB 内、隐藏图标）**，attr 可 tint、无新依赖。备选 `CircularProgressDrawable` 需引入 `swiperefreshlayout` 依赖（当前 `build.gradle` 无此包），不如前者；不用 indeterminate drawable wrap `ImageView` 的 hack（尺寸/居中/着色都更难控）。
- **展开卡配色与 compact 同源**：底色=同状态容器色，标题 `on*Container`（TitleMedium），副文案（配置名/服务器）同 `on*Container`（BodyMedium）——与现 `ConnectionStatusCard.setState()` 的四分支映射完全一致，可平移复用。

---

## 4. Top area simplification

**最终头部结构：只留 Toolbar。**

```
[MaterialToolbar：title=应用名, subtitle=版本号]   ← 现状保留，不动
[RecyclerView（配置列表）]                        ← 占满剩余空间
[左下 VPN FAB]  [右下 Add FAB]                    ← 56dp×2，margin 16dp，elevation 一致
[EmptyStateView（空列表时居中）]                   ← 保留
```

删除项：`ConnectionStatusCard`（`status_card`）+ `ErrorBanner`（`error_banner`）两个布局节点。页面唯一主操作 = VPN FAB；Add FAB 语义为"添加/导入"，非本页主操作，维持现状即可（规格只要求两 FAB 尺寸/margin/elevation 对称，不要求同色）。

**被删信息（当前配置名/服务器地址）是否需要被动展示位？——不需要，列表高亮已足够，但补一个零 UI 成本的补偿项。**

- 列表行本身常驻"当前项 primaryContainer 高亮 + RadioButton 选中 + 名称 + 服务器地址 + 协议标签"（`ProfileAdapter.onBindViewHolder` 已实现），信息量 ≥ 原状态卡副文案；`show_server_addr` 关闭时的 `••••••` 掩码逻辑也随行保留，无泄露回归。
- 唯一缺口：**高亮行滚出视口后，"当前连的是哪个"只剩 FAB（compact 态无配置名）**。补偿：`refreshProfileList()` 后若用户未手动滚动过，`LinearLayoutManager.scrollToPosition(currentIndex)`，保证回到主页/换配置后当前行始终可见。这比顶部加"当前配置 chip/副标题"更好——后者重新引入"第二状态展示位"，违背本轮"顶部去 CTA 化、页面只有一个状态出口"的方向。
- Toolbar subtitle 保持版本号。不建议在"VPN 运行时把 subtitle 换成当前配置名"：同一位置语义漂移（版本↔配置）会造成不可预期感；若后续 QA 实测仍漏看当前项，再考虑此降级方案，二者不要同时上。
- 附带清理：`status_tap_to_connect` 等仅状态卡使用的字符串、`ConnectionStatusCard.java`/`view_connection_status_card.xml`、`ErrorBanner.java`/`view_error_banner.xml`（无他处引用）应在实现 commit 中一并删除，避免 qa-report §2 反对的"死布局"回潮。

---

## 5. Action menu color semantics（四操作最终配色）

| 操作 | 底色（attr） | 图标 tint（attr） | light 实值 / dark 实值 | 语义 |
|---|---|---|---|---|
| 分享 share | `?attr/colorPrimaryContainer` | `?attr/colorOnPrimaryContainer` | #CDE9FF / #0B4A6E | 蓝系 container＝对外分发（品牌蓝容器，现 edit 用色转移给 share） |
| 复制 copy | `?attr/colorSurfaceContainerHigh` | `?attr/colorOnSurface` | #ECE6F0 / #2B2930 | 中性＝原地副本，无方向性（维持现状） |
| 编辑 edit | `?attr/colorTertiaryContainer`（**新增**） | `?attr/colorOnTertiaryContainer`（**新增**） | #B4EBE3 / #1F4B46 | teal 系＝"进入修改"独立色相 |
| 删除 delete | `?attr/colorError`（**实心保持**） | `?attr/colorOnError` | #B3261E / #F2B8B5 | 破坏性动作唯一强色 |

四键 = 蓝 container + 中性 + teal container + 实心红：3 彩 1 中，色相互斥（蓝 H≈201° / teal H≈180° / 红 H≈0°），不构成彩色按钮墙。

**tertiaryContainer 新 token 建议**（由品牌种子 `p_brand_seed #0BA3F3`（H≈201°）做 -20° 邻近色相偏移得 teal，明度对齐 M3 tone 结构）：

| token | light | dark | 用途 |
|---|---|---|---|
| `x_tertiary` | `#FF006961` | `#FF83D5C9` | 可选主角色（本轮仅占位） |
| `x_on_tertiary` | `#FFFFFFFF` | `#FF00201C` | tertiary 上前景 |
| `x_tertiary_container` | `#FFB4EBE3` | `#FF1F4B46` | 编辑按钮底 |
| `x_on_tertiary_container` | `#FF003733` | `#FFB2ECE2` | 编辑按钮图标 |

对比度粗验（WCAG 相对亮度法）：

- light：`#003733` on `#B4EBE3` ≈ **10.0:1**（远超图形 3:1 / 文字 4.5:1）
- dark：`#B2ECE2` on `#1F4B46` ≈ **7.4:1**（达标）
- 与 primaryContainer 的区分度：light `#B4EBE3`（G>B，青绿）vs `#CDE9FF`（B>G，蓝）；dark `#1F4B46` vs `#0B4A6E`——两主题下色相差均可辨，不会出现"编辑/分享/选中行"三处蓝不分。

落地位置（qa-report 已确认 **values-night/styles.xml 是整主题完整重声明**，改一处漏一处即夜间丢 token）：

1. `values/colors.xml` + `values-night/colors.xml`：新增 `x_tertiary*` 四值；
2. `values/styles.xml` **和** `values-night/styles.xml`：同时绑定 `colorTertiary*` 四个 attr（这是本轮唯一需要动 theme 的点，与任务说明一致）。

**delete 确认用实心 `colorError`，不改 `errorContainer`**：

- 滑动菜单里 4 个 64dp 色块并排，container 系（浅底深图标）视觉重量轻、似"次级按钮"；删除是菜单内唯一破坏性动作，实心红是它不该被连点的"重量信号"，也延续 iOS Mail 删除键等平台惯例。
- `errorContainer`（light #F9DEDC）与 copy 的 surfaceContainerHigh（#ECE6F0）明度接近，改过去反而削弱删除的辨识度、加重"彩色按钮墙"观感。

---

## 6. Accessibility concerns

逐项清单（实现时逐条验收）：

1. **FAB 状态的 contentDescription 必须随五态切换且含动作动词**（§3 表第 5 列）。现 `updateConnectionState()` 已按状态换 `acc_connect/acc_disconnect`，此模式平移到 FAB；新增"正在连接/正在断开/连接失败"三串。装饰性层（进度圈背景、展开卡图标）`importantForAccessibility=no`，避免双读。
2. **liveRegion**：展开卡文本容器 `android:accessibilityLiveRegion="polite"`；同时 FAB 根节点也建议标 polite——CONNECTED/ERROR 可能在用户视线离开 FAB 时由广播异步到达（如 STARTED 返回、60s 超时），compact 图标变色本身没有播报事件，liveRegion 保证状态变化被 TalkBack 读出。
3. **颜色不得是唯一信息通道**：五态已由"图标形状（电源/进度/✓/!）+ 文案（展开卡）+ contentDescription"三通道承载，色盲用户可辨。DISCONNECTED 与 DISCONNECTING 同为中性色，靠"电源图标 vs 进度圈"区分——图标必须真的切换，不能只变色。
4. **touch target**：56dp FAB ≥48 ✔；两 FAB 间距（56+16+16+间隙）在 320dp 小屏也远超 48dp ✔；展开卡点击区更大 ✔。
5. **长按的可达性**：见 §1——`longClickable=true` + 带标签的 AccessibilityAction（"查看连接信息"）+ 被动信息位兜底。**不允许出现只有长按才能获得的信息**。
6. **自动折叠对 a11y 用户的影响**：TalkBack 触摸探索开启或展开卡持有 a11y 焦点时**暂停/取消 dwell 计时器**，焦点离开再恢复（或直接取消，用户手动收起）；否则 2-3.5s 窗口内探索模式用户读不完内容。
7. **动画对 a11y/前庭敏感用户**：`ANIMATOR_DURATION_SCALE=0` → 跳切（含内容淡入）；此判断要在自定义 View 内部做，不依赖系统自动缩放。indeterminate 进度圈属于功能性反馈，可保留旋转（业界普遍豁免），如需更保守可在 `isTouchExplorationEnabled` 时同步放慢。
8. **字体缩放**：**规格中的"高度 76dp 定高"在 fontScale 2.0 会裁切 16sp/14sp 双行文本**。实现应为展开卡 `height=wrap_content + minHeight=76dp`（56→76 只作为动画起点值），或文本 `maxLines=1 + ellipsize`。这是规格一处必要修正。
9. **焦点遍历**：VPN FAB 需 `focusable=true`，顺序 = 列表项 → VPN FAB → Add FAB（布局声明顺序自然达成，勿在两 FAB 间插入 `previousUp` 干扰）。

---

## 7. Potential regressions

### 7.1 错误反馈链路（最大风险点）

删 ErrorBanner 后，错误承载面从"常驻横幅（消息 + 重试 + 查看日志，直到下一状态）"变为"FAB 展开卡（停留 3.5s 后只剩 compact 红色 ! 图标）"。**能力映射与缺口**：

| 原 ErrorBanner 能力 | FAB 展开卡是否覆盖 | 补法 |
|---|---|---|
| 错误消息文本 | ⚠️ 部分覆盖 | 展开卡正文展示同一 message（复用 `error_banner_prefix` 拼接逻辑）；文案可能很长，需 `maxLines=2 + ellipsize`，全文引导看日志 |
| 重试按钮 | ⚠️ **缺** | 展开卡内嵌 MaterialTextButton「重试」（onErrorContainer tint，同 ErrorBanner 样式）；**且展开卡期间的 tap 只折叠不重试**——否则"看错误信息"与"重连"两个意图抢同一个手势，误触重连概率高 |
| 查看日志按钮 | ⚠️ **缺** | 展开卡内嵌「查看日志」TextButton → `RuntimeLogActivity`（平移 `setOnDetailsListener` 逻辑） |
| 错误持续可见 | ⚠️ dwell 后消失 | compact ERROR 态（红 ! FAB）**必须保持到用户下一次操作或状态变化**，不超时回落 DISCONNECTED——"连接失败过"这件事不能被计时器抹掉 |

其余错误入口同步改道：60s 超时（`vpnStartupTimeout`）与 `doStartVpn` 异常路径现调 `showErrorBanner(...)`，须替换为 FAB ERROR 态（同 message），并在 `onDestroy`/状态切换时移除 Handler 回调。建议同时**删掉 ERROR 与 STARTED 的重复 Toast**（现两处 `Toast.makeText`）：morph 展开卡 + compact 常驻态已是完整反馈，Toast 属于冗余通道且 TalkBack 会双读。三个 `showErrorBanner` 调用点全部收编后，ErrorBanner 才可安全删除。

### 7.2 60s 超时路径

逻辑保留（`vpnStartupTimeout` 未动），新增注意点：超时把 FAB 置 ERROR 时，若 CONNECTING 展开卡还开着，应**原位换装为 ERROR 内容并重置 dwell**，而不是"折叠完再展开"；实现用"状态代际计数器"（state generation）作废旧 dwell/collapse 回调，防止慢回调打在新状态上（例如 ERROR 的 3.5s 折叠回调晚于用户重试、正在 CONNECTING 时触发）。

### 7.3 断开中连点防抖

现状不对称：`toggleVpn()` 有 `vpnStarting` 防抖，但**断开方向没有**——`stopVpn()` 可被连点，重复 `startService(ACTION_DISCONNECT)`（服务侧 `stopping` 同步锁幂等，不崩但产生无谓调用）。FAB 引入 DISCONNECTING 态后**该态必须吞掉 tap**，直至 `STATUS_STOPPED` 或 3s 兜底超时。同时建议补一个反向防抖：DISCONNECTING 期间 tap 不应触发 startVpn（`prefs.getEnable()` 已为 false，点一下会走 startVpn，与服务端 cleanup 竞态——现有代码就有此隐患，FAB 化时一并堵上）。

### 7.4 多窗口/旋转重建

- **展开态丢失 = 可接受**：morph 属瞬时 UI 态，重建后从 `prefs.getEnable()` + 广播重建 compact 态即可；不持久化展开状态。
- **必须处理**：dwell/collapse/超时 Runnable 持有 view 引用，重建后旧回调若仍挂主线程会操作已分离的 view。所有回调在触发前检查 `view.isAttachedToWindow()`（或状态代际），并在 `onStop`/`onDestroy` 移除。
- **既有缺口顺带暴露**：重建时 `vpnStarting=false` 不随实例恢复，若 service 正在 STARTING，FAB 会短暂显示 DISCONNECTED（现状态卡同病）。可在 `onResume` 里向 service 拉一次状态或依据 `isOwnVpnServiceRunning() + starting` 粗判；本轮至少记录，不强制修。
- 分屏（multi-window）：morph 宽度按"屏宽-32dp"封顶，300dp 上限在半屏窗格自动收缩，无溢出。

### 7.5 其他低风险项

- **EmptyStateView**：空列表时主按钮仍指向 Add FAB ✔；VPN FAB 在空态点按会走"地址为空"Toast——建议保留 Toast（配置缺失 ≠ 连接失败，不污染 ERROR 语义）。
- **Add FAB PopupMenu** 锚点、菜单项不动；仅 VPN FAB 新增长按，不影响其手势。
- **RTL**：两 FAB 用 `layout_gravity="start|bottom"` / `end|bottom` 声明（不要 left/right），展开卡 translationX 方向用 `getLayoutDirection()` 判断或用 ViewPropertyAnimator 的相对量。
- **qa-report 矩阵更新**：主页 Success/Loading/Error 三态承载面从"卡片"改为"FAB"，§3 状态矩阵与 §6 a11y 表需在实现后同步修订（"状态卡片 ≥96dp 触控"改为"双 FAB 56dp"）。

---

## 附：实现顺序建议（供 plan 参考，非本轮范围）

1. token 先行：`x_tertiary*` 四色 + 双 styles 绑定（独立 commit，构建可验）。
2. 新建 `ConnectionFab`（含五态映射 + reduced-motion 跳切），先以 compact 态替换 `statusCard` 点击语义，行为等价验证广播流转（STARTING/STARTED/ERROR/STOPPED + 60s 超时）。
3. morph 展开/折叠 + 三个收编的错误入口（重试/日志/常驻态）。
4. 布局删卡 + `scrollToPosition` 补偿 + 字符串/死文件清理。
5. `item_profile_swipe` 语义色套用（依赖第 1 步 token）。
