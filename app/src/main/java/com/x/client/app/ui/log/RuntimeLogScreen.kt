package com.x.client.app.ui.log

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.x.client.app.vpn.TProxyService
import kotlinx.coroutines.delay

private const val REQUEST_TIMEOUT_MS = 2000L

/**
 * 运行日志屏：向 TProxyService 请求日志并展示，2 秒超时显示"日志服务未响应"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }

    var logs by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在读取日志…") }
    var responseReceived by remember { mutableStateOf(false) }

    fun renderLogs(raw: String?) {
        val value = raw?.trim().orEmpty()
        logs = value
        status = if (value.isEmpty()) {
            "暂无运行日志"
        } else {
            "${countLines(value)} 行"
        }
    }

    val logReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (TProxyService.ACTION_RUNTIME_LOGS != intent.action) return
                responseReceived = true
                renderLogs(intent.getStringExtra(TProxyService.EXTRA_RUNTIME_LOGS))
            }
        }
    }

    fun requestLogs() {
        responseReceived = false
        status = "正在读取日志…"
        val request = Intent(context, TProxyService::class.java).apply {
            action = TProxyService.ACTION_REQUEST_RUNTIME_LOGS
        }
        try {
            context.startService(request)
        } catch (_: RuntimeException) {
            renderLogs("")
            status = "日志服务未响应"
            return
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!responseReceived) {
                renderLogs("")
                status = "日志服务未响应"
            }
        }, REQUEST_TIMEOUT_MS)
    }

    LaunchedEffect(Unit) {
        ContextCompat.registerReceiver(
            context, logReceiver,
            IntentFilter(TProxyService.ACTION_RUNTIME_LOGS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        requestLogs()
    }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            try {
                context.unregisterReceiver(logReceiver)
            } catch (_: RuntimeException) { /* 忽略未注册 */ }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { requestLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    val canCopy = logs.isNotEmpty()
                    IconButton(onClick = { copyLogs(context, logs) }, enabled = canCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                val scrollState = rememberScrollState()
                LaunchedEffect(logs) {
                    // 自动滚到底
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Text(
                    logs,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                )
            }
        }
    }
}

private fun countLines(value: String): Int {
    var count = 1
    for (ch in value) if (ch == '\n') count++
    return count
}

private fun copyLogs(context: Context, logs: String) {
    if (logs.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("运行日志", logs))
    android.widget.Toast.makeText(context, "日志已复制", android.widget.Toast.LENGTH_SHORT).show()
}
