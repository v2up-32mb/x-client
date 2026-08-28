package com.x.client.app.ui.profiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.x.client.app.data.model.Limits
import com.x.client.app.data.model.ProfileConfig
import com.x.client.app.data.model.ProfileInfo
import com.x.client.app.data.model.ProfileUriCodec
import com.x.client.app.data.model.Protocol
import com.x.client.app.data.repository.ProfileRepository
import com.x.client.app.data.repository.SettingsRepository
import com.x.client.app.ui.common.generateQrCode
import com.x.client.app.ui.user.AppViewModel
import com.x.client.app.vpn.TProxyService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileListUiState(
    val profiles: List<ProfileInfo> = emptyList(),
    val currentProfileId: String? = null,
    val enable: Boolean = false,
    val vpnStarting: Boolean = false,
)

@HiltViewModel
class ProfileListViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _vpnStarting = MutableStateFlow(false)

    val state: StateFlow<ProfileListUiState> = combine(
        profileRepository.profileList,
        profileRepository.currentProfileId,
        settingsRepository.settings,
        _vpnStarting,
    ) { list, currentId, settings, starting ->
        ProfileListUiState(
            profiles = list,
            currentProfileId = currentId,
            enable = settings.enable,
            vpnStarting = starting,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileListUiState())

    fun onVpnStatus(status: String) {
        when (status) {
            TProxyService.STATUS_STARTING -> _vpnStarting.value = true
            TProxyService.STATUS_STARTED -> {
                _vpnStarting.value = false
                viewModelScope.launch { settingsRepository.setEnable(true) }
            }
            TProxyService.STATUS_ERROR, TProxyService.STATUS_STOPPED -> {
                _vpnStarting.value = false
                viewModelScope.launch { settingsRepository.setEnable(false) }
            }
        }
    }

    fun selectProfile(id: String) {
        if (state.value.enable) return // VPN 运行中禁止切换
        viewModelScope.launch { profileRepository.setCurrentProfileId(id) }
    }

    fun addNewProfile(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = profileRepository.addProfile("新配置")
            onCreated(id)
        }
    }

    fun copyProfile(id: String, name: String) {
        viewModelScope.launch { profileRepository.copyProfile(id, name) }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            profileRepository.removeProfile(id)
        }
    }

    fun importProfile(uri: String, onResult: (ProfileUriCodec.ImportResult?) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(ProfileUriCodec.parse(uri))
            } catch (e: ProfileUriCodec.InvalidProtocolException) {
                onResult(null)
            }
        }
    }

    fun saveImported(result: ProfileUriCodec.ImportResult, name: String) {
        viewModelScope.launch {
            val id = profileRepository.addProfile(name)
            val config = profileRepository.getProfile(id)
            val updated = if (result.protocol == Protocol.XTUNNEL) {
                config.copy(
                    name = name,
                    protocol = Protocol.XTUNNEL,
                    xtServerAddr = result.serverAddr,
                    xtToken = result.xtToken,
                    xtRelayNodes = result.xtRelayNodes,
                    xtConnections = result.xtConnections,
                    xtDisableEch = result.xtDisableEch,
                    xtInsecure = result.xtInsecure,
                    xtEnableHotPair = result.xtEnableHotPair,
                    xtHotPairCount = result.xtHotPairCount,
                )
            } else {
                config.copy(
                    name = name,
                    protocol = Protocol.GCM,
                    workerHost = result.serverAddr,
                    prefIp = result.prefIp,
                    fallbackIp = result.fallbackIp,
                    userId = result.userId,
                    disableEch = result.disableEch,
                    wsConn = result.wsConn,
                    enableDynamicPool = result.enableDynamicPool,
                    dynamicPoolMax = result.dynamicPoolMax,
                )
            }
            profileRepository.saveProfile(updated)
        }
    }

    suspend fun exportUri(id: String): String {
        val config = profileRepository.getProfile(id)
        return if (config.protocol == Protocol.XTUNNEL) {
            ProfileUriCodec.exportXtunnel(
                serverAddr = config.xtServerAddr,
                token = config.xtToken,
                relayNodes = config.xtRelayNodes,
                connections = config.xtConnections,
                disableEch = config.xtDisableEch,
                insecure = config.xtInsecure,
                enableHotPair = config.xtEnableHotPair,
                hotPairCount = config.xtHotPairCount,
                profileName = config.name,
            )
        } else {
            ProfileUriCodec.exportGcm(
                workerHost = config.workerHost,
                prefIp = config.prefIp,
                fallbackIp = config.fallbackIp,
                userId = config.userId,
                disableEch = config.disableEch,
                wsConn = config.wsConn,
                enableDynamicPool = config.enableDynamicPool,
                dynamicPoolMax = config.dynamicPoolMax,
                profileName = config.name,
            )
        }
    }

    /** 校正 VPN 状态：APP 被杀后 Enable 可能为陈旧 true，但 VPN 隧道已不存在。 */
    fun reconcileVpnState(context: Context) {
        if (!state.value.enable) return
        if (hasActiveVpnNetwork(context) && isOwnVpnServiceRunning(context)) return
        viewModelScope.launch { settingsRepository.setEnable(false) }
    }

    private fun hasActiveVpnNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun isOwnVpnServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        for (info in am.getRunningServices(Int.MAX_VALUE)) {
            if (info.service != null && TProxyService::class.java.name == info.service.className) return true
        }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    onEditProfile: (String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRuntimeLog: () -> Unit,
    onOpenScan: () -> Unit,
    scanResult: String? = null,
    viewModel: ProfileListViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val themeMode by appViewModel.themeMode.collectAsState()

    // 注册 VPN 状态广播
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (TProxyService.ACTION_STATUS != intent.action) return
                val status = intent.getStringExtra(TProxyService.EXTRA_STATUS) ?: return
                viewModel.onVpnStatus(status)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(TProxyService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: RuntimeException) {} }
    }

    LaunchedEffect(Unit) { viewModel.reconcileVpnState(context) }



    // VPN 启动授权
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpn(context, viewModel)
        } else {
            android.widget.Toast.makeText(context, "VPN 权限被拒绝", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleVpn() {
        if (state.vpnStarting) return
        if (state.enable) {
            // 停止
            val intent = Intent(context, TProxyService::class.java).apply { action = TProxyService.ACTION_DISCONNECT }
            try { context.startService(intent) } catch (_: Exception) {}
        } else {
            // 启动：先校验当前配置服务器地址非空
            val currentId = state.currentProfileId
            val wssAddr = state.profiles.firstOrNull { it.id == currentId }?.serverAddr
            if (wssAddr.isNullOrBlank() || wssAddr == "未配置") {
                android.widget.Toast.makeText(context, "当前配置的服务器地址为空，请先编辑配置", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                vpnPrepareLauncher.launch(prepare)
            } else {
                startVpn(context, viewModel)
            }
        }
    }

    // 导入对话框状态
    var showImportNameDialog by remember { mutableStateOf<ProfileUriCodec.ImportResult?>(null) }
    var manualImportUri by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var showImportChoice by remember { mutableStateOf(false) }
    var showImportMethod by remember { mutableStateOf(false) }
    // 导出对话框
    var exportUri by remember { mutableStateOf<String?>(null) }

    // 扫码返回结果：解析并弹名称确认框（放在 state 声明之后，避免前向引用）
    LaunchedEffect(scanResult) {
        val code = scanResult ?: return@LaunchedEffect
        if (code.isBlank()) return@LaunchedEffect
        viewModel.importProfile(code) { result ->
            if (result != null) showImportNameDialog = result
            else android.widget.Toast.makeText(context, "无效的协议格式", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("X 代理", style = MaterialTheme.typography.titleMedium)
                        val version = remember {
                            try {
                                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                                pi.versionName ?: ""
                            } catch (_: Throwable) { "" }
                        }
                        Text(version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 循环切换主题：SYSTEM -> LIGHT -> DARK -> SYSTEM
                        val next = when (themeMode) {
                            com.x.client.app.data.model.ThemeMode.SYSTEM -> com.x.client.app.data.model.ThemeMode.LIGHT
                            com.x.client.app.data.model.ThemeMode.LIGHT -> com.x.client.app.data.model.ThemeMode.DARK
                            else -> com.x.client.app.data.model.ThemeMode.SYSTEM
                        }
                        appViewModel.setThemeMode(next)
                    }) {
                        val icon = when (themeMode) {
                            com.x.client.app.data.model.ThemeMode.LIGHT -> com.x.client.app.R.drawable.ic_light_mode
                            com.x.client.app.data.model.ThemeMode.DARK -> com.x.client.app.R.drawable.ic_dark_mode
                            else -> com.x.client.app.R.drawable.ic_system_mode
                        }
                        Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = "切换主题")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showImportChoice = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "菜单")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.profiles, key = { it.id }) { profile ->
                        ProfileSwipeItem(
                            profile = profile,
                            isSelected = profile.id == state.currentProfileId,
                            vpnRunning = state.enable,
                            onClick = { viewModel.selectProfile(profile.id) },
                            onEdit = { onEditProfile(profile.id, false) },
                            onCopy = { showCopyDialog(context, profile.name) { newName -> viewModel.copyProfile(profile.id, newName) } },
                            onDelete = { showDeleteDialog(context, profile, state, viewModel) },
                            onShare = { viewModel.exportUri(profile.id) },
                        )
                    }
                }
                // 底部启动/停止按钮
                val (btnText, btnColor) = when {
                    state.vpnStarting -> "启动中..." to Color(0xFFFF9800)
                    state.enable -> "停止" to Color(0xFFF44336)
                    else -> "启动" to Color(0xFF4CAF50)
                }
                Button(
                    onClick = { toggleVpn() },
                    enabled = !state.vpnStarting,
                    colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text(btnText, color = Color.White) }
            }
        }
    }

    // FAB 菜单：导入/新增/设置/运行日志
    if (showImportChoice) {
        AlertDialog(
            onDismissRequest = { showImportChoice = false },
            title = { Text("操作") },
            text = {
                Column {
                    TextButton(onClick = { showImportChoice = false; showImportMethod = true }) { Text("导入") }
                    TextButton(onClick = { showImportChoice = false; viewModel.addNewProfile { id -> onEditProfile(id, true) } }) { Text("新增") }
                    TextButton(onClick = { showImportChoice = false; onOpenSettings() }) { Text("设置") }
                    TextButton(onClick = { showImportChoice = false; onOpenRuntimeLog() }) { Text("查看本次运行日志") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportChoice = false }) { Text("取消") } }
        )
    }

    // 导入方式选择（手动输入 / 扫描二维码）
    if (showImportMethod) {
        AlertDialog(
            onDismissRequest = { showImportMethod = false },
            title = { Text("导入配置") },
            text = {
                Column {
                    TextButton(onClick = { showImportMethod = false; showManualInput = true }) { Text("手动输入") }
                    TextButton(onClick = { showImportMethod = false; onOpenScan() }) { Text("扫描二维码") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportMethod = false }) { Text("取消") } }
        )
    }

    // 手动导入输入
    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false; manualImportUri = "" },
            title = { Text("导入配置") },
            text = {
                OutlinedTextField(
                    value = manualImportUri,
                    onValueChange = { manualImportUri = it },
                    label = { Text("gcm://... 或 xtunnel://...") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = manualImportUri.trim()
                    showManualInput = false
                    manualImportUri = ""
                    if (uri.isNotEmpty()) {
                        viewModel.importProfile(uri) { result ->
                            if (result != null) showImportNameDialog = result
                            else android.widget.Toast.makeText(context, "无效的协议格式", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showManualInput = false; manualImportUri = "" }) { Text("取消") } }
        )
    }

    // 导入名称确认
    showImportNameDialog?.let { result ->
        var name by remember(result) { mutableStateOf(result.name) }
        AlertDialog(
            onDismissRequest = { showImportNameDialog = null },
            title = { Text("配置名称") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("节点名称") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val finalName = name.ifBlank { result.name }
                    viewModel.saveImported(result, finalName)
                    showImportNameDialog = null
                    android.widget.Toast.makeText(context, "配置已导入", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showImportNameDialog = null }) { Text("取消") } }
        )
    }

    // 导出对话框（URI + 二维码 + 复制）
    exportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { exportUri = null },
            title = { Text("导出配置") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(uri, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                    val bmp = remember(uri) { generateQrCode(uri) }
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "二维码")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("X Client", uri))
                    android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                    exportUri = null
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { exportUri = null }) { Text("关闭") } }
        )
    }
}

private fun startVpn(context: Context, viewModel: ProfileListViewModel) {
    val intent = Intent(context, TProxyService::class.java).apply { action = TProxyService.ACTION_CONNECT }
    try {
        ContextCompat.startForegroundService(context, intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "无法启动 VPN 服务: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun showCopyDialog(context: Context, originalName: String, onCopy: (String) -> Unit) {
    val input = android.widget.EditText(context).apply { setText(originalName); setSingleLine(true); setSelection(originalName.length) }
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("复制配置")
        .setMessage("输入新配置的名称")
        .setView(input)
        .setPositiveButton("确定") { _, _ ->
            val name = input.text.toString().trim().ifEmpty { originalName }
            onCopy(name)
            android.widget.Toast.makeText(context, "配置已复制: $name", android.widget.Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun showDeleteDialog(
    context: Context,
    profile: ProfileInfo,
    state: ProfileListUiState,
    viewModel: ProfileListViewModel,
) {
    if (state.enable && profile.id == state.currentProfileId) {
        android.widget.Toast.makeText(context, "VPN 正在运行，无法删除当前配置", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("删除配置")
        .setMessage("确认删除配置 ${profile.name}?")
        .setPositiveButton("确定") { _, _ ->
            viewModel.deleteProfile(profile.id)
            android.widget.Toast.makeText(context, "配置已删除", android.widget.Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("取消", null)
        .show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSwipeItem(
    profile: ProfileInfo,
    isSelected: Boolean,
    vpnRunning: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val isCurrent = isSelected
    val disableEditDelete = vpnRunning && isCurrent
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // 不通过滑动直接触发动作（保留在原位，露出按钮手动点）
            // 返回 false 让它弹回，保持"滑动揭示"而非"滑动删除"语义
            value != SwipeToDismissBoxValue.StartToEnd && value != SwipeToDismissBoxValue.EndToStart
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val actions = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                // 向右滑露出分享/复制
                listOf(Icons.Default.Share to onShare, Icons.Default.ContentCopy to onCopy)
            } else {
                // 向左滑露出编辑/删除
                listOf(Icons.Default.Edit to onEdit, Icons.Default.Delete to onDelete)
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { (icon, action) ->
                    IconButton(onClick = action, enabled = !(disableEditDelete && (icon == Icons.Default.Edit || icon == Icons.Default.Delete))) {
                        Icon(icon, contentDescription = null, tint = Color.White)
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .selectable(selected = isSelected, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                Text(profile.serverAddr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(
                onClick = {},
                label = { Text(if (profile.protocol == Protocol.XTUNNEL) "X-Tunnel" else "GCM") },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
    }
}

// （background modifier 由 androidx.compose.foundation.background 提供，已在顶部导入）
