package com.x.client.app.data.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnsupportedEncodingException

/**
 * Profile URI 编解码（纯 JVM，可单测）。
 *
 * 1:1 复刻原 ProfileListActivity / ProfileEditActivity 的 importFromProtocol /
 * exportProfile 逻辑，保持 `gcm://` / `ech://`(兼容) / `xtunnel://` 格式完全兼容。
 *
 * 导出规则：
 * - gcm：wssAddr(去 wss://) + ?ip=&fip=&user_id=&disable_ech=1&ws_conn=&
 *   enable_dynamic_pool=&dynamic_pool_max= + #URLEncoder(name)
 * - xtunnel：xtServerAddr(去 wss://|ws://) + ?token=&relay_nodes=&connections=&
 *   ech=0&insecure=1&hotpair=N + #URLEncoder(name)；ECH 域名/DoH 不写入(复用全局)
 *
 * 导入规则：解析 host(容错去多余/、补 wss://、拒绝空 host)、query(各 key 容错)、
 * fragment(URLDecoder name)，兼容旧 relay/fallbackip/domain/dns/hotpair=1。
 */
object ProfileUriCodec {

    /** 导入结果（不立即持久化，由调用方决定创建/填充表单）。 */
    data class ImportResult(
        val protocol: String,
        val serverAddr: String,      // 已补全 wss:// 前缀
        val name: String,
        // GCM 字段
        val prefIp: String = "",
        val fallbackIp: String = "",
        val userId: String = "",
        val disableEch: Boolean = false,
        val wsConn: Int = Limits.DEFAULT_WS_CONN,
        val enableDynamicPool: Boolean = false,
        val dynamicPoolMax: Int = Limits.DEFAULT_DYNAMIC_POOL_MAX,
        // X-Tunnel 字段
        val xtToken: String = "",
        val xtRelayNodes: String = "",
        val xtConnections: Int = Limits.DEFAULT_XT_CONNECTIONS,
        val xtDisableEch: Boolean = false,
        val xtInsecure: Boolean = false,
        val xtEnableHotPair: Boolean = false,
        val xtHotPairCount: Int = Limits.DEFAULT_XT_HOT_PAIR_COUNT,
    )

    /** 解析失败异常。 */
    class InvalidProtocolException(message: String) : Exception(message)

    /**
     * 解析 URI 字符串为 [ImportResult]。非法格式抛 [InvalidProtocolException]。
     */
    @Throws(InvalidProtocolException::class)
    fun parse(uri: String): ImportResult {
        val isXtunnel = uri.startsWith("xtunnel://")
        if (!isXtunnel && !uri.startsWith("gcm://") && !uri.startsWith("ech://")) {
            throw InvalidProtocolException("无效的协议格式")
        }
        val schemeLen = if (isXtunnel) 10 else 6
        var rest = uri.substring(schemeLen)

        // 分离 fragment
        var fragment = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = rest.substring(hash + 1)
            rest = rest.substring(0, hash)
        }

        var wssAddr: String
        var query = ""
        val qmark = rest.indexOf('?')
        if (qmark >= 0) {
            wssAddr = rest.substring(0, qmark)
            query = rest.substring(qmark + 1)
        } else {
            wssAddr = rest
        }
        // 容错：剥离 host 前的多余斜杠（旧版分享链接可能多一个 /）
        while (wssAddr.startsWith("/")) {
            wssAddr = wssAddr.substring(1)
        }
        // 确保 wss:// 前缀
        if (!wssAddr.startsWith("wss://") && !wssAddr.startsWith("ws://")) {
            wssAddr = "wss://" + wssAddr
        }
        // 拒绝缺少服务器主机的链接（如旧版 xtunnel://?token=... 无 host）
        if (wssAddr.length <= 6) {
            throw InvalidProtocolException("链接缺少服务器地址，无法导入")
        }

        // 解析查询参数
        var prefIp = ""
        var fallbackIp = ""
        var userId = ""
        var disableEch = false
        var wsConn = Limits.DEFAULT_WS_CONN
        var enableDynamicPool = false
        var dynamicPoolMax = Limits.DEFAULT_DYNAMIC_POOL_MAX
        var xtToken = ""
        var xtRelayNodes = ""
        var xtConnections = Limits.DEFAULT_XT_CONNECTIONS
        var xtDisableEch = false
        var xtInsecure = false
        var xtEnableHotPair = false
        var xtHotPairCount = Limits.DEFAULT_XT_HOT_PAIR_COUNT
        if (query.isNotEmpty()) {
            for (pair in query.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size != 2) continue
                val key = kv[0]
                val value = kv[1]
                when (key) {
                    "ip", "relay" -> prefIp = value // 兼容旧 relay=
                    "fip", "fallbackip" -> fallbackIp = value
                    "disable_ech" ->
                        disableEch = value.isTruthy()
                    "ws_conn" -> value.toIntOrNull()?.let { wsConn = it }
                    "enable_dynamic_pool" ->
                        enableDynamicPool = value.isTruthy()
                    "dynamic_pool_max" -> value.toIntOrNull()?.let { dynamicPoolMax = it }
                    "token", "user_id" ->
                        if (isXtunnel) xtToken = value else userId = value
                    "relay_nodes" -> xtRelayNodes = value
                    "connections" -> value.toIntOrNull()?.let { xtConnections = it }
                    "ech" ->
                        // ech=0 表示禁用 ECH（与 GCM 的 disable_ech 语义一致）
                        xtDisableEch = value.isFalsy()
                    "domain", "dns" -> { /* 兼容旧 URI，忽略；复用全局设置 */ }
                    "insecure" -> xtInsecure = value.isTruthy()
                    "hotpair" -> {
                        // hotpair=1/true/yes 兼容旧格式（启用 1 对）；
                        // hotpair=2..8 表示启用 N 对
                        val n = value.toIntOrNull() ?: 1
                        if (n in 2..Limits.MAX_XT_HOT_PAIR_COUNT) {
                            xtEnableHotPair = true
                            xtHotPairCount = n
                        } else {
                            xtEnableHotPair = value.isTruthy()
                            xtHotPairCount = 1
                        }
                    }
                }
            }
        }

        // 从 fragment 解码配置名称
        var name = "导入节点"
        if (fragment.isNotEmpty()) {
            name = try {
                URLDecoder.decode(fragment, "UTF-8")
            } catch (_: UnsupportedEncodingException) {
                fragment
            }
        }

        return ImportResult(
            protocol = if (isXtunnel) Protocol.XTUNNEL else Protocol.GCM,
            serverAddr = wssAddr,
            name = name,
            prefIp = prefIp,
            fallbackIp = fallbackIp,
            userId = userId,
            disableEch = disableEch,
            wsConn = wsConn,
            enableDynamicPool = enableDynamicPool,
            dynamicPoolMax = dynamicPoolMax,
            xtToken = xtToken,
            xtRelayNodes = xtRelayNodes,
            xtConnections = xtConnections,
            xtDisableEch = xtDisableEch,
            xtInsecure = xtInsecure,
            xtEnableHotPair = xtEnableHotPair,
            xtHotPairCount = xtHotPairCount,
        )
    }

    /**
     * 导出 GCM 协议 URI。
     * @param workerHost 服务器地址（可能含 wss:// 前缀）
     * @param profileName 配置名称（URL 编码为 fragment）
     */
    fun exportGcm(
        workerHost: String,
        prefIp: String,
        fallbackIp: String,
        userId: String,
        disableEch: Boolean,
        wsConn: Int,
        enableDynamicPool: Boolean,
        dynamicPoolMax: Int,
        profileName: String,
    ): String {
        var wssAddr = workerHost
        if (wssAddr.startsWith("wss://")) wssAddr = wssAddr.substring(6)
        val query = StringBuilder()
        if (prefIp.isNotEmpty()) query.append("ip=$prefIp")
        if (fallbackIp.isNotEmpty()) {
            if (query.isNotEmpty()) query.append("&")
            query.append("fip=$fallbackIp")
        }
        if (userId.isNotEmpty()) {
            if (query.isNotEmpty()) query.append("&")
            query.append("user_id=$userId")
        }
        if (disableEch) {
            if (query.isNotEmpty()) query.append("&")
            query.append("disable_ech=1")
        }
        // 连接池参数始终导出，保证分享无损
        if (query.isNotEmpty()) query.append("&")
        query.append("ws_conn=$wsConn")
        query.append("&enable_dynamic_pool=${if (enableDynamicPool) 1 else 0}")
        query.append("&dynamic_pool_max=$dynamicPoolMax")
        return "gcm://$wssAddr?$query#${urlEncode(profileName)}"
    }

    /**
     * 导出 X-Tunnel 协议 URI。ECH 域名/DoH 复用全局设置，不写入 URI。
     * @param serverAddr 服务器地址（可能含 wss://|ws:// 前缀）
     */
    fun exportXtunnel(
        serverAddr: String,
        token: String,
        relayNodes: String,
        connections: Int,
        disableEch: Boolean,
        insecure: Boolean,
        enableHotPair: Boolean,
        hotPairCount: Int,
        profileName: String,
    ): String {
        var addr = serverAddr
        if (addr.startsWith("wss://")) addr = addr.substring(6)
        else if (addr.startsWith("ws://")) addr = addr.substring(5)
        val query = StringBuilder()
        if (token.isNotEmpty()) query.append("token=$token")
        if (relayNodes.isNotEmpty()) {
            if (query.isNotEmpty()) query.append("&")
            query.append("relay_nodes=$relayNodes")
        }
        if (connections != Limits.DEFAULT_XT_CONNECTIONS) {
            if (query.isNotEmpty()) query.append("&")
            query.append("connections=$connections")
        }
        if (disableEch) {
            if (query.isNotEmpty()) query.append("&")
            query.append("ech=0")
        }
        if (insecure) {
            if (query.isNotEmpty()) query.append("&")
            query.append("insecure=1")
        }
        if (enableHotPair) {
            if (query.isNotEmpty()) query.append("&")
            query.append("hotpair=$hotPairCount")
        }
        val sb = StringBuilder("xtunnel://$addr")
        if (query.isNotEmpty()) sb.append("?").append(query)
        sb.append("#").append(urlEncode(profileName))
        return sb.toString()
    }

    private fun urlEncode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: UnsupportedEncodingException) {
        value
    }

    private fun String.isTruthy(): Boolean =
        this == "1" || equals("true", ignoreCase = true) || equals("yes", ignoreCase = true)

    private fun String.isFalsy(): Boolean =
        this == "0" || equals("false", ignoreCase = true) || equals("no", ignoreCase = true)
}
