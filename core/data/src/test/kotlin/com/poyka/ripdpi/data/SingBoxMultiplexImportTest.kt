package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxMultiplexImportTest {
    @Test
    fun `enabled multiplex is rejected while a supported sibling remains selectable`() {
        val mux = reality("\"multiplex\":{\"enabled\":true,\"protocol\":\"smux\"}")
        val vision = reality("\"flow\":\"xtls-rprx-vision\"")
        val result = parse("{\"outbounds\":[$mux,$vision]}")

        assertEquals(1, result.profiles.size)
        assertEquals("xtls-rprx-vision", (result.profiles.single() as ProxyProfile.VlessReality).flow)
        val skipped = result.skipped.single()
        assertEquals(0, skipped.index)
        assertEquals("edge", skipped.label)
        assertEquals(SingBoxSkipReason.UNSUPPORTED_TRANSPORT, skipped.reason)
        assertEquals("multiplex", skipped.detail)
    }

    @Test
    fun `enabled multiplex is rejected for absent and unknown protocols without reflecting fields`() {
        for (protocol in listOf("", ", \"protocol\":\"credential-like-value\"")) {
            val result = parse(reality(""""multiplex":{"enabled":true$protocol}"""))

            assertTrue(result.profiles.isEmpty())
            assertEquals("multiplex", result.skipped.single().detail)
        }
    }

    @Test
    fun `disabled and absent multiplex preserve explicit Vision`() {
        for (multiplex in listOf("", """, "multiplex":{"enabled":false,"protocol":"smux"}""")) {
            val result = parse(reality(""""flow":"xtls-rprx-vision"$multiplex"""))

            assertEquals("xtls-rprx-vision", (result.profiles.single() as ProxyProfile.VlessReality).flow)
            assertTrue(result.skipped.isEmpty())
        }
    }

    @Test
    fun `omitted and empty REALITY flow remain no-flow`() {
        for (fields in listOf("", "\"flow\":\"\"", "\"multiplex\":{\"enabled\":false}")) {
            val result = parse(reality(fields))

            assertEquals("", (result.profiles.single() as ProxyProfile.VlessReality).flow)
            assertTrue(result.skipped.isEmpty())
        }
    }

    private fun reality(fields: String): String =
        """{"type":"vless","tag":"edge","server":"edge.example","server_port":443,
            "uuid":"11111111-1111-1111-1111-111111111111",
            "tls":{"enabled":true,"reality":{"enabled":true,"public_key":"fixture-public-key"}}
            ${if (fields.isEmpty()) "" else ",$fields"}}"""

    private fun parse(payload: String): SingBoxParseResult.Success =
        SingBoxSubscriptionParser.parse(payload, "mux-test") as SingBoxParseResult.Success
}
