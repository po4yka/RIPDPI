package com.poyka.ripdpi.failover

import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.RelayCapabilityProbe
import com.poyka.ripdpi.services.RelayCapabilityProbeResult
import com.poyka.ripdpi.services.RelayProbeEndpoint
import com.poyka.ripdpi.services.RelayProbePlan
import javax.inject.Inject

internal data class FailoverEgressProbeResult(
    val succeeded: Boolean,
    val failure: String? = null,
)

internal fun interface FailoverEgressProbe {
    suspend fun probe(
        endpoint: FailoverProxyEndpoint,
        plan: RelayProbePlan,
    ): FailoverEgressProbeResult
}

internal fun interface FailoverRelayCapabilityProbe {
    suspend fun probe(
        endpoint: RelayProbeEndpoint,
        url: String,
        requirements: EgressRequirements,
    ): RelayCapabilityProbeResult
}

internal class DefaultFailoverRelayCapabilityProbe
    @Inject
    constructor(
        private val delegate: RelayCapabilityProbe,
    ) : FailoverRelayCapabilityProbe {
        override suspend fun probe(
            endpoint: RelayProbeEndpoint,
            url: String,
            requirements: EgressRequirements,
        ): RelayCapabilityProbeResult = delegate.probe(endpoint, url, requirements)
    }

internal data class FailoverProxyEndpoint(
    val host: String,
    val port: Int,
)

internal class CapabilityAwareFailoverEgressProbe
    @Inject
    constructor(
        private val relayProbe: FailoverRelayCapabilityProbe,
    ) : FailoverEgressProbe {
        /** cancel-safe: delegates to bounded TCP and UDP capability probes. */
        override suspend fun probe(
            endpoint: FailoverProxyEndpoint,
            plan: RelayProbePlan,
        ): FailoverEgressProbeResult {
            val targetUrl =
                plan.targetUrl
                    ?: return FailoverEgressProbeResult(succeeded = false, failure = "probe_target_missing")
            val result =
                relayProbe.probe(
                    endpoint = RelayProbeEndpoint(endpoint.host, endpoint.port),
                    url = targetUrl,
                    requirements = plan.requirements,
                )
            return FailoverEgressProbeResult(
                succeeded = result.succeeded,
                failure = result.failure,
            )
        }
    }
