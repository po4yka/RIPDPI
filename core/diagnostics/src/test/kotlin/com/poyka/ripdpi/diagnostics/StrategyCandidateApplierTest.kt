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
    fun `a candidate config with explicit nulls, as the engine writes it, is applied`() =
        runTest {
            val repository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings())
            val before = repository.snapshot()
            // The engine marks an unset option with a null; the app's own encoder leaves the key out.
            val engineConfig = config.replaceFirst("\"fakePackets\":{", "\"fakePackets\":{\"entropyPaddingMax\":null,")
            assertTrue(engineConfig != config)
            val report = report(candidate("tcp-pass", "success", succeeded = 4).copy(proxyConfigJson = engineConfig))

            assertEquals(setOf("tcp-pass"), StrategyCandidateApplier(repository).applicableIds(report))
            assertTrue(StrategyCandidateApplier(repository).apply(report, "tcp-pass"))
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

            assertEquals(setOf("tcp-pass"), applier.applicableIds(report()))
            assertFalse(applier.apply(report(), "tcp-blocked"))
            assertFalse(applier.apply(report(), "missing"))
            assertEquals(before, repository.snapshot())
        }

    private fun report(pass: StrategyProbeCandidateSummary = candidate("tcp-pass", "success", succeeded = 4)) =
        StrategyProbeReport(
            suiteId = "full_matrix_v1",
            tcpCandidates = listOf(pass, candidate("tcp-blocked", "failed", 0)),
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
