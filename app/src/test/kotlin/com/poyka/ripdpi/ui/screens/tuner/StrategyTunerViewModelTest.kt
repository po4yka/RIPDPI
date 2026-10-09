package com.poyka.ripdpi.ui.screens.tuner

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.FakeStringResolver
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

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

    @Test
    fun `new sweep replaces the applied strategy feedback`() =
        runTest {
            val runner = FakeStrategyTunerRunner(listOf(StrategyTunerEvent.Complete))
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())
            viewModel.start()
            advanceUntilIdle()
            viewModel.apply("tls")
            advanceUntilIdle()

            viewModel.start()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.messageRes)
            assertEquals(
                FakeStringResolver().getString(R.string.strategy_tuner_message_sweep_complete),
                viewModel.uiState.value.message,
            )
        }

    @Test
    fun `running sweep keeps its domain scope when editing is requested`() =
        runTest {
            val runner = FakeStrategyTunerRunner(emptyList(), holdAfterStarted = true)
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())
            viewModel.updateDomainsText("one.example")
            viewModel.start()
            runCurrent()

            viewModel.updateDomainsText("other.example")

            assertEquals("one.example", viewModel.uiState.value.domainsText)
            assertEquals(listOf("one.example"), viewModel.uiState.value.activeDomains)
            viewModel.cancel()
        }

    @Test
    fun `new running sweep and cancellation clear applied feedback`() =
        runTest {
            val runner = FakeStrategyTunerRunner(listOf(StrategyTunerEvent.Complete))
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())
            viewModel.start()
            advanceUntilIdle()
            viewModel.apply("tls")
            advanceUntilIdle()
            runner.holdAfterStarted = true

            viewModel.start()
            runCurrent()
            assertNull(viewModel.uiState.value.messageRes)
            viewModel.cancel()
            assertNull(viewModel.uiState.value.messageRes)
            assertEquals(
                FakeStringResolver().getString(R.string.strategy_tuner_message_sweep_cancelled),
                viewModel.uiState.value.message,
            )
        }

    @Test
    fun `empty domains replace applied feedback with input guidance`() =
        runTest {
            val runner = FakeStrategyTunerRunner(listOf(StrategyTunerEvent.Complete))
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())
            viewModel.apply("tls")
            advanceUntilIdle()
            viewModel.updateDomainsText("")

            viewModel.start()

            assertNull(viewModel.uiState.value.messageRes)
            assertEquals(
                FakeStringResolver().getString(R.string.strategy_tuner_message_add_domain),
                viewModel.uiState.value.message,
            )
        }

    @Test
    fun `failed sweep replaces applied feedback with the failure`() =
        runTest {
            val runner = FakeStrategyTunerRunner(emptyList())
            val viewModel = StrategyTunerViewModel(runner, FakeStringResolver())
            viewModel.apply("tls")
            advanceUntilIdle()
            runner.failure = IOException("Probe unavailable")

            viewModel.start()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.messageRes)
            assertEquals("Probe unavailable", viewModel.uiState.value.message)
            assertEquals(StrategyTunerRunState.Error, viewModel.uiState.value.runState)
        }
}

private class FakeStrategyTunerRunner(
    private val events: List<StrategyTunerEvent>,
    var holdAfterStarted: Boolean = false,
) : StrategyTunerRunner {
    var failure: IOException? = null
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
            failure?.let {
                failure = null
                throw it
            }
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
            if (holdAfterStarted) awaitCancellation()
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
