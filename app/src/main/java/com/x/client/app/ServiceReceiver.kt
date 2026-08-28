package com.x.client.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.x.client.app.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机完成 + VPN 状态广播接收器（Kotlin 重写，行为与原 ServiceReceiver 一致）。
 *
 * - BOOT_COMPLETED：不自动启动，重置 Enable 为停止状态，等待用户手动启用。
 * - ACTION_STATUS：主进程兜底维护 Enable 状态（即使主界面不在前台也能收到
 *   TProxyService 发送的显式包广播），避免状态残留。
 *
 * 使用 Hilt [AndroidEntryPoint] 注入 [SettingsRepository]（Hilt 支持
 * BroadcastReceiver 注入，onReceive 内可用 goAsync 完成异步写）。
 */
@AndroidEntryPoint
class ServiceReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> settingsRepository.setEnable(false)
                    TProxyService.ACTION_STATUS -> {
                        val status = intent.getStringExtra(TProxyService.EXTRA_STATUS) ?: return@launch
                        when (status) {
                            TProxyService.STATUS_STARTED -> settingsRepository.setEnable(true)
                            TProxyService.STATUS_ERROR, TProxyService.STATUS_STOPPED ->
                                settingsRepository.setEnable(false)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
