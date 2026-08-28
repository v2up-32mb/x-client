package com.x.client.app.ui.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.x.client.app.data.model.Limits
import com.x.client.app.data.model.ProfileConfig
import com.x.client.app.data.model.ProfileUriCodec
import com.x.client.app.data.model.Protocol
import com.x.client.app.data.model.XtAdvancedParams
import com.x.client.app.data.repository.ProfileRepository
import com.x.client.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _config = MutableStateFlow<ProfileConfig?>(null)
    val config: StateFlow<ProfileConfig?> = _config.asStateFlow()

    var vpnRunning = false
        private set

    fun load(profileId: String) {
        viewModelScope.launch {
            _config.value = profileRepository.getProfile(profileId)
            vpnRunning = settingsRepository.snapshot().enable
        }
    }

    fun save(config: ProfileConfig, onError: (String) -> Unit, onSuccess: () -> Unit) {
        if (config.name.isBlank()) { onError("配置名称不能为空"); return }
        if (config.protocol == Protocol.XTUNNEL) {
            if (config.xtServerAddr.isBlank()) { onError("服务器地址不能为空"); return }
            if (!config.xtServerAddr.startsWith("wss://") && !config.xtServerAddr.startsWith("ws://")) {
                onError("服务器地址必须以 wss:// 或 ws:// 开头"); return
            }
        } else {
            if (config.workerHost.isBlank()) { onError("服务器地址不能为空"); return }
        }
        if (config.protocol == Protocol.GCM) {
            if (config.wsConn !in 1..Limits.MAX_DYNAMIC_POOL_LIMIT) {
                onError("WebSocket 连接数必须在 1-${Limits.MAX_DYNAMIC_POOL_LIMIT} 之间"); return
            }
            if (config.enableDynamicPool && (config.dynamicPoolMax > Limits.MAX_DYNAMIC_POOL_LIMIT || config.dynamicPoolMax < config.wsConn)) {
                onError("启用动态扩容时，上限必须在 WebSocket 连接数和 ${Limits.MAX_DYNAMIC_POOL_LIMIT} 之间"); return
            }
        }
        if (config.protocol == Protocol.XTUNNEL && config.xtEnableHotPair) {
            if (config.xtHotPairCount !in 1..Limits.MAX_XT_HOT_PAIR_COUNT) {
                onError("热通道对数必须在 1-${Limits.MAX_XT_HOT_PAIR_COUNT} 之间"); return
            }
        }
        viewModelScope.launch {
            profileRepository.saveProfile(config)
            onSuccess()
        }
    }

    fun deleteIfNew(profileId: String, wasSaved: Boolean) {
        if (!wasSaved) {
            viewModelScope.launch { profileRepository.removeProfile(profileId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    profileId: String,
    isNew: Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onOpenScan: () -> Unit,
    viewModel: ProfileEditViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsState()
    val hasBeenSaved = remember { mutableStateOf(false) }

    LaunchedEffect(profileId) { viewModel.load(profileId) }

    // 表单状态（key 到 current.id，切换 profile 时重置）
    var name by remember(profileId) { mutableStateOf("") }
    var protocol by remember(profileId) { mutableStateOf(Protocol.GCM) }
    var workerHost by remember(profileId) { mutableStateOf("") }
    var prefIp by remember(profileId) { mutableStateOf("") }
    var userId by remember(profileId) { mutableStateOf("") }
    var fallbackIp by remember(profileId) { mutableStateOf("") }
    var disableEch by remember(profileId) { mutableStateOf(false) }
    var disableIpv6Route by remember(profileId) { mutableStateOf(false) }
    var wsConn by remember(profileId) { mutableStateOf(Limits.DEFAULT_WS_CONN.toString()) }
    var enableDynamicPool by remember(profileId) { mutableStateOf(false) }
    var dynamicPoolMax by remember(profileId) { mutableStateOf(Limits.DEFAULT_DYNAMIC_POOL_MAX.toString()) }
    var xtServerAddr by remember(profileId) { mutableStateOf("") }
    var xtToken by remember(profileId) { mutableStateOf("") }
    var xtRelayNodes by remember(profileId) { mutableStateOf("") }
    var xtConnections by remember(profileId) { mutableStateOf(Limits.DEFAULT_XT_CONNECTIONS.toString()) }
    var xtDisableEch by remember(profileId) { mutableStateOf(false) }
    var xtInsecure by remember(profileId) { mutableStateOf(false) }
    var xtEnableHotPair by remember(profileId) { mutableStateOf(false) }
    var xtHotPairCount by remember(profileId) { mutableStateOf(Limits.DEFAULT_XT_HOT_PAIR_COUNT.toString()) }
    var advExpanded by remember { mutableStateOf(false) }
    var advBackpressure by remember(profileId) { mutableStateOf("") }
    var advWriteQueueWait by remember(profileId) { mutableStateOf("") }
    var advDialTimeout by remember(profileId) { mutableStateOf("") }
    var advHandshakeTimeout by remember(profileId) { mutableStateOf("") }
    var advReadTimeout by remember(profileId) { mutableStateOf("") }
    var advWriteTimeout by remember(profileId) { mutableStateOf("") }
    var advPingInterval by remember(profileId) { mutableStateOf("") }
    var advReconnectDelay by remember(profileId) { mutableStateOf("") }
    var advConnectTimeout by remember(profileId) { mutableStateOf("") }
    var advMaxSocks5 by remember(profileId) { mutableStateOf("") }
    var advUdpPorts by remember(profileId) { mutableStateOf("") }

    // 首次加载 config 后填充表单
    val current = config
    LaunchedEffect(current) {
        current ?: return@LaunchedEffect
        name = current.name
        protocol = current.protocol
        workerHost = current.workerHost
        prefIp = current.prefIp
        userId = current.userId
        fallbackIp = current.fallbackIp
        disableEch = current.disableEch
        disableIpv6Route = current.disableIpv6Route
        wsConn = current.wsConn.toString()
        enableDynamicPool = current.enableDynamicPool
        dynamicPoolMax = current.dynamicPoolMax.toString()
        xtServerAddr = current.xtServerAddr
        xtToken = current.xtToken
        xtRelayNodes = current.xtRelayNodes
        xtConnections = current.xtConnections.toString()
        xtDisableEch = current.xtDisableEch
        xtInsecure = current.xtInsecure
        xtEnableHotPair = current.xtEnableHotPair
        xtHotPairCount = current.xtHotPairCount.toString()
        current.xtAdvancedParams.backpressureLimit?.let { advBackpressure = (it / 1048576).toString() }
        current.xtAdvancedParams.writeQueueWaitTimeout?.let { advWriteQueueWait = it.toString() }
        current.xtAdvancedParams.dialTimeout?.let { advDialTimeout = (it / 1000).toString() }
        current.xtAdvancedParams.handshakeTimeout?.let { advHandshakeTimeout = (it / 1000).toString() }
        current.xtAdvancedParams.readTimeout?.let { advReadTimeout = (it / 1000).toString() }
        current.xtAdvancedParams.writeTimeout?.let { advWriteTimeout = (it / 1000).toString() }
        current.xtAdvancedParams.pingInterval?.let { advPingInterval = (it / 1000).toString() }
        current.xtAdvancedParams.reconnectDelay?.let { advReconnectDelay = (it / 1000).toString() }
        current.xtAdvancedParams.connectTimeout?.let { advConnectTimeout = (it / 1000).toString() }
        current.xtAdvancedParams.maxSocks5Connections?.let { advMaxSocks5 = it.toString() }
        current.xtAdvancedParams.udpBlockedPorts?.let { advUdpPorts = it }
    }

    if (current == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("加载中…") }) }) {}
        return
    }

    val vpnRunning = viewModel.vpnRunning
    val readOnly = vpnRunning
    val protocolMenuExpanded = remember { mutableStateOf(false) }
    val protocolLabel = if (protocol == Protocol.XTUNNEL) "X-Tunnel" else "GCM"

    fun collectAdvanced(): XtAdvancedParams = XtAdvancedParams(
        backpressureLimit = advBackpressure.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { it.toLong() * 1048576 },
        writeQueueWaitTimeout = advWriteQueueWait.trim().toIntOrNull()?.takeIf { it >= 1 }?.toLong(),
        dialTimeout = advDialTimeout.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        handshakeTimeout = advHandshakeTimeout.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        readTimeout = advReadTimeout.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        writeTimeout = advWriteTimeout.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        pingInterval = advPingInterval.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        reconnectDelay = advReconnectDelay.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        connectTimeout = advConnectTimeout.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
        maxSocks5Connections = advMaxSocks5.trim().toIntOrNull()?.takeIf { it >= 0 },
        udpBlockedPorts = advUdpPorts.trim().takeIf { it.isNotBlank() },
    )

    fun buildConfig(): ProfileConfig = current.copy(
        name = name.trim(),
        protocol = protocol,
        workerHost = workerHost.trim(),
        prefIp = prefIp.trim(),
        userId = userId.trim(),
        fallbackIp = fallbackIp.trim(),
        disableEch = disableEch,
        disableIpv6Route = disableIpv6Route,
        wsConn = wsConn.toIntOrNull() ?: Limits.DEFAULT_WS_CONN,
        enableDynamicPool = enableDynamicPool,
        dynamicPoolMax = dynamicPoolMax.toIntOrNull() ?: Limits.DEFAULT_DYNAMIC_POOL_MAX,
        xtServerAddr = xtServerAddr.trim(),
        xtToken = xtToken.trim(),
        xtRelayNodes = xtRelayNodes.trim(),
        xtConnections = xtConnections.toIntOrNull() ?: Limits.DEFAULT_XT_CONNECTIONS,
        xtDisableEch = xtDisableEch,
        xtInsecure = xtInsecure,
        xtEnableHotPair = xtEnableHotPair,
        xtHotPairCount = xtHotPairCount.toIntOrNull() ?: Limits.DEFAULT_XT_HOT_PAIR_COUNT,
        xtAdvancedParams = collectAdvanced(),
    )

    fun applyImport(uri: String): Boolean {
        return try {
            val r = ProfileUriCodec.parse(uri)
            if (r.protocol == Protocol.XTUNNEL) {
                protocol = Protocol.XTUNNEL
                xtServerAddr = r.serverAddr
                if (r.xtToken.isNotEmpty()) xtToken = r.xtToken
                if (r.xtRelayNodes.isNotEmpty()) xtRelayNodes = r.xtRelayNodes
                xtConnections = r.xtConnections.toString()
                xtDisableEch = r.xtDisableEch
                xtInsecure = r.xtInsecure
                xtEnableHotPair = r.xtEnableHotPair
                xtHotPairCount = r.xtHotPairCount.toString()
            } else {
                protocol = Protocol.GCM
                workerHost = r.serverAddr
                if (r.prefIp.isNotEmpty()) prefIp = r.prefIp
                if (r.fallbackIp.isNotEmpty()) fallbackIp = r.fallbackIp
                if (r.userId.isNotEmpty()) userId = r.userId
                disableEch = r.disableEch
                wsConn = r.wsConn.toString()
                enableDynamicPool = r.enableDynamicPool
                dynamicPoolMax = r.dynamicPoolMax.toString()
            }
            if (r.name.isNotEmpty() && r.name != "导入节点") name = r.name
            true
        } catch (e: ProfileUriCodec.InvalidProtocolException) {
            android.widget.Toast.makeText(context, e.message ?: "无效的协议格式", android.widget.Toast.LENGTH_SHORT).show()
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑配置: ${current.name}") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.deleteIfNew(profileId, hasBeenSaved.value)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (readOnly) {
                Text("VPN 正在运行，无法修改当前配置", color = androidx.compose.ui.graphics.Color(0xFFE65100))
            }

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("配置名称") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())

            ExposedDropdownMenuBox(expanded = protocolMenuExpanded.value, onExpandedChange = { if (!readOnly) protocolMenuExpanded.value = it }, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = protocolLabel, onValueChange = {}, readOnly = true, label = { Text("代理协议") }, enabled = !readOnly, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolMenuExpanded.value) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, !readOnly).fillMaxWidth())
                ExposedDropdownMenu(
                    expanded = protocolMenuExpanded.value,
                    onDismissRequest = { protocolMenuExpanded.value = false }
                ) {
                    DropdownMenuItem(text = { Text("GCM") }, onClick = { protocol = Protocol.GCM; protocolMenuExpanded.value = false })
                    DropdownMenuItem(text = { Text("X-Tunnel") }, onClick = { protocol = Protocol.XTUNNEL; protocolMenuExpanded.value = false })
                }
            }

            if (protocol == Protocol.GCM) {
                OutlinedTextField(value = workerHost, onValueChange = { workerHost = it }, label = { Text("服务器地址（Worker 域名）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = prefIp, onValueChange = { prefIp = it }, label = { Text("优选中转节点（IP:端口，逗号分隔多个）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = userId, onValueChange = { userId = it }, label = { Text("用户 ID") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fallbackIp, onValueChange = { fallbackIp = it }, label = { Text("出口代理 IP（逗号分隔多个）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("配置选项", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                LabeledCheckbox("禁用 ECH（标准 TLS 1.3）", disableEch, !readOnly) { disableEch = it }
                LabeledCheckbox("禁用 IPv6 路由", disableIpv6Route, !readOnly) { disableIpv6Route = it }
                OutlinedTextField(value = wsConn, onValueChange = { wsConn = it.filter(Char::isDigit) }, label = { Text("WebSocket 连接数") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                LabeledCheckbox("启用连接池动态扩容", enableDynamicPool, !readOnly) { enableDynamicPool = it }
                OutlinedTextField(value = dynamicPoolMax, onValueChange = { dynamicPoolMax = it.filter(Char::isDigit) }, label = { Text("动态扩容连接上限") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            if (protocol == Protocol.XTUNNEL) {
                OutlinedTextField(value = xtServerAddr, onValueChange = { xtServerAddr = it }, label = { Text("服务器地址（wss://）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = xtToken, onValueChange = { xtToken = it }, label = { Text("Token") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = xtRelayNodes, onValueChange = { xtRelayNodes = it }, label = { Text("中转节点（逗号分隔）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = xtConnections, onValueChange = { xtConnections = it.filter(Char::isDigit) }, label = { Text("连接数") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("配置选项", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                LabeledCheckbox("禁用 ECH", xtDisableEch, !readOnly) { xtDisableEch = it }
                LabeledCheckbox("跳过证书验证（Insecure）", xtInsecure, !readOnly) { xtInsecure = it }
                LabeledCheckbox("启用热通道对（Hot Pair）", xtEnableHotPair, !readOnly) { xtEnableHotPair = it }
                OutlinedTextField(value = xtHotPairCount, onValueChange = { xtHotPairCount = it.filter(Char::isDigit) }, label = { Text("热通道对数（1-8）") }, enabled = !readOnly && xtEnableHotPair, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { advExpanded = !advExpanded }) { Text(if (advExpanded) "高级参数 ▾" else "高级参数 ▸") }
                AnimatedVisibility(advExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = advBackpressure, onValueChange = { advBackpressure = it.filter(Char::isDigit) }, label = { Text("背压上限（MB，默认 8）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advWriteQueueWait, onValueChange = { advWriteQueueWait = it.filter(Char::isDigit) }, label = { Text("写队列等待超时（毫秒，默认 100）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advDialTimeout, onValueChange = { advDialTimeout = it }, label = { Text("拨号超时（秒，默认 3）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advHandshakeTimeout, onValueChange = { advHandshakeTimeout = it }, label = { Text("TLS 握手超时（秒，默认 5）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advReadTimeout, onValueChange = { advReadTimeout = it }, label = { Text("读取超时（秒，默认 15）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advWriteTimeout, onValueChange = { advWriteTimeout = it }, label = { Text("写入超时（秒，默认 5）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advPingInterval, onValueChange = { advPingInterval = it }, label = { Text("Ping 间隔（秒，默认 5）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advReconnectDelay, onValueChange = { advReconnectDelay = it }, label = { Text("重连延迟（秒，默认 1）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advConnectTimeout, onValueChange = { advConnectTimeout = it }, label = { Text("连接建立超时（秒，默认 15）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advMaxSocks5, onValueChange = { advMaxSocks5 = it.filter(Char::isDigit) }, label = { Text("SOCKS5 最大连接数（默认 1024，0 无限制）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = advUdpPorts, onValueChange = { advUdpPorts = it }, label = { Text("UDP 拦截端口（逗号分隔，默认 443）") }, enabled = !readOnly, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Button(onClick = {
                showImportDialog(context, onOpenScan) { uri -> if (applyImport(uri)) android.widget.Toast.makeText(context, "配置已导入，请检查并保存", android.widget.Toast.LENGTH_LONG).show() }
            }, enabled = !readOnly, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("导入配置") }

            Button(onClick = {
                viewModel.save(buildConfig(),
                    onError = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() },
                    onSuccess = {
                        hasBeenSaved.value = true
                        android.widget.Toast.makeText(context, "配置已保存", android.widget.Toast.LENGTH_SHORT).show()
                        onDone()
                    })
            }, enabled = !readOnly, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("保存") }
        }
    }
}

@Composable
private fun LabeledCheckbox(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun showImportDialog(context: android.content.Context, onOpenScan: () -> Unit, onManual: (String) -> Unit) {
    val items = arrayOf("手动输入", "扫描二维码")
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("导入配置")
        .setItems(items) { _, which ->
            if (which == 0) {
                val input = android.widget.EditText(context)
                input.hint = "gcm://server.com?ip=1.1.1.1:443#Name"
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("导入配置")
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> onManual(input.text.toString().trim()) }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                onOpenScan()
            }
        }
        .setNegativeButton("取消", null)
        .show()
}
