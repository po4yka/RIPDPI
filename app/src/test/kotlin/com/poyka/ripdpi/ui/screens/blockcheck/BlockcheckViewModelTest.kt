package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeConfig
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.summarizeStrategyProbeResults
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockcheckViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `startProbe ranks emitted results as they arrive`() =
        runTest {
            val viewModel =
                testViewModel(
                    results =
                        listOf(
                            result("split", success = false, latencyMs = 300),
                            result("fake", success = true, latencyMs = 90),
                        ),
                )

            viewModel.startProbe()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(BlockcheckRunState.Complete, state.runState)
            assertEquals("fake", state.bestStrategy?.strategyId)
            assertEquals(2, state.results.size)
        }

    @Test
    fun `startProbe updates ranked strategies incrementally while stream is running`() =
        runTest {
            val probeService = StreamingStrategyProbeService()
            val viewModel =
                testViewModel(
                    probeService = probeService,
                    results = emptyList(),
                )
            val emittedResults =
                listOf(
                    result("split", success = false, latencyMs = 300),
                    result("fake", success = false, latencyMs = 100),
                    result("split", success = true, latencyMs = 50),
                )

            viewModel.startProbe()
            advanceUntilIdle()
            assertTrue(probeService.emit(emittedResults[0]))
            advanceUntilIdle()
            assertEquals(
                listOf("split"),
                viewModel.uiState.value.rankedStrategies
                    .map { it.strategyId },
            )
            assertEquals(1, viewModel.uiState.value.results.size)

            assertTrue(probeService.emit(emittedResults[1]))
            advanceUntilIdle()
            assertEquals(
                listOf("fake", "split"),
                viewModel.uiState.value.rankedStrategies
                    .map { it.strategyId },
            )
            assertEquals(2, viewModel.uiState.value.results.size)

            assertTrue(probeService.emit(emittedResults[2]))
            advanceUntilIdle()
            assertEquals(
                summarizeStrategyProbeResults(emittedResults).rankedStrategies,
                viewModel.uiState.value.rankedStrategies,
            )
            assertEquals(3, viewModel.uiState.value.results.size)

            viewModel.cancelProbe()
            advanceUntilIdle()
        }

    @Test
    fun `cancelProbe keeps partial results`() =
        runTest {
            val probeService = StreamingStrategyProbeService()
            val viewModel =
                testViewModel(
                    probeService = probeService,
                    results = emptyList(),
                )

            viewModel.startProbe()
            advanceUntilIdle()
            assertTrue(
                probeService.emit(result("split", success = true, latencyMs = 140)),
            )
            advanceUntilIdle()
            viewModel.cancelProbe()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(BlockcheckRunState.Complete, state.runState)
            assertEquals(1, state.results.size)
            assertEquals("split", state.bestStrategy?.strategyId)
        }

    @Test
    fun `startProbe shows empty state when no candidates are registered`() =
        runTest {
            val viewModel =
                testViewModel(
                    candidateProvider = FakeStrategyProbeCandidateProvider(emptyList()),
                    results = emptyList(),
                )

            viewModel.startProbe()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(BlockcheckRunState.Complete, state.runState)
            assertTrue(state.noStrategiesRegistered)
        }

    @Test
    fun `applyBestStrategy writes winner config and reloads native config`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader()
            val viewModel =
                testViewModel(
                    repository = repository,
                    reloader = reloader,
                    results = listOf(result("fake", success = true, latencyMs = 90)),
                )

            viewModel.startProbe()
            advanceUntilIdle()
            viewModel.applyBestStrategy()
            advanceUntilIdle()

            val appliedStep =
                repository
                    .snapshot()
                    .effectiveTcpChainSteps()
                    .single()
            assertEquals(TcpChainStepKind.Fake, appliedStep.kind)
            assertEquals(1, reloader.reloadCount)
            assertEquals("Strategy applied", viewModel.uiState.value.message)
        }

    @Test
    fun `exportReport includes raw results and ranked summary`() {
        val report =
            encodeBlockcheckReport(
                BlockcheckUiState(
                    runState = BlockcheckRunState.Complete,
                    results = listOf(result("fake", success = true, latencyMs = 90)),
                    rankedStrategies =
                        listOf(
                            com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult(
                                strategyId = "fake",
                                strategyLabel = "Fake packet",
                                total = 1,
                                successes = 1,
                                successRate = 1.0,
                                averageLatencyMs = 90,
                                dnsTamperedCount = 0,
                            ),
                        ),
                ),
            )

        assertTrue(report.contains("\"strategy_id\": \"fake\""))
        assertTrue(report.contains("\"ranked_strategies\""))
    }

    private fun testViewModel(
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        reloader: FakeBlockcheckStrategyReloader = FakeBlockcheckStrategyReloader(),
        probeService: StrategyProbeService? = null,
        candidateProvider: StrategyProbeCandidateProvider = FakeStrategyProbeCandidateProvider(),
        results: List<StrategyProbeResult>,
    ): BlockcheckViewModel {
        val viewModel =
            BlockcheckViewModel(
                probeService = probeService ?: FakeStrategyProbeService(results),
                candidateProvider = candidateProvider,
                appSettingsRepository = repository,
            )
        viewModel.strategyReloader = reloader
        return viewModel
    }
}

private class FakeStrategyProbeService(
    private val results: List<StrategyProbeResult>,
) : StrategyProbeService {
    override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> =
        flow {
            results.forEach { emit(it) }
        }
}

private class StreamingStrategyProbeService : StrategyProbeService {
    private val results = MutableSharedFlow<StrategyProbeResult>(extraBufferCapacity = 8)

    override fun run(config: StrategyProbeConfig): Flow<StrategyProbeResult> = results

    fun emit(result: StrategyProbeResult): Boolean = results.tryEmit(result)
}

private class FakeStrategyProbeCandidateProvider(
    private val candidates: List<StrategyProbeCandidate> = defaultCandidates(),
) : StrategyProbeCandidateProvider {
    override suspend fun listCandidates(): List<StrategyProbeCandidate> = candidates
}

private fun defaultCandidates(): List<StrategyProbeCandidate> =
    listOf(
        StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto(balanced)"),
        StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
    )

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettings.getDefaultInstance())

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state
                .value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class FakeBlockcheckStrategyReloader : BlockcheckStrategyReloader {
    var reloadCount = 0

    override fun reloadConfig(): String? {
        reloadCount += 1
        return null
    }
}

private fun result(
    strategyId: String,
    success: Boolean,
    latencyMs: Long,
): StrategyProbeResult =
    StrategyProbeResult(
        strategyId = strategyId,
        strategyLabel = if (strategyId == "fake") "Fake packet" else "Split",
        domain = "youtube.com",
        success = success,
        latencyMs = latencyMs,
    )
