package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnellImportTest {
    @Test
    fun `plain v4 stream node becomes a snell profile`() {
        val profile =
            import(
                """"version":4,"psk":"psk-x","userkey":"user-x","reuse":false,"obfs_mode":"none","network":"tcp"""",
            )
        profile as ProxyProfile.Snell
        assertEquals("a.example", profile.server)
        assertEquals(2443, profile.serverPort)
        assertEquals("psk-x", profile.psk)
        assertEquals("user-x", profile.userKey)
        assertEquals("n", profile.displayName)
    }

    @Test
    fun `optional keys may be absent`() {
        val profile = import("\"version\":4,\"psk\":\"psk-x\"") as ProxyProfile.Snell
        assertEquals("", profile.userKey)
    }

    @Test
    fun `every other snell shape stays raw`() {
        listOf(
            "\"version\":6,\"psk\":\"p\",\"mode\":\"default\"",
            "\"psk\":\"p\"",
            "\"version\":4,\"psk\":\"p\",\"reuse\":true",
            "\"version\":4,\"psk\":\"p\",\"obfs_mode\":\"http\"",
            "\"version\":4,\"psk\":\"p\",\"network\":\"udp\"",
            "\"version\":4,\"psk\":\" \"",
            "\"version\":4",
            "\"version\":4,\"psk\":\"p\",\"userkey\":\"${"k".repeat(256)}\"",
            // 128 two-byte characters: 128 chars but 256 UTF-8 bytes.
            "\"version\":4,\"psk\":\"p\",\"userkey\":\"${"\u00e9".repeat(128)}\"",
        ).forEach { fields -> assertTrue(fields, import(fields) is ProxyProfile.RawConfig) }
        assertEquals(
            255,
            (import("\"version\":4,\"psk\":\"p\",\"userkey\":\"${"k".repeat(255)}\"") as ProxyProfile.Snell)
                .userKey.length,
        )
    }

    private fun import(fields: String): ProxyProfile =
        (
            SingBoxSubscriptionParser.parse(
                """{"type":"snell","tag":"n","server":"a.example","server_port":2443,$fields}""",
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single()
}
