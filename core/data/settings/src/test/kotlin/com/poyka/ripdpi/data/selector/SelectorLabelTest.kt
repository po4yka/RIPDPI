package com.poyka.ripdpi.data.selector

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorLabelTest {
    @Test
    fun `CF-backed profile carries OptionalEdgeFallback label`() {
        val profile =
            ProfileCandidate(
                id = "fixture-cf-label",
                kind = RelayProfileKind.CloudflareXhttp,
                isCloudflareBacked = true,
                label = ProfileSelectorLabel.OptionalEdgeFallback,
            )
        assertEquals(ProfileSelectorLabel.OptionalEdgeFallback, profile.label)
    }

    @Test
    fun `REALITY-direct profile carries Primary label`() {
        val profile =
            ProfileCandidate(
                id = "fixture-reality-label",
                kind = RelayProfileKind.RealityDirect,
                isCloudflareBacked = false,
                label = ProfileSelectorLabel.Primary,
            )
        assertEquals(ProfileSelectorLabel.Primary, profile.label)
    }

    @Test
    fun `non-CF HTTPS profile carries Primary label`() {
        val profile =
            ProfileCandidate(
                id = "fixture-non-cf-https-label",
                kind = RelayProfileKind.NonCloudflareHttps,
                isCloudflareBacked = false,
                label = ProfileSelectorLabel.Primary,
            )
        assertEquals(ProfileSelectorLabel.Primary, profile.label)
    }

    @Test
    fun `ProfileSelectorLabel has exactly two variants`() {
        val variants = ProfileSelectorLabel.entries
        assertEquals(2, variants.size)
    }
}
