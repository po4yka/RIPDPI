package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

class RipDpiProxyPreferencesTest {
    @Test
    fun commandLinePreferencesEncodeSingleJsonPayload() {
        val preferences = RipDpiProxyCmdPreferences("--port 1081 --no-domain")

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("command_line", payload.string("kind"))
        val args = payload["args"] as JsonArray
        assertEquals("ciadpi", args[0].jsonPrimitive.content)
        assertTrue("--port" in args.map { it.jsonPrimitive.content })
        assertTrue("--no-domain" in args.map { it.jsonPrimitive.content })
    }

    @Test
    fun uiPreferencesEncodeCamelCaseFields() {
        val preferences =
            RipDpiProxyUIPreferences(
                ip = "127.0.0.1",
                port = 1080,
                maxConnections = 1024,
                hostsMode = RipDpiProxyUIPreferences.HostsMode.Blacklist,
                hosts = "example.com",
                fakeSni = "www.example.com",
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("ui", payload.string("kind"))
        assertEquals("127.0.0.1", payload.string("ip"))
        assertEquals(1024, payload.int("maxConnections"))
        assertEquals("blacklist", payload.string("hostsMode"))
        assertEquals("example.com", payload.string("hosts"))
        assertEquals("www.example.com", payload.string("fakeSni"))
        assertEquals("1", payload.string("splitMarker"))
        assertEquals("disorder", payload.array("tcpChainSteps")[0].jsonObject.string("kind"))
        assertEquals("1", payload.array("tcpChainSteps")[0].jsonObject.string("marker"))
        assertEquals("route_and_cache", payload.string("quicInitialMode"))
        assertEquals("true", payload.string("quicSupportV1"))
        assertEquals("true", payload.string("quicSupportV2"))
    }

    @Test
    fun uiPreferencesDropHostsWhenModeDisabled() {
        val preferences =
            RipDpiProxyUIPreferences(
                hostsMode = RipDpiProxyUIPreferences.HostsMode.Disable,
                hosts = "example.com",
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("ui", payload.string("kind"))
        assertEquals("disable", payload.string("hostsMode"))
        assertEquals(null, payload["hosts"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun uiPreferencesEncodeQuicOverrides() {
        val preferences =
            RipDpiProxyUIPreferences(
                quicInitialMode = "route",
                quicSupportV1 = false,
                quicSupportV2 = true,
            )

        val payload = preferences.toNativeConfigJson().parseJsonObject()

        assertEquals("route", payload.string("quicInitialMode"))
        assertEquals("false", payload.string("quicSupportV1"))
        assertEquals("true", payload.string("quicSupportV2"))
    }
}

private fun String.parseJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).content

private fun JsonObject.int(name: String): Int = (getValue(name) as JsonPrimitive).content.toInt()

private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
