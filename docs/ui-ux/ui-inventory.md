# UI Surface Inventory

## 总表

| 表面 | 文件位置 | UI 技术 | 关键组件/样式 | 主题/Token 引用 | 硬编码问题 | 状态缺失 | Accessibility 缺口 | 视觉不一致 |
|------|----------|---------|---------------|----------------|------------|----------|-------------------|------------|
| ProfileListActivity | app/src/main/java/com/x/client/app/ProfileListActivity.java | AppCompatActivity + RecyclerView | MaterialToolbar, RecyclerView, Button btn_start, FAB | toolbar_background @color/toolbar_background, fab_background @color/fab_background | activity_profile_list.xml:42 textColor="#FFFFFF", :43 backgroundTint="#4CAF50" ; ProfileListActivity.java:222 0xFFFF9800, :229 0xFFF44336, :232 0xFF4CAF50 | 无 empty/loading 态，列表为空时无提示 | FAB 无 contentDescription | 按钮颜色硬编码 vs token |
| ProfileEditActivity | .../ProfileEditActivity.java | AppCompatActivity | ScrollView, EditText, CheckBox, Spinner | 无 | activity_profile_edit.xml:395 textColor="#FFFFFF", :396 backgroundTint="#FF9800", :405 textColor="#FFFFFF", :406 backgroundTint="#2196F3" | 无 loading/错误态反馈 | 进阶参数折叠未提供 accessibility 提示 | GCM/ X-Tunnel 字段硬编码颜色 |
| SettingsActivity | .../SettingsActivity.java | AppCompatActivity | ScrollView, CheckBox, EditText, Spinner | 无 | activity_settings.xml:47 backgroundTint="#9C27B0", :200 textColor="#FFFFFF", :201 backgroundTint="#2196F3" | 无 | | |
| RuntimeLogActivity | .../RuntimeLogActivity.java | AppCompatActivity | ScrollView, TextView, Button | code_background @color/code_background | 无 | loading 有，empty 有 runtime_logs_empty | btn_log_copy 可访问性 ok | |
| AppListActivity | .../AppListActivity.java | AppCompatActivity | ListView | 无 | appitem.xml:14 android:layout_width="100px" 硬编码像素, :18 android:textSize="50px" | 无 empty/loading | CheckBox 无 contentDescription | 像素单位硬编码 |
| CustomCaptureActivity | .../CustomCaptureActivity.java | AppCompatActivity | DecoratedBarcodeView | 无 | custom_barcode_scanner.xml:25 textColor="#FFFFFF", :27 background="#80000000" | 无 | | |
| item_profile_swipe | res/layout/item_profile_swipe.xml | SwipeRevealLayout | RadioButton, TextView, ImageButton | secondary_text @color/secondary_text, divider @color/divider | btn 背景 #4CAF50/#FF9800/#2196F3/#F44336 硬编码, tint #FFFFFF | 无 disabled 态区分 | ImageButton contentDescription 已设 | 按钮颜色不一致 |
| dialog_export | res/layout/dialog_export.xml | AlertDialog | TextView, ImageView | code_background @color/code_background | 无 | 无错误态 | QR Image 无 contentDescription | |
| ScannerOverlayView | .../ScannerOverlayView.java | Custom View | Canvas 绘制 | 无 | maskColor 0x80000000, frameColor 0xFFFFFFFF, cornerColor 0xFF00FF00 硬编码 ScannerOverlayView.java:23-25, scan line 0x8000FF00:108 | 无 | 无 contentDescription | 颜色为硬编码 |
| SwipeRevealLayout | .../SwipeRevealLayout.java | Custom ViewGroup | Overlay 滑动 | 无 | 无 | 无 | 无 | |
| Menu | res/menu/menu_main.xml | MaterialToolbar menu | action_theme | ic_system_mode | 无 | 无 | 无 | |

### 详细记录

#### ProfileListActivity
- 文件位置：app/src/main/java/com/x/client/app/ProfileListActivity.java
- 布局：res/layout/activity_profile_list.xml
- 组件：MaterialToolbar id toolbar，background @color/toolbar_background, elevation 4dp; RecyclerView id profile_list; Button id btn_start backgroundTint 硬编码 #4CAF50 activity_profile_list.xml:43，textColor #FFFFFF:42; FloatingActionButton id fab_main backgroundTint @color/fab_background
- 主题：AppTheme MaterialComponents.DayNight.NoActionBar
- 硬编码颜色/尺寸：btn_start 背景在代码中动态 setBackgroundTintList 0xFFFF9800:222, 0xFFF44336:229, 0xFF4CAF50:232；Toolbar 标题通过 SpannableString 动态生成，版本号 alpha 硬编码
- 状态：无 empty/loading/error 态，列表为空时 RecyclerView 空白
- Accessibility：FAB 无 contentDescription；RadioButton clickable false 但无 label
- 不一致：两种蓝并存 toolbar_background #0BA3F3 colors.xml 与 fab_background #2B63A5 colors.xml

#### ProfileEditActivity
- 布局 res/layout/activity_profile_edit.xml
- 组件：Spinner protocol_spinner，LinearLayout gcm_fields / xtunnel_fields 动态显隐
- 硬编码：btn_import backgroundTint #FF9800:396，btn_save backgroundTint #2196F3:406
- 状态缺失：保存失败仅 Toast，无 inline 错误
- Accessibility：CheckBox 文本长但无帮助说明

#### SettingsActivity
- 布局 res/layout/activity_settings.xml
- 硬编码：button_apps backgroundTint #9C27B0:47，btn_save backgroundTint #2196F3:201
- 状态：VPN 运行中禁用全量控件，提示 Toast，但无 disabled visual 区分

#### RuntimeLogActivity
- 布局 res/layout/activity_runtime_log.xml
- 组件：ImageButton btn_log_back，Button btn_log_refresh / btn_log_copy，ScrollView
- 状态：loading runtime_logs_loading，empty runtime_logs_empty，已处理
- Accessibility：btn_log_back contentDescription @string/back

#### AppListActivity
- 布局 res/layout/activity_app_list.xml，item appitem.xml
- 硬编码像素：ImageView 100px, TextView textSize 50px appitem.xml
- 状态缺失：无 empty 态
- Accessibility：ListView choiceMode multiple，但无 spoken feedback

#### CustomCaptureActivity / ScannerOverlayView
- 布局 custom_barcode_scanner.xml 硬编码 textColor #FFFFFF:25，background #80000000:27
- ScannerOverlayView 硬编码颜色 0x80000000:23, 0xFFFFFFFF:24, 0xFF00FF00:25
- 无 loading/error 态

#### SwipeRevealLayout + item_profile_swipe
- 自定义 ViewGroup，前景层 id foreground_layout
- ImageButton 背景硬编码 #4CAF50:94, #FF9800:105, #2196F3:116, #F44336:127，tint #FFFFFF
- contentDescription 已设
- 状态缺失：无 disabled visual，VPN 运行中仅 Toast 提示

#### Theme / Styles
- res/values/styles.xml AppTheme statusBarColor #FFEFEFEF:4, navigationBarColor #FFEFEFEF，windowLightStatusBar true
- res/values-night/styles.xml statusBarColor #FF121212
- 硬编码颜色导致与 Material 2 主题不一致

#### Manifest 问题
- AndroidManifest.xml 声明 .MainActivity 但 Java 目录无对应文件，导出可能导致启动崩溃

