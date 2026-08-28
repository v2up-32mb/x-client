package com.x.client.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.x.client.app.data.model.GlobalSettings
import com.x.client.app.data.model.Limits
import com.x.client.app.data.model.LogLevel
import com.x.client.app.data.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局设置 DataStore（跨进程一致）。
 *
 * 使用 [MultiProcessDataStoreFactory]（在 [DataStoreModule] 中创建），保证主进程 UI 与
 * :vpn 服务进程读写同一份设置时具备：read-after-write 一致性、写串行化、读不被写阻塞。
 * 同一文件在单进程内只创建一个实例（Hilt @Singleton），主进程和 :vpn 各自的
 * SingletonComponent 持有各自实例（天然多进程隔离）。
 *
 * 对应原 Preferences.java 中无 _<id> 后缀的全局键读写。
 */
@Singleton
class GlobalSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<GlobalSettings> = dataStore.data.map { it.toGlobalSettings() }

    /** 同步读取当前全局设置（供 :vpn 进程启动时使用，runBlocking 包裹）。 */
    suspend fun snapshot(): GlobalSettings = settings.first()

    suspend fun setEnable(value: Boolean) {
        dataStore.edit { it[Keys.ENABLE] = value }
    }

    suspend fun setSocksPort(port: Int) {
        dataStore.edit { it[Keys.SOCKS_PORT] = port.coerceAtLeast(Limits.MIN_SOCKS_PORT) }
    }

    suspend fun setGlobal(value: Boolean) {
        dataStore.edit { it[Keys.GLOBAL] = value }
    }

    suspend fun setApps(apps: Set<String>) {
        dataStore.edit { it[Keys.APPS] = apps }
    }

    suspend fun setBypassPrivate(value: Boolean) {
        dataStore.edit { it[Keys.BYPASS_PRIVATE] = value }
    }

    suspend fun setBypassGeoIpCn(value: Boolean) {
        dataStore.edit { it[Keys.BYPASS_GEOIP_CN] = value }
    }

    suspend fun setBypassGeoSiteCn(value: Boolean) {
        dataStore.edit { it[Keys.BYPASS_GEOSITE_CN] = value }
    }

    suspend fun setBypassRules(rules: String) {
        dataStore.edit { it[Keys.BYPASS_RULES] = rules }
    }

    suspend fun setEchDns(value: String) {
        dataStore.edit { it[Keys.ECH_DNS] = value }
    }

    suspend fun setEchDomain(value: String) {
        dataStore.edit { it[Keys.ECH_DOMAIN] = value }
    }

    suspend fun setEnableDnsWarmup(value: Boolean) {
        dataStore.edit { it[Keys.ENABLE_DNS_WARMUP] = value }
    }

    suspend fun setLogLevel(level: String) {
        val normalized = normalizeLogLevel(level)
        dataStore.edit { it[Keys.LOG_LEVEL] = normalized }
    }

    suspend fun setThemeMode(mode: Int) {
        val normalized = when (mode) {
            ThemeMode.LIGHT, ThemeMode.DARK -> mode
            else -> ThemeMode.SYSTEM
        }
        dataStore.edit { it[Keys.THEME_MODE] = normalized }
    }

    suspend fun setCurrentProfileId(id: String?) {
        dataStore.edit { p ->
            if (id == null) p.remove(Keys.CURRENT_PROFILE_ID) else p[Keys.CURRENT_PROFILE_ID] = id
        }
    }

    suspend fun setProfileIds(ids: Set<String>) {
        dataStore.edit { it[Keys.PROFILES] = ids }
    }

    private fun Preferences.toGlobalSettings(): GlobalSettings = GlobalSettings(
        enable = this[Keys.ENABLE] ?: false,
        socksPort = this[Keys.SOCKS_PORT] ?: Limits.DEFAULT_SOCKS_PORT,
        global = this[Keys.GLOBAL] ?: true,
        apps = this[Keys.APPS] ?: emptySet(),
        bypassPrivate = this[Keys.BYPASS_PRIVATE] ?: false,
        bypassGeoIpCn = this[Keys.BYPASS_GEOIP_CN] ?: false,
        bypassGeoSiteCn = this[Keys.BYPASS_GEOSITE_CN] ?: false,
        bypassRules = this[Keys.BYPASS_RULES] ?: "",
        echDns = this[Keys.ECH_DNS] ?: Limits.DEFAULT_ECH_DNS,
        echDomain = this[Keys.ECH_DOMAIN] ?: Limits.DEFAULT_ECH_DOMAIN,
        enableDnsWarmup = this[Keys.ENABLE_DNS_WARMUP] ?: false,
        logLevel = normalizeLogLevel(this[Keys.LOG_LEVEL]),
        themeMode = this[Keys.THEME_MODE] ?: ThemeMode.SYSTEM,
        currentProfileId = this[Keys.CURRENT_PROFILE_ID],
        profileIds = this[Keys.PROFILES] ?: emptySet(),
    )

    private fun normalizeLogLevel(raw: String?): String {
        val level = raw?.trim()?.uppercase() ?: return LogLevel.INFO
        return when (level) {
            LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR -> level
            else -> LogLevel.INFO
        }
    }
}
