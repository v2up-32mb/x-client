# AGENTS.md

> 面向任意 AI 编程代理的项目协作指引。读完这一份即可开始有效工作。
> Claude Code 用户：本文件与 `CLAUDE.md` 内容互补，`CLAUDE.md` 侧重协议细节与历史，
> 本文件侧重「操作约束」「验证流程」「易踩坑点」。

## 一句话定位

X Client 是一个 **Android 多协议 VPN 客户端**：每个 Profile（节点）独立选择代理协议
（GCM / X-Tunnel），共享同一套 VPN 框架、节点管理、路由绕过与全局设置。协议核心由 Go
编写（`golib/`），通过 `gomobile bind` 编译为 AAR；VPN 通道基于 hev-socks5-tunnel。

## 关键约束（务必先读）

1. **本地无法编译 Android。** 本机没有 Android SDK / NDK。一切 Android 编译都通过
   **GitHub Actions** 云端完成。本机只能：
   - 跑 Go 测试：`cd golib && go test ./...`（需要 Go 1.25.5，本机 1.23 可跑 `shared/`）
   - 跑 app 的纯 JVM 单元测试（CI 跑 `./gradlew test`；本机缺 Android SDK 无法直接跑）
   - 你**绝不能**尝试本地 `./gradlew assemble*`，会失败。
2. **不要改 `golib/`（Go 核心库）除非任务明确要求。** 它是稳定的能力层，14100+ 行 +
   完整测试。重构/新功能默认只动 Android 层（`app/`）。
3. **hev-socks5-tunnel 不入库。** 它在 CI 阶段 `git clone` 到 `app/src/main/jni`。
   本地该目录不存在是正常的，别试图补全或报错。
4. **重构分支 `refactor/kotlin-compose` 未合并 `main`。** 在该分支上工作，等用户明确
   指令再合并。不要自行合并或删除分支。
5. **`xclient.aar` 不入库。** CI 通过 `gomobile bind` 生成到 `app/libs/xclient.aar`。
   `app/build.gradle.kts` 对该文件做了「存在才引入」处理，本地缺失时不阻断配置阶段。

## 技术栈速览

| 层 | 技术 | 备注 |
|---|---|---|
| Android UI | Kotlin 2.0.21 + Jetpack Compose + Material3 | 单 Activity + navigation-compose 多屏 |
| 架构 | MVVM + Repository | `@HiltViewModel` + `StateFlow` + `collectAsState` |
| 依赖注入 | Hilt 2.52 (KSP) | `@HiltAndroidApp` / `@AndroidEntryPoint` |
| 持久化 | DataStore Preferences（**MultiProcessDataStore**） | 跨进程一致，见下方陷阱 |
| 扫码 | CameraX 1.4.0 + ML Kit barcode-scanning 17.3.0 | 扫描用 ML Kit，不再用 ZXing 扫描 |
| 二维码生成 | ZXing core 3.5.3 | 仅导出配置时生成 QR |
| 图片 | Coil | |
| Go 核心 | Go 1.25.5 + gomobile | `golib/`，module name `xclient` |
| VPN 隧道 | hev-socks5-tunnel (NDK) | `:vpn` 独立进程 |

## 目录结构

```
app/src/main/java/com/x/client/app/
├── data/
│   ├── model/        # 常量(Limits/Protocol/ThemeMode/LogLevel)、ProfileConfig、GlobalSettings、ProfileUriCodec、XtAdvancedParams
│   ├── prefs/        # GlobalSettingsDataStore、ProfileDataStore（MultiProcess）、Keys、ProcessUtil
│   └── repository/   # ProfileRepository、SettingsRepository、XclientBridge（封装 gomobile AAR）
├── di/               # DataStoreModule（MultiProcessDataStoreFactory）
├── ui/
│   ├── theme/        # XClientTheme + ThemeManager
│   ├── nav/          # AppNavHost + Routes
│   ├── profiles/     # ProfileListScreen + ProfileEditScreen（+ ViewModel）
│   ├── settings/     # SettingsScreen（+ ViewModel）
│   ├── log/          # RuntimeLogScreen
│   ├── applist/      # AppListScreen（分应用代理）
│   ├── scan/         # ScanScreen（CameraX + ML Kit）
│   ├── common/       # generateQrCode
│   └── user/         # AppViewModel（全局主题模式）
├── vpn/              # TProxyService（VpnService，:vpn 进程）+ ServiceReceiver
├── MainActivity.kt   # 唯一 Activity（Compose 入口）
└── XclientApplication.kt
```

## 验证流程（每次改动后建议跑）

```bash
# 1. Go 核心库测试（本机可跑，快速回归）
cd /opt/x-client/golib && go test ./...

# 2. Kotlin 语法/引用自检（无 Android SDK 时只能靠静态检查）：
#    确认 import 完整、无重复、无引用已删除的符号
cd /opt/x-client && grep -rn "import" app/src/main/java | grep -v "^[^:]*:import" | head  # 抽查异常 import

# 3. 提交到 refactor/kotlin-compose 分支后，GitHub Actions 自动触发 Build Debug
#    在 Actions 页面查看 build 结果；失败时下载 build-reports artifact 排查
```

**不要**在本地跑 `./gradlew` 任何任务（会因缺 SDK 失败）。

## 易踩坑点（高频）

### 1. DataStore 多进程 —— 不要用 SharedPreferences
`TProxyService` 运行在 `:vpn` 独立进程（见 `AndroidManifest` `android:process=":vpn"`）。
主进程 UI 与 :vpn 服务共享同一份设置，**必须**用 `MultiProcessDataStoreFactory`
（见 `di/DataStoreModule.kt`）。旧项目用 `SharedPreferences.MODE_MULTI_PROCESS`，该模式
已被官方标记不可靠，重构已迁移到 MultiProcessDataStore。

约束（官方要求）：
- 同一文件在**单个进程内只创建一个实例**（由 Hilt `@Singleton` 保证）。
- 主进程与 :vpn 各有独立 `SingletonComponent`，天然隔离，无需特殊处理。
- 不要混用 `SingleProcessDataStore` 和 `MultiProcessDataStore` 访问同一文件。

### 2. `:vpn` 进程读设置要用 `runBlocking`
`TProxyService.startVpn()` 在 :vpn 进程同步启动 VPN，需同步读取设置/Profile。
使用 `runBlocking { globalStore.snapshot() }` / `runBlocking { profileStore.getProfile(id) }`。
`XclientBridge` 的同步调用同样在启动线程内。

### 3. `:vpn` 进程退出要 `killProcess`
`TProxyService.onDestroy()` 在正常停止路径末尾调用
`android.os.Process.killProcess(android.os.Process.myPid())`，确保下一次 VPN 会话以全新
进程从磁盘重新加载 DataStore（Android 多进程缓存不自动刷新，陈旧缓存会覆盖主进程
新写入）。**不要移除这行**。

### 4. gomobile AAR 的类名前缀是 `xclient.Xclient`
`XclientBridge.kt` 通过 `xclient.Xclient.startSocksProxy(...)` 调用 Go。本地无 AAR 时
编译会失败——这是预期的，由 CI 生成 AAR 后解决。不要把对 `xclient.Xclient` 的引用
改成别的包名。

### 5. native 方法必须 `@JvmStatic external`
`TProxyService` 的 `TProxyStartService` 等 native 方法声明在 `companion object` 内并
标注 `@JvmStatic external fun`（对应 hev-socks5-tunnel 的 C 接口）。实例 `external fun`
不会生成 JNI static 签名，链接会失败。

### 6. Android 14+ VpnService 必须声明 specialUse 前台类型
`AndroidManifest` 中 `TProxyService` 已声明 `android:foregroundServiceType="specialUse"` +
`<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="VPN service"/>`，
`startForeground` 在 API 34+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`。修改时不要遗漏。

### 7. URI 导入导出格式必须向后兼容
`ProfileUriCodec`（`data/model/ProfileUriCodec.kt`）支持 `gcm://` / `ech://`(兼容) /
`xtunnel://`。修改编解码逻辑时务必保证：
- 旧参数别名兼容：`relay`→`ip`、`fallbackip`→`fip`、`hotpair=1`/`true`/`yes`→启用1对、
  `hotpair=2..8`→启用 N 对、`domain`/`dns` 忽略（现属全局设置）。
- `ProfileUriCodecTest.kt` 是回归基线，改动后 CI 会跑。

### 8. VPN 运行时禁止修改当前配置/全局设置
原 Java 行为：`settings.enable == true` 时，当前 Profile 的编辑页和全局设置页全部禁用。
Compose 重构沿用此约束（`ProfileEditScreen` / `SettingsScreen` 的 `readOnly`/`enabled`）。
不要为了让 UI "更友好"而放开。

### 9. X-Tunnel 高级参数单位换算
收集：秒→毫秒(`*1000` round)、MB→字节(`*1048576`)、端口 1-65535 校验；留空表示默认值。
见 `ProfileEditScreen.collectAdvanced()` 与 `XtAdvancedParams.toParamsMap()`。改动注意
`ProfileDataStore` 的 JSON 编解码往返（`dialTimeout` 等以毫秒 Long 存储）。

### 10. Go 测试改 Go 代码才需要跑
`golib/` 的测试与 Android 层无关。只改 `app/` 下的 Kotlin 不需要跑 `go test`。
但如果改了 `golib/`，务必 `cd golib && go test ./...`（部分包需 Go 1.25.5）。

## CI 工作流

| 文件 | 触发 | 产物 |
|---|---|---|
| `.github/workflows/build-debug.yml` | push 到 `main`/`develop`/`feat/*`/`fix/*`/`repair/*`/`refactor/**` + 手动 | 4 ABI debug APK + 测试报告 |
| `.github/workflows/release.yml` | push `v*` tag + 手动 | 签名 APK + GitHub Release（版本号取 tag、VERSION_CODE 取提交计数） |
| `.github/workflows/check-keystore.yml` | 手动 | 验证签名密钥 secrets |

CI 步骤要点：clone hev-socks5-tunnel → 条件编译 xclient.aar（gomobile bind，Go 1.25）
→ `./gradlew test assembleDebug/Release` → 上传 artifact / 签名 / 发布。

## 提交规范

- Conventional Commits：`feat(scope):` / `fix(scope):` / `refactor(scope):` / `docs:` / `build:` / `chore:`
- scope 常用：`xtunnel` / `gcm` / `ui` / `vpn` / `ci`
- Release Notes 由 `release.yml` 按前缀自动分类，请保持提交信息规范。

## 常见任务速查

- **新增一个 Profile 字段**：`data/model/ProfileConfig.kt` 加属性 → `Keys.kt` 加键 →
  `ProfileDataStore` save/get/toProfileConfig → `ProfileEditScreen` 表单 →
  `TProxyService.buildGCMParams/buildXtunnelParams` 传给 Go → `ProfileUriCodec` 导入导出
  （如需分享）→ 测试。
- **新增一个全局设置**：`GlobalSettings.kt` → `Keys.kt` → `GlobalSettingsDataStore` →
  `SettingsRepository` → `SettingsScreen` → `TProxyService` 使用。
- **新增屏幕**：`ui/<name>/` 下建 `XxxScreen.kt`（+ `@HiltViewModel`）→
  `ui/nav/AppNavHost.kt` 加 `Routes` 常量 + `composable{}`。
- **改 VPN 启动参数**：`TProxyService.buildGCMParams` / `buildXtunnelParams`，对照
  `golib/android.go` 的参数键名（`worker_host` / `server_addr` / `bypass_*` 等）。

## 参考：SparePartsWarehouse

本项目重构对标 `v2up-32mb/spareparts-warehouse`（同作者），其技术栈为成熟模板：
Kotlin + Compose + Material3 + Hilt + Room + DataStore + CameraX + ML Kit + CI 编译。
本项目的 Compose 屏幕模式（`ScanScreen`、ViewModel + StateFlow、导航）参照其实现。
