package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveDetectabilityMetrics
import com.poyka.ripdpi.diagnostics.export.editionArchiveEvidence
import com.poyka.ripdpi.diagnostics.export.hasEditionDetectabilityEvidence
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStandardEditionTest {
    @Test
    fun `historical privileged observations decode as unavailable without becoming promotable`() {
        val historical =
            Json.decodeFromString<StrategyProbeCandidateSummary>(
                candidate(",\"emitterTier\":\"ROOTED_PRODUCTION\",\"exactEmitterRequiresRoot\":true"),
            )
        assertEquals(StrategyEmitterTier.UNAVAILABLE, historical.emitterTier)
        assertFalse(historical.hasSupportedEmitter(AppSettings.getDefaultInstance()))
        assertFalse(historical.requiresExternalEmitter)
        assertNull(historical.editionRecommendationMetadata("Tcp"))
        assertEquals("", historical.editionArchiveEvidence())
        assertFalse(historical.hasEditionDetectabilityEvidence())
        val written = Json.encodeToString(historical)
        assertFalse(written.contains("ROOTED_PRODUCTION"))
        assertFalse(written.contains("exactEmitterRequiresRoot"))
    }

    @Test
    fun `production observations remain eligible while missing tiers are unavailable`() {
        val standard =
            Json.decodeFromString<StrategyProbeCandidateSummary>(
                candidate(",\"emitterTier\":\"PRODUCTION\""),
            )
        assertTrue(standard.hasSupportedEmitter(AppSettings.getDefaultInstance()))
        val unknown = Json.decodeFromString<StrategyProbeCandidateSummary>(candidate(""))
        assertEquals(StrategyEmitterTier.UNAVAILABLE, unknown.emitterTier)
        assertFalse(unknown.hasSupportedEmitter(AppSettings.getDefaultInstance()))
    }

    @Test
    fun `current execution plan can omit the emitter requirement flag`() {
        val candidate =
            Json.decodeFromString<StrategyCandidatePlanSnapshot>(
                """{"id":"tcp-1","label":"TCP","family":"split","emitterTier":"PRODUCTION",
                  |"eligibility":"always","warmup":"none","preserveAdaptiveFakeTtl":false,
                  |"requiresFakeTtl":false,"requiresTcpFastOpen":false}
                """.trimMargin(),
            )
        assertEquals(StrategyEmitterTier.PRODUCTION, candidate.emitterTier)
        assertFalse(candidate.requiresExternalEmitter)
        assertFalse(Json.encodeToString(candidate).contains("exactEmitterRequiresRoot"))
    }

    @Test
    fun `detectability output excludes legacy edition fields even when defaults are enabled`() {
        val json = Json { encodeDefaults = true }
        val metrics = DiagnosticsArchiveDetectabilityMetrics(labOnlyCandidates = 2)
        val written = json.encodeToString(metrics)
        assertTrue(written.contains("\"labOnlyCandidates\":2"))
        assertFalse(written.contains("root", ignoreCase = true))
        assertFalse(written.contains("ExternalEmitter"))
    }

    private fun candidate(extra: String): String =
        """{"id":"tcp-1","label":"TCP","family":"split","outcome":"success","rationale":"Measured",
          |"succeededTargets":1,"totalTargets":1,"weightedSuccessScore":1,"totalWeight":1,"qualityScore":3$extra}
        """.trimMargin()
}
