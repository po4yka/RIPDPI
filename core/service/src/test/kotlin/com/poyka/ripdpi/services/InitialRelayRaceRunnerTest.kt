package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class InitialRelayRaceRunnerTest {
    @Test
    fun `duplicate profile identities are rejected before startup`() =
        runTest {
            val plan = racePlan()
            val duplicate = plan.copy(candidates = plan.candidates.map { it.copy(profileId = "same-profile") })

            val failure = runCatching { unusedRunner().run(duplicate) {} }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }

    @Test
    fun `blank profile identities are rejected before startup`() =
        runTest {
            for (profileId in listOf("", " ", "\t")) {
                val plan = racePlan()
                val blank = plan.copy(candidates = listOf(plan.candidates.first().copy(profileId = profileId)))

                val failure = runCatching { unusedRunner().run(blank) {} }.exceptionOrNull()

                assertTrue("profileId=$profileId failure=$failure", failure is IllegalArgumentException)
            }
        }

    @Test
    fun `exited provisional contender does not displace a live reserve`() =
        runTest {
            val plan = racePlan()
            val slots = mutableListOf<RelayRuntimeSlot>()
            val runner =
                InitialRelayRaceRunner(
                    relayActiveProbe =
                        RelayActiveProbe { endpoint, _, _ ->
                            val latency = if (endpoint.port == RealityPort) 10L else 100L
                            delay(latency)
                            RelayActiveProbeResult(false, statusCode = 503, latencyMs = latency)
                        },
                    relayHealthDecisionEngine = RelayHealthDecisionEngine(),
                    startCandidate = { candidate ->
                        val runtime = TestRelayRuntime()
                        startSlot(candidate, runtime).also { slot ->
                            slots += slot
                            if (candidate == plan.candidates.first()) {
                                backgroundScope.launch {
                                    delay(20L)
                                    runtime.complete(0)
                                }
                            }
                        }
                    },
                    promoteCandidate = {},
                    stopDiscardedCandidates = ::stopSlots,
                )

            try {
                val promoted = runner.run(plan) {}

                assertEquals("hysteria", promoted.result.selectedCandidate.profileId)
                assertEquals(HysteriaPort, promoted.endpoint.port)
                assertTrue(promoted.result.verificationInconclusive)
                assertTrue(slots.first().job.isCompleted)
                assertTrue(slots.last().job.isActive)
            } finally {
                stopSlots(slots, null)
            }
        }

    @Test
    fun `candidate exit during loser cleanup cannot publish selection`() =
        runTest {
            val slots = mutableListOf<RelayRuntimeSlot>()
            val states = mutableListOf<String>()
            val runner =
                InitialRelayRaceRunner(
                    relayActiveProbe =
                        RelayActiveProbe { endpoint, _, _ ->
                            delay(if (endpoint.port == RealityPort) 10L else 100L)
                            RelayActiveProbeResult(true, statusCode = 204, latencyMs = 10L)
                        },
                    relayHealthDecisionEngine = RelayHealthDecisionEngine(),
                    startCandidate = { startSlot(it, TestRelayRuntime()).also(slots::add) },
                    promoteCandidate = {},
                    stopDiscardedCandidates = { candidates, winner ->
                        winner?.let { stopSlots(listOf(it), null) }
                        stopSlots(candidates, winner)
                    },
                )

            val failure = runCatching { runner.run(racePlan()) { states += it.state } }.exceptionOrNull()

            assertTrue(failure is com.poyka.ripdpi.data.InitialTransportSelectionException)
            assertFalse(states.contains("selected"))
            assertTrue(slots.all { it.job.isCompleted })
        }

    @Test
    fun `final cleanup does not replace selection failure or cancellation`() =
        runTest {
            for (primary in listOf(IllegalStateException("promotion failed"), CancellationException("cancelled"))) {
                val slots = mutableListOf<RelayRuntimeSlot>()
                val cleanup = IOException("cleanup failed")
                val runner =
                    InitialRelayRaceRunner(
                        relayActiveProbe =
                            RelayActiveProbe { _, _, _ ->
                                RelayActiveProbeResult(true, statusCode = 204, latencyMs = 10L)
                            },
                        relayHealthDecisionEngine = RelayHealthDecisionEngine(),
                        startCandidate = { startSlot(it, TestRelayRuntime()).also(slots::add) },
                        promoteCandidate = { throw primary },
                        stopDiscardedCandidates = { candidates, winner ->
                            stopSlots(candidates, winner)
                            if (winner == null) throw cleanup
                        },
                    )

                val failure = runCatching { runner.run(racePlan()) {} }.exceptionOrNull()

                assertEquals(primary.javaClass, failure?.javaClass)
                assertEquals(primary.message, failure?.message)
                assertTrue(primary.suppressed.contains(cleanup))
                assertTrue(slots.all { it.job.isCompleted })
            }
        }

    @Test
    fun `replaced runtime cannot reuse a previous startup success`() =
        runTest {
            var probeSucceeds = true
            val slots = mutableListOf<RelayRuntimeSlot>()
            val factory =
                InitialRelayRaceRunnerFactory(
                    RelayActiveProbe { _, _, _ ->
                        RelayActiveProbeResult(
                            probeSucceeds,
                            statusCode = if (probeSucceeds) 204 else 503,
                            latencyMs = 10L,
                        )
                    },
                )
            val plan = racePlan().let { it.copy(candidates = it.candidates.take(1)) }

            suspend fun runGeneration(): PromotedRelayRuntime =
                factory
                    .create(
                        startCandidate = { startSlot(it, TestRelayRuntime()).also(slots::add) },
                        promoteCandidate = {},
                        stopDiscardedCandidates = ::stopSlots,
                    ).run(plan) {}

            try {
                assertFalse(runGeneration().result.verificationInconclusive)
                stopSlots(slots, null)
                slots.clear()
                probeSucceeds = false

                assertTrue(runGeneration().result.verificationInconclusive)
            } finally {
                stopSlots(slots, null)
            }
        }

    private suspend fun TestScope.startSlot(
        candidate: InitialRelayCandidate,
        runtime: RipDpiRelayRuntime,
    ): RelayRuntimeSlot {
        val config = sampleResolvedRelayConfig(kind = candidate.relayKind, profileId = candidate.profileId)
        val job = backgroundScope.launch { runtime.start(config) }
        runtime.awaitReady()
        return RelayRuntimeSlot(
            runtime = runtime,
            job = job,
            exitCause = CompletableDeferred(),
            stopRequested = AtomicBoolean(),
            shouldReportExit = AtomicBoolean(),
            exitReported = AtomicBoolean(),
            onUnexpectedExit = {},
            udpEnabled = false,
            localNetworkDependent = false,
            consumedConfiguration = ConsumedUpstreamConfiguration.relay(config),
            endpoint =
                LocalProxyEndpoint(
                    "127.0.0.1",
                    if (candidate.profileId ==
                        "reality"
                    ) {
                        RealityPort
                    } else {
                        HysteriaPort
                    },
                ),
        )
    }

    private suspend fun stopSlots(
        slots: Collection<RelayRuntimeSlot>,
        winner: RelayRuntimeSlot?,
    ) {
        for (slot in slots.filter { it !== winner }) {
            slot.runtime.stop()
            slot.job.join()
        }
    }

    private fun unusedRunner(): InitialRelayRaceRunner =
        InitialRelayRaceRunner(
            relayActiveProbe = RelayActiveProbe { _, _, _ -> throw AssertionError("Invalid plan started a probe") },
            relayHealthDecisionEngine = RelayHealthDecisionEngine(),
            startCandidate = { throw AssertionError("Invalid plan started a runtime") },
            promoteCandidate = { throw AssertionError("Invalid plan promoted a runtime") },
            stopDiscardedCandidates = { _, _ -> throw AssertionError("Invalid plan allocated runtime resources") },
        )

    private fun racePlan(): InitialRelayRacePlan =
        InitialRelayRacePlan(
            probePlan =
                RelayProbePlan(
                    targetUrl = "https://probe.example/generate_204",
                    targetCategory = RelayTargetCategory.ApplicationHttp,
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                ),
            candidates =
                listOf(
                    InitialRelayCandidate(InitialRelayTransportClass.TlsMimicry, "reality", RelayKindVlessReality),
                    InitialRelayCandidate(InitialRelayTransportClass.UdpObfuscation, "hysteria", RelayKindHysteria2),
                ),
            requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
            healthScope = RelayHealthScope("network-test", 1L),
        )

    private companion object {
        const val RealityPort = 19_001
        const val HysteriaPort = 19_002
    }
}
