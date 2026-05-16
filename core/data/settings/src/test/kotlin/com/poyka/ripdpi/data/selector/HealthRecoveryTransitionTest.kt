package com.poyka.ripdpi.data.selector

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRecoveryTransitionTest {
    private val fixtureCfProfile =
        ProfileCandidate(
            id = "fixture-cf-recovery",
            kind = RelayProfileKind.CloudflareXhttp,
            isCloudflareBacked = true,
            label = ProfileSelectorLabel.OptionalEdgeFallback,
        )

    private val fixtureNonCfProfile =
        ProfileCandidate(
            id = "fixture-non-cf-recovery",
            kind = RelayProfileKind.RealityDirect,
            isCloudflareBacked = false,
            label = ProfileSelectorLabel.Primary,
        )

    private val allCandidates = listOf(fixtureCfProfile, fixtureNonCfProfile)

    @Test
    fun `CF excluded when degraded, re-enters auto after recovery to healthy`() {
        val observer = CloudflareHealthStateObserver()

        // Starts Healthy — CF included
        val healthyOrdering = AutoSelector.order(allCandidates, observer.currentStateValue)
        assertEquals(2, healthyOrdering.size)

        // Transition to Degraded — CF dropped
        observer.onDegraded()
        val degradedOrdering = AutoSelector.order(allCandidates, observer.currentStateValue)
        assertEquals(1, degradedOrdering.size)
        assertEquals(fixtureNonCfProfile.id, degradedOrdering.first().id)

        // Recovery back to Healthy — CF re-enters
        observer.onHealthy()
        val recoveredOrdering = AutoSelector.order(allCandidates, observer.currentStateValue)
        assertEquals(2, recoveredOrdering.size)
    }

    @Test
    fun `observer emits state transitions via StateFlow`() =
        runTest {
            val observer = CloudflareHealthStateObserver()
            assertEquals(CloudflareHealthState.Healthy, observer.currentState.first())

            observer.onDegraded()
            assertEquals(CloudflareHealthState.Degraded, observer.currentState.first())

            observer.onHealthy()
            assertEquals(CloudflareHealthState.Healthy, observer.currentState.first())
        }
}
