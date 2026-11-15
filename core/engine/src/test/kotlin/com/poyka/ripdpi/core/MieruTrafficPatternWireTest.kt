package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.MieruHandshakeNoWait
import com.poyka.ripdpi.data.MieruLowEntropyPattern
import com.poyka.ripdpi.data.MieruNoncePattern
import com.poyka.ripdpi.data.MieruPaddingPattern
import com.poyka.ripdpi.data.MieruTcpFragment
import com.poyka.ripdpi.data.MieruTrafficPattern
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MieruTrafficPatternWireTest {
    @Test
    fun `legacy Mieru wire omits optional advanced settings`() {
        val original = relayNativeBaseFixture("mieru")
        val wire = RipDpiJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), original)
        val fields = RipDpiJson.parseToJsonElement(wire) as JsonObject
        assertFalse(fields.containsKey("mieruHandshakeMode"))
        assertFalse(fields.containsKey("mieruTrafficPattern"))
        assertEquals(original, RipDpiJson.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), wire))
    }

    @Test
    fun `native wire carries concrete advanced settings and remains stable across section conversion`() {
        val original =
            MieruTrafficPattern(
                seed = 42,
                unlockAll = false,
                tcpFragment = MieruTcpFragment(true, 1),
                nonce = MieruNoncePattern(3, false, 0, 12, listOf("00010203")),
                padding = MieruPaddingPattern(3, 4),
                lowEntropy = MieruLowEntropyPattern(4, 48),
            )
        val base = relayNativeBaseFixture("mieru").toSections()
        val resolved =
            base
                .copy(
                    mieru = base.mieru.copy(mieruHandshakeMode = MieruHandshakeNoWait, mieruTrafficPattern = original),
                ).toResolvedConfig()
        val wire = RipDpiJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), resolved)
        assertTrue(wire.contains("\"mieruHandshakeMode\":\"no_wait\""))
        assertTrue(wire.contains("\"maskRotation\":48"))
        val decoded = RipDpiJson.decodeFromString(ResolvedRipDpiRelayConfig.serializer(), wire)
        assertEquals(original, decoded.mieruTrafficPattern)
        assertEquals(resolved, decoded.toSections().toResolvedConfig())
    }
}
