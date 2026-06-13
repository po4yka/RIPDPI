package com.poyka.ripdpi.ui.screens.tuner

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.FakeStringResolver
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyTunerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `start bounds domains and ranks emitted strategy results`() =
        runTest {
            val runner =
                FakeStrategyTunerRunner(
                    events =
                        listOf(
                            StrategyTunerEvent.Result(result("split", success = false, latencyMs = 300)),
                            StrategyTunerEvent.Result(result("tls", success = true, latencyMs = 90)),
                            StrategyTunerEvent.Complete,
                        ),
                )
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())

            viewModel.updateDomainsText("one.example\ntwo.example\nthree.example")
            viewModel.start()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf("one.example", "two.example"), runner.lastDomains)
            assertEquals(StrategyTunerRunState.Complete, state.runState)
            assertEquals("tls", state.bestStrategy?.strategyId)
            assertEquals(2, state.results.size)
            assertTrue(runner.appliedStrategyIds.isEmpty())
        }

    @Test
    fun `apply is explicit and records applied winner`() =
        runTest {
            val runner =
                FakeStrategyTunerRunner(
                    events =
                        listOf(
                            StrategyTunerEvent.Result(result("tls", success = true, latencyMs = 90)),
                            StrategyTunerEvent.Complete,
                        ),
                )
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())

            viewModel.start()
            advanceUntilIdle()
            viewModel.apply("tls")
            advanceUntilIdle()

            assertEquals(listOf("tls"), runner.appliedStrategyIds)
            assertEquals("tls", viewModel.uiState.value.appliedStrategyId)
            assertEquals(R.string.strategy_tuner_message_strategy_applied, viewModel.uiState.value.messageRes)
        }
}

private class FakeStrategyTunerRunner(
    private val events: List<StrategyTunerEvent>,
) : StrategyTunerRunner {
    override val budget: StrategyTunerBudget =
        StrategyTunerBudget(
            maxStrategies = 2,
            maxDomains = 2,
            maxTotalProbes = 4,
            probeIntervalMs = 500L,
            timeoutMs = 2_000L,
        )
    val appliedStrategyIds = mutableListOf<String>()
    var lastDomains: List<String> = emptyList()

    override fun run(domains: List<String>): Flow<StrategyTunerEvent> =
        flow {
            lastDomains = domains
            emit(
                StrategyTunerEvent.Started(
                    domains = domains,
                    candidates =
                        listOf(
                            StrategyProbeCandidate("split", "Split"),
                            StrategyProbeCandidate("tls", "TLS record split"),
                        ),
                    totalExpectedResults = budget.maxTotalProbes,
                ),
            )
            events.forEach { emit(it) }
        }

    override suspend fun apply(strategyId: String): String? {
        appliedStrategyIds += strategyId
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
        strategyLabel = strategyId,
        domain = "one.example",
        success = success,
        latencyMs = latencyMs,
    )
