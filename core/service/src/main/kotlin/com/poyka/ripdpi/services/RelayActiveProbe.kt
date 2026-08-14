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

internal fun RelayHealthDecisionEngine.evaluateInitialProbe(
    candidate: InitialRelayCandidate,
    plan: InitialRelayRacePlan,
    result: RelayActiveProbeResult?,
    observedAtMs: Long,
): RelayHealthDecision =
    observe(
        RelayHealthObservation(
            attemptId =
                "startup-${plan.healthScope.sessionGeneration}-" +
                    opaqueRelayProfileToken(candidate.profileId).take(StartupProfileTokenPrefixLength),
            profileToken = opaqueRelayProfileToken(candidate.profileId),
            relayKind = candidate.relayKind,
            capabilityProof = plan.probePlan.requirements.toRelayCapabilityProof(),
            scope = plan.healthScope,
            source = RelayHealthObservationSource.InitialProbe,
            outcome =
                when {
                    plan.probePlan.targetUrl == null -> RelayHealthObservationOutcome.CapabilityUnavailable
                    result?.succeeded == true -> RelayHealthObservationOutcome.Succeeded
                    else -> RelayHealthObservationOutcome.TargetFailure
                },
            targetCategory = plan.probePlan.targetCategory,
            observedAtMs = observedAtMs,
            dataPlaneWatermark = observedAtMs.takeIf { result?.succeeded == true },
        ),
    )

private const val StartupProfileTokenPrefixLength = 16

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
