package com.x.client.app.data.model

/**
 * 一个 Profile 的全部配置字段（GCM + X-Tunnel 共存，切换协议时互不丢失）。
 * 对应原 Preferences.java 中带 _<id> 后缀的全部 per-profile 键。
 */
data class ProfileConfig(
    val id: String,
    val name: String,
    val protocol: String = Protocol.GCM,

    // ---- GCM 字段 ----
    val workerHost: String = "",
    val prefIp: String = "",
    val userId: String = "",
    val fallbackIp: String = "",
    val disableEch: Boolean = false,
    val disableIpv6Route: Boolean = false,
    val wsConn: Int = Limits.DEFAULT_WS_CONN,
    val enableDynamicPool: Boolean = false,
    val dynamicPoolMax: Int = Limits.DEFAULT_DYNAMIC_POOL_MAX,

    // ---- X-Tunnel 字段 ----
    val xtServerAddr: String = "",
    val xtToken: String = "",
    val xtRelayNodes: String = "",
    val xtConnections: Int = Limits.DEFAULT_XT_CONNECTIONS,
    val xtDisableEch: Boolean = false,
    val xtInsecure: Boolean = false,
    val xtEnableHotPair: Boolean = false,
    val xtHotPairCount: Int = Limits.DEFAULT_XT_HOT_PAIR_COUNT,
    val xtAdvancedParams: XtAdvancedParams = XtAdvancedParams.EMPTY,
) {
    /** 列表/卡片展示用服务器地址。 */
    val displayServerAddr: String
        get() = if (protocol == Protocol.XTUNNEL) {
            ProfileInfo.displayAddr(xtServerAddr)
        } else {
            ProfileInfo.displayAddr(workerHost)
        }
}
