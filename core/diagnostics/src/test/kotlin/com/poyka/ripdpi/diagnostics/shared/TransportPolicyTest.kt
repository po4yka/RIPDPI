package com.poyka.ripdpi.diagnostics.shared

import com.poyka.ripdpi.data.CurrentTransportPolicyEnvelopeVersion
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DnsMode
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
import com.poyka.ripdpi.data.TransportPolicyEnvelope
import com.poyka.ripdpi.data.defaultTransportPolicyEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Covers acceptance criteria for the TransportPolicy type:
 *  - Enum exhaustiveness for all five policy enums.
 *  - Default constructor returns a sensible "unknown-host" policy.
 *  - Versioned envelope carries schemaVersion and round-trips via
 *    kotlinx.serialization.
 *  - State-transition unit tests: degrade quic_mode and preferred_stack.
 */
class TransportPolicyTest {
    // ---- 1. Enum exhaustiveness ------------------------------------------------

    @Test
    fun `QuicMode has exactly ALLOW SOFT_DISABLE HARD_DISABLE variants`() {
        val names = QuicMode.entries.map { it.name }
        assertEquals(3, names.size)
        assertEquals(listOf("ALLOW", "SOFT_DISABLE", "HARD_DISABLE"), names)
    }

    @Test
    fun `PreferredStack has exactly H3 H2 H1 variants`() {
        val names = PreferredStack.entries.map { it.name }
        assertEquals(3, names.size)
        assertEquals(listOf("H3", "H2", "H1"), names)
    }

    @Test
    fun `DnsMode has exactly SYSTEM DOH_PRIMARY DOH_SECONDARY variants`() {
        val names = DnsMode.entries.map { it.name }
        assertEquals(3, names.size)
        assertEquals(listOf("SYSTEM", "DOH_PRIMARY", "DOH_SECONDARY"), names)
    }

    @Test
    fun `TcpFamily contains NONE SEG_PRE_SNI SEG_MID_SNI REC_PRE_SNI REC_MID_SNI`() {
        val names = TcpFamily.entries.map { it.name }
        listOf("NONE", "SEG_PRE_SNI", "SEG_MID_SNI", "REC_PRE_SNI", "REC_MID_SNI").forEach {
            assertEquals("TcpFamily must contain $it", true, names.contains(it))
        }
    }

    @Test
    fun `DirectModeOutcome has TRANSPARENT_OK OWNED_STACK_ONLY NO_DIRECT_SOLUTION`() {
        val names = DirectModeOutcome.entries.map { it.name }
        listOf(
            "TRANSPARENT_OK",
            "OWNED_STACK_ONLY",
            "NO_DIRECT_SOLUTION",
        ).forEach {
            assertEquals("DirectModeOutcome must contain $it", true, names.contains(it))
        }
    }

    // ---- 2. Default constructor -------------------------------------------------

    @Test
    fun `default TransportPolicy is sensible unknown-host policy`() {
        val policy = TransportPolicy()
        assertEquals(QuicMode.ALLOW, policy.quicMode)
        assertEquals(PreferredStack.H3, policy.preferredStack)
        assertEquals(DnsMode.SYSTEM, policy.dnsMode)
        assertEquals(TcpFamily.NONE, policy.tcpFamily)
        assertEquals(DirectModeOutcome.TRANSPARENT_OK, policy.outcome)
    }

    @Test
    fun `defaultTransportPolicyEnvelope returns version 1 with default policy`() {
        val envelope = defaultTransportPolicyEnvelope()
        assertEquals(CurrentTransportPolicyEnvelopeVersion, envelope.version)
        assertEquals(TransportPolicy(), envelope.policy)
    }

    // ---- 3. Versioned envelope round-trip --------------------------------------

    @Test
    fun `TransportPolicyEnvelope round-trips via kotlinx serialization`() {
        val original =
            TransportPolicyEnvelope(
                policy =
                    TransportPolicy(
                        quicMode = QuicMode.SOFT_DISABLE,
                        preferredStack = PreferredStack.H2,
                        dnsMode = DnsMode.DOH_PRIMARY,
                        tcpFamily = TcpFamily.SEG_PRE_SNI,
                        outcome = DirectModeOutcome.OWNED_STACK_ONLY,
                    ),
                ipSetDigest = "deadbeef",
            )
        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<TransportPolicyEnvelope>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `serialized envelope contains version field`() {
        val envelope = TransportPolicyEnvelope()
        val json = Json { encodeDefaults = true }.encodeToString(envelope)
        assertEquals(true, json.contains("\"version\""))
    }

    @Test
    fun `envelope version is stable across re-serializations`() {
        val envelope = TransportPolicyEnvelope()
        val json1 = Json.encodeToString(envelope)
        val json2 = Json.encodeToString(Json.decodeFromString<TransportPolicyEnvelope>(json1))
        assertEquals(json1, json2)
    }

    @Test
    fun `unknown extra fields are ignored during deserialization`() {
        val jsonWithExtra =
            """
            {
              "version": 1,
              "policy": {
                "quicMode": "ALLOW",
                "preferredStack": "H3",
                "dnsMode": "SYSTEM",
                "tcpFamily": "NONE",
                "outcome": "TRANSPARENT_OK"
              },
              "ipSetDigest": "",
              "futureFieldUnknown": true
            }
            """.trimIndent()
        val lenient = Json { ignoreUnknownKeys = true }
        val decoded = lenient.decodeFromString<TransportPolicyEnvelope>(jsonWithExtra)
        assertEquals(TransportPolicyEnvelope(), decoded)
    }

    // ---- 4. State transitions --------------------------------------------------

    @Test
    fun `quic_mode degrades ALLOW to SOFT_DISABLE to HARD_DISABLE`() {
        val allow = TransportPolicy(quicMode = QuicMode.ALLOW)
        val soft = allow.copy(quicMode = QuicMode.SOFT_DISABLE)
        val hard = soft.copy(quicMode = QuicMode.HARD_DISABLE)

        assertEquals(QuicMode.ALLOW, allow.quicMode)
        assertEquals(QuicMode.SOFT_DISABLE, soft.quicMode)
        assertEquals(QuicMode.HARD_DISABLE, hard.quicMode)
        assertNotEquals(allow, soft)
        assertNotEquals(soft, hard)
    }

    @Test
    fun `preferred_stack downgrades H3 to H2 to H1`() {
        val h3 = TransportPolicy(preferredStack = PreferredStack.H3)
        val h2 = h3.copy(preferredStack = PreferredStack.H2)
        val h1 = h2.copy(preferredStack = PreferredStack.H1)

        assertEquals(PreferredStack.H3, h3.preferredStack)
        assertEquals(PreferredStack.H2, h2.preferredStack)
        assertEquals(PreferredStack.H1, h1.preferredStack)
        assertNotEquals(h3, h2)
        assertNotEquals(h2, h1)
    }

    @Test
    fun `when expression over QuicMode is exhaustive`() {
        fun describeQuic(mode: QuicMode): String =
            when (mode) {
                QuicMode.ALLOW -> "allow"
                QuicMode.SOFT_DISABLE -> "soft"
                QuicMode.HARD_DISABLE -> "hard"
            }
        assertEquals("allow", describeQuic(QuicMode.ALLOW))
        assertEquals("soft", describeQuic(QuicMode.SOFT_DISABLE))
        assertEquals("hard", describeQuic(QuicMode.HARD_DISABLE))
    }

    @Test
    fun `when expression over PreferredStack is exhaustive`() {
        fun describeStack(stack: PreferredStack): String =
            when (stack) {
                PreferredStack.H3 -> "h3"
                PreferredStack.H2 -> "h2"
                PreferredStack.H1 -> "h1"
            }
        assertEquals("h3", describeStack(PreferredStack.H3))
        assertEquals("h2", describeStack(PreferredStack.H2))
        assertEquals("h1", describeStack(PreferredStack.H1))
    }

    @Test
    fun `when expression over DirectModeOutcome is exhaustive`() {
        fun describeOutcome(outcome: DirectModeOutcome): String =
            when (outcome) {
                DirectModeOutcome.TRANSPARENT_OK -> "ok"
                DirectModeOutcome.OWNED_STACK_ONLY -> "owned"
                DirectModeOutcome.NO_DIRECT_SOLUTION -> "none"
            }
        assertEquals("ok", describeOutcome(DirectModeOutcome.TRANSPARENT_OK))
        assertEquals("owned", describeOutcome(DirectModeOutcome.OWNED_STACK_ONLY))
        assertEquals("none", describeOutcome(DirectModeOutcome.NO_DIRECT_SOLUTION))
    }
}
