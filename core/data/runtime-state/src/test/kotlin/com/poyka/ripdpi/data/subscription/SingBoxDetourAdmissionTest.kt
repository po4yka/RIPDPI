package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxDetourAdmissionTest {
    @Test
    fun `a detoured node cannot become a direct profile`() {
        val result =
            SingBoxSubscriptionParser.parse(
                """{"outbounds":[${node("edge", ",\"detour\":\"upstream\"")},${node("upstream") }]}""",
                "group",
            ) as SingBoxParseResult.Success

        assertEquals(listOf("upstream"), result.profiles.map { it.displayName })
        assertEquals(SingBoxSkipReason.UNSUPPORTED_TRANSPORT, result.skipped.single().reason)
        assertEquals("detour", result.skipped.single().detail)
    }

    @Test
    fun `absent and empty detour keep a direct node usable`() {
        for (options in listOf("", ",\"detour\":\"\"")) {
            val result = SingBoxSubscriptionParser.parse(node("edge", options), "group") as SingBoxParseResult.Success
            assertTrue(result.skipped.isEmpty())
            assertTrue(result.profiles.single() is ProxyProfile.Shadowsocks)
        }
    }

    private fun node(
        tag: String,
        options: String = "",
    ): String =
        """{"type":"shadowsocks","tag":"$tag","server":"$tag.example","server_port":443,
            "method":"aes-128-gcm","password":"synthetic"$options}"""
}
