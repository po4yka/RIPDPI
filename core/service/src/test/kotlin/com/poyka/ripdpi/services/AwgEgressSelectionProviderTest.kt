package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AwgEgressSelectionProviderTest {
    @Test
    fun `provider selects active simple fallback before saved standalone selection`() =
        runTest {
            val standalone = sampleRequest("standalone")
            val simpleFailover = sampleRequest("simple")
            val provider =
                DefaultAwgEgressSelectionProvider(
                    sources =
                        setOf(
                            StaticAwgEgressSelectionSource(selectionPriority = 10, request = standalone),
                            StaticAwgEgressSelectionSource(selectionPriority = 0, request = simpleFailover),
                        ),
                )

            assertEquals(simpleFailover, provider.selectedAwgEgress())
        }

    @Test
    fun `provider falls through empty higher priority source`() =
        runTest {
            val simpleFailover = sampleRequest("simple")
            val provider =
                DefaultAwgEgressSelectionProvider(
                    sources =
                        setOf(
                            StaticAwgEgressSelectionSource(selectionPriority = 0, request = null),
                            StaticAwgEgressSelectionSource(selectionPriority = 10, request = simpleFailover),
                        ),
                )

            assertEquals(simpleFailover, provider.selectedAwgEgress())
        }

    private class StaticAwgEgressSelectionSource(
        override val selectionPriority: Int,
        private val request: AwgActivationRequest?,
    ) : AwgEgressSelectionSource {
        override suspend fun selectedAwgEgress(): AwgActivationRequest? = request
    }

    private fun sampleRequest(profileId: String): AwgActivationRequest =
        AwgActivationRequest(
            profileId = profileId,
            privateKey = "private",
            peerPublicKey = "peer",
            endpointHost = "198.51.100.10",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
        )
}
