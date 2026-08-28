package com.x.client.app.data.model

/**
 * 全局设置（与 Profile 无关，对应原 Preferences.java 中无 _<id> 后缀的全局键）。
 */
data class GlobalSettings(
    val enable: Boolean = false,
    val socksPort: Int = Limits.DEFAULT_SOCKS_PORT,
    val global: Boolean = true,
    val apps: Set<String> = emptySet(),

    // 路由绕过
    val bypassPrivate: Boolean = false,
    val bypassGeoIpCn: Boolean = false,
    val bypassGeoSiteCn: Boolean = false,
    val bypassRules: String = "",

    // ECH / DoH / DNS 预热 / 日志等级（全局）
    val echDns: String = Limits.DEFAULT_ECH_DNS,
    val echDomain: String = Limits.DEFAULT_ECH_DOMAIN,
    val enableDnsWarmup: Boolean = false,
    val logLevel: String = Limits.DEFAULT_LOG_LEVEL,

    // 主题
    val themeMode: Int = ThemeMode.SYSTEM,

    // Profile 管理
    val currentProfileId: String? = null,
    val profileIds: Set<String> = emptySet(),
)
