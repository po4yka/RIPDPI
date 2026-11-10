package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgEgressReadinessVerifierTest {
    @Test
    fun `replaced AWG runtime cannot reuse previous egress success`() =
        runTest {
            val supervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    amneziaWgFactory = TestRipDpiAmneziaWgFactory(),
                    runtimeConfigResolver = RecordingAmneziaWgRuntimeConfigResolver(),
                    activeRegistry = ActiveAwgRuntimeRegistry(),
                )
            var probeSucceeds = true
            val states = mutableListOf<String>()
            val selected = mutableListOf<InitialRelayRaceResult>()
            val verifier =
                AwgEgressReadinessVerifier(
                    supervisor,
                    RelayActiveProbe { _, _, _ ->
                        RelayActiveProbeResult(
                            probeSucceeds,
                            statusCode = if (probeSucceeds) 204 else 503,
                            latencyMs = 10L,
                        )
                    },
                )
            val request =
                AwgActivationRequest(
                    profileId = "awg-test",
                    privateKey = "privkey==",
                    peerPublicKey = "peerpub==",
                    endpointHost = "vpn.example.org",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                )
            val plan =
                InitialRelayRacePlan(
                    probePlan =
                        RelayProbePlan(
                            "https://probe.example/generate_204",
                            RelayTargetCategory.ApplicationHttp,
                            EgressRequirements(tcpConnect = true, udpAssociate = false),
                        ),
                    candidates =
                        listOf(
                            InitialRelayCandidate(
                                InitialRelayTransportClass.UdpObfuscation,
                                request.profileId,
                                AmneziaWgEgressKind,
                            ),
                        ),
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                    healthScope = RelayHealthScope("network-test", 1L),
                )

            suspend fun verify() = verifier.verify(request.profileId, plan, { states += it.state }, selected::add)

            supervisor.start(request) {}
            verify()
            supervisor.stop()
            probeSucceeds = false
            supervisor.start(request) {}

            val failure = runCatching { verify() }.exceptionOrNull()

            assertTrue(failure is InitialTransportSelectionException)
            assertEquals("exhausted", states.last())
            assertEquals(1, selected.size)
            assertNull(supervisor.runtime)
        }
}
