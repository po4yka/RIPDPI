package com.poyka.ripdpi.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class XrayImportBudgetTest {
    @Test
    fun `exact byte budget imports while a larger payload is rejected`() {
        val exact = outbound.padEnd(MaxSubscriptionPayloadBytes.toInt(), ' ')

        assertEquals(1, translated(exact).profiles.size)
        assertTooLarge(exact + " ")
    }

    @Test
    fun `UTF-8 bytes determine the budget instead of character count`() {
        val multibyte = outbound.dropLast(1) + """, "tag":"${"я".repeat(MaxSubscriptionPayloadBytes.toInt() / 2)}"}"""

        assertTrue(multibyte.length < MaxSubscriptionPayloadBytes)
        assertTooLarge(multibyte)
    }

    @Test
    fun `JSON outbounds obey the node budget before profile conversion`() {
        assertEquals(2_048, translated(nodes(2_048)).profiles.size)
        assertTooLarge(nodes(2_049))
    }

    @Test
    fun `plain and Base64 links obey the same node budget`() {
        val exact = List(2_048) { "trojan://fixture@t.example:443#node" }.joinToString("\n")
        val oversized = exact + "\ntrojan://fixture@t.example:443#extra"

        assertEquals(2_048, translated(exact).profiles.size)
        assertTooLarge(oversized)
        assertTooLarge(Base64.getEncoder().encodeToString(oversized.toByteArray(Charsets.UTF_8)))
    }

    private val outbound =
        """{"protocol":"shadowsocks","settings":{"address":"ss.example","port":443,
        "method":"aes-128-gcm","password":"fixture-password"}}"""

    private fun nodes(count: Int): String = List(count) { outbound }.joinToString(prefix = "[", postfix = "]")

    private fun translated(input: String) =
        XrayConfigImportParser.parse(input, "group") as XrayConfigImportResult.Translated

    private fun assertTooLarge(input: String) {
        val result = XrayConfigImportParser.parse(input, "group")

        assertTrue("oversized input must not create importable profiles", result is XrayConfigImportResult.Unparseable)
        assertEquals(XrayUnparseableReason.PAYLOAD_TOO_LARGE, (result as XrayConfigImportResult.Unparseable).reason)
    }
}
