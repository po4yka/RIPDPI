package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.applyStrategyToSettings
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyCandidateApplierTest {
    private val config = RipDpiProxyUIPreferences().toNativeConfigJson()

    @Test
    fun `a candidate that passed is applied to its own lane`() =
        runTest {
            val repository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings())
            val before = repository.snapshot()

            assertTrue(StrategyCandidateApplier(repository).apply(report(), "tcp-pass"))

            assertEquals(
                decodeRipDpiProxyUiPreferences(
                    config,
                )!!.applyStrategyToSettings(before, applyTcp = true, applyQuic = false),
                repository.snapshot(),
            )
        }

    @Test
    fun `a blocked or unknown candidate changes nothing`() =
        runTest {
            val repository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings())
            val before = repository.snapshot()
            val applier = StrategyCandidateApplier(repository)

            assertFalse(applier.apply(report(), "tcp-blocked"))
            assertFalse(applier.apply(report(), "missing"))
            assertEquals(before, repository.snapshot())
        }

    private fun report() =
        StrategyProbeReport(
            suiteId = "full_matrix_v1",
            tcpCandidates =
                listOf(
                    candidate("tcp-pass", "success", succeeded = 4),
                    candidate("tcp-blocked", "failed", 0),
                ),
            quicCandidates = emptyList(),
            recommendation = null,
        )

    private fun candidate(
        id: String,
        outcome: String,
        succeeded: Int,
    ) = StrategyProbeCandidateSummary(
        id = id,
        label = id,
        family = "split",
        outcome = outcome,
        rationale = "",
        succeededTargets = succeeded,
        totalTargets = 4,
        weightedSuccessScore = succeeded,
        totalWeight = 4,
        qualityScore = succeeded,
        proxyConfigJson = config,
        desyncExecutionRequired = false,
        runtimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.CLEAN_SHUTDOWN,
    )
}
