package com.poyka.ripdpi.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeProxyDebugLogDefaultsTest {
    @Test
    fun `debug defaults preserve explicit UI and command line log levels`() {
        val defaults = RipDpiProxyUIPreferences().toNativeConfigJson()
        val explicit = RipDpiProxyUIPreferences(nativeLogLevel = "warn").toNativeConfigJson()
        val debug = withDebugProxyLogDefault(defaults)
        assertEquals("debug", RipDpiProxyJsonCodec.decodeUiPreferences(debug)?.nativeLogLevel)
        assertEquals(explicit, withDebugProxyLogDefault(explicit))

        val cli = RipDpiProxyCmdPreferences(arrayOf("-x", "2")).toNativeConfigJson()
        val debugCli = withDebugProxyLogDefault(cli)
        val args =
            Json
                .parseToJsonElement(debugCli)
                .jsonObject
                .getValue("args")
                .jsonArray
        assertEquals(listOf("--debug", "1", "-x", "2"), args.map { it.jsonPrimitive.content })
    }
}
