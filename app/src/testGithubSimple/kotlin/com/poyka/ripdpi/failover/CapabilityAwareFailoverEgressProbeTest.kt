package com.poyka.ripdpi.failover

import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.RelayCapabilityProbeResult
import com.poyka.ripdpi.services.RelayProbePlan
import com.poyka.ripdpi.services.RelayTargetCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityAwareFailoverEgressProbeTest {
    @Test
    fun `runtime probe uses target supplied by imported profile plan`() =
        runTest {
            var observedUrl: String? = null
            val plan =
                RelayProbePlan(
                    targetUrl = "https://profile.example/health",
                    targetCategory = RelayTargetCategory.ApplicationHttp,
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                )
            val probe =
                CapabilityAwareFailoverEgressProbe(
                    relayProbe =
                        FailoverRelayCapabilityProbe { _, url, _ ->
                            observedUrl = url
                            RelayCapabilityProbeResult(
                                succeeded = true,
                                statusCode = 204,
                                latencyMs = 12,
                                failure = null,
                            )
                        },
                )

            val result = probe.probe(FailoverProxyEndpoint("127.0.0.1", 1080), plan)

            assertEquals("https://profile.example/health" to true, observedUrl to result.succeeded)
        }
}
