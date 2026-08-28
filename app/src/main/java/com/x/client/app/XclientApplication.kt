package com.x.client.app

import android.app.Application
import android.util.Log
import com.x.client.app.data.repository.SettingsRepository
import com.x.client.app.ui.theme.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.runBlocking
import java.util.TimeZone
import javax.inject.Inject

/**
 * Application 入口。
 *
 * 多进程说明：Android 每个进程都会创建独立的 Application 实例并调用 onCreate。
 * - 主进程（进程名 == 包名）：应用主题、同步 Go 运行时区。
 * - :vpn 进程：只做 VPN 相关初始化，Hilt 各进程有独立 SingletonComponent。
 *
 * Go 时区同步对两个进程都有意义（:vpn 进程也要写日志时间戳），因此不做进程过滤；
 * 但 Preferences 旧版的"全局网络设置迁移"只允许在主进程执行（避免 :vpn 进程
 * 用陈旧缓存全量写回覆盖主进程保存的全局设置），该逻辑迁移到 DataStore 层后
 * 已天然由 MultiProcessDataStore 保证一致性，无需再特殊处理。
 */
@HiltAndroidApp
class XclientApplication : Application() {

    @Inject
    lateinit var xclientBridge: com.x.client.app.data.repository.XclientBridge

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // 同步读取主题模式并缓存，避免首帧闪屏；DataStore 单次磁盘读开销很小。
        runCatching {
            val mode = runBlocking { settingsRepository.snapshot().themeMode }
            ThemeManager.cacheMode(mode)
        }
        syncSystemTimeZone()
    }

    private fun syncSystemTimeZone() {
        try {
            xclientBridge.setTimeZone(TimeZone.getDefault().id)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to sync system timezone", error)
        }
    }

    companion object {
        private const val TAG = "XclientApplication"
    }
}
