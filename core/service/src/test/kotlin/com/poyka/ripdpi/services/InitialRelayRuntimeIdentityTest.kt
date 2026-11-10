package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialRelayRuntimeIdentityTest {
    @Test
    fun `changed candidate kind is rejected before runtime creation`() =
        runTest {
            assertRejectedIdentity(sampleResolvedRelayConfig(kind = RelayKindHysteria2, profileId = "reality"))
        }

    @Test
    fun `changed candidate profile is rejected before runtime creation`() =
        runTest {
            assertRejectedIdentity(sampleResolvedRelayConfig(kind = RelayKindVlessReality, profileId = "replacement"))
        }

    private suspend fun TestScope.assertRejectedIdentity(resolved: ResolvedRipDpiRelayConfig) {
        val relayFactory = TestRipDpiRelayFactory()
        val states = mutableListOf<String>()
        val supervisor =
            UpstreamRelaySupervisor(
                scope = backgroundScope,
                dispatcher = StandardTestDispatcher(testScheduler),
                relayFactory = relayFactory,
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                runtimeConfigResolver = TestUpstreamRelayRuntimeConfigResolver(resolved),
                initialRelayRaceRunnerFactory =
                    InitialRelayRaceRunnerFactory(
                        RelayActiveProbe { _, _, _ -> RelayActiveProbeResult(true, statusCode = 204, latencyMs = 10L) },
                    ),
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
                        InitialRelayCandidate(InitialRelayTransportClass.TlsMimicry, "reality", RelayKindVlessReality),
                    ),
                requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                healthScope = RelayHealthScope("network-test", 1L),
            )

        try {
            val failure =
                runCatching {
                    supervisor.startRace(
                        plan = plan,
                        inputs = testRelayResolutionInputs(),
                        expectedSelection = RuntimeConfigurationSelection("native"),
                        onUnexpectedExit = {},
                        onState = { states += it.state },
                    )
                }.exceptionOrNull()

            assertTrue(failure is InitialTransportSelectionException)
            assertTrue(relayFactory.runtimes.isEmpty())
            assertFalse(supervisor.hasOwnedRuntime)
            assertEquals(listOf("racing", "exhausted"), states)
        } finally {
            supervisor.stop()
        }
    }
}
