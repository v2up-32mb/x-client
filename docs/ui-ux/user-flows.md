# User Flow Map

## 1. 首次启动（无 Profile）

入口：ProfileListActivity onCreate → recyclerView 显示空列表
步骤：
1. ProfileListActivity 加载 prefs.getProfileList()
2. 列表为空时 RecyclerView 无条目，无 empty 提示
3. FAB 点击显示 PopupMenu → 新增 / 导入
分支：
- 新增：showAddProfileDialog → UUID 创建空 Profile → 启动 ProfileEditActivity EXTRA_IS_NEW_PROFILE=true
- 导入：showImportDialog → 手工输入 / 扫描二维码
出口：跳转 ProfileEditActivity 填写配置
当前问题：无空态引导；新增后直接进入编辑页无说明；无 loading 态

## 2. 导入配置

入口：ProfileListActivity → FAB → 导入 或 ProfileEditActivity → btn_import
步骤：
ProfileListActivity.importProfile → showImportDialog
→ showManualInputDialog EditText 或 scanQrCode 启动 CustomCaptureActivity
→ onActivityResult 解析 protocol 串
分支：
- gcm:// / ech:// / xtunnel:// 解析成功 → showImportNameDialog → prefs.addProfile + 设置参数
- 解析失败 Toast "无效的协议格式"
出口：refreshProfileList
问题：扫描无失败重试提示；手动输入无格式校验提示；xtunnel 参数部分不写入 URI

## 3. 连接 VPN 主流程

入口：ProfileListActivity btn_start
步骤：
1. 检查 prefs.getCurrentProfileId() workerHost 非空
2. VpnService.prepare 权限请求
3. doStartVpn → 启动 TProxyService ACTION_CONNECT
4. BroadcastReceiver ACTION_STATUS 接收 STATUS_STARTING / STARTED / ERROR / STOPPED
5. updateStartButton 显示 启动中 / 启动 / 停止 并改背景色
分支：
- 权限拒绝 → Toast VPN 权限被拒绝
- 启动超时 60s → Handler vpnStartupTimeout → prefs.setEnable(false)
- VPN 运行中禁止切换 Profile / 编辑 / 删除
出口：按钮状态更新，通知显示
问题：启动按钮颜色硬编码，与主题不一致；无进度指示；超时提示文字中文硬编码

## 4. 编辑 Profile

入口：ProfileListActivity swipe → 编辑 / 列表点击进入 ProfileEditActivity
步骤：
1. loadProfileData 临时切换 currentProfileId 读取 prefs
2. spinner_protocol 根据 prefs.getProtocol 设置
3. GCM 字段 worker_host / pref_ip / user_id / fallback_ip / disable_ech / disable_ipv6_route / ws_conn / enable_dynamic_pool / dynamic_pool_max
4. X-Tunnel 字段 xt_server_addr / xt_token / xt_relay_nodes / xt_connections / xt_disable_ech / xt_insecure / xt_enable_hot_pair / xt_hot_pair_count / 高级参数折叠
分支：
- VPN 运行且是当前配置 → 输入框全部 disabled，btn_save disabled，Toast 提示
- 保存时协议校验：X-Tunnel 必须 wss://；GCM worker_host 非空
- 新配置未保存返回 → onBackPressed 删除临时 Profile
出口：finish 返回列表
问题：X-Tunnel 高级参数 UI 无默认值提示；折叠状态未持久化；字段校验信息分散

## 5. 分应用代理

入口：SettingsActivity button_apps → AppListActivity
步骤：
1. AppListActivity 加载已安装含 INTERNET 权限应用
2. ListView choiceMode multiple，adapter 根据 prefs.getApps 初筛 selected
3. 搜索框实时 filter
4. onDestroy 收集 selected packageNames → prefs.setApps
分支：未授予 QUERY_ALL_PACKAGES 导致列表不全
问题：appitem.xml 使用 100px / 50px 硬编码像素；无 empty 态；取消未保存时无提示

## 6. 设置

入口：ProfileListActivity FAB → 设置
步骤：
SettingsActivity loadSettings 读取全局代理、SOCKS 端口、bypass 选项、ECH DNS/域、dns warmup、日志等级、show_server_addr
分支：
- VPN 运行中 → 全局控件 disabled，Toast "VPN 正在运行，无法修改全局设置"
- 保存时校验端口 ≥1024，bypassRules 通过 Xclient.validateBypassRules
出口：finish
问题：show_server_addr 开关实时生效无提示；日志等级变更需重启 VPN 才生效无提示

## 7. 运行日志

入口：FAB → 查看本次运行日志
步骤：
RuntimeLogActivity onStart 注册 receiver → requestLogs 发送 ACTION_REQUEST_RUNTIME_LOGS
→ TProxyService 回复 ACTION_RUNTIME_LOGS 或 2s 超时
→ renderLogs 显示行数计数或 empty 文案
→ 复制按钮复制内容
问题：仅在服务存活时获取；无分页，长日志卡顿

## 8. 错误路径

- 无配置启动 VPN → Toast "当前配置的服务器地址为空"
- 删除当前配置且 VPN 运行 → Toast 阻止
- 导入链接缺少 host → Toast "链接缺少服务器地址，无法导入"
- 网络切换 → TProxyService 注册 NetworkCallback，可自动重连
- 配置列表显示服务器地址开关关闭 → textServerAddr 显示 "••••••"，但无视觉区分

### 总结体验问题
1. 空态缺失，首次用户迷失
2. 硬编码颜色导致主题不一致
3. VPN 运行状态下编辑限制仅 Toast，无视觉 disabled 提示
4. 错误提示分散，缺乏 inline 校验
5. 扫描/导入流程无失败重试与格式帮助
