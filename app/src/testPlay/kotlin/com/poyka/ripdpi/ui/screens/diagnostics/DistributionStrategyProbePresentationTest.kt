package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionStrategyProbePresentationTest {
    @Test
    fun `old privileged observations do not offer replay or show privileged instructions`() {
        val historical =
            StrategyProbeCandidateSummary(
                id = "tcp-historical",
                label = "TCP candidate",
                family = "split",
                emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
                exactEmitterRequiresRoot = true,
                outcome = "skipped",
                rationale = "Requires root",
                notes = listOf("Enable root mode"),
                succeededTargets = 0,
                totalTargets = 1,
                weightedSuccessScore = 0,
                totalWeight = 1,
                qualityScore = 0,
            )

        assertTrue(historical.presentedRationale().isEmpty())
        assertTrue(historical.presentedNotes().isEmpty())
        assertFalse(historical.canReplayInDistribution())

        val ordinary =
            historical.copy(
                emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
                exactEmitterRequiresRoot = false,
                rationale = "Ordinary observation",
                notes = listOf("The target timed out."),
            )
        assertEquals("Ordinary observation", ordinary.presentedRationale())
        assertEquals(listOf("The target timed out."), ordinary.presentedNotes())
        assertTrue(ordinary.canReplayInDistribution())
    }
}
