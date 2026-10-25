package com.poyka.ripdpi.ui.screens.tuner

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeStringResolver
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.toStrategyActivationYaml
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The runner is a singleton shared by every tuner screen instance, so a screen that is left and
 * re-entered while a cancelled sweep still restores the settings uses the same lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultStrategyTunerRunnerTest {
    private val candidate = StrategyProbeCandidate("tls", "TLS record split")

    @Test
    fun `apply after a cancelled sweep lands after its settings restore`() =
        runTest {
            val repository = FakeAppSettingsRepository().apply { update { strategyChainYaml = UserYaml } }
            val probes = RestoringProbeService(repository)
            val runner = runner(repository, probes)

            val sweep = launch { runner.run(listOf("one.example")).collect {} }
            runCurrent()
            sweep.cancel()
            // Native reload is unavailable on the JVM; the settings write is what matters here.
            val applied = async { runCatching { runner.apply("tls") } }
            runCurrent()
            probes.restoreGate.complete(Unit)
            applied.await()
            advanceUntilIdle()

            assertEquals(candidate.toStrategyActivationYaml(), repository.snapshot().strategyChainYaml)
        }

    @Test
    fun `sweep after a cancelled sweep snapshots the restored user settings`() =
        runTest {
            val repository = FakeAppSettingsRepository().apply { update { strategyChainYaml = UserYaml } }
            val probes = RestoringProbeService(repository)
            val runner = runner(repository, probes)

            val first = launch { runner.run(listOf("one.example")).collect {} }
            runCurrent()
            first.cancel()
            val second = launch { runner.run(listOf("one.example")).collect {} }
            runCurrent()
            probes.restoreGate.complete(Unit)
            runCurrent()
            second.cancel()
            advanceUntilIdle()

            assertEquals(UserYaml, repository.snapshot().strategyChainYaml)
        }

    private fun runner(
        repository: FakeAppSettingsRepository,
        probes: StrategyProbeService,
    ): DefaultStrategyTunerRunner =
        DefaultStrategyTunerRunner(
            candidateProvider =
                object : StrategyProbeCandidateProvider {
                    override suspend fun listCandidates(): List<StrategyProbeCandidate> = listOf(candidate)
                },
            probeService = probes,
            appSettingsRepository = repository,
            stringResolver = FakeStringResolver(),
        )

    /** Installs a candidate and restores the snapshot in a non-cancellable finally gated on [restoreGate]. */
    private class RestoringProbeService(
        private val repository: FakeAppSettingsRepository,
    ) : StrategyProbeService {
        val restoreGate = CompletableDeferred<Unit>()

        override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> =
            flow {
                val snapshot = repository.snapshot().strategyChainYaml
                try {
                    repository.update { strategyChainYaml = "probe-candidate" }
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        restoreGate.await()
                        repository.update { strategyChainYaml = snapshot }
                    }
                }
            }
    }

    private companion object {
        const val UserYaml = "user-strategy"
    }
}
