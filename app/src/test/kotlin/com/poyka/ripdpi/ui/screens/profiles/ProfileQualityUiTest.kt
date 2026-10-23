package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityDecision
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityKey
import com.poyka.ripdpi.data.ProfileQualityObservation
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualitySnapshot
import com.poyka.ripdpi.data.ProfileQualityStatus
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ProfileQualityUiTest {
    private val app = RuntimeEnvironment.getApplication()
    private val strings = AndroidStringResolver(app)
    private val key = ProfileQualityKey("first", "shared")

    @Test
    fun qualityUsesTypedGroupAndMemberIdentity() {
        val observation = observation()
        val snapshot = ProfileQualitySnapshot(observations = mapOf(key to observation))
        assertEquals(
            observation,
            snapshot.forProfile(ProfileUtilityReference.SelectorMember("first", "shared"))?.observation,
        )
        assertNull(snapshot.forProfile(ProfileUtilityReference.SelectorMember("second", "shared")))
        assertNull(snapshot.forProfile(ProfileUtilityReference.NativeRelay("shared")))
        assertNull(snapshot.forProfile(ProfileUtilityReference.Xray("shared")))
    }

    @Test
    fun completeObservationShowsVerifiedBodyAndSeparateRequestTimes() {
        val lines = lines(observation())
        assertTrue(lines.contains(app.getString(R.string.profile_quality_goodput, "256")))
        assertTrue(lines.contains(app.getString(R.string.profile_quality_transfer, "262,144", "1")))
        assertTrue(lines.contains(app.getString(R.string.profile_quality_baseline, 25L)))
        assertTrue(lines.contains(app.getString(R.string.profile_quality_loaded, 40L)))
        assertTrue(lines.contains(app.getString(R.string.profile_quality_verified)))
    }

    @Test
    fun partialLoadNeverDisplaysUnobservedLoadedLatencyAndStaleHidesMetrics() {
        val partial =
            observation().copy(
                status = ProfileQualityStatus.Partial,
                reason = ProfileQualityReason.LoadNotOverlapped,
                loadedOverlap = false,
            )
        val lines = lines(partial)
        assertTrue(lines.contains(app.getString(R.string.profile_quality_no_overlap)))
        assertFalse(lines.contains(app.getString(R.string.profile_quality_loaded, 40L)))
        val stale = lines(observation().copy(reason = ProfileQualityReason.ScopeChanged))
        assertTrue(stale.contains(app.getString(R.string.profile_quality_stale)))
        assertFalse(stale.any { it.contains("256") || it.contains("262,144") })
    }

    @Test
    fun deferredBudgetRemainsVisibleWithoutMeasurement() {
        val budget = ProfileQualityBudget("first", 0, 8192, 8192, 5000, ProfileQualityReason.BudgetExhausted)
        val decision =
            ProfileQualityDecision("first", "shared", null, ProfileQualityDecisionReason.DeferredBudget, 1000)
        val snapshot =
            ProfileQualitySnapshot(budgets = mapOf("first" to budget), decisions = mapOf("first" to decision))
        val state = checkNotNull(snapshot.forProfile(ProfileUtilityReference.SelectorMember("first", "shared")))
        assertNull(state.observation)
        val lines = profileQualityLines(state, strings, Locale.US)
        assertTrue(lines.contains(app.getString(R.string.profile_quality_budget_exhausted)))
        assertTrue(lines.contains(app.getString(R.string.profile_quality_budget, 0L, 8192L, 8192L)))
    }

    @Test
    fun unavailableBudgetDoesNotClaimExhaustedAllowance() {
        val budget = ProfileQualityBudget("first", 0, 0, 8192, null, ProfileQualityReason.BudgetUnavailable)
        val decision =
            ProfileQualityDecision("first", "shared", null, ProfileQualityDecisionReason.DeferredBudget, 1000)
        val lines = profileQualityLines(ProfileQualityUiState(null, budget, decision), strings, Locale.US)
        assertTrue(lines.contains(app.getString(R.string.profile_quality_budget_unavailable)))
        assertFalse(lines.contains(app.getString(R.string.profile_quality_budget_exhausted)))
    }

    private fun lines(observation: ProfileQualityObservation) =
        profileQualityLines(ProfileQualityUiState(observation, null, null), strings, Locale.US)

    private fun observation() =
        ProfileQualityObservation(
            key,
            ProfileQualityStatus.Verified,
            262144,
            1_000_000_000,
            262144,
            25,
            40,
            true,
            ProfileQualityReason.Verified,
            1000,
        )
}
