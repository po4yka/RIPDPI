package com.poyka.ripdpi.diagnostics.orchestrator

import com.poyka.ripdpi.diagnostics.shared.DnsClassification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that DirectModeOrchestrator exits Phase 4 immediately upon the
 * first stable success when [AttemptBudget.stopOnFirstStableSuccess] is true,
 * and continues when the flag is false.
 */
class EarlyStopOnFirstStableSuccessTest {
    private fun orchestratorFor(
        stopOnFirstStableSuccess: Boolean,
        maxActiveArms: Int,
        executor: ArmExecutor,
    ): DirectModeOrchestrator =
        DirectModeOrchestrator(
            dnsClassifier = StubDnsClassifier(DnsClassification.CLEAN),
            transportClassifier = StubTransportClassifier(TransportClass.SniTlsSuspect),
            armRanker = IdentityArmRanker(),
            armExecutor = executor,
            ownedStackPinGuard = ConfirmedPinGuard(),
            budget =
                AttemptBudget(
                    maxActiveArms = maxActiveArms,
                    stopOnFirstStableSuccess = stopOnFirstStableSuccess,
                ),
        )

    @Test
    fun `early-stop true halts after first stable success`() =
        runTest {
            // SniTlsSuspect has 7 arms; succeed on the second arm (index 1).
            val result =
                orchestratorFor(
                    stopOnFirstStableSuccess = true,
                    maxActiveArms = 7,
                    executor = SucceedAtIndexExecutor(successIndex = 1),
                ).run()

            assertTrue(result.stableSuccessReached)
            assertEquals(2, result.armsExecuted.size)
        }

    @Test
    fun `early-stop false continues after first stable success`() =
        runTest {
            // Succeed on first arm; without early-stop all 3 budget slots run.
            val result =
                orchestratorFor(
                    stopOnFirstStableSuccess = false,
                    maxActiveArms = 3,
                    executor = SucceedAtIndexExecutor(successIndex = 0),
                ).run()

            assertTrue(result.stableSuccessReached)
            assertEquals(3, result.armsExecuted.size)
        }

    @Test
    fun `no stable success leaves stableSuccessReached false`() =
        runTest {
            val result =
                orchestratorFor(
                    stopOnFirstStableSuccess = true,
                    maxActiveArms = 3,
                    executor = NeverSucceedExecutor(),
                ).run()

            assertFalse(result.stableSuccessReached)
        }

    @Test
    fun `early-stop true on first arm executes exactly one arm`() =
        runTest {
            val result =
                orchestratorFor(
                    stopOnFirstStableSuccess = true,
                    maxActiveArms = 7,
                    executor = SucceedAtIndexExecutor(successIndex = 0),
                ).run()

            assertTrue(result.stableSuccessReached)
            assertEquals(1, result.armsExecuted.size)
        }
}
