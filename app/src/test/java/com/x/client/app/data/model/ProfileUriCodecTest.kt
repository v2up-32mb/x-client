package com.x.client.app.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ProfileUriCodec 往返编解码、容错、旧格式兼容测试。
 * 纯 JVM，不依赖 Android Framework。
 */
class ProfileUriCodecTest {

    @Test
    fun parse_gcm_basic() {
        val r = ProfileUriCodec.parse("gcm://example.com#MyNode")
        assertThat(r.protocol).isEqualTo(Protocol.GCM)
        assertThat(r.serverAddr).isEqualTo("wss://example.com")
        assertThat(r.name).isEqualTo("MyNode")
    }

    @Test
    fun parse_gcm_fullQuery() {
        val uri = "gcm://example.com?ip=1.1.1.1:443&fip=2.2.2.2&user_id=u123&disable_ech=1&ws_conn=5&enable_dynamic_pool=1&dynamic_pool_max=32#Node"
        val r = ProfileUriCodec.parse(uri)
        assertThat(r.protocol).isEqualTo(Protocol.GCM)
        assertThat(r.serverAddr).isEqualTo("wss://example.com")
        assertThat(r.prefIp).isEqualTo("1.1.1.1:443")
        assertThat(r.fallbackIp).isEqualTo("2.2.2.2")
        assertThat(r.userId).isEqualTo("u123")
        assertThat(r.disableEch).isTrue()
        assertThat(r.wsConn).isEqualTo(5)
        assertThat(r.enableDynamicPool).isTrue()
        assertThat(r.dynamicPoolMax).isEqualTo(32)
        assertThat(r.name).isEqualTo("Node")
    }

    @Test
    fun parse_xtunnel_basic() {
        val r = ProfileUriCodec.parse("xtunnel://tunnel.example.com:8443?token=abc#T")
        assertThat(r.protocol).isEqualTo(Protocol.XTUNNEL)
        assertThat(r.serverAddr).isEqualTo("wss://tunnel.example.com:8443")
        assertThat(r.xtToken).isEqualTo("abc")
        assertThat(r.name).isEqualTo("T")
    }

    @Test
    fun parse_xtunnel_fullQuery() {
        val uri = "xtunnel://tunnel.example.com:8443?token=abc&relay_nodes=r1.com:443,r2.com&connections=4&ech=0&insecure=1&hotpair=3#T"
        val r = ProfileUriCodec.parse(uri)
        assertThat(r.xtToken).isEqualTo("abc")
        assertThat(r.xtRelayNodes).isEqualTo("r1.com:443,r2.com")
        assertThat(r.xtConnections).isEqualTo(4)
        assertThat(r.xtDisableEch).isTrue() // ech=0 → 禁用
        assertThat(r.xtInsecure).isTrue()
        assertThat(r.xtEnableHotPair).isTrue()
        assertThat(r.xtHotPairCount).isEqualTo(3)
    }

    @Test
    fun parse_ech_legacyScheme_treatedAsGcm() {
        val r = ProfileUriCodec.parse("ech://example.com?user_id=u1#Legacy")
        assertThat(r.protocol).isEqualTo(Protocol.GCM)
        assertThat(r.userId).isEqualTo("u1")
    }

    @Test
    fun parse_stripsExtraSlashes() {
        val r = ProfileUriCodec.parse("gcm:///example.com#N")
        assertThat(r.serverAddr).isEqualTo("wss://example.com")
    }

    @Test
    fun parse_keepsWsScheme() {
        val r = ProfileUriCodec.parse("xtunnel://ws://tunnel.example.com:8443#N")
        assertThat(r.serverAddr).isEqualTo("ws://tunnel.example.com:8443")
    }

    @Test
    fun parse_rejectsEmptyHost() {
        try {
            ProfileUriCodec.parse("xtunnel://?token=abc#N")
            throw AssertionError("应拒绝空 host")
        } catch (e: ProfileUriCodec.InvalidProtocolException) {
            assertThat(e.message).contains("缺少服务器地址")
        }
    }

    @Test
    fun parse_rejectsUnknownScheme() {
        try {
            ProfileUriCodec.parse("https://example.com#N")
            throw AssertionError("应拒绝未知协议")
        } catch (e: ProfileUriCodec.InvalidProtocolException) {
            assertThat(e.message).contains("无效的协议格式")
        }
    }

    @Test
    fun parse_legacyRelayAlias() {
        // 旧版用 relay= 表示优选中转节点
        val r = ProfileUriCodec.parse("gcm://example.com?relay=1.1.1.1#N")
        assertThat(r.prefIp).isEqualTo("1.1.1.1")
    }

    @Test
    fun parse_legacyFallbackipAlias() {
        val r = ProfileUriCodec.parse("gcm://example.com?fip=2.2.2.2#N")
        assertThat(r.fallbackIp).isEqualTo("2.2.2.2")
    }

    @Test
    fun parse_legacyHotpairTrueEnablesOnePair() {
        val r = ProfileUriCodec.parse("xtunnel://tunnel.com?hotpair=true#N")
        assertThat(r.xtEnableHotPair).isTrue()
        assertThat(r.xtHotPairCount).isEqualTo(1)
    }

    @Test
    fun parse_legacyHotpairOneAlias() {
        val r = ProfileUriCodec.parse("xtunnel://tunnel.com?hotpair=1#N")
        assertThat(r.xtEnableHotPair).isTrue()
        assertThat(r.xtHotPairCount).isEqualTo(1)
    }

    @Test
    fun parse_legacyHotpairCountEnablesN() {
        val r = ProfileUriCodec.parse("xtunnel://tunnel.com?hotpair=6#N")
        assertThat(r.xtEnableHotPair).isTrue()
        assertThat(r.xtHotPairCount).isEqualTo(6)
    }

    @Test
    fun parse_ignoresLegacyDomainAndDns() {
        // domain/dns 现在是全局设置，导入时忽略
        val r = ProfileUriCodec.parse("gcm://example.com?domain=foo.com&dns=https://doh&user_id=u1#N")
        assertThat(r.userId).isEqualTo("u1")
        // 无字段对应 domain/dns，断言不抛即可
    }

    @Test
    fun parse_urlEncodedFragment() {
        val r = ProfileUriCodec.parse("gcm://example.com#%E4%B8%AD%E6%96%87%E8%8A%82%E7%82%B9")
        assertThat(r.name).isEqualTo("中文节点")
    }

    @Test
    fun parse_defaultName_whenNoFragment() {
        val r = ProfileUriCodec.parse("gcm://example.com")
        assertThat(r.name).isEqualTo("导入节点")
    }

    @Test
    fun parse_echZero_isDisableEch_xtunnel() {
        val r = ProfileUriCodec.parse("xtunnel://t.com?ech=0#N")
        assertThat(r.xtDisableEch).isTrue()
    }

    @Test
    fun parse_echFalse_isDisableEch_xtunnel() {
        val r = ProfileUriCodec.parse("xtunnel://t.com?ech=false#N")
        assertThat(r.xtDisableEch).isTrue()
    }

    @Test
    fun parse_echOne_isNotDisableEch_xtunnel() {
        val r = ProfileUriCodec.parse("xtunnel://t.com?ech=1#N")
        assertThat(r.xtDisableEch).isFalse()
    }

    // ---- 导出 ----

    @Test
    fun export_gcm_basic() {
        val uri = ProfileUriCodec.exportGcm(
            workerHost = "wss://example.com",
            prefIp = "1.1.1.1",
            fallbackIp = "2.2.2.2",
            userId = "u1",
            disableEch = true,
            wsConn = 3,
            enableDynamicPool = true,
            dynamicPoolMax = 16,
            profileName = "Node",
        )
        assertThat(uri).startsWith("gcm://example.com?")
        assertThat(uri).contains("ip=1.1.1.1")
        assertThat(uri).contains("fip=2.2.2.2")
        assertThat(uri).contains("user_id=u1")
        assertThat(uri).contains("disable_ech=1")
        assertThat(uri).contains("ws_conn=3")
        assertThat(uri).contains("enable_dynamic_pool=1")
        assertThat(uri).contains("dynamic_pool_max=16")
        assertThat(uri).endsWith("#Node")
    }

    @Test
    fun export_gcm_stripsWssPrefix() {
        val uri = ProfileUriCodec.exportGcm(
            workerHost = "wss://worker.example.com",
            prefIp = "", fallbackIp = "", userId = "",
            disableEch = false, wsConn = 3,
            enableDynamicPool = false, dynamicPoolMax = 16,
            profileName = "N",
        )
        assertThat(uri).startsWith("gcm://worker.example.com?")
        assertThat(uri).doesNotContain("wss://worker.example.com?")
    }

    @Test
    fun export_gcm_urlEncodesName() {
        val uri = ProfileUriCodec.exportGcm(
            workerHost = "example.com",
            prefIp = "", fallbackIp = "", userId = "",
            disableEch = false, wsConn = 3,
            enableDynamicPool = false, dynamicPoolMax = 16,
            profileName = "中文 节点",
        )
        assertThat(uri).endsWith("#" + java.net.URLEncoder.encode("中文 节点", "UTF-8"))
    }

    @Test
    fun export_xtunnel_basic() {
        val uri = ProfileUriCodec.exportXtunnel(
            serverAddr = "wss://tunnel.example.com:8443",
            token = "abc",
            relayNodes = "r1.com:443,r2.com",
            connections = 4,
            disableEch = true,
            insecure = true,
            enableHotPair = true,
            hotPairCount = 2,
            profileName = "T",
        )
        assertThat(uri).startsWith("xtunnel://tunnel.example.com:8443?")
        assertThat(uri).contains("token=abc")
        assertThat(uri).contains("relay_nodes=r1.com:443,r2.com")
        assertThat(uri).contains("connections=4")
        assertThat(uri).contains("ech=0")
        assertThat(uri).contains("insecure=1")
        assertThat(uri).contains("hotpair=2")
        assertThat(uri).endsWith("#T")
    }

    @Test
    fun export_xtunnel_omitsDefaults() {
        // connections 等于默认值时不导出该参数
        val uri = ProfileUriCodec.exportXtunnel(
            serverAddr = "wss://tunnel.example.com:8443",
            token = "",
            relayNodes = "",
            connections = Limits.DEFAULT_XT_CONNECTIONS,
            disableEch = false,
            insecure = false,
            enableHotPair = false,
            hotPairCount = 1,
            profileName = "T",
        )
        assertThat(uri).doesNotContain("connections=")
        assertThat(uri).doesNotContain("ech=0")
        assertThat(uri).doesNotContain("insecure=1")
        assertThat(uri).doesNotContain("hotpair=")
        assertThat(uri).isEqualTo("xtunnel://tunnel.example.com:8443#T")
    }

    // ---- 往返 ----

    @Test
    fun roundtrip_gcm() {
        val original = "gcm://example.com?ip=1.1.1.1:443&fip=2.2.2.2&user_id=u123&disable_ech=1&ws_conn=5&enable_dynamic_pool=1&dynamic_pool_max=32#RoundTrip"
        val parsed = ProfileUriCodec.parse(original)
        val exported = ProfileUriCodec.exportGcm(
            workerHost = parsed.serverAddr, // 已补 wss://，导出会再次去除
            prefIp = parsed.prefIp,
            fallbackIp = parsed.fallbackIp,
            userId = parsed.userId,
            disableEch = parsed.disableEch,
            wsConn = parsed.wsConn,
            enableDynamicPool = parsed.enableDynamicPool,
            dynamicPoolMax = parsed.dynamicPoolMax,
            profileName = parsed.name,
        )
        // 重新解析导出的 URI 应得到等价字段
        val reparsed = ProfileUriCodec.parse(exported)
        assertThat(reparsed.serverAddr).isEqualTo(parsed.serverAddr)
        assertThat(reparsed.prefIp).isEqualTo(parsed.prefIp)
        assertThat(reparsed.fallbackIp).isEqualTo(parsed.fallbackIp)
        assertThat(reparsed.userId).isEqualTo(parsed.userId)
        assertThat(reparsed.disableEch).isEqualTo(parsed.disableEch)
        assertThat(reparsed.wsConn).isEqualTo(parsed.wsConn)
        assertThat(reparsed.enableDynamicPool).isEqualTo(parsed.enableDynamicPool)
        assertThat(reparsed.dynamicPoolMax).isEqualTo(parsed.dynamicPoolMax)
        assertThat(reparsed.name).isEqualTo(parsed.name)
    }

    @Test
    fun roundtrip_xtunnel() {
        val original = "xtunnel://tunnel.example.com:8443?token=abc&relay_nodes=r1.com:443&connections=4&ech=0&insecure=1&hotpair=3#RoundTrip"
        val parsed = ProfileUriCodec.parse(original)
        val exported = ProfileUriCodec.exportXtunnel(
            serverAddr = parsed.serverAddr,
            token = parsed.xtToken,
            relayNodes = parsed.xtRelayNodes,
            connections = parsed.xtConnections,
            disableEch = parsed.xtDisableEch,
            insecure = parsed.xtInsecure,
            enableHotPair = parsed.xtEnableHotPair,
            hotPairCount = parsed.xtHotPairCount,
            profileName = parsed.name,
        )
        val reparsed = ProfileUriCodec.parse(exported)
        assertThat(reparsed.serverAddr).isEqualTo(parsed.serverAddr)
        assertThat(reparsed.xtToken).isEqualTo(parsed.xtToken)
        assertThat(reparsed.xtRelayNodes).isEqualTo(parsed.xtRelayNodes)
        assertThat(reparsed.xtConnections).isEqualTo(parsed.xtConnections)
        assertThat(reparsed.xtDisableEch).isEqualTo(parsed.xtDisableEch)
        assertThat(reparsed.xtInsecure).isEqualTo(parsed.xtInsecure)
        assertThat(reparsed.xtEnableHotPair).isEqualTo(parsed.xtEnableHotPair)
        assertThat(reparsed.xtHotPairCount).isEqualTo(parsed.xtHotPairCount)
        assertThat(reparsed.name).isEqualTo(parsed.name)
    }
}
