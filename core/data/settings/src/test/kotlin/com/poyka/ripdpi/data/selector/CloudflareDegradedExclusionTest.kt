package com.poyka.ripdpi.data.selector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareDegradedExclusionTest {
    private val fixtureCfProfile =
        ProfileCandidate(
            id = "fixture-cf-profile",
            kind = RelayProfileKind.CloudflareXhttp,
            isCloudflareBacked = true,
            label = ProfileSelectorLabel.OptionalEdgeFallback,
        )

    private val fixtureNonCfProfile =
        ProfileCandidate(
            id = "fixture-non-cf-profile",
            kind = RelayProfileKind.RealityDirect,
            isCloudflareBacked = false,
            label = ProfileSelectorLabel.Primary,
        )

    @Test
    fun `CF profile excluded from auto when health state is Degraded`() {
        val gate = CloudflareHealthGate
        assertFalse(gate.shouldIncludeInAuto(fixtureCfProfile, CloudflareHealthState.Degraded))
    }

    @Test
    fun `CF profile included in auto when health state is Healthy`() {
        val gate = CloudflareHealthGate
        assertTrue(gate.shouldIncludeInAuto(fixtureCfProfile, CloudflareHealthState.Healthy))
    }

    @Test
    fun `non-CF profile always included regardless of health state`() {
        val gate = CloudflareHealthGate
        assertTrue(gate.shouldIncludeInAuto(fixtureNonCfProfile, CloudflareHealthState.Healthy))
        assertTrue(gate.shouldIncludeInAuto(fixtureNonCfProfile, CloudflareHealthState.Degraded))
    }

    @Test
    fun `auto order drops CF when degraded`() {
        val candidates = listOf(fixtureCfProfile, fixtureNonCfProfile)
        val result = AutoSelector.order(candidates, CloudflareHealthState.Degraded)
        assertTrue(result.none { it.isCloudflareBacked })
        assertTrue(result.any { it.id == fixtureNonCfProfile.id })
    }
}
