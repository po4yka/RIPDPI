package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the strategy-pack per-protocol compatibility hint
 * ([StrategyPackCatalog.protocolHints]). The hint is advisory metadata for the
 * recommender / UI (e.g. "SSH direct vs SSH-over-TLS", AnyTLS in QUIC-heavy
 * neighborhoods). It is optional and additive, so an absent field decodes to an
 * empty list without a schema-version bump.
 */
class StrategyPackProtocolHintTest {
    @Test
    fun `default catalog ships no protocol hints`() {
        assertTrue(StrategyPackCatalog().protocolHints.isEmpty())
        assertNull(StrategyPackCatalog().hintForProtocol("ssh"))
    }

    @Test
    fun `json round trip preserves protocol hints`() {
        val catalog =
            StrategyPackCatalog(
                protocolHints =
                    listOf(
                        StrategyPackProtocolHint(
                            protocol = "ssh",
                            title = "SSH outbound",
                            recommendedArms = listOf("ssh-direct", "ssh-over-tls"),
                            notes = "direct for self-hosted VPS",
                        ),
                        StrategyPackProtocolHint(
                            protocol = "anytls",
                            recommendedArms = listOf("anytls-padding"),
                            quicHeavyNeighborhood = true,
                        ),
                    ),
            )

        val decoded = strategyPackCatalogFromJson(catalog.toJson())

        assertEquals(listOf("ssh", "anytls"), decoded.protocolHints.map { it.protocol })
        assertEquals(listOf("ssh-direct", "ssh-over-tls"), decoded.hintForProtocol("ssh")?.recommendedArms)
        assertTrue(decoded.hintForProtocol("anytls")!!.quicHeavyNeighborhood)
    }

    @Test
    fun `hint lookup is case-insensitive and trim-tolerant`() {
        val catalog =
            StrategyPackCatalog(
                protocolHints = listOf(StrategyPackProtocolHint(protocol = "mieru", title = "Mieru")),
            )

        assertEquals("Mieru", catalog.hintForProtocol("  MIERU ")?.title)
        assertNull(catalog.hintForProtocol("vless"))
        assertNull(catalog.hintForProtocol(""))
    }

    @Test
    fun `json decode stays backward compatible when the field is absent`() {
        val decoded =
            strategyPackCatalogFromJson(
                """
                {
                  "schemaVersion": 3,
                  "generatedAt": "2026-04-18T13:00:00Z",
                  "channel": "stable",
                  "packs": []
                }
                """.trimIndent(),
            )

        assertTrue(decoded.protocolHints.isEmpty())
        assertFalse(decoded.protocolHints.isNotEmpty())
    }
}
