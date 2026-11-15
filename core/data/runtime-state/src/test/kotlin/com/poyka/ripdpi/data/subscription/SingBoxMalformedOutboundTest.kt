package com.poyka.ripdpi.data.subscription

import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxMalformedOutboundTest {
    @Test
    fun `a malformed sibling makes the whole recognized bundle invalid`() {
        listOf(
            "42",
            "null",
            "[]",
            "{}",
            "{\"tag\":\"missing-type\"}",
            "{\"type\":null}",
            "{\"type\":42}",
            "{\"type\":\"\"}",
        ).forEach { malformed ->
            val result = SingBoxSubscriptionParser.parse("""{"outbounds":[$valid,$malformed]}""", "group")
            assertTrue(malformed, result is SingBoxParseResult.Error)
            assertTrue(malformed, (result as SingBoxParseResult.Error).recognizedBundle)
        }
    }

    @Test
    fun `valid utility outbounds remain metadata`() {
        val result =
            SingBoxSubscriptionParser.parse(
                """{"outbounds":[$valid,{"type":"direct","tag":"direct"}]}""",
                "group",
            ) as SingBoxParseResult.Success

        assertTrue(result.skipped.isEmpty())
        assertTrue(result.profiles.single().displayName == "valid")
    }

    private val valid =
        """{"type":"shadowsocks","tag":"valid","server":"relay.example","server_port":443,
            "method":"aes-128-gcm","password":"synthetic"}"""
}
