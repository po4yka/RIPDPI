package com.poyka.ripdpi.core

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiProxyUIPreferencesSettingsWriterTest {
    @Test
    fun `parser evasions survive applying preferences to settings`() {
        val preferences =
            RipDpiProxyUIPreferences(
                parserEvasions = RipDpiParserEvasionConfig(httpHostExtraSpace = true, httpHostTab = true),
            )

        val updated = preferences.applyToSettings(AppSettings.getDefaultInstance())

        assertTrue(updated.httpHostExtraSpace)
        assertTrue(updated.httpHostTab)
    }

    @Test
    fun `range headers and signature programs survive settings persistence and native projection`() {
        val config =
            RipDpiWarpAmneziaConfig(
                enabled = true,
                h1 = "100-199",
                h2 = "200-299",
                h3 = "300-399",
                h4 = "400-499",
                i1 = "<b 0xf0><r 16><rd 4><rc 4><t>",
                i2 = "<b 0x02>",
                i3 = "<r 8>",
                i4 = "<rd 8>",
                i5 = "<rc 8>",
                s3 = 12,
                s4 = 12,
            )
        val preferences = RipDpiProxyUIPreferences(warp = RipDpiWarpConfig(amneziaPreset = "custom", amnezia = config))
        val stored = AppSettings.parseFrom(preferences.applyToSettings(AppSettings.getDefaultInstance()).toByteArray())
        assertEquals("100-199", stored.warpAmneziaH1)
        assertEquals(config, RipDpiProxyUIPreferences.fromSettings(stored).warp.amnezia)
        val nativeJson = RipDpiProxyUIPreferences.fromSettings(stored).toNativeConfigJson()
        assertTrue(nativeJson.contains("100-199"))
        assertTrue(nativeJson.contains("<b 0xf0><r 16><rd 4><rc 4><t>"))
        assertTrue(nativeJson.contains("\"s3\":12") && nativeJson.contains("\"s4\":12"))
    }

    @Test
    fun `settings writer rejects malformed supported profiles before persistence`() {
        listOf(
            RipDpiWarpAmneziaConfig(h1 = "100-199", h2 = "150-250"),
            RipDpiWarpAmneziaConfig(i1 = "deadbeef"),
            RipDpiWarpAmneziaConfig(wireProfile = "future"),
            RipDpiWarpAmneziaConfig(jc = Int.MAX_VALUE),
            RipDpiWarpAmneziaConfig(s3 = 1281),
            RipDpiWarpAmneziaConfig(s4 = -1),
        ).forEach { config ->
            assertThrows(IllegalArgumentException::class.java) {
                RipDpiProxyUIPreferences(warp = RipDpiWarpConfig(amnezia = config))
                    .applyToSettings(AppSettings.getDefaultInstance())
            }
        }
    }

    @Test
    fun `proxy JSON refuses unsupported AWG options before tolerant decoding`() {
        val json = RipDpiProxyUIPreferences().toNativeConfigJson()
        listOf("disableCookies", "headerProtectionKey", "randomTrailers", "contentPaddingAddition").forEach { field ->
            val injected = json.replace("\"wireProfile\":", "\"$field\":true,\"wireProfile\":")
            assertNull(RipDpiProxyJsonCodec.decodeUiPreferences(injected))
        }
    }
}
