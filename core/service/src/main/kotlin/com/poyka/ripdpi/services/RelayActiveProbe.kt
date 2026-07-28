package com.poyka.ripdpi.services

internal data class RelayActiveProbeResult(
    val succeeded: Boolean,
    val statusCode: Int? = null,
    val latencyMs: Long,
    val failure: String? = null,
)

internal fun interface RelayActiveProbe {
    suspend fun probe(
        endpoint: LocalProxyEndpoint,
        url: String,
        requirements: EgressRequirements,
    ): RelayActiveProbeResult
}

internal class OkHttpRelayActiveProbe(
    private val capabilityProbe: RelayCapabilityProbe = RelayCapabilityProbe(),
) : RelayActiveProbe {
    /** cancel-safe: delegates to the capability probe's bounded child probes. */
    override suspend fun probe(
        endpoint: LocalProxyEndpoint,
        url: String,
        requirements: EgressRequirements,
    ): RelayActiveProbeResult {
        val result =
            capabilityProbe.probe(
                endpoint = RelayProbeEndpoint(endpoint.host, endpoint.port),
                url = url,
                requirements = requirements,
            )
        return RelayActiveProbeResult(
            succeeded = result.succeeded,
            statusCode = result.statusCode,
            latencyMs = result.latencyMs,
            failure = result.failure,
        )
    }
}
