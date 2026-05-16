package com.poyka.ripdpi.data.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSelectorOrderingTest {
    private val fixtureRealityDirect =
        ProfileCandidate(
            id = "fixture-reality-direct",
            kind = RelayProfileKind.RealityDirect,
            isCloudflareBacked = false,
            label = ProfileSelectorLabel.Primary,
        )

    private val fixtureNonCfHttps =
        ProfileCandidate(
            id = "fixture-non-cf-https",
            kind = RelayProfileKind.NonCloudflareHttps,
            isCloudflareBacked = false,
            label = ProfileSelectorLabel.Primary,
        )

    private val fixtureCfXhttp =
        ProfileCandidate(
            id = "fixture-cf-xhttp",
            kind = RelayProfileKind.CloudflareXhttp,
            isCloudflareBacked = true,
            label = ProfileSelectorLabel.OptionalEdgeFallback,
        )

    @Test
    fun `auto order is REALITY-direct first, non-CF HTTPS second, CF last`() {
        val mixed = listOf(fixtureCfXhttp, fixtureNonCfHttps, fixtureRealityDirect)
        val ordered = AutoSelector.order(mixed, CloudflareHealthState.Healthy)
        assertEquals(
            listOf(fixtureRealityDirect.id, fixtureNonCfHttps.id, fixtureCfXhttp.id),
            ordered.map { it.id },
        )
    }

    @Test
    fun `auto order with only CF candidates keeps CF at end`() {
        val result = AutoSelector.order(listOf(fixtureCfXhttp), CloudflareHealthState.Healthy)
        assertEquals(listOf(fixtureCfXhttp.id), result.map { it.id })
    }

    @Test
    fun `auto order with only non-CF candidates preserves REALITY before HTTPS`() {
        val result =
            AutoSelector.order(
                listOf(fixtureNonCfHttps, fixtureRealityDirect),
                CloudflareHealthState.Healthy,
            )
        assertEquals(
            listOf(fixtureRealityDirect.id, fixtureNonCfHttps.id),
            result.map { it.id },
        )
    }
}
