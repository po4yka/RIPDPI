package com.poyka.ripdpi.subscription

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorUrltestProberResilienceTest {
    @Test
    fun `a probe pass that throws does not end the run loop`() =
        runTest {
            val store = ProbeTestSelectionStore()
            store.select("g", "slow")
            var captureFails = true
            val prober =
                SelectorUrltestProber(
                    store,
                    MemberLatencyProbe { profile, _ -> MemberProbeResult(if (profile.id == "fast") 100L else 300L) },
                    SelectorProbeScopeProvider { group ->
                        check(!captureFails) { "network capture failed" }
                        selectorTestScope(group)
                    },
                )
            val group =
                selectorTestGroup(listOf(selectorTestMember("slow"), selectorTestMember("fast")), intervalSeconds = 10)
            val loop = backgroundScope.launch { prober.run(group) }

            advanceTimeBy(10_001)
            runCurrent()
            captureFails = false
            advanceTimeBy(10_001)
            runCurrent()

            assertTrue(loop.isActive)
            assertEquals("fast", store.selectedProfileId("g").value)
        }
}
