package com.poyka.ripdpi.diagnostics.replay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [runToCompletion] against canned [ReplayStepEvent] flows,
 * asserting the terminal-aggregate contract that archive persistence
 * and downstream verdict consumers depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeReplayServiceExtTest {
    private val request = ReplayProbeRequest(domain = "example.test", strategyId = "default", timeoutMs = 1_000)

    @Test
    fun runToCompletion_aggregatesAllEventsIntoSingleResult() =
        runTest {
            val canned =
                listOf<ReplayStepEvent>(
                    ReplayStepEvent.StepStarted(ReplayStepKind.DnsResolve, 0),
                    ReplayStepEvent.StepCompleted(ReplayStepKind.DnsResolve, 10, "ok"),
                    ReplayStepEvent.Finished(
                        verdict = ReplayVerdict.Success,
                        terminalStep = null,
                        recommendationKey = "",
                    ),
                )
            val service = ProbeReplayService { flowOf(*canned.toTypedArray()) }

            val result = service.runToCompletion(request)

            assertEquals(3, result.events.size)
            assertEquals(ReplayVerdict.Success, result.verdict)
            assertEquals(request, result.request)
            assertEquals(null, result.terminalStep)
            assertEquals("", result.recommendationKey)
        }

    @Test
    fun runToCompletion_failureVerdict_propagatesTerminalStepAndRecommendationKey() =
        runTest {
            val canned =
                listOf<ReplayStepEvent>(
                    ReplayStepEvent.StepStarted(ReplayStepKind.TlsHandshake, 0),
                    ReplayStepEvent.StepFailed(
                        step = ReplayStepKind.TlsHandshake,
                        errorKind = ReplayErrorKind.ConnectionReset,
                        detail = "reset",
                    ),
                    ReplayStepEvent.Finished(
                        verdict = ReplayVerdict.Failure,
                        terminalStep = ReplayStepKind.TlsHandshake,
                        recommendationKey = "vpn_replay_rec_rst_after_client_hello",
                    ),
                )
            val service = ProbeReplayService { flowOf(*canned.toTypedArray()) }

            val result = service.runToCompletion(request)

            assertEquals(ReplayVerdict.Failure, result.verdict)
            assertEquals(ReplayStepKind.TlsHandshake, result.terminalStep)
            assertEquals("vpn_replay_rec_rst_after_client_hello", result.recommendationKey)
        }

    @Test
    fun runToCompletion_withNoFinished_returnsCancelledVerdict() =
        runTest {
            val service =
                ProbeReplayService {
                    flowOf(ReplayStepEvent.StepStarted(ReplayStepKind.DnsResolve, 0))
                }

            val result = service.runToCompletion(request)

            assertEquals(ReplayVerdict.Cancelled, result.verdict)
            assertEquals(null, result.terminalStep)
            assertEquals("", result.recommendationKey)
            assertEquals(1, result.events.size)
        }

    @Test
    fun runToCompletion_withEmptyFlow_returnsCancelledVerdictAndEmptyEvents() =
        runTest {
            val service = ProbeReplayService { flowOf() }

            val result = service.runToCompletion(request)

            assertEquals(ReplayVerdict.Cancelled, result.verdict)
            assertEquals(0, result.events.size)
        }
}
