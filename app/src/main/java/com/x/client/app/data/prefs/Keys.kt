package com.x.client.app.data.prefs

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * DataStore 键集中定义。键名与原 SharedPreferences 保持一致，便于（如有）历史数据
 * 兼容；MultiProcessDataStore 使用独立 pb 文件，实际不读取旧 SP，键名一致仅为
 * 语义对齐与排查便利。
 */
internal object Keys {
    // ---- 全局键（无 profile 后缀） ----
    val ENABLE = booleanPreferencesKey("Enable")
    val SOCKS_PORT = intPreferencesKey("SocksPort")
    val GLOBAL = booleanPreferencesKey("Global")
    val APPS = stringSetPreferencesKey("Apps")

    val BYPASS_PRIVATE = booleanPreferencesKey("BypassPrivate")
    val BYPASS_GEOIP_CN = booleanPreferencesKey("BypassGeoIpCn")
    val BYPASS_GEOSITE_CN = booleanPreferencesKey("BypassGeoSiteCn")
    val BYPASS_RULES = stringPreferencesKey("BypassRules")

    val ECH_DNS = stringPreferencesKey("EchDns")
    val ECH_DOMAIN = stringPreferencesKey("EchDomain")
    val ENABLE_DNS_WARMUP = booleanPreferencesKey("EnableDnsWarmup")
    val LOG_LEVEL = stringPreferencesKey("LogLevel")

    val THEME_MODE = intPreferencesKey("ThemeMode")

    val CURRENT_PROFILE_ID = stringPreferencesKey("CurrentProfileId")
    val PROFILES = stringSetPreferencesKey("Profiles")

    // ---- per-profile 键（带 _<id> 后缀，与原 SP 一致） ----
    // 用 stringPreferencesKey 动态拼接后缀。
    fun profileName(id: String) = stringPreferencesKey("ProfileName_$id")

    fun workerHost(id: String) = stringPreferencesKey("WorkerHost_$id")
    fun prefIp(id: String) = stringPreferencesKey("PrefIp_$id")
    fun userId(id: String) = stringPreferencesKey("UserId_$id")
    fun fallbackIp(id: String) = stringPreferencesKey("FallbackIp_$id")
    fun disableEch(id: String) = booleanPreferencesKey("DisableEch_$id")
    fun disableIpv6Route(id: String) = booleanPreferencesKey("DisableIpv6Route_$id")
    fun wsConn(id: String) = intPreferencesKey("WsConn_$id")
    fun enableDynamicPool(id: String) = booleanPreferencesKey("EnableDynamicPool_$id")
    fun dynamicPoolMax(id: String) = intPreferencesKey("DynamicPoolMax_$id")
    fun protocol(id: String) = stringPreferencesKey("Protocol_$id")

    fun xtServerAddr(id: String) = stringPreferencesKey("XtServerAddr_$id")
    fun xtToken(id: String) = stringPreferencesKey("XtToken_$id")
    fun xtRelayNodes(id: String) = stringPreferencesKey("XtRelayNodes_$id")
    fun xtConnections(id: String) = intPreferencesKey("XtConnections_$id")
    fun xtDisableEch(id: String) = booleanPreferencesKey("XtDisableEch_$id")
    fun xtInsecure(id: String) = booleanPreferencesKey("XtInsecure_$id")
    fun xtEnableHotPair(id: String) = booleanPreferencesKey("XtEnableHotPair_$id")
    fun xtHotPairCount(id: String) = intPreferencesKey("XtHotPairCount_$id")
    fun xtAdvancedParams(id: String) = stringPreferencesKey("XtAdvancedParams_$id")

    /** 返回某个 profile 的全部 per-profile 键，用于复制/删除。 */
    fun profileKeys(id: String): List<Preferences.Key<*>> = listOf(
        workerHost(id), prefIp(id), userId(id), fallbackIp(id), disableEch(id),
        disableIpv6Route(id), wsConn(id), enableDynamicPool(id), dynamicPoolMax(id),
        protocol(id), xtServerAddr(id), xtToken(id), xtRelayNodes(id), xtConnections(id),
        xtDisableEch(id), xtInsecure(id), xtEnableHotPair(id), xtHotPairCount(id),
        xtAdvancedParams(id),
    )
}
