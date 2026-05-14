package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the strategy-pack "fixed-config protocol" compatibility hint.
 *
 * AmneziaWG profiles are server-coordinated fixed config: their obfuscation
 * params (`Jc`/`Jmin`/`Jmax`/`S1`-`S4`/`H1`-`H4`/`I1`-`I5`) are part of the
 * server's configuration and must match it exactly. The strategy learner must
 * treat AWG profiles as opaque and never emit a candidate arm that mutates
 * those params. The catalog schema carries that constraint in
 * [StrategyPackCatalog.fixedConfigProtocols].
 */
class StrategyPackFixedConfigHintTest {
    @Test
    fun `default catalog flags amneziawg as a fixed-config protocol`() {
        val catalog = StrategyPackCatalog()

        assertTrue(catalog.fixedConfigProtocols.contains(StrategyPackFixedConfigProtocolAmneziaWg))
        assertTrue(catalog.isFixedConfigProtocol("amneziawg"))
        // Matching is case-insensitive and trim-tolerant.
        assertTrue(catalog.isFixedConfigProtocol("  AmneziaWG "))
    }

    @Test
    fun `non-fixed-config protocols are not flagged`() {
        val catalog = StrategyPackCatalog()

        assertFalse(catalog.isFixedConfigProtocol("vless"))
        assertFalse(catalog.isFixedConfigProtocol("wireguard"))
        assertFalse(catalog.isFixedConfigProtocol(""))
    }

    @Test
    fun `json round trip preserves the fixed-config protocol list`() {
        val catalog =
            StrategyPackCatalog(
                fixedConfigProtocols = listOf("amneziawg", "custom-fixed"),
            )

        val decoded = strategyPackCatalogFromJson(catalog.toJson())

        assertEquals(listOf("amneziawg", "custom-fixed"), decoded.fixedConfigProtocols)
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

        // Absent field falls back to the schema default, which still flags AWG.
        assertTrue(decoded.isFixedConfigProtocol("amneziawg"))
    }

    @Test
    fun `candidate arm that mutates an AWG obfuscation param is rejected by validation`() {
        val catalog = StrategyPackCatalog()
        val arm =
            StrategyPackCandidateArm(
                id = "awg-jc-mutation",
                protocol = "amneziawg",
                mutatedParams = listOf("Jc"),
            )

        val outcome = catalog.validateCandidateArm(arm)

        assertFalse(outcome.isValid)
        assertTrue(outcome.reason.orEmpty().contains("amneziawg"))
    }

    @Test
    fun `candidate arm that mutates any AWG param family is rejected`() {
        val catalog = StrategyPackCatalog()
        val mutatedFields = listOf("Jmin", "Jmax", "S1", "S4", "H1", "H4", "I1", "I5")

        mutatedFields.forEach { field ->
            val arm =
                StrategyPackCandidateArm(
                    id = "awg-mutation-$field",
                    protocol = "amneziawg",
                    mutatedParams = listOf(field),
                )
            assertFalse(
                "mutating $field on an AWG profile must be rejected",
                catalog.validateCandidateArm(arm).isValid,
            )
        }
    }

    @Test
    fun `candidate arm that selects between AWG profiles without mutating params is allowed`() {
        val catalog = StrategyPackCatalog()
        val selectionOnlyArm =
            StrategyPackCandidateArm(
                id = "awg-profile-selection",
                protocol = "amneziawg",
                mutatedParams = emptyList(),
            )

        val outcome = catalog.validateCandidateArm(selectionOnlyArm)

        // The runtime selector may still pick between AWG profiles; it just
        // must not rewrite an individual profile's obfuscation params.
        assertTrue(outcome.isValid)
        assertEquals(null, outcome.reason)
    }

    @Test
    fun `candidate arm for a non-fixed-config protocol may mutate params freely`() {
        val catalog = StrategyPackCatalog()
        val tlsArm =
            StrategyPackCandidateArm(
                id = "tls-record-split",
                protocol = "vless",
                mutatedParams = listOf("tlsRecordSplit", "fragmentSize"),
            )

        assertTrue(catalog.validateCandidateArm(tlsArm).isValid)
    }
}
