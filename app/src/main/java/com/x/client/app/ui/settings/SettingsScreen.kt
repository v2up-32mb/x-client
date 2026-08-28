package com.x.client.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.x.client.app.data.model.GlobalSettings
import com.x.client.app.data.model.Limits
import com.x.client.app.data.model.LogLevel
import com.x.client.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<GlobalSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalSettings())

    fun save(
        global: Boolean,
        socksPort: Int,
        bypassPrivate: Boolean,
        bypassGeoIpCn: Boolean,
        bypassGeoSiteCn: Boolean,
        bypassRules: String,
        echDns: String,
        echDomain: String,
        enableDnsWarmup: Boolean,
        logLevel: String,
        onError: (String) -> Unit,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                if (socksPort < Limits.MIN_SOCKS_PORT) {
                    onError("端口号必须 ≥ ${Limits.MIN_SOCKS_PORT}")
                    return@launch
                }
                repository.saveSettings(
                    global = global,
                    socksPort = socksPort,
                    bypassPrivate = bypassPrivate,
                    bypassGeoIpCn = bypassGeoIpCn,
                    bypassGeoSiteCn = bypassGeoSiteCn,
                    bypassRules = bypassRules,
                    echDns = echDns,
                    echDomain = echDomain,
                    enableDnsWarmup = enableDnsWarmup,
                    logLevel = logLevel,
                )
                onSuccess()
            } catch (e: Throwable) {
                onError("绕过规则格式错误: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAppList: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val vpnRunning = settings.enable

    var global by remember { mutableStateOf(settings.global) }
    var socksPort by remember { mutableStateOf(settings.socksPort.toString()) }
    var bypassPrivate by remember { mutableStateOf(settings.bypassPrivate) }
    var bypassGeoIpCn by remember { mutableStateOf(settings.bypassGeoIpCn) }
    var bypassGeoSiteCn by remember { mutableStateOf(settings.bypassGeoSiteCn) }
    var bypassRules by remember { mutableStateOf(settings.bypassRules) }
    var echDns by remember { mutableStateOf(settings.echDns) }
    var echDomain by remember { mutableStateOf(settings.echDomain) }
    var enableDnsWarmup by remember { mutableStateOf(settings.enableDnsWarmup) }
    var logLevel by remember { mutableStateOf(settings.logLevel) }

    LaunchedEffect(settings) {
        global = settings.global
        socksPort = settings.socksPort.toString()
        bypassPrivate = settings.bypassPrivate
        bypassGeoIpCn = settings.bypassGeoIpCn
        bypassGeoSiteCn = settings.bypassGeoSiteCn
        bypassRules = settings.bypassRules
        echDns = settings.echDns
        echDomain = settings.echDomain
        enableDnsWarmup = settings.enableDnsWarmup
        logLevel = settings.logLevel
    }

    val logLevels = listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
    val logLevelLabels = listOf("调试（DEBUG）", "信息（INFO）", "警告（WARN）", "错误（ERROR）")
    var logMenuExpanded by remember { mutableStateOf(false) }
    val logLabel = logLevelLabels.getOrElse(logLevels.indexOf(logLevel)) { logLevelLabels[1] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // VPN 运行提示
            if (vpnRunning) {
                Text(
                    "VPN 正在运行，无法修改全局设置",
                    color = androidx.compose.ui.graphics.Color(0xFFE65100),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }

            // 全局代理
            LabeledCheckbox("全局代理", global, enabled = !vpnRunning) { global = it }

            // 选择应用
            Button(
                onClick = onOpenAppList,
                enabled = !vpnRunning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("选择应用（分应用代理）") }

            // SOCKS5 端口
            OutlinedTextField(
                value = socksPort,
                onValueChange = { socksPort = it.filter(Char::isDigit) },
                label = { Text("本地代理端口") },
                enabled = !vpnRunning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("路由绕过", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            LabeledCheckbox("绕过本地和局域网地址", bypassPrivate, enabled = !vpnRunning) { bypassPrivate = it }
            LabeledCheckbox("绕过 GEOIP:CN", bypassGeoIpCn, enabled = !vpnRunning) { bypassGeoIpCn = it }
            LabeledCheckbox("绕过 GEOSITE:CN", bypassGeoSiteCn, enabled = !vpnRunning) { bypassGeoSiteCn = it }
            OutlinedTextField(
                value = bypassRules,
                onValueChange = { bypassRules = it },
                label = { Text("手动规则（每行一条）") },
                placeholder = { Text("192.168.1.0/24\nexample.com\nfull:api.example.com") },
                enabled = !vpnRunning,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            Text("ECH / DoH", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = echDns,
                onValueChange = { echDns = it },
                label = { Text("DoH 服务器") },
                enabled = !vpnRunning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = echDomain,
                onValueChange = { echDomain = it },
                label = { Text("ECH 查询域名") },
                enabled = !vpnRunning,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            LabeledCheckbox("启用 DNS 预热", enableDnsWarmup, enabled = !vpnRunning) { enableDnsWarmup = it }

            Text("日志等级", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Text("控制代理协议输出到运行日志的详细程度，下次启动 VPN 时生效",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            ExposedDropdownMenuBox(
                expanded = logMenuExpanded,
                onExpandedChange = { if (!vpnRunning) logMenuExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = logLabel,
                    onValueChange = {},
                    readOnly = true,
                    enabled = !vpnRunning,
                    label = { Text("日志等级") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = logMenuExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, !vpnRunning).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = logMenuExpanded, onDismissRequest = { logMenuExpanded = false }) {
                    logLevels.forEachIndexed { i, level ->
                        DropdownMenuItem(
                            text = { Text(logLevelLabels[i]) },
                            onClick = {
                                logLevel = level
                                logMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    viewModel.save(
                        global = global,
                        socksPort = socksPort.toIntOrNull() ?: Limits.DEFAULT_SOCKS_PORT,
                        bypassPrivate = bypassPrivate,
                        bypassGeoIpCn = bypassGeoIpCn,
                        bypassGeoSiteCn = bypassGeoSiteCn,
                        bypassRules = bypassRules,
                        echDns = echDns,
                        echDomain = echDomain,
                        enableDnsWarmup = enableDnsWarmup,
                        logLevel = logLevel,
                        onError = { msg ->
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        },
                        onSuccess = {
                            android.widget.Toast.makeText(context, "设置已保存", android.widget.Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    )
                },
                enabled = !vpnRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) { Text("保存") }
        }
    }
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

// collectAsState 扩展导入（避免遗漏）
