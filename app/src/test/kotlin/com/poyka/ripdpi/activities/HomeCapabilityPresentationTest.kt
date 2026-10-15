package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeCapabilityPresentationTest {
    @Test
    fun `capability generated captions and flags localize with exact authority enums source and custom fields`() {
        val evidence =
            DiagnosticsCapabilityEvidence(
                authority = "[2001:4860::8888]:443",
                summary = "Owned stack required · DNS tampered · QUIC blocked",
                details =
                    listOf(
                        DiagnosticsAppliedSetting("Policy", "OWNED_STACK_ONLY"),
                        DiagnosticsAppliedSetting("DNS class", "TAMPERED"),
                        DiagnosticsAppliedSetting("QUIC mode", "OWNED_STACK"),
                        DiagnosticsAppliedSetting("QUIC", "Blocked"),
                        DiagnosticsAppliedSetting("Auth", "Available"),
                        DiagnosticsAppliedSetting("Fallback", "Required"),
                        DiagnosticsAppliedSetting("Last failure", "Failure · raw token"),
                        DiagnosticsAppliedSetting("Custom caption", "Available"),
                    ),
                source = "custom · Source",
                updatedAt = 42L,
            )
        val outcome = homeOutcome().copy(capabilityEvidence = listOf(evidence))
        val arabic = projectHome(outcome, "ar").analysisSheet!!.capabilityEvidence.single()
        assertEquals(evidence.authority, arabic.authority)
        assertArabic(arabic.summary)
        assertTrue(arabic.summary.contains("tampered"))
        assertTrue(arabic.summary.contains("QUIC"))
        assertEquals("OWNED_STACK_ONLY", arabic.fields[0].value)
        assertArabic(arabic.fields[0].label)
        assertArabic(arabic.fields[3].value)
        assertArabic(arabic.fields[4].label)
        assertArabic(arabic.fields[4].value)
        assertArabic(arabic.fields[5].value)
        assertEquals("Failure · raw token", arabic.fields[6].value)
        assertEquals("Custom caption", arabic.fields[7].label)
        assertEquals("Available", arabic.fields[7].value)
        assertEquals("custom · Source", arabic.fields.last().value)
        assertArabic(arabic.fields.last().label)
        assertEquals(
            evidence.summary,
            projectHome(outcome, "en")
                .analysisSheet!!
                .capabilityEvidence
                .single()
                .summary,
        )
        assertEquals(evidence, outcome.capabilityEvidence.single())
    }

    @Test
    fun `unrecognized summary and mixed custom fragments are preserved as a whole`() {
        listOf("Custom summary · QUIC usable", "Failure raw · custom", "QUIC usable soon").forEach { raw ->
            val outcome =
                homeOutcome().copy(
                    capabilityEvidence =
                        listOf(
                            DiagnosticsCapabilityEvidence(
                                authority = "custom.example",
                                summary = raw,
                            ),
                        ),
                )
            assertEquals(
                raw,
                projectHome(outcome, "ar")
                    .analysisSheet!!
                    .capabilityEvidence
                    .single()
                    .summary,
            )
        }
    }

    @Test
    fun `every generated capability branch localizes including delimiter bearing failure payload`() {
        val flags =
            mapOf(
                "QUIC" to ("QUIC usable" to "QUIC blocked"),
                "UDP" to ("UDP usable" to "UDP blocked"),
                "Auth" to ("Auth accepted" to "Auth rejected"),
                "Multiplex" to ("Multiplex reusable" to "Multiplex limited"),
                "ShadowTLS" to ("ShadowTLS accepted" to "ShadowTLS rejected"),
                "HTTPS proxy" to ("HTTPS proxy accepted" to "HTTPS proxy rejected"),
            )
        flags.forEach { (label, summaries) ->
            listOf(true, false).forEach { available ->
                val summary = if (available) summaries.first else summaries.second
                val evidence =
                    DiagnosticsCapabilityEvidence(
                        authority = "example.org:443",
                        summary = summary,
                        details = listOf(DiagnosticsAppliedSetting(label, if (available) "Available" else "Blocked")),
                    )
                val outcome = homeOutcome().copy(capabilityEvidence = listOf(evidence))
                assertArabic(
                    projectHome(outcome, "ar")
                        .analysisSheet!!
                        .capabilityEvidence
                        .single()
                        .summary,
                )
                assertEquals(
                    summary,
                    projectHome(outcome, "en")
                        .analysisSheet!!
                        .capabilityEvidence
                        .single()
                        .summary,
                )
            }
        }
        val cases =
            listOf(
                "No direct solution" to listOf(DiagnosticsAppliedSetting("Policy", "NO_DIRECT_SOLUTION")),
                "Fallback required" to listOf(DiagnosticsAppliedSetting("Fallback", "Required")),
                "Failure custom · raw" to listOf(DiagnosticsAppliedSetting("Last failure", "custom · raw")),
                "Capability evidence recorded for this authority." to emptyList(),
            )
        cases.forEach { (summary, fields) ->
            val outcome =
                homeOutcome().copy(
                    capabilityEvidence =
                        listOf(
                            DiagnosticsCapabilityEvidence(
                                authority = "example.org",
                                summary = summary,
                                details = fields,
                            ),
                        ),
                )
            val value =
                projectHome(outcome, "ar")
                    .analysisSheet!!
                    .capabilityEvidence
                    .single()
                    .summary
            assertArabic(value)
            if (summary.startsWith("Failure")) assertTrue(value.contains("custom · raw"))
        }
    }
}
