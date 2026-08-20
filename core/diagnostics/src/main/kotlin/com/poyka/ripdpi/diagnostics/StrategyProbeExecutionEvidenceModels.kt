package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class StrategyProbeDomainOutcome(
    val domain: String,
    val succeeded: Boolean,
    val isControl: Boolean = false,
)

@Serializable
data class StrategyProbeCandidateSummary(
    val id: String,
    val label: String,
    val family: String,
    val emitterTier: StrategyEmitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
    val exactEmitterRequiresRoot: Boolean = false,
    val emitterDowngraded: Boolean = false,
    val quicLayoutFamily: String? = null,
    val outcome: String,
    val rationale: String,
    val succeededTargets: Int,
    val totalTargets: Int,
    val weightedSuccessScore: Int,
    val totalWeight: Int,
    val qualityScore: Int,
    val proxyConfigJson: String? = null,
    val notes: List<String> = emptyList(),
    val averageLatencyMs: Long? = null,
    val skipped: Boolean = false,
    val domainOutcomes: List<StrategyProbeDomainOutcome> = emptyList(),
    val observationRole: StrategyProbeObservationRole = StrategyProbeObservationRole.EPHEMERAL_CANDIDATE_RAW_PATH,
    val activeSnapshotFaithful: Boolean = false,
    val desyncExecutionRequired: Boolean = true,
    val runtimeTerminalStatus: StrategyProbeRuntimeTerminalStatus = StrategyProbeRuntimeTerminalStatus.UNAVAILABLE,
    val executionEvidenceComplete: Boolean = false,
    val executionAttempts: List<StrategyProbeAttemptExecutionEvidence> = emptyList(),
    val routeFeatures: List<StrategyProbeRouteFeature> = emptyList(),
)

@Serializable
enum class StrategyProbeRouteFeature {
    UPSTREAM_RELAY,
    WARP,
    WEB_SOCKET_TUNNEL,
    DESTINATION_ROUTING,
    TCP_ROTATION,
    ADAPTIVE_FALLBACK,
    GROUP_ACTIVATION_FILTER,
}

@Serializable
enum class StrategyProbeObservationRole {
    EPHEMERAL_CANDIDATE_RAW_PATH,
    ACTIVE_SERVICE_IN_PATH,
    UNAVAILABLE,
}

@Serializable
data class StrategyActivePathObservation(
    val role: StrategyProbeObservationRole,
    val responseStage: StrategyProbeResponseStage,
    val activePathAuthority: StrategyActivePathAuthority = StrategyActivePathAuthority.UNVERIFIED,
    val attemptedTargets: Int,
    val routeReachedTargets: Int = 0,
    val responseObservedTargets: Int,
    val successfulTargets: Int = 0,
)

internal fun StrategyActivePathObservation.hasCoherentResponseCounts(): Boolean =
    attemptedTargets > 0 &&
        responseObservedTargets in 0..attemptedTargets &&
        routeReachedTargets in responseObservedTargets..attemptedTargets &&
        successfulTargets in 0..responseObservedTargets &&
        when (responseStage) {
            StrategyProbeResponseStage.RESPONSE_OBSERVED -> {
                responseObservedTargets > 0
            }

            StrategyProbeResponseStage.RESPONSE_NOT_OBSERVED -> {
                responseObservedTargets == 0 && routeReachedTargets == attemptedTargets
            }

            StrategyProbeResponseStage.UNAVAILABLE -> {
                false
            }
        }

@Serializable
enum class StrategyActivePathAuthority {
    UNVERIFIED,
    OWNED_ROUTE_LEASE_AT_SCAN,
}

@Serializable
enum class StrategyProbeRuntimeTerminalStatus {
    UNAVAILABLE,
    CLEAN_SHUTDOWN,
    FORCED_ABORT,
    RUNTIME_FAILED,
    RUNTIME_PANICKED,
}

@Serializable
data class StrategyProbeAttemptExecutionEvidence(
    val probeSucceeded: Boolean,
    val complete: Boolean,
    val responseStage: StrategyProbeResponseStage = StrategyProbeResponseStage.UNAVAILABLE,
    val receipts: List<StrategyDesyncExecutionReceipt> = emptyList(),
)

@Serializable
enum class StrategyProbeResponseStage {
    UNAVAILABLE,
    RESPONSE_OBSERVED,
    RESPONSE_NOT_OBSERVED,
}

@Serializable
data class StrategyDesyncExecutionReceipt(
    val connectionOrdinal: Int = 0,
    val transport: StrategyExecutionTransport = StrategyExecutionTransport.TCP,
    val disposition: StrategyExecutionDisposition,
    val configuredFamily: StrategyExecutionFamily? = null,
    val effectiveFamily: StrategyExecutionFamily? = null,
    val markerBase: StrategyOffsetMarkerBase? = null,
    val markerDelta: Int? = null,
    val resolvedOffset: Int? = null,
    val plannedSteps: Int,
    val attemptedActions: Int,
    val completedActions: Int,
    val realWritesCommitted: Int,
    val completedAwaits: Int,
    val payloadBytesCommitted: Int,
    val tlsRecordPreludeApplied: Boolean = false,
    val tlsPreludeConfiguredCount: Int = 0,
    val tlsPreludeAppliedCount: Int = 0,
    val tlsPreludeKind: StrategyTlsPreludeKind? = null,
    val tlsPreludeMarkerBase: StrategyOffsetMarkerBase? = null,
    val tlsPreludeMarkerDelta: Int? = null,
    val tlsPreludeResolvedOffset: Int? = null,
    val udpIpv6ExtensionProfile: StrategyUdpIpv6ExtensionProfile? = null,
    val fallbackReason: StrategyFallbackReason? = null,
    val terminalReason: StrategyTerminalReason? = null,
)

@Serializable
enum class StrategyTlsPreludeKind {
    TLS_REC,
    TLS_RAND_REC,
}

@Serializable
enum class StrategyUdpIpv6ExtensionProfile {
    HOP_BY_HOP,
    HOP_BY_HOP2,
    DESTINATION_OPTIONS,
    HOP_BY_HOP_DESTINATION_OPTIONS,
}

@Serializable
enum class StrategyExecutionTransport {
    TCP,
    UDP,
}

@Serializable
enum class StrategyExecutionDisposition {
    APPLIED,
    ACTIVATION_SKIPPED,
    PLAN_FAILED_PLAIN_FALLBACK,
    EXECUTION_FAILED,
}

@Serializable
enum class StrategyExecutionFamily {
    SPLIT,
    TLS_RECORD_SPLIT,
    SEQ_OVERLAP,
    TLS_RECORD_SEQ_OVERLAP,
    MULTI_DISORDER,
    TLS_RECORD_MULTI_DISORDER,
    DISORDER,
    OUT_OF_BAND,
    DISORDERED_OUT_OF_BAND,
    FAKE,
    FAKE_SPLIT,
    FAKE_DISORDER,
    HOST_FAKE,
    IP_FRAGMENT2,
    FAKE_RST,
    TLS_RECORD,
    QUIC_BURST,
    QUIC_SNI_SPLIT,
    QUIC_FAKE_VERSION,
    QUIC_CRYPTO_SPLIT,
    QUIC_PADDING_LADDER,
    QUIC_VERSION_NEGOTIATION_DECOY,
    QUIC_MULTI_INITIAL_REALISTIC,
    QUIC_DUMMY_PREPEND,
    QUIC_IP_FRAGMENT2,
    UNKNOWN,
}

@Serializable
enum class StrategyOffsetMarkerBase {
    ABSOLUTE,
    PAYLOAD_END,
    PAYLOAD_MID,
    PAYLOAD_RAND,
    HOST,
    END_HOST,
    HOST_MID,
    HOST_RAND,
    SLD,
    MID_SLD,
    END_SLD,
    METHOD,
    EXT_LEN,
    ECH_EXT,
    SNI_EXT,
    ADAPTIVE,
}

@Serializable
enum class StrategyFallbackReason {
    ANDROID_TTL_UNAVAILABLE,
    STRATEGY_FAMILY_FALLBACK,
}

@Serializable
enum class StrategyTerminalReason {
    TRANSPORT,
    STRATEGY_EXECUTION,
    PLANNING,
}
