package com.poyka.ripdpi.data.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualOverrideAllowedTest {
    private val fixtureCfProfile =
        ProfileCandidate(
            id = "fixture-cf-manual",
            kind = RelayProfileKind.CloudflareXhttp,
            isCloudflareBacked = true,
            label = ProfileSelectorLabel.OptionalEdgeFallback,
        )

    @Test
    fun `manual selection of CF profile succeeds when degraded`() {
        val result = ManualSelector.select(fixtureCfProfile)
        assertEquals(fixtureCfProfile.id, result.selectedId)
    }

    @Test
    fun `manual selection of CF profile succeeds when healthy`() {
        val result = ManualSelector.select(fixtureCfProfile)
        assertEquals(fixtureCfProfile.id, result.selectedId)
    }

    @Test
    fun `manual selection ignores health gate`() {
        // ManualSelector must not consult CloudflareHealthGate; selection is unconditional.
        val resultDegraded = ManualSelector.select(fixtureCfProfile)
        val resultHealthy = ManualSelector.select(fixtureCfProfile)
        assertEquals(resultDegraded.selectedId, resultHealthy.selectedId)
    }
}
