package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Verifies that DirectModeOrchestrator stops arm execution when any of the
 * three hard budget limits is reached.
 */
class AttemptBudgetEnforcementTest {
    private fun orchestratorWith(
        budget: AttemptBudget,
        executor: ArmExecutor,
        clock: () -> Long = System::currentTimeMillis,
    ): DirectModeOrchestrator =
        DirectModeOrchestrator(
            dnsClassifier = StubDnsClassifier(DnsClassification.CLEAN),
            transportClassifier = StubTransportClassifier(TransportClass.SniTlsSuspect),
            armRanker = IdentityArmRanker(),
            armExecutor = executor,
            ownedStackPinGuard = ConfirmedPinGuard(),
            budget = budget,
            clock = clock,
        )

    @Test
    fun `maxActiveArms stops execution after the limit is reached`() =
        runTest {
            val executor = StubArmExecutor(stableSuccess = false, probeBytes = 0L)
            val orchestrator =
                orchestratorWith(
                    budget = AttemptBudget(maxActiveArms = 2, stopOnFirstStableSuccess = false),
                    executor = executor,
                )

            val result = orchestrator.run()

            assertEquals(2, result.armsExecuted.size)
            assertEquals(2, executor.callCount)
        }

    @Test
    fun `maxProbeBytes stops execution when byte budget is exhausted`() =
        runTest {
            // Each arm costs 30 000 bytes; budget is 65 536.
            // arm 0: 0 bytes so far → executes (cumulative 30 000)
            // arm 1: 30 000 < 65 536 → executes (cumulative 60 000)
            // arm 2: 60 000 < 65 536 → executes (cumulative 90 000)
            // arm 3: 90 000 >= 65 536 → stops
            val executor = StubArmExecutor(stableSuccess = false, probeBytes = 30_000L)
            val orchestrator =
                orchestratorWith(
                    budget =
                        AttemptBudget(
                            maxActiveArms = 10,
                            maxProbeBytes = 65_536L,
                            stopOnFirstStableSuccess = false,
                        ),
                    executor = executor,
                )

            val result = orchestrator.run()
            assertEquals(3, result.armsExecuted.size)
        }

    @Test
    fun `maxElapsedMs stops execution when wall clock budget is exceeded`() =
        runTest {
            // Fake clock: first call returns startMs=0, second check returns 7 000 ms.
            val times = longArrayOf(0L, 0L, 7_000L)
            var callIndex = 0
            val fakeClock: () -> Long = { times[callIndex++] }

            val executor = StubArmExecutor(stableSuccess = false, probeBytes = 0L)
            val orchestrator =
                orchestratorWith(
                    budget =
                        AttemptBudget(
                            maxActiveArms = 10,
                            maxElapsedMs = 6_000L,
                            stopOnFirstStableSuccess = false,
                        ),
                    executor = executor,
                    clock = fakeClock,
                )

            val result = orchestrator.run()

            // Only the first arm executes; on the second iteration the clock
            // reports 7 000 ms >= 6 000 ms → loop exits.
            assertEquals(1, result.armsExecuted.size)
        }

    @Test
    fun `zero maxActiveArms produces empty execution list`() =
        runTest {
            val executor = StubArmExecutor(stableSuccess = false, probeBytes = 0L)
            val orchestrator =
                orchestratorWith(
                    budget = AttemptBudget(maxActiveArms = 0),
                    executor = executor,
                )

            val result = orchestrator.run()

            assertEquals(0, result.armsExecuted.size)
            assertEquals(0, executor.callCount)
            assertFalse(result.stableSuccessReached)
        }
}
