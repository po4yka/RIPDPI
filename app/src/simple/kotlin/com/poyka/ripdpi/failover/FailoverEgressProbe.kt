package com.poyka.ripdpi.failover

import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.RelayCapabilityProbe
import com.poyka.ripdpi.services.RelayProbeEndpoint
import javax.inject.Inject

internal data class FailoverEgressProbeResult(
    val succeeded: Boolean,
    val failure: String? = null,
)

internal fun interface FailoverEgressProbe {
    suspend fun probe(
        endpoint: FailoverProxyEndpoint,
        requirements: EgressRequirements,
    ): FailoverEgressProbeResult
}

internal data class FailoverProxyEndpoint(
    val host: String,
    val port: Int,
)

internal class CapabilityAwareFailoverEgressProbe
    @Inject
    constructor(
        private val relayProbe: RelayCapabilityProbe,
    ) : FailoverEgressProbe {
        /** cancel-safe: delegates to bounded TCP and UDP capability probes. */
        override suspend fun probe(
            endpoint: FailoverProxyEndpoint,
            requirements: EgressRequirements,
        ): FailoverEgressProbeResult {
            val result =
                relayProbe.probe(
                    endpoint = RelayProbeEndpoint(endpoint.host, endpoint.port),
                    url = ProbeUrl,
                    requirements = requirements,
                )
            return FailoverEgressProbeResult(
                succeeded = result.succeeded,
                failure = result.failure,
            )
        }

        private companion object {
            const val ProbeUrl = "https://connectivitycheck.gstatic.com/generate_204"
        }
    }
