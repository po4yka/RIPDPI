package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.applyStrategyToSettings
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.coroutines.flow.first
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

            assertTrue(StrategyCandidateApplier(repository).apply(session(), Network, "tcp-pass"))

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
            val engineConfig = config.replaceFirst("\"fakePackets\":{", "\"fakePackets\":{\"entropyPaddingMax\":null,")
            assertTrue(engineConfig != config)
            val report = report(candidate("tcp-pass", "success", succeeded = 4).copy(proxyConfigJson = engineConfig))

            assertEquals(
                setOf("tcp-pass"),
                StrategyCandidateApplier(repository).applicableIds(session(report), Network).first(),
            )
            assertTrue(StrategyCandidateApplier(repository).apply(session(report), Network, "tcp-pass"))
            assertEquals(
                decodeRipDpiProxyUiPreferences(
                    config,
                )!!.applyStrategyToSettings(before, applyTcp = true, applyQuic = false),
                repository.snapshot(),
            )
        }

    @Test
    fun `an applied candidate is no longer offered`() =
        runTest {
            val repository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings())
            val applier = StrategyCandidateApplier(repository)
            assertEquals(setOf("tcp-pass"), applier.applicableIds(session(), Network).first())

            assertTrue(applier.apply(session(), Network, "tcp-pass"))

            assertEquals(emptySet<String>(), applier.applicableIds(session(), Network).first())
        }

    @Test
    fun `a blocked or unknown candidate changes nothing`() =
        runTest {
            val repository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings())
            val before = repository.snapshot()
            val applier = StrategyCandidateApplier(repository)

            assertEquals(setOf("tcp-pass"), applier.applicableIds(session(), Network).first())
            assertFalse(applier.apply(session(), Network, "tcp-blocked"))
            assertFalse(applier.apply(session(), Network, "missing"))
            assertEquals(before, repository.snapshot())
        }

    @Test
    fun `a test from another network, an unknown network or a partial run applies nothing`() =
        runTest {
            val repository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings())
            val before = repository.snapshot()
            val applier = StrategyCandidateApplier(repository)
            val partial = session(report().copy(completionKind = StrategyProbeCompletionKind.PARTIAL_RESULTS))
            val unknownNetwork = session().copy(networkScopeKey = null)

            assertEquals(emptySet<String>(), applier.applicableIds(session(), "other-network").first())
            assertFalse(applier.apply(session(), "other-network", "tcp-pass"))
            assertFalse(applier.apply(unknownNetwork, Network, "tcp-pass"))
            assertEquals(emptySet<String>(), applier.applicableIds(partial, Network).first())
            assertFalse(applier.apply(partial, Network, "tcp-pass"))
            assertEquals(before, repository.snapshot())
        }

    @Test
    fun `one success out of several targets is not applicable`() =
        runTest {
            val applier = StrategyCandidateApplier(FakeAppSettingsRepository(defaultDiagnosticsAppSettings()))
            val lucky = session(report(candidate("tcp-pass", "partial", succeeded = 1)))
            val half = session(report(candidate("tcp-pass", "partial", succeeded = 2)))

            assertFalse(applier.apply(lucky, Network, "tcp-pass"))
            assertTrue(applier.apply(half, Network, "tcp-pass"))
        }

    private fun session(report: StrategyProbeReport = report()) =
        DiagnosticScanSession(
            id = "session",
            profileId = "automatic-probing",
            pathMode = ScanPathMode.RAW_PATH.name,
            serviceMode = null,
            status = "completed",
            summary = "",
            report = DiagnosticsSessionProjection(strategyProbeReport = report),
            startedAt = 0L,
            finishedAt = 1L,
            networkScopeKey = Network,
        )

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
        emitterTier = productionEmitterForTests,
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

    private companion object {
        const val Network = "network"
    }
}
