package com.poyka.ripdpi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketBackendWireTest {
    private val socket = "/tmp/packet-backend.sock"

    @Test
    fun proxyBackendFieldsKeepTheirWireShapeAndDecode() {
        val defaults = RipDpiProxyJsonCodec.encodeUiPreferences(RipDpiProxyUIPreferences())
        val defaultsDocument = Json.parseToJsonElement(defaults).jsonObject
        assertEquals(JsonPrimitive(false), defaultsDocument["rootMode"])
        assertFalse(defaultsDocument.containsKey("rootHelperSocketPath"))
        val defaultPreferences = requireNotNull(decodeRipDpiProxyUiPreferences(defaults))
        assertFalse(defaultPreferences.packetBackendRequested)
        assertNull(defaultPreferences.packetBackendSocketPath)

        val config =
            RipDpiProxyJsonCodec.encodeUiPreferences(
                RipDpiProxyUIPreferences(),
                packetBackendRequested = true,
                packetBackendSocketPath = socket,
                geoipDbPath = "/tmp/geoip.db",
            )
        val document = Json.parseToJsonElement(config).jsonObject
        assertEquals(JsonPrimitive(true), document["rootMode"])
        assertEquals(JsonPrimitive(socket), document["rootHelperSocketPath"])
        val keys = document.keys.toList()
        assertEquals(keys.indexOf("geoipDbPath") - 1, keys.indexOf("rootHelperSocketPath"))
        val preferences = requireNotNull(decodeRipDpiProxyUiPreferences(config))
        assertTrue(preferences.packetBackendRequested)
        assertEquals(socket, preferences.packetBackendSocketPath)

        val rewritten = RipDpiProxyJsonCodec.rewriteJson(config, null, null, null, null)
        val updated = Json.parseToJsonElement(rewritten).jsonObject
        assertEquals(JsonPrimitive(false), updated["rootMode"])
        assertEquals(JsonPrimitive(socket), updated["rootHelperSocketPath"])
    }

    @Test
    fun tunnelConfigRetainsExplicitNullAndConfiguredSocketPath() {
        val defaults = Tun2SocksConfig(socks5Port = 1080)
        val defaultDocument = Json.parseToJsonElement(encodeTunnelConfig(defaults)).jsonObject
        assertEquals(JsonNull, defaultDocument["rootHelperSocketPath"])
        val document =
            Json.parseToJsonElement(encodeTunnelConfig(defaults.copy(packetBackendSocketPath = socket))).jsonObject
        assertEquals(JsonPrimitive(socket), document["rootHelperSocketPath"])
        val keys = document.keys.toList()
        assertEquals(keys.indexOf("luaScriptBaseDir") - 1, keys.indexOf("rootHelperSocketPath"))
    }
}
