package com.x.client.app.data.model

/**
 * 协议标识，与 Go 侧 [xclient] android.go 保持一致。
 */
object Protocol {
    const val GCM = "gcm"
    const val XTUNNEL = "xtunnel"
}

/**
 * 主题模式（持久化整数）。
 */
object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/**
 * 日志等级（与 Go 侧 logger 对齐）。
 */
object LogLevel {
    const val DEBUG = "DEBUG"
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
}

/**
 * 协议参数范围与默认值，与原 Preferences.java 一致。
 */
object Limits {
    const val DEFAULT_WS_CONN = 3
    const val DEFAULT_DYNAMIC_POOL_MAX = 16
    const val MAX_DYNAMIC_POOL_LIMIT = 64

    const val DEFAULT_XT_CONNECTIONS = 3
    const val XT_CONNECTIONS_MAX = 16
    const val DEFAULT_XT_HOT_PAIR_COUNT = 1
    const val MAX_XT_HOT_PAIR_COUNT = 8

    const val DEFAULT_SOCKS_PORT = 1080
    const val MIN_SOCKS_PORT = 1024

    const val DEFAULT_ECH_DNS = "https://doh.pub/dns-query"
    const val DEFAULT_ECH_DOMAIN = "cloudflare-ech.com"
    const val DEFAULT_LOG_LEVEL = LogLevel.INFO

    /** VPN 接口固定参数（与原 Preferences 一致，不可配置）。 */
    const val TUNNEL_MTU = 8500
    const val TUNNEL_IPV4 = "198.18.0.1"
    const val TUNNEL_IPV4_PREFIX = 32
    const val TUNNEL_IPV6 = "fc00::1"
    const val TUNNEL_IPV6_PREFIX = 128
    const val MAPPED_DNS = "198.18.0.2"
    const val TASK_STACK_SIZE = 81920
    const val SOCKS_ADDR = "127.0.0.1"

    fun clampWsConn(value: Int): Int = value.coerceIn(1, MAX_DYNAMIC_POOL_LIMIT)
    fun clampDynamicPoolMax(value: Int): Int = value.coerceIn(1, MAX_DYNAMIC_POOL_LIMIT)
    fun clampXtConnections(value: Int): Int = value.coerceIn(1, XT_CONNECTIONS_MAX)
    fun clampHotPairCount(value: Int): Int = value.coerceIn(1, MAX_XT_HOT_PAIR_COUNT)
}
