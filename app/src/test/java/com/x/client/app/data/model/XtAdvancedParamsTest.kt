package com.x.client.app.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * XtAdvancedParams + Limits 范围裁剪测试。纯 JVM。
 */
class XtAdvancedParamsTest {

    @Test
    fun toParamsMap_includesAllSetFields() {
        val params = XtAdvancedParams(
            backpressureLimit = 8L * 1024 * 1024, // 8MB 字节
            writeQueueWaitTimeout = 100L,
            dialTimeout = 3000L,
            handshakeTimeout = 5000L,
            readTimeout = 15000L,
            writeTimeout = 5000L,
            pingInterval = 5000L,
            reconnectDelay = 1000L,
            connectTimeout = 15000L,
            maxSocks5Connections = 1024,
            udpBlockedPorts = "443,53",
        )
        val map = params.toParamsMap()
        assertThat(map["backpressure_limit"]).isEqualTo(8L * 1024 * 1024)
        assertThat(map["write_queue_wait_timeout"]).isEqualTo(100L)
        assertThat(map["dial_timeout"]).isEqualTo(3000L)
        assertThat(map["handshake_timeout"]).isEqualTo(5000L)
        assertThat(map["read_timeout"]).isEqualTo(15000L)
        assertThat(map["write_timeout"]).isEqualTo(5000L)
        assertThat(map["ping_interval"]).isEqualTo(5000L)
        assertThat(map["reconnect_delay"]).isEqualTo(1000L)
        assertThat(map["connect_timeout"]).isEqualTo(15000L)
        assertThat(map["max_socks5_connections"]).isEqualTo(1024)
        assertThat(map["udp_blocked_ports"]).isEqualTo("443,53")
    }

    @Test
    fun toParamsMap_emptyForAllNull() {
        assertThat(XtAdvancedParams.EMPTY.toParamsMap()).isEmpty()
    }

    @Test
    fun toParamsMap_skipsNullFields() {
        val params = XtAdvancedParams(dialTimeout = 3000L)
        val map = params.toParamsMap()
        assertThat(map).hasSize(1)
        assertThat(map["dial_timeout"]).isEqualTo(3000L)
        assertThat(map).doesNotContainKey("backpressure_limit")
    }

    // ---- Limits 范围裁剪 ----

    @Test
    fun clampWsConn_withinRange() {
        assertThat(Limits.clampWsConn(5)).isEqualTo(5)
    }

    @Test
    fun clampWsConn_belowMinClampedToOne() {
        assertThat(Limits.clampWsConn(0)).isEqualTo(1)
        assertThat(Limits.clampWsConn(-10)).isEqualTo(1)
    }

    @Test
    fun clampWsConn_aboveMaxClampedToLimit() {
        assertThat(Limits.clampWsConn(100)).isEqualTo(Limits.MAX_DYNAMIC_POOL_LIMIT)
    }

    @Test
    fun clampDynamicPoolMax_bounds() {
        assertThat(Limits.clampDynamicPoolMax(16)).isEqualTo(16)
        assertThat(Limits.clampDynamicPoolMax(0)).isEqualTo(1)
        assertThat(Limits.clampDynamicPoolMax(999)).isEqualTo(Limits.MAX_DYNAMIC_POOL_LIMIT)
    }

    @Test
    fun clampXtConnections_bounds() {
        assertThat(Limits.clampXtConnections(3)).isEqualTo(3)
        assertThat(Limits.clampXtConnections(0)).isEqualTo(1)
        assertThat(Limits.clampXtConnections(50)).isEqualTo(Limits.XT_CONNECTIONS_MAX)
    }

    @Test
    fun clampHotPairCount_bounds() {
        assertThat(Limits.clampHotPairCount(4)).isEqualTo(4)
        assertThat(Limits.clampHotPairCount(0)).isEqualTo(1)
        assertThat(Limits.clampHotPairCount(99)).isEqualTo(Limits.MAX_XT_HOT_PAIR_COUNT)
    }
}
