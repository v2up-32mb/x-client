# 架构与风险

## 技术栈明细

- AGP/ Gradle：AGP 8.x，Java 17，minSdk 24 / targetSdk 34
- UI 框架：XML Views，Material Components 1.9.0，Theme.MaterialComponents.DayNight.NoActionBar
- 依赖：appcompat 1.6.1，recyclerview 1.3.2，zxing 扫码，golib gomobile AAR
- 无 Compose / 无 Navigation Component / 无 ViewModel / 无 Flow / 无 DI
- 包名 com.x.client.app

## UI 状态流向

TProxyService 运行于 :vpn 进程，通过本地广播传递状态：
- 服务启动 → sendBroadcast ACTION_STATUS，EXTRA_STATUS = STATUS_STARTING / STARTED / ERROR / STOPPED
- ProfileListActivity 注册 vpnStatusReceiver ContextCompat.registerReceiver RECEIVER_NOT_EXPORTED，接收后更新 prefs.setEnable 与按钮状态
- 日志请求：RuntimeLogActivity 发送 Intent ACTION_REQUEST_RUNTIME_LOGS 到 TProxyService，服务回复 ACTION_RUNTIME_LOGS 广播
- 网络变化：TProxyService 注册 ConnectivityManager.NetworkCallback，网络丢失时触发重连
- 配置变更：Preferences SharedPreferences MODE_MULTI_PROCESS，各 Activity 直接读写，无统一 Repository

## 可复用组件与一次性代码

可复用：
- SwipeRevealLayout 自定义滑动揭示布局，被 item_profile_swipe 复用
- ScannerOverlayView 自定义扫描遮罩
- Preferences 集中 SharedPreferences 访问，ProfileInfo 数据类
- ThemeManager 统一主题切换 AppCompatDelegate

一次性 / 耦合：
- ProfileAdapter 强耦合 SwipeRevealLayout 与 ProfileActionListener，滑动逻辑内聚在 Adapter
- ProfileListActivity 包含导入导出、QR 生成、VPN 启动全部逻辑，职责臃肿
- ProfileEditActivity 协议切换通过手工显隐 LinearLayout，字段校验散落在 savePrefs

## UI 重构风险清单

1. SwipeRevealLayout 与 ProfileAdapter 耦合
   - 滑动监听、打开状态管理存于 Adapter，改动动画或交互需同时修改布局与 Adapter
   - 打开关闭状态通过 currentlyOpenedLayout 单例管理，重用 RecyclerView 时可能错位

2. ThemeManager 机制
   - ThemeManager.applyMode 直接调用 AppCompatDelegate.setDefaultNightMode，切换需重启 Activity
   - styles.xml 硬编码 statusBarColor #FFEFEFEF / #FF121212，难以通过设计 Token 覆盖

3. 布局硬编码颜色多处
   - activity_profile_list.xml btn_start backgroundTint #4CAF50，textColor #FFFFFF
   - item_profile_swipe.xml 四个按钮背景 #4CAF50/#FF9800/#2196F3/#F44336
   - ScannerOverlayView 颜色 0x80000000 等硬编码，无法跟随主题

4. Preferences 多进程缓存
   - SharedPreferences MODE_MULTI_PROCESS 在 Android 7+ 不可靠，:vpn 进程可能持有陈旧缓存，导致全局设置被覆盖

5. Manifest 残留 MainActivity
   - AndroidManifest.xml 声明 .MainActivity 但无 Java 文件，安装包可能在特定路径崩溃

6. 硬编码像素单位
   - appitem.xml ImageView 100px，TextView 50px，触控目标与密度不匹配

7. 配置切换限制
   - VPN 运行中禁止编辑/删除当前配置，仅 Toast 提示，无 UI 视觉 disabled，误操作风险

## ThemeManager 实现机制

ThemeManager.java:
- 读取 Preferences.THEME_MODE：THEME_SYSTEM=0, THEME_LIGHT=1, THEME_DARK=2
- setMode → prefs.setThemeMode → applyMode
- applyMode 根据模式调用 AppCompatDelegate.setDefaultNightMode MODE_NIGHT_NO / YES / FOLLOW_SYSTEM
- ProfileListActivity.setupToolbar 中通过 prefs.getThemeMode 更新 Toolbar 菜单图标 ic_light_mode / ic_dark_mode / ic_system_mode

限制：无运行时主题 Token 切换，颜色仍依赖 xml 硬编码与代码中 ColorStateList.valueOf 0xFFxxxxxx

## 设计系统缺口

- 无 primitive→semantic→component 三层 Token，组件直接使用硬 hex
- 配色混乱：toolbar_background #0BA3F3 与 fab_background #2B63A5 两种蓝并存
- 字号/字重未统一，item_profile 16sp bold 与 item_profile_swipe 14sp bold 不一致
- 触控目标：部分按钮 48dp 合格，FAB mini 可能小于 48dp
- 无 empty/error/loading 规范组件
