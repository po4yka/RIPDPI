package com.poyka.ripdpi.ui.screens.blockcheck

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.diagnostics.StrategyProbeService
import com.poyka.ripdpi.diagnostics.summarizeStrategyProbeResults
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            assertEquals(R.string.blockcheck_message_strategy_applied, viewModel.uiState.value.messageRes)
        }

    @Test
    fun `startProbe maps diagnosis to matching recommended strategy class`() =
        runTest {
            val viewModel =
                testViewModel(
                    results =
                        listOf(
                            result("split", success = false, latencyMs = 300),
                            result("tlsrec_split", success = true, latencyMs = 90),
                        ),
                    diagnosisRunner =
                        FakeBlockcheckDiagnosisRunner(
                            listOf(
                                diagnosis(
                                    domain = "youtube.com",
                                    layer = BlockLayer.SNI_BASED_RESET,
                                    bypassClass = BypassStrategyClass.TLS_RECORD_SPLIT,
                                ),
                            ),
                        ),
                    candidateProvider =
                        FakeStrategyProbeCandidateProvider(
                            listOf(
                                StrategyProbeCandidate("fake", "Fake packet", "[tcp]\nfake auto(host)"),
                                StrategyProbeCandidate("tlsrec_split", "TLS record split", "[tcp]\ntlsrec split"),
                            ),
                        ),
                )

            viewModel.startProbe()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(BlockLayer.SNI_BASED_RESET, state.primaryDiagnosis?.diagnosis?.layer)
            assertEquals(BypassStrategyClass.TLS_RECORD_SPLIT, state.primaryDiagnosis?.diagnosis?.bypassClass)
            assertEquals("tlsrec_split", state.recommendedStrategyId)
            assertEquals("TLS record split", state.recommendedStrategyLabel)
        }

    @Test
    fun `applyRecommendedStrategy writes matching class strategy`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val reloader = FakeBlockcheckStrategyReloader()
            val viewModel =
                testViewModel(
                    repository = repository,
                    reloader = reloader,
                    results = listOf(result("fake", success = true, latencyMs = 90)),
                    diagnosisRunner =
                        FakeBlockcheckDiagnosisRunner(
                            listOf(
                                diagnosis(
                                    domain = "youtube.com",
                                    layer = BlockLayer.IP_BLOCK,
                                    bypassClass = BypassStrategyClass.FAKE_PACKET_TTL,
                                ),
                            ),
                        ),
                )

            viewModel.startProbe()
            advanceUntilIdle()
            viewModel.applyRecommendedStrategy()
            advanceUntilIdle()

            val appliedStep =
                repository
                    .snapshot()
                    .effectiveTcpChainSteps()
                    .single()
            assertEquals(TcpChainStepKind.Fake, appliedStep.kind)
            assertEquals(1, reloader.reloadCount)
        }

    @Test
    fun `exportReport includes raw results and ranked summary`() {
        val report =
            encodeBlockcheckReport(
                BlockcheckUiState(
                    runState = BlockcheckRunState.Complete,
                    diagnoses =
                        listOf(
                            diagnosis(
                                domain = "youtube.com",
                                layer = BlockLayer.DNS_POISONING,
                                bypassClass = BypassStrategyClass.ENCRYPTED_DNS,
                            ),
                        ),
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
        assertTrue(report.contains("\"layer\": \"dns_poisoning\""))
        assertTrue(report.contains("\"bypass_class\": \"encrypted_dns\""))
    }

    private fun testViewModel(
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        reloader: FakeBlockcheckStrategyReloader = FakeBlockcheckStrategyReloader(),
        probeService: StrategyProbeService? = null,
        candidateProvider: StrategyProbeCandidateProvider = FakeStrategyProbeCandidateProvider(),
        diagnosisRunner: BlockcheckDiagnosisRunner = FakeBlockcheckDiagnosisRunner(),
        results: List<StrategyProbeResult>,
    ): BlockcheckViewModel =
        BlockcheckViewModel(
            orchestrator =
                BlockcheckProbeOrchestrator(
                    probeService = probeService ?: FakeStrategyProbeService(results),
                    candidateProvider = candidateProvider,
                    diagnosisRunner = diagnosisRunner,
                ),
            recommendationRepository =
                DefaultBlockcheckRecommendationRepository(
                    appSettingsRepository = repository,
                    reloader = reloader,
                ),
        )
}

private fun diagnosis(
    domain: String,
    layer: BlockLayer,
    bypassClass: BypassStrategyClass,
): BlockcheckSiteDiagnosis = blockcheckDiagnosis(domain = domain, layer = layer, bypassClass = bypassClass)

private fun result(
    strategyId: String,
    success: Boolean,
    latencyMs: Long,
): StrategyProbeResult = blockcheckProbeResult(strategyId = strategyId, success = success, latencyMs = latencyMs)
