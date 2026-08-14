package com.poyka.ripdpi.failover

import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.RelayCapabilityProof
import com.poyka.ripdpi.services.RelayHealthDecision
import com.poyka.ripdpi.services.RelayHealthObservation
import com.poyka.ripdpi.services.RelayHealthObservationOutcome
import com.poyka.ripdpi.services.RelayHealthObservationSource
import com.poyka.ripdpi.services.RelayHealthScope
import com.poyka.ripdpi.services.RelayProbePlan
import com.poyka.ripdpi.services.RelayTargetCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleRelayHealthProbeCoordinatorTest {
    @Test
    fun `concurrent callers share one active probe for the relay tuple`() =
        runTest {
            var probeCalls = 0
            val releaseProbe = CompletableDeferred<Unit>()
            val request =
                RelayHealthProbeRequest(
                    attemptId = "attempt-one",
                    profileToken = "fixture-profile-hash",
                    relayKind = "vless_reality",
                    capabilityProof = RelayCapabilityProof.TcpOnly,
                    scope = RelayHealthScope(persistentNetworkHash = "network-hash", sessionGeneration = 3),
                    endpoint = FailoverProxyEndpoint("127.0.0.1", 1080),
                    plan =
                        RelayProbePlan(
                            targetUrl = "https://profile.example/health",
                            targetCategory = RelayTargetCategory.ApplicationHttp,
                            requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                        ),
                )
            val coordinator =
                LifecycleRelayHealthProbeCoordinator(
                    lifecycleScope = backgroundScope,
                    clock =
                        object : FailoverClock {
                            override fun nowMillis(): Long = 1_000L
                        },
                    probe =
                        RelayHealthObservationProbe { admitted ->
                            probeCalls++
                            releaseProbe.await()
                            RelayHealthObservation(
                                attemptId = admitted.attemptId,
                                profileToken = admitted.profileToken,
                                relayKind = admitted.relayKind,
                                capabilityProof = admitted.capabilityProof,
                                scope = admitted.scope,
                                source = RelayHealthObservationSource.ActiveProbe,
                                outcome = RelayHealthObservationOutcome.TargetFailure,
                                targetCategory = admitted.plan.targetCategory,
                                observedAtMs = 1_000L,
                            )
                        },
                )

            val first = async { coordinator.confirm(request) }
            val second = async { coordinator.confirm(request.copy(attemptId = "attempt-two")) }
            runCurrent()
            releaseProbe.complete(Unit)

            val expected =
                RelayHealthDecision.Inconclusive(
                    attemptId = "attempt-one",
                    decidedAtMs = 1_000L,
                    reason = com.poyka.ripdpi.services.RelayHealthInconclusiveReason.TargetSpecificFailure,
                )
            assertEquals(Triple(1, expected, expected), Triple(probeCalls, first.await(), second.await()))
        }
}
