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

**所有 APK 编译验证走 GitHub Actions**（本地仅可验证 Go 侧与 AAR 构建）：

| 工作流 | 触发 | 说明 |
|---|---|---|
| `build-aar.yml` | main 推送（`golib/**`）/ 手动 | gomobile AAR 构建，预热默认分支缓存 |
| `release.yml` | `v*` tag / 手动 | tag=构建+签名+发版；手动=release 类型 CI 构建验证（unsigned，不发版） |
| `check-keystore.yml` | 手动 | 验证签名密钥 secrets |
