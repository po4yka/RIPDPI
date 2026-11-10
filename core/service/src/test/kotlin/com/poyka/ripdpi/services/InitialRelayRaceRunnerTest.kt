package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
