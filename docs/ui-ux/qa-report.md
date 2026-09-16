# X Client UI/UX 重构 QA 报告

> 分支：`refactor/ui-ux-redesign` ｜ 验证方式：GitHub Actions CI（本地无构建环境）+ 静态禁则检查 + 代码级回归清单核对
> 范围：redesign-plan §11 测试策略的全部条目

---

## 1. 编译验证（GitHub Actions build-debug.yml）

| 阶段 | Commit | 结果 | 备注 |
|---|---|---|---|
| material 1.12.0 升级 + M3 主题 token | `0dbfef5`→`52c18a7` | ✅（修复 CI 基础设施后） | 先修 `setup-android` tools 包移除问题 |
| 共享组件 + 主页重构 | `a9a43d8`→`7df4306` | ✅（修复 3 处编译错后绿） | 见 §4 缺陷记录 |
| C5 manifest 清理 | `a2d8788` | ✅（随上轮） | |
| C6/C7/C8 三屏重构 | `a2590c6`/`fd50374`/`297dff4` | ✅（run 35072766453） | |
| C9/C10 收尾 | `312da03` | ✅（run 35073121871） | |

最终状态：**4 ABI（armeabi-v7a/arm64-v8a/x86/x86_64）debug APK 构建成功**，NDK（hev-socks5-tunnel + gomobile AAR）全流程通过。

## 2. 静态禁则检查（最终值）

| 禁则 | 重构前 | 重构后 | 规则 |
|---|---|---|---|
| 布局/菜单裸 hex | 39 处 | **0** | 组件层只允许 ?attr |
| px 单位 | 6 处 | **0** | 全部 dp/sp |
| Java 裸色值 | 5 处 | **2（豁免）** | ScannerOverlayView TypedArray 默认值回退，实际取值走 token |
| textSize 裸值 | 全部裸值 | **1（豁免）** | 日志 monospace 12sp 功能文本 |
| 旧色别名引用 | 13 处 | **0** | toolbar_background 等 5 个别名已删除 |
| 死布局 | item_profile.xml、main.xml | **已删除** | |

## 3. 状态覆盖矩阵（redesign-plan §3「必须考虑所有状态」）

| Screen | Success | Loading | Empty | Error | Disabled | Offline | 首次用户 | 说明 |
|---|---|---|---|---|---|---|---|---|
| 主页 | ✅ 卡片绿态 | ✅ 卡片蓝态+进度 | ✅ EmptyStateView | ✅ ErrorBanner(重试/日志) | ✅ 启动中禁点 | ➖ 经 Toast+自动重连（业务既有） | ✅ 空态指向 FAB | 连接中 60s 超时亦有横幅 |
| Profile 列表 | ✅ | ➖（本地读取即时） | ✅ | ➖ | ✅ VPN 运行中滑动按钮 Toast+视觉 | ➖ | ✅ | |
| Profile 编辑 | ✅ | ➖（本地即时） | ➖（不适用） | ✅ inline setError | ✅ alpha+enabled 视觉 | ➖ | ✅ 新建直入编辑 | |
| 设置 | ✅ | ➖ | ➖ | ✅ 端口/bypass inline | ✅ alpha 0.5f | ➖ | ✅ summary 显示当前值 | |
| 分应用 | ✅ | ✅ 后台枚举+进度 | ✅ 搜索空态 | ➖（枚举失败走既有路径） | ➖ | ➖ | ✅ 计数条 | |
| 运行日志 | ✅ | ✅（既有） | ✅（既有） | ➖ 超时提示（既有） | ✅ 复制随空态禁用 | ✅ 服务不可达提示（既有） | ✅ | 新增 ERROR/WARN 行级着色 |
| 扫码页 | ✅ | ➖ | ➖ | ➖ | ➖ | ➖ | ✅ 提示文案资源化 | 恒暗功能色 |

➖ = 该页面不适用此状态（设计如此，非缺失）。

## 4. 缺陷记录（过程中发现并修复）

| # | 缺陷 | 修复 | commit |
|---|---|---|---|
| 1 | CI 基础设施：SDK 仓库移除 `tools` 包导致 setup-android 失败（main 分支同样受影响） | 显式 packages 列表 + 修复 release.yml 多行输入 bug | `0dad495` |
| 2 | values-night styles 整体替换语义导致暗色丢主题 | night 版完整重声明 | `52c18a7` |
| 3 | `Widget.Material3.CardView.ElevatedCard` 不存在 | 改 `Widget.Material3.CardView.Elevated` | `58a9924` |
| 4 | Manifest 遗留 AppTheme 引用 | 改 Theme.XClient | `58d4550` |
| 5 | nonTransitiveRClass：app R 无 material attr | static import material R.attr | `8968329` |
| 6 | Gravity.CENTER 未限定 | 全限定 | `7df4306` |
| 7 | merge 布局命名空间拼写错误（res/auto）；误删 ic_launcher_background | 修正+恢复 | `ff6a277` |

## 5. 视觉 QA（约束说明）

本地无模拟器/真机环境，视觉验证以下列方式执行：
- 布局审查（全部重写布局逐文件人工核对层级/padding/对齐）
- token 语义核对（每处取色对照 redesign-plan §6.5 状态色总表）
- CI 构建产物 APK 供用户安装体验（4 ABI）

**待真机确认项**（建议安装 debug APK 后核对）：
- [ ] Light/Dark 双主题下六个页面观感与对比度
- [ ] 主页四态切换动画与文案
- [ ] 小屏（≤5 英寸）主页卡片与列表密度
- [ ] 分应用列表 500+ 应用滚动流畅度（已加 LruCache+ViewHolder）
- [ ] 俄语界面新增字符串回退中文的问题（见 §7）

## 6. Accessibility 检查

| 项 | 状态 |
|---|---|
| contentDescription 覆盖 | ✅ FAB/状态卡片(随状态切换 acc_connect/acc_disconnect)/扫码/滑动按钮/返回/搜索清除/应用 CheckBox |
| 触控目标 ≥48dp | ✅ 状态卡片 ≥96dp、按钮 48dp、列表行 56-64dp、滑动按钮 64dp |
| 对比度 | ✅ 全部配色经角色配对（primary 系实测 5.17:1/8.26:1，状态色 5.43:1~10.15:1） |
| 字体缩放 | ✅ 无固定行高+sp 单位，随系统缩放；TextInputLayout hint 浮动 |
| 语义层级 | ✅ 标题走 TitleLarge/Medium，正文 BodyMedium，标签 LabelMedium/Large |

## 7. 已知问题 / 风险

1. **俄语翻译缺口**：新增 60+ 字符串仅在默认（中文）定义，俄语用户将看到中文回退。需翻译来源（redesign-plan §13 已记录）。
2. **M3 默认样式漂移**：M2→M3 后部分组件默认形状/高度由 M3 接管（如旧 Button 默认圆角 4dp→20dp）。已逐一显式指定样式，但个别间距细节建议真机复核。
3. **SharedPreferences MODE_MULTI_PROCESS** 既有可靠性问题未动（业务层，超出 UI 重构边界）。
4. **QR 导入对话框**在 M3 主题下的按钮配色继承主题（primary TextButton），未单独定制。

## 8. 回归清单核对结果（代码级）

| 流程 | 核对结果 |
|---|---|
| 连接/断开流转（STATUS_STARTING/STARTED/ERROR/STOPPED → 卡片状态） | ✅ 广播映射逐分支核对 |
| 导入（gcm://ech://xtunnel:// 解析 + 参数容错） | ✅ importFromProtocol 未改动 |
| 导出（URI 构建 + QR） | ✅ exportProfile/showExportDialog 未改动 |
| 编辑保存（协议校验规则） | ✅ savePrefs 判定逻辑未改动，仅反馈方式 inline 化 |
| 分应用选择保存 | ✅ prefs.setApps 同路径，显式保存+onDestroy 兜底去重 |
| 设置校验（端口≥1024、bypass 规则） | ✅ 语义保留 |
| 主题切换（三模式） | ✅ ThemeManager 未改动，入口保留 |
| 60s 启动超时 | ✅ 超时路径保留并升级为横幅 |
