package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedAutoSelectionExclusionTest {
    @Test
    fun `Healthy profile can be auto-selected`() {
        assertTrue(ProfileSelectorPredicate.canAutoSelect(ProfileHealthState.Healthy))
    }

    @Test
    fun `DegradedCloudflareLike profile cannot be auto-selected`() {
        assertFalse(ProfileSelectorPredicate.canAutoSelect(ProfileHealthState.DegradedCloudflareLike))
    }

    @Test
    fun `Unknown profile cannot be auto-selected`() {
        assertFalse(ProfileSelectorPredicate.canAutoSelect(ProfileHealthState.Unknown))
    }

    @Test
    fun `DegradedCloudflareLike profile can be manually selected`() {
        assertTrue(ProfileSelectorPredicate.canManualSelect(ProfileHealthState.DegradedCloudflareLike))
    }

    @Test
    fun `Healthy profile can be manually selected`() {
        assertTrue(ProfileSelectorPredicate.canManualSelect(ProfileHealthState.Healthy))
    }

    @Test
    fun `Unknown profile can be manually selected`() {
        assertTrue(ProfileSelectorPredicate.canManualSelect(ProfileHealthState.Unknown))
    }
}
