package com.x.client.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.x.client.app.data.model.Limits
import com.x.client.app.data.model.ProfileConfig
import com.x.client.app.data.model.ProfileInfo
import com.x.client.app.data.model.Protocol
import com.x.client.app.data.model.XtAdvancedParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile 配置 DataStore（跨进程一致）。
 *
 * per-profile 键名带 _<id> 后缀（与原 SharedPreferences 完全一致），全局键由
 * [GlobalSettingsDataStore] 管理。Profile 增删改通过 edit 原子写入。
 *
 * 迁移说明：原 Preferences.migrateGlobalNetworkSettings 将某些 per-profile 键
 * 提升为全局键（仅在首次升级、主进程执行）。新项目从空白 DataStore 起步，
 * 不存在旧 SP 数据，故迁移逻辑保留为幂等占位（见 [GlobalSettingsDataStore] 的
 * 默认值），不再需要显式搬迁。
 */
@Singleton
class ProfileDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {

    /** 同步读取某个 Profile 完整配置（供 :vpn 进程启动时 runBlocking 使用）。 */
    suspend fun getProfile(id: String): ProfileConfig {
        val prefs = dataStore.data.first()
        return prefs.toProfileConfig(id)
    }

    /** 列表展示：从当前快照构造所有 Profile 的摘要。 */
    suspend fun getProfileList(profileIds: Set<String>): List<ProfileInfo> {
        val prefs = dataStore.data.first()
        return profileIds.map { id ->
            val protocol = prefs[Keys.protocol(id)] ?: Protocol.GCM
            val name = prefs[Keys.profileName(id)] ?: "Node $id"
            val rawAddr = if (protocol == Protocol.XTUNNEL) {
                prefs[Keys.xtServerAddr(id)] ?: ""
            } else {
                prefs[Keys.workerHost(id)] ?: ""
            }
            ProfileInfo(id, name, ProfileInfo.displayAddr(rawAddr), protocol)
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun setProfileName(id: String, name: String) {
        dataStore.edit { it[Keys.profileName(id)] = name }
    }

    suspend fun saveProfile(config: ProfileConfig) {
        dataStore.edit { p ->
            p[Keys.profileName(config.id)] = config.name
            p[Keys.protocol(config.id)] = config.protocol
            p[Keys.workerHost(config.id)] = config.workerHost
            p[Keys.prefIp(config.id)] = config.prefIp
            p[Keys.userId(config.id)] = config.userId
            p[Keys.fallbackIp(config.id)] = config.fallbackIp
            p[Keys.disableEch(config.id)] = config.disableEch
            p[Keys.disableIpv6Route(config.id)] = config.disableIpv6Route
            p[Keys.wsConn(config.id)] = Limits.clampWsConn(config.wsConn)
            p[Keys.enableDynamicPool(config.id)] = config.enableDynamicPool
            p[Keys.dynamicPoolMax(config.id)] = Limits.clampDynamicPoolMax(config.dynamicPoolMax)
            p[Keys.xtServerAddr(config.id)] = config.xtServerAddr
            p[Keys.xtToken(config.id)] = config.xtToken
            p[Keys.xtRelayNodes(config.id)] = config.xtRelayNodes
            p[Keys.xtConnections(config.id)] = Limits.clampXtConnections(config.xtConnections)
            p[Keys.xtDisableEch(config.id)] = config.xtDisableEch
            p[Keys.xtInsecure(config.id)] = config.xtInsecure
            p[Keys.xtEnableHotPair(config.id)] = config.xtEnableHotPair
            p[Keys.xtHotPairCount(config.id)] = Limits.clampHotPairCount(config.xtHotPairCount)
            p[Keys.xtAdvancedParams(config.id)] = config.xtAdvancedParams.toJson()
        }
    }

    /** 复制 profile：逐键读取带 _<sourceId> 后缀的值，按新后缀写入。 */
    suspend fun copyProfile(sourceId: String, newId: String) {
        val prefs = dataStore.data.first()
        dataStore.edit { p ->
            p[Keys.profileName(newId)] = prefs[Keys.profileName(sourceId)] ?: ""
            p[Keys.workerHost(newId)] = prefs[Keys.workerHost(sourceId)] ?: ""
            p[Keys.prefIp(newId)] = prefs[Keys.prefIp(sourceId)] ?: ""
            p[Keys.userId(newId)] = prefs[Keys.userId(sourceId)] ?: ""
            p[Keys.fallbackIp(newId)] = prefs[Keys.fallbackIp(sourceId)] ?: ""
            p[Keys.disableEch(newId)] = prefs[Keys.disableEch(sourceId)] ?: false
            p[Keys.disableIpv6Route(newId)] = prefs[Keys.disableIpv6Route(sourceId)] ?: false
            p[Keys.wsConn(newId)] = prefs[Keys.wsConn(sourceId)] ?: Limits.DEFAULT_WS_CONN
            p[Keys.enableDynamicPool(newId)] = prefs[Keys.enableDynamicPool(sourceId)] ?: false
            p[Keys.dynamicPoolMax(newId)] = prefs[Keys.dynamicPoolMax(sourceId)] ?: Limits.DEFAULT_DYNAMIC_POOL_MAX
            p[Keys.protocol(newId)] = prefs[Keys.protocol(sourceId)] ?: Protocol.GCM
            p[Keys.xtServerAddr(newId)] = prefs[Keys.xtServerAddr(sourceId)] ?: ""
            p[Keys.xtToken(newId)] = prefs[Keys.xtToken(sourceId)] ?: ""
            p[Keys.xtRelayNodes(newId)] = prefs[Keys.xtRelayNodes(sourceId)] ?: ""
            p[Keys.xtConnections(newId)] = prefs[Keys.xtConnections(sourceId)] ?: Limits.DEFAULT_XT_CONNECTIONS
            p[Keys.xtDisableEch(newId)] = prefs[Keys.xtDisableEch(sourceId)] ?: false
            p[Keys.xtInsecure(newId)] = prefs[Keys.xtInsecure(sourceId)] ?: false
            p[Keys.xtEnableHotPair(newId)] = prefs[Keys.xtEnableHotPair(sourceId)] ?: false
            p[Keys.xtHotPairCount(newId)] = prefs[Keys.xtHotPairCount(sourceId)] ?: Limits.DEFAULT_XT_HOT_PAIR_COUNT
            p[Keys.xtAdvancedParams(newId)] = prefs[Keys.xtAdvancedParams(sourceId)] ?: ""
        }
    }

    /** 删除 profile：移除该 id 的全部 per-profile 键。 */
    suspend fun removeProfile(id: String) {
        dataStore.edit { p ->
            p.remove(Keys.profileName(id))
            Keys.profileKeys(id).forEach { p.remove(it) }
        }
    }

    private fun Preferences.toProfileConfig(id: String): ProfileConfig = ProfileConfig(
        id = id,
        name = this[Keys.profileName(id)] ?: "Node $id",
        protocol = this[Keys.protocol(id)]?.takeIf { it.isNotBlank() } ?: Protocol.GCM,
        workerHost = this[Keys.workerHost(id)] ?: "",
        prefIp = this[Keys.prefIp(id)] ?: "",
        userId = this[Keys.userId(id)] ?: "",
        fallbackIp = this[Keys.fallbackIp(id)] ?: "",
        disableEch = this[Keys.disableEch(id)] ?: false,
        disableIpv6Route = this[Keys.disableIpv6Route(id)] ?: false,
        wsConn = this[Keys.wsConn(id)] ?: Limits.DEFAULT_WS_CONN,
        enableDynamicPool = this[Keys.enableDynamicPool(id)] ?: false,
        dynamicPoolMax = this[Keys.dynamicPoolMax(id)] ?: Limits.DEFAULT_DYNAMIC_POOL_MAX,
        xtServerAddr = this[Keys.xtServerAddr(id)] ?: "",
        xtToken = this[Keys.xtToken(id)] ?: "",
        xtRelayNodes = this[Keys.xtRelayNodes(id)] ?: "",
        xtConnections = this[Keys.xtConnections(id)] ?: Limits.DEFAULT_XT_CONNECTIONS,
        xtDisableEch = this[Keys.xtDisableEch(id)] ?: false,
        xtInsecure = this[Keys.xtInsecure(id)] ?: false,
        xtEnableHotPair = this[Keys.xtEnableHotPair(id)] ?: false,
        xtHotPairCount = this[Keys.xtHotPairCount(id)] ?: Limits.DEFAULT_XT_HOT_PAIR_COUNT,
        xtAdvancedParams = this[Keys.xtAdvancedParams(id)].parseAdvanced(),
    )

    // ---- X-Tunnel 高级参数 JSON 编解码（秒↔毫秒、MB↔字节） ----

    private fun String?.parseAdvanced(): XtAdvancedParams {
        val json = this?.trim().orEmpty()
        if (json.isEmpty()) return XtAdvancedParams.EMPTY
        return try {
            val o = JSONObject(json)
            XtAdvancedParams(
                backpressureLimit = o.optLong("backpressure_limit", 0).takeIf { it > 0 },
                writeQueueWaitTimeout = o.optLong("write_queue_wait_timeout", 0).takeIf { it > 0 },
                dialTimeout = o.optLong("dial_timeout", 0).takeIf { it > 0 },
                handshakeTimeout = o.optLong("handshake_timeout", 0).takeIf { it > 0 },
                readTimeout = o.optLong("read_timeout", 0).takeIf { it > 0 },
                writeTimeout = o.optLong("write_timeout", 0).takeIf { it > 0 },
                pingInterval = o.optLong("ping_interval", 0).takeIf { it > 0 },
                reconnectDelay = o.optLong("reconnect_delay", 0).takeIf { it > 0 },
                connectTimeout = o.optLong("connect_timeout", 0).takeIf { it > 0 },
                maxSocks5Connections = o.optInt("max_socks5_connections", -1).takeIf { it >= 0 },
                udpBlockedPorts = o.optString("udp_blocked_ports", "").takeIf { it.isNotEmpty() },
            )
        } catch (_: JSONException) {
            XtAdvancedParams.EMPTY // 损坏数据忽略，使用默认值
        }
    }

    private fun XtAdvancedParams.toJson(): String {
        if (backpressureLimit == null && writeQueueWaitTimeout == null && dialTimeout == null &&
            handshakeTimeout == null && readTimeout == null && writeTimeout == null &&
            pingInterval == null && reconnectDelay == null && connectTimeout == null &&
            maxSocks5Connections == null && udpBlockedPorts == null
        ) return ""
        val o = JSONObject()
        backpressureLimit?.let { o.put("backpressure_limit", it) }
        writeQueueWaitTimeout?.let { o.put("write_queue_wait_timeout", it) }
        dialTimeout?.let { o.put("dial_timeout", it) }
        handshakeTimeout?.let { o.put("handshake_timeout", it) }
        readTimeout?.let { o.put("read_timeout", it) }
        writeTimeout?.let { o.put("write_timeout", it) }
        pingInterval?.let { o.put("ping_interval", it) }
        reconnectDelay?.let { o.put("reconnect_delay", it) }
        connectTimeout?.let { o.put("connect_timeout", it) }
        maxSocks5Connections?.let { o.put("max_socks5_connections", it) }
        udpBlockedPorts?.let { o.put("udp_blocked_ports", it) }
        return if (o.length() > 0) o.toString() else ""
    }
}
