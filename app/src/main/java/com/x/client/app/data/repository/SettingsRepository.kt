package com.x.client.app.data.repository

import com.x.client.app.data.model.GlobalSettings
import com.x.client.app.data.prefs.GlobalSettingsDataStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局设置仓库，转发到 [GlobalSettingsDataStore]，并对保存校验失败时抛出。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: GlobalSettingsDataStore,
    private val xclientBridge: XclientBridge,
) {

    val settings: Flow<GlobalSettings> = store.settings

    suspend fun snapshot(): GlobalSettings = store.snapshot()

    suspend fun setEnable(value: Boolean) = store.setEnable(value)

    suspend fun saveSettings(
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
        apps: Set<String> = snapshot().apps, // apps 由 AppListActivity 单独保存
    ) {
        // 校验绕过规则（Go 侧校验，失败抛异常由 UI 捕获展示）
        xclientBridge.validateBypassRules(bypassRules)

        store.setGlobal(global)
        store.setSocksPort(socksPort)
        store.setBypassPrivate(bypassPrivate)
        store.setBypassGeoIpCn(bypassGeoIpCn)
        store.setBypassGeoSiteCn(bypassGeoSiteCn)
        store.setBypassRules(bypassRules)
        store.setEchDns(echDns)
        store.setEchDomain(echDomain)
        store.setEnableDnsWarmup(enableDnsWarmup)
        store.setLogLevel(logLevel)
    }

    suspend fun setApps(apps: Set<String>) = store.setApps(apps)

    suspend fun setThemeMode(mode: Int) = store.setThemeMode(mode)
}
