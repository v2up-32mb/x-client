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

**所有 Go / AAR / APK 编译和测试均走 GitHub Actions**（本地仅允许静态检查）：

| 工作流 | 触发 | 说明 |
|---|---|---|
| `build-aar.yml` | main 推送（`golib/**`）/ 手动 | gomobile AAR 构建，预热默认分支缓存 |
| `release.yml` | `v*` tag / 手动 | tag=构建+签名+发版；手动=release 类型 CI 构建验证（unsigned，不发版） |
| `check-keystore.yml` | 手动 | 验证签名密钥 secrets |

## 全局优选 IP（首版）

入口：主页「更多 → 优选 IP」或节点编辑页的「管理全局优选 IP」。`PreferredIpActivity`
提供来源 / 测试 / 已选三个页签，沿用现有 Material 3 语义色与触控尺寸。

- 官网 IPv4 默认入口、官网 IPv6 可选（检查本机直连 IPv6 地址），`776771.xyz/all.txt`
  仅预置，不会自动抓取；支持其他 HTTPS 列表、粘贴、UTF-8 TXT（2MiB）和旧节点入口收集。
  解析通过固定 Go JSON API，错误行显示原文及行号，必须明确确认只导入有效行。
- 测试使用实际参考 Profile 的最终目标 / ECH / DNS；XT 高级 JSON 先合并。
  默认 CIDR 抽样256、并发8（最多32），推荐最多3个。前5个顺序下载，每IP最多
  5秒/20MiB，总正文最多100MiB，移动/计费网络再次确认。探测及ECH/DNS有额外开销。
- 真实任务每500ms在工作线程查询：`startPreferredIPTask` / `getPreferredIPTask` /
  `cancelPreferredIPTask`，列表解析使用 `parsePreferredIPList`。UI不生成假进度。
  首版必须断开所有可检测VPN；不支持VPN中物理网络桥接。旋转保留控制器和taskId，
  离开页面/后台取消，网络切换取消并标旧结果；进程死亡后不自动启动新任务。
- 保存前每批最多3个已选IP，对需要接管的实际Profile目标做无下载兼容检查
  (`download_top=0`, `recommend_count=0`)。只在同一页面会话、完整测试、同网络及
  相同目标/ECH/DNS时复用结果。确认页列出接管范围、例外、XT最终连接数乘法。
  每个节点联网前独立校验高级JSON、入口冲突、最终目标和合并后的连接数；坏节点留独立并列出
  具体原因，不阻断正常节点。XT连接数沿用编辑页的1–16，GCM沿用1–64；接受Go支持的整数标量/
  整数字符串，不用 `getInt()` 截断小数。取消/网络变化仍中止整轮，零兼容节点不替换旧池。
  仅HTTP/CF trace通过，不等同WebSocket升级/认证或完整VPN速度。
- 应用私有 `preferred-ip-v1.json` 由UI通过 `AtomicFile` 写入，含 schema/revision、来源快照、
  候选、已选、最近测试、模式映射、按实际目标的兼容地址和接管前模式。保存使用revision
  比较；UI与`:vpn`均先对永久独立的 `preferred-ip-v1.lock` 取OS `FileLock`，再执行完整
  读取/恢复/写入事务（包括API 24的 `.bak` 协议）。`openRead()`只在锁内恢复，写入先fsync，
  模式复制/删除/恢复与池保存使用同一协议；内部 `readLocked` 不重复取锁，避免同进程重叠锁。
  不删除/替换锁文件，不依赖SharedPreferences多进程缓存。损坏库不覆盖；服务保留原入口并记录原因。
- 编辑页支持跟随 / 独立 / 域名默认。原 `PrefIp` / `XtRelayNodes` 保留；GCM
  `FallbackIp` 不参与。未知目标不接管；目标变更使验证失配，运行参数回到原值，需重新验证。
  XT高级JSON含`relay_nodes`时强制独立，可明确确认删除该覆盖键；其他未知高级键不因编辑丢失。
  编辑/冲突删除/URI导入/保存共用高级JSON草稿，旋转后仍保留；导入XT链接前列出并确认删除
  `server_addr/token/relay_nodes/connections` 等链接字段的旧高级覆盖（ECH域名/DNS恢复全局设置），
  取消不修改表单。完整URI缺少或留空 `ip/relay_nodes` 均清空旧入口，并显式保存为独立快照。
- `PreferredIpResolver`统一模式优先级，服务在高级JSON合并后应用池，分享URI导出当时有效
  入口快照（无备注/继承绑定）。`Preferences.forProfile(id)`提供不改变当前Profile指针的
  独立访问器，编辑、导入、分享和遍历验证不再通过持久切换当前节点读取其他节点。
- 更新来源或取消测试不修改已选池。全局保存/禁用均下次连接生效，不自动重连；
  「恢复原配置 / 禁用全局」恢复接管前模式，仍保留池与原IP。复制/删除节点同步处理模式。

验证边界：本地仅进行了源码/XML/资源引用静态检查。新AAR API绑定、Android编译、真机的
深浅主题/字体放大/横屏/网络切换/VPN检测和跨进程文件读写需集成后在GitHub Actions及设备验证。

### 优选 IP 审查修复：静态回归场景

以下是源码路径检查与后续设备验收输入，**不是已运行的Android测试**，不引入测试依赖。
本地只能运行下方Python源码断言；真正的并发时序、Activity旋转/对话框及协议运行结果需CI/设备验证。

| 场景/输入 | 应保持的结果与检查位置 |
|---|---|
| API 24写者在 `startWrite` 后暂停，VPN读者读取；另测写者进程死亡并遗留 `.bak` | `PreferredIpStore.withLock` 在存在性判断之前取独立文件锁；读者不能打开在写base；进程死亡释放锁后 `readLocked/openRead` 恢复完整旧版本。现代系统 `.new` 恢复亦在锁内。 |
| `setMode/copyMode/removeMode`、保存池、禁用恢复，与另一进程读者交错；陈旧revision提交 | 内部只调用 `readLocked/writeLocked`，不嵌套 `withLock`；CAS与写后读取在同一锁内。禁用/池保存均经 `write`；陈旧提交报错，不能覆盖新模式。 |
| 旧高级JSON为 `{"server_addr":"wss://old.example","token":"old","relay_nodes":"1.1.1.1:443","connections":8,"custom_key":"keep"}`，导入 `xtunnel://new.example?token=new` | 弹窗列出前4个覆盖键；取消时表单/草稿/模式均不变。确认后保存实际server/token来自链接，relay为空，connections为默认3，`custom_key`仍保留；高级表单其他修改仍可保存。 |
| 删除高级relay冲突→普通高级字段编辑→导入第二个链接→旋转→保存；或退出不保存 | 所有删除只改 `xtAdvancedDraft`；保存不重读旧JSON复活覆盖键。旋转恢复草稿与模式，退出未保存不改旧JSON。即使导入前为隐式独立，保存也明确写入independent。 |
| 旧 `PrefIp/XtRelayNodes` 非空，导入GCM/XT链接：不含入口参数，或 `ip=` / `relay_nodes=` | 两种形式都无条件写空输入框，保存为空入口；XT高级relay覆盖必须先确认删除。GCM出口FallbackIp不参与本修复。 |
| 混合节点：损坏高级JSON、`relay_nodes`覆盖、错误target类型/非法端口，另有正常节点 | `prepareSave` 的逐节点try/catch覆盖这些检查；坏节点independent且scope含具体原因，无探测；正常节点继续校验和保存。全坏时保留旧池并报告各节点原因。 |
| XT高级connections取 `null/true/{}/[]/"abc"/"3.5"/3.5/0/-1/17`；有效取 `1/16/"3"/3.0` | 前组拒绝且不探测，后组得到1/16/3/3；检查在base+advanced合并后、任何缓存复用及 `run` 前。GCM范围1–64，乘法使用已验证整数。 |
| 参数异常时/探测途中/缓存复用结束时取消或切网；预览后修改connections为非法 | 各catch和循环边界仍检查generation/network；整轮中止，不生成/提交新池；`commitPreview`再次检查最终连接数。 |

从仓库根目录执行（仅检查源码结构，不编译、不执行Android/Go代码）：

```sh
python3 - <<'PY'
from pathlib import Path
root = Path('app/src/main/java/com/x/client/app')
store, editor, controller, resolver = [
    (root / (name + '.java')).read_text()
    for name in ['PreferredIpStore', 'ProfileEditActivity', 'PreferredIpController', 'PreferredIpResolver']
]
def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]

# Persistent lock surrounds recovery, CAS, write and verification; no nested public read/write.
assert 'new RandomAccessFile(lockFile, "rw")' in store and 'lock.getChannel().lock()' in store
assert 'return withLock(this::readLocked)' in store
assert 'return withLock(() -> writeLocked(data, expectedRevision))' in store
assert 'file.openRead()' in section(store, 'private JSONObject readLocked()', 'public static JSONObject empty()')
assert 'read().getLong' not in store and 'store.read()' not in store and 'store.write(' not in store
assert store.count('store.withLock(() -> {') == 3  # mode set/copy/remove
write = section(store, 'private JSONObject writeLocked(', 'public static String mode(')
assert write.index('readLocked()') < write.index('file.startWrite()') < write.index('stream.getFD().sync()')
assert write.index('file.finishWrite(stream)') < write.rindex('readLocked()')
assert 'lockFile.delete' not in store

# Draft only; import confirmation precedes all form mutation; absent entries clear the widgets.
collect = section(editor, 'private String collectXtAdvancedParams()', 'private void putTimeoutSeconds(')
assert 'parseXtAdvancedDraft()' in collect and 'getXtAdvancedParams' not in collect
assert 'savedInstanceState.getString("xt_advanced_draft")' in editor
assert 'out.putString("xt_advanced_draft", xtAdvancedDraft)' in editor
imp = section(editor, 'private void importFromProtocol(', 'private boolean confirmXtImportOverrides(')
assert imp.index('!confirmXtImportOverrides(protocol)') < imp.index('preferredMode.setSelection(1)')
assert 'if (!relayNodes.isEmpty())' not in imp and 'if (!prefIp.isEmpty())' not in imp
assert 'edittext_xt_relay_nodes.setText(relayNodes)' in imp and 'edittext_pref_ip.setText(prefIp)' in imp
confirm = editor.split('private boolean confirmXtImportOverrides(', 1)[1]
for key in ['server_addr', 'token', 'relay_nodes', 'connections', 'enable_ech', 'ech_domain',
            'dns_server', 'insecure', 'ip_strategy', 'enable_hot_pair', 'hot_pair_count']:
    assert '"' + key + '"' in confirm
assert confirm.index('setPositiveButton') < confirm.index('draft.remove(key)')
assert 'preferredDraftDirty || !preferredInitialImplicit' in editor

# All risky profile validation is inside the per-profile boundary, before the first probe.
prepare = section(controller, 'void prepareSave()', 'private static boolean contains(')
loop = prepare.split('for (Preferences.ProfileInfo info', 1)[1]
validation = section(loop, 'try {', '} catch (Exception invalid)')
for call in ['conflict(p)', 'baseParams(p)', 'target(p, merged)', 'validatedConnections(p, merged)']:
    assert call in validation
assert loop.index('catch (Exception invalid)') < loop.index('JSONObject result = run(')
failed = section(loop, 'catch (Exception invalid)', 'String key =')
assert failed.index('check(epoch, net)') < failed.index('modes.put(info.id, "independent")')
assert 'invalid.getMessage()' in failed and 'continue;' in failed
assert 'int connections = merged.getInt' not in prepare
assert 'int max = xt ? 16 : Preferences.MAX_DYNAMIC_POOL_LIMIT' in resolver
assert 'count != Math.rint(count)' in resolver and 'count < 1 || count > max' in resolver
assert 'value instanceof String' in resolver and 'value instanceof Number' in resolver
assert 'validatedConnections(scoped, merged)' in controller
print('PASS: preferred-IP source guards (not Android runtime tests)')
PY
```
