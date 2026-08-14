package com.poyka.ripdpi.services

import java.security.MessageDigest

private const val DefaultPositiveEvidenceTtlMs = 30_000L
private const val DefaultRelayFailureDebounceMs = 20_000L

data class RelayProbePlan(
    val targetUrl: String?,
    val targetCategory: RelayTargetCategory,
    val requirements: EgressRequirements,
)

fun opaqueRelayProfileToken(profileId: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

fun EgressRequirements.toRelayCapabilityProof(): RelayCapabilityProof =
    if (udpAssociate) RelayCapabilityProof.TcpAndUdp else RelayCapabilityProof.TcpOnly

enum class RelayTargetCategory {
    Unavailable,
    ApplicationHttp,
    ApplicationDns,
    ApplicationUdp,
}

enum class RelayCapabilityProof {
    TcpOnly,
    TcpAndUdp,
}

data class RelayHealthScope(
    val persistentNetworkHash: String?,
    val sessionGeneration: Long,
)

enum class RelayHealthObservationSource {
    InitialProbe,
    ActiveProbe,
    DataPlane,
    NativeRelay,
}

enum class RelayHealthObservationOutcome {
    Succeeded,
    TargetFailure,
    RelayStageFailure,
    PermanentRejection,
    CapabilityUnavailable,
}

enum class RelayHealthInconclusiveReason {
    TargetSpecificFailure,
    MissingCapabilityTarget,
    AwaitingRelayFailureConfirmation,
    Unclassified,
}

enum class RelayFailureStage {
    TcpConnect,
    RealityTls,
    VlessAuth,
    VlessRequest,
    VlessResponse,
}

data class RelayHealthObservation(
    val attemptId: String,
    val profileToken: String,
    val relayKind: String,
    val capabilityProof: RelayCapabilityProof,
    val scope: RelayHealthScope,
    val source: RelayHealthObservationSource,
    val outcome: RelayHealthObservationOutcome,
    val targetCategory: RelayTargetCategory,
    val observedAtMs: Long,
    val dataPlaneWatermark: Long? = null,
    val failureStage: RelayFailureStage? = null,
)

sealed interface RelayHealthDecision {
    val attemptId: String
    val decidedAtMs: Long
    val positiveEvidenceWatermark: Long?

    data class Healthy(
        override val attemptId: String,
        override val decidedAtMs: Long,
        override val positiveEvidenceWatermark: Long,
    ) : RelayHealthDecision

    data class Inconclusive(
        override val attemptId: String,
        override val decidedAtMs: Long,
        override val positiveEvidenceWatermark: Long? = null,
        val reason: RelayHealthInconclusiveReason = RelayHealthInconclusiveReason.Unclassified,
    ) : RelayHealthDecision

    data class ConfirmedFailed(
        override val attemptId: String,
        override val decidedAtMs: Long,
        override val positiveEvidenceWatermark: Long? = null,
        val failureStage: RelayFailureStage?,
        val permanent: Boolean,
        val observationCount: Int,
    ) : RelayHealthDecision
}

class RelayHealthDecisionEngine(
    private val positiveEvidenceTtlMs: Long = DefaultPositiveEvidenceTtlMs,
    private val relayFailureDebounceMs: Long = DefaultRelayFailureDebounceMs,
) {
    private val positiveEvidence = mutableMapOf<RelayHealthTuple, PositiveEvidence>()
    private val transientFailures = mutableMapOf<RelayHealthTuple, TransientFailure>()

    fun observe(observation: RelayHealthObservation): RelayHealthDecision {
        val tuple = observation.tuple()
        if (observation.source in PositiveEvidenceSources &&
            observation.outcome == RelayHealthObservationOutcome.Succeeded &&
            observation.dataPlaneWatermark != null
        ) {
            transientFailures.remove(tuple)
            positiveEvidence[tuple] =
                PositiveEvidence(
                    observedAtMs = observation.observedAtMs,
                    watermark = observation.dataPlaneWatermark,
                )
        }

        val recentPositive =
            positiveEvidence[tuple]?.takeIf {
                observation.observedAtMs - it.observedAtMs in 0..positiveEvidenceTtlMs
            }
        return if (recentPositive != null) {
            transientFailures.remove(tuple)
            RelayHealthDecision.Healthy(
                attemptId = observation.attemptId,
                decidedAtMs = observation.observedAtMs,
                positiveEvidenceWatermark = recentPositive.watermark,
            )
        } else {
            decideWithoutPositiveEvidence(observation, tuple)
        }
    }

    private fun decideWithoutPositiveEvidence(
        observation: RelayHealthObservation,
        tuple: RelayHealthTuple,
    ): RelayHealthDecision =
        when {
            observation.outcome == RelayHealthObservationOutcome.PermanentRejection -> {
                RelayHealthDecision.ConfirmedFailed(
                    attemptId = observation.attemptId,
                    decidedAtMs = observation.observedAtMs,
                    failureStage = observation.failureStage,
                    permanent = true,
                    observationCount = 1,
                )
            }

            observation.outcome == RelayHealthObservationOutcome.RelayStageFailure &&
                observation.failureStage != null -> {
                decideRelayStageFailure(observation, tuple)
            }

            else -> {
                RelayHealthDecision.Inconclusive(
                    attemptId = observation.attemptId,
                    decidedAtMs = observation.observedAtMs,
                    positiveEvidenceWatermark = observation.dataPlaneWatermark,
                    reason = observation.outcome.toInconclusiveReason(),
                )
            }
        }

    private fun decideRelayStageFailure(
        observation: RelayHealthObservation,
        tuple: RelayHealthTuple,
    ): RelayHealthDecision {
        val failureStage = checkNotNull(observation.failureStage)
        val previous = transientFailures[tuple]
        val debounceElapsed =
            previous != null && observation.observedAtMs - previous.observedAtMs >= relayFailureDebounceMs
        return if (debounceElapsed) {
            RelayHealthDecision.ConfirmedFailed(
                attemptId = observation.attemptId,
                decidedAtMs = observation.observedAtMs,
                failureStage = failureStage,
                permanent = false,
                observationCount = 2,
            )
        } else {
            if (previous == null) {
                transientFailures[tuple] =
                    TransientFailure(
                        observedAtMs = observation.observedAtMs,
                        failureStage = failureStage,
                    )
            }
            RelayHealthDecision.Inconclusive(
                attemptId = observation.attemptId,
                decidedAtMs = observation.observedAtMs,
                reason = RelayHealthInconclusiveReason.AwaitingRelayFailureConfirmation,
            )
        }
    }

    private companion object {
        val PositiveEvidenceSources =
            setOf(
                RelayHealthObservationSource.InitialProbe,
                RelayHealthObservationSource.ActiveProbe,
                RelayHealthObservationSource.DataPlane,
            )
    }
}

private data class RelayHealthTuple(
    val profileToken: String,
    val relayKind: String,
    val capabilityProof: RelayCapabilityProof,
    val scope: RelayHealthScope,
)

private data class PositiveEvidence(
    val observedAtMs: Long,
    val watermark: Long,
)

private data class TransientFailure(
    val observedAtMs: Long,
    val failureStage: RelayFailureStage,
)

private fun RelayHealthObservation.tuple(): RelayHealthTuple =
    RelayHealthTuple(
        profileToken = profileToken,
        relayKind = relayKind,
        capabilityProof = capabilityProof,
        scope = scope,
    )

private fun RelayHealthObservationOutcome.toInconclusiveReason(): RelayHealthInconclusiveReason =
    when (this) {
        RelayHealthObservationOutcome.TargetFailure -> RelayHealthInconclusiveReason.TargetSpecificFailure
        RelayHealthObservationOutcome.CapabilityUnavailable -> RelayHealthInconclusiveReason.MissingCapabilityTarget
        else -> RelayHealthInconclusiveReason.Unclassified
    }
