package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileQualityStoreTest {
    @Test
    fun `revocation rejects observations and decisions from an earlier generation but retains budget`() {
        val store = ProfileQualityStore()
        val key = ProfileQualityKey("g", "p")
        val generation = store.generation("g")
        val observation =
            ProfileQualityObservation(
                key,
                ProfileQualityStatus.Verified,
                32_768,
                100_000_000,
                327_680,
                10,
                20,
                true,
                ProfileQualityReason.Verified,
                100,
            )
        val decision = ProfileQualityDecision("g", "p", "r", ProfileQualityDecisionReason.PromotedForGoodput, 100)
        store.observe(listOf(observation), generation)
        store.budget(ProfileQualityBudget("g", 131_076, 131_076, 262_152, null, ProfileQualityReason.Verified))
        store.invalidate("g")
        store.observe(listOf(observation), generation)
        store.decide(decision, generation)
        assertEquals(
            ProfileQualityReason.ScopeChanged,
            store.state.value.observations[key]
                ?.reason,
        )
        assertNull(
            store.state.value.observations[key]
                ?.goodputBytesPerSecond,
        )
        assertNull(store.state.value.decisions["g"])
        assertEquals(
            131_076L,
            store.state.value.budgets["g"]
                ?.consumedWindowBytes,
        )
        store.observe(listOf(observation), store.generation("g"))
        assertEquals(observation, store.state.value.observations[key])
    }
}
