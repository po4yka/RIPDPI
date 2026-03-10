package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Tun2SocksTunnelTest {
    @Test
    fun tunnelStatsMapNativeArrayIntoTypedModel() {
        val stats = TunnelStats.fromNative(longArrayOf(10L, 20L, 30L, 40L))

        assertEquals(10L, stats.txPackets)
        assertEquals(20L, stats.txBytes)
        assertEquals(30L, stats.rxPackets)
        assertEquals(40L, stats.rxBytes)
    }

    @Test
    fun tunnelConfigSerializesToCamelCaseJson() {
        val config =
            Tun2SocksConfig(
                tunnelName = "tun1",
                socks5Port = 1081,
                logLevel = "info",
            )

        val payload = Json.parseToJsonElement(Json.encodeToString(config)).jsonObject

        assertEquals("tun1", payload.getValue("tunnelName").jsonPrimitive.content)
        assertEquals("1081", payload.getValue("socks5Port").jsonPrimitive.content)
        assertEquals("info", payload.getValue("logLevel").jsonPrimitive.content)
    }
}
