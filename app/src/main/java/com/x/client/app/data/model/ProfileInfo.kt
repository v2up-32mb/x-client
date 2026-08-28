package com.x.client.app.data.model

/**
 * 列表展示用的配置摘要。对应原 Preferences.ProfileInfo。
 */
data class ProfileInfo(
    val id: String,
    val name: String,
    val serverAddr: String,
    val protocol: String,
) {
    companion object {
        /**
         * 从原始 workerHost / xtServerAddr 提取展示用的服务器地址：
         * 去掉 wss:// / ws:// 前缀与路径，空则显示"未配置"。
         */
        fun displayAddr(raw: String?): String {
            var addr = raw ?: ""
            if (addr.startsWith("wss://")) addr = addr.substring(6)
            else if (addr.startsWith("ws://")) addr = addr.substring(5)
            val slash = addr.indexOf('/')
            if (slash > 0) addr = addr.substring(0, slash)
            return addr.ifEmpty { "未配置" }
        }
    }
}

/**
 * X-Tunnel 高级参数（JSON 存储；留空项表示使用默认值）。
 * 对应原 ProfileEditActivity 的 collectXtAdvancedParams / loadXtAdvancedParams。
 */
data class XtAdvancedParams(
    val backpressureLimit: Long? = null,        // 字节，UI 输入 MB
    val writeQueueWaitTimeout: Long? = null,    // 毫秒
    val dialTimeout: Long? = null,
    val handshakeTimeout: Long? = null,
    val readTimeout: Long? = null,
    val writeTimeout: Long? = null,
    val pingInterval: Long? = null,
    val reconnectDelay: Long? = null,
    val connectTimeout: Long? = null,
    val maxSocks5Connections: Int? = null,      // 0 表示无限制
    val udpBlockedPorts: String? = null,        // 逗号分隔端口
) {
    /** 序列化为 Go 侧 paramsJSON 覆盖键（毫秒/字节整数，负值非法已在收集时校验）。 */
    fun toParamsMap(): Map<String, Any> = buildMap {
        backpressureLimit?.let { put("backpressure_limit", it) }
        writeQueueWaitTimeout?.let { put("write_queue_wait_timeout", it) }
        dialTimeout?.let { put("dial_timeout", it) }
        handshakeTimeout?.let { put("handshake_timeout", it) }
        readTimeout?.let { put("read_timeout", it) }
        writeTimeout?.let { put("write_timeout", it) }
        pingInterval?.let { put("ping_interval", it) }
        reconnectDelay?.let { put("reconnect_delay", it) }
        connectTimeout?.let { put("connect_timeout", it) }
        maxSocks5Connections?.let { put("max_socks5_connections", it) }
        udpBlockedPorts?.let { put("udp_blocked_ports", it) }
    }

    companion object {
        val EMPTY = XtAdvancedParams()
    }
}
