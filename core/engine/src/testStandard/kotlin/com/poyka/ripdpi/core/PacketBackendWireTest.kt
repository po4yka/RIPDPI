package com.poyka.ripdpi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PacketBackendWireTest {
    private val uiConfig =
        """{"kind":"ui","schemaVersion":2,"listen":{"port":1111},"rootMode":true,"rootHelperSocketPath":"/tmp/legacy"}"""

    @Test
    fun legacyBackendFieldsAreIgnoredAndNeverForwarded() {
        val preferences = requireNotNull(decodeRipDpiProxyUiPreferences(uiConfig))
        assertEquals(1111, preferences.listen.port)
        assertFalse(preferences.packetBackendRequested)
        assertNull(preferences.packetBackendSocketPath)
        for (encoded in listOf(
            RipDpiProxyJsonCodec.encodeUiPreferences(
                preferences,
                packetBackendRequested = true,
                packetBackendSocketPath = "/tmp/supplied",
            ),
            RipDpiProxyJsonCodec.rewriteJson(uiConfig, null, null, null, null, packetBackendRequested = true),
            RipDpiProxyJsonCodec.stripRuntimeContext(uiConfig),
            RipDpiProxyJsonCodec.rewriteUdpAssociateEnabled(uiConfig, false),
        )) {
            val document = Json.parseToJsonElement(RipDpiProxyJsonCodec.prepareNativeCreateJson(encoded)).jsonObject
            assertFalse(document.containsKey("rootMode"))
            assertFalse(document.containsKey("rootHelperSocketPath"))
            assertEquals(
                "1111",
                document
                    .getValue("listen")
                    .jsonObject
                    .getValue("port")
                    .toString(),
            )
        }
    }

    @Test
    fun tunnelConfigOmitsPacketBackendChannel() {
        val document =
            Json
                .parseToJsonElement(
                    encodeTunnelConfig(Tun2SocksConfig(socks5Port = 1080, packetBackendSocketPath = "/tmp/supplied")),
                ).jsonObject
        assertFalse(document.containsKey("rootHelperSocketPath"))
        assertEquals("1080", document.getValue("socks5Port").toString())
    }
}
