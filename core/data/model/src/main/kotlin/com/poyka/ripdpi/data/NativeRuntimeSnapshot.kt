package com.poyka.ripdpi.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val NativeRuntimeTelemetrySchemaVersion: Int = 3

@Serializable
enum class NativeConfirmGoodDpiEvidenceSource {
    ACTIVE,
    PASSIVE,
    MIXED,
}

@Serializable
data class NativeConfirmGoodDpiEvidence(
    val source: NativeConfirmGoodDpiEvidenceSource,
    val stalledFlowCount: Int,
    val distinctTargetCount: Int,
    val catalogProfileValidated: Boolean,
    val realityHandshakeConfirmed: Boolean,
    val applicationResponseBytes: Long,
    val quicControlSucceeded: Boolean = false,
)

@Serializable
data class LatencyPercentiles(
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val min: Long,
    val max: Long,
    val count: Long,
)

@Serializable
data class LatencyDistributions(
    val dnsResolution: LatencyPercentiles? = null,
    val tcpConnect: LatencyPercentiles? = null,
    val tlsHandshake: LatencyPercentiles? = null,
)

/**
 * Per-hop live telemetry for one intermediate hop of an N-hop (3..4) chain relay
 * (a hop strictly between entry and exit). Mirrors the native relay-core
 * `ChainIntermediateHopTelemetry`; [hopIndex] is the hop's ordered position in
 * the chain (1..exit). Entry and exit are carried by the dedicated
 * `chainEntry*` / `chainExit*` snapshot fields instead.
 */
@Serializable
data class ChainIntermediateHopSnapshot(
    val hopIndex: Int,
    val state: String,
    val latencyMs: Long? = null,
)

/**
 * Privacy-safe aggregate health for VLESS Reality XUDP associations.
 *
 * This intentionally carries no destination, endpoint, profile identifier,
 * payload metadata, UUID, or XUDP GlobalID.
 */
@Serializable
data class XudpTelemetrySnapshot(
    val activeAssociations: Long = 0,
    val openedAssociations: Long = 0,
    val closedAssociations: Long = 0,
    val uplinkPackets: Long = 0,
    val uplinkBytes: Long = 0,
    val downlinkPackets: Long = 0,
    val downlinkBytes: Long = 0,
    val lastSuccessfulDownlinkAt: Long? = null,
    val writeTimeouts: Long = 0,
    val readTimeouts: Long = 0,
    val carrierReconnects: Long = 0,
    val consecutiveUdpFailures: Long = 0,
    val queueHighWaterMark: Long = 0,
    val lastTerminationReason: String? = null,
)

@Serializable
data class NativeRuntimeEvent(
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
    val kind: String? = null,
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val subsystem: String? = null,
    val attemptId: Long? = null,
    val attemptSequence: Long? = null,
    val stage: String? = null,
    val outcome: String? = null,
    val durationMs: Long? = null,
    val failureStage: String? = null,
    val failureClass: String? = null,
    val ioErrorKind: String? = null,
    val osErrorCode: Int? = null,
    val peerClosePhase: String? = null,
    val carrierDisposition: String? = null,
)

/**
 * Direct-path learning event identifier.
 *
 * Decoded from the free-form `event` string the native runtime emits on
 * [DirectPathLearningSignal]. Modeled as a wire-preserving value class rather
 * than an enum so a future runtime build emitting a new event name decodes
 * cleanly instead of failing the entire enclosing [NativeRuntimeSnapshot].
 * A known event serializes to exactly its wire string, byte-identical to the
 * former enum encoding. Known events are exposed as companion constants;
 * [isKnown] reports whether this value is one of them.
 */
@JvmInline
@Serializable
value class DirectPathLearningEvent(
    val wire: String,
) {
    /** True when [wire] is one of the events this build recognizes. */
    val isKnown: Boolean
        get() = wire in KNOWN_WIRE_VALUES

    companion object {
        val QUIC_SUCCESS: DirectPathLearningEvent = DirectPathLearningEvent("QUIC_SUCCESS")
        val QUIC_BLOCKED_TCP_OK: DirectPathLearningEvent = DirectPathLearningEvent("QUIC_BLOCKED_TCP_OK")
        val TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK: DirectPathLearningEvent =
            DirectPathLearningEvent("TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK")
        val ALL_IPS_FAILED: DirectPathLearningEvent = DirectPathLearningEvent("ALL_IPS_FAILED")
        val NO_TCP_FALLBACK_DETECTED: DirectPathLearningEvent = DirectPathLearningEvent("NO_TCP_FALLBACK_DETECTED")
        val OWNED_STACK_REQUIRED: DirectPathLearningEvent = DirectPathLearningEvent("OWNED_STACK_REQUIRED")

        private val KNOWN_WIRE_VALUES: Set<String> =
            setOf(
                QUIC_SUCCESS.wire,
                QUIC_BLOCKED_TCP_OK.wire,
                TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK.wire,
                ALL_IPS_FAILED.wire,
                NO_TCP_FALLBACK_DETECTED.wire,
                OWNED_STACK_REQUIRED.wire,
            )
    }
}

@Serializable
data class DirectPathLearningSignal(
    val authority: String,
    val ipSetDigest: String,
    val event: DirectPathLearningEvent,
    val strategyFamily: String? = null,
    val capturedAt: Long = 0,
)

@Serializable
data class NativeRuntimeSnapshot(
    val source: String,
    /**
     * Required runtime-telemetry payload schema version. Native boundary
     * decoders reject missing and non-current versions.
     */
    @Required
    val schemaVersion: Int = NativeRuntimeTelemetrySchemaVersion,
    val state: String = "idle",
    val health: String = "idle",
    val activeSessions: Long = 0,
    val totalSessions: Long = 0,
    val totalErrors: Long = 0,
    val networkErrors: Long = 0,
    val routeChanges: Long = 0,
    val retryPacedCount: Long = 0,
    val lastRetryBackoffMs: Long? = null,
    val lastRetryReason: String? = null,
    val candidateDiversificationCount: Long = 0,
    val lastRouteGroup: Int? = null,
    val listenerAddress: String? = null,
    val upstreamAddress: String? = null,
    val upstreamRttMs: Long? = null,
    val profileId: String? = null,
    val protocolKind: String? = null,
    val tcpCapable: Boolean? = null,
    val udpCapable: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val xudpTelemetry: XudpTelemetrySnapshot? = null,
    val fallbackMode: String? = null,
    val lastHandshakeError: String? = null,
    val chainEntryState: String? = null,
    val chainEntryLatencyMs: Long? = null,
    val chainExitState: String? = null,
    val chainExitLatencyMs: Long? = null,
    val chainIntermediateHops: List<ChainIntermediateHopSnapshot> = emptyList(),
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverEndpoint: String? = null,
    val resolverLatencyMs: Long? = null,
    val resolverLatencyAvgMs: Long? = null,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String? = null,
    val relayDnsRoute: String? = null,
    val relayDnsFailClosed: Boolean = false,
    val dhtTriggerObservations: Long? = null,
    val lastDhtTriggerEndpoint: String? = null,
    val lastDhtTriggerAt: Long? = null,
    val networkHandoverClass: String? = null,
    val strategyPackId: String? = null,
    val strategyPackVersion: String? = null,
    val tlsProfileId: String? = null,
    val tlsProfileCatalogVersion: String? = null,
    val morphPolicyId: String? = null,
    val morphHintFamily: String? = null,
    val morphRollbackReason: String? = null,
    val quicMigrationStatus: String? = null,
    val quicMigrationReason: String? = null,
    val ptRuntimeKind: String? = null,
    val ptRuntimeState: String? = null,
    val ptRuntimeVersion: String? = null,
    val confirmGoodDpiEligible: Boolean = false,
    val confirmGoodDpiEvidence: NativeConfirmGoodDpiEvidence? = null,
    val lastTarget: String? = null,
    val lastHost: String? = null,
    val lastError: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
    val adaptiveOverrideActive: Boolean = false,
    val adaptiveTriggerMask: Long? = null,
    val adaptiveLastTrigger: String? = null,
    val adaptiveOverrideReason: String? = null,
    val dnsQueriesTotal: Long = 0,
    val dnsCacheHits: Long = 0,
    val dnsCacheMisses: Long = 0,
    val dnsFailuresTotal: Long = 0,
    val splitDnsProxyDecisions: Long = 0,
    val splitDnsDirectFallbackDecisions: Long = 0,
    val splitDnsBlockDecisions: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directDnsSuccesses: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directDnsStaleResponses: Long = 0,
    val lastSplitDnsCoverageReason: String? = null,
    val lastDnsHost: String? = null,
    val lastDnsError: String? = null,
    val autolearnEnabled: Boolean = false,
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastBlockSignal: String? = null,
    val lastBlockProvider: String? = null,
    val lastAutolearnHost: String? = null,
    val lastAutolearnGroup: Int? = null,
    val lastAutolearnAction: String? = null,
    val slotExhaustions: Long = 0,
    /**
     * Cumulative count of successful WS-tunnel handshakes established with the
     * fake-SNI cover active (TLS certificate verification disabled). A non-zero
     * value at deploy time signals that insecure-SNI connections are actually
     * occurring. Defaults to 0 when the current producer omits the counter.
     */
    val wsTunnelFakeSniActive: Long = 0,
    /**
     * Cumulative count of successful WG-over-WebSocket carrier handshakes (a
     * protected carrier socket opened, TLS/WS upgraded, and the first real
     * WireGuard datagram framed) reported by the AmneziaWG native runtime. Stays
     * 0 on the plain-UDP path. Additive defaulted field — no schema bump.
     */
    val wsCarrierHandshakes: Long = 0,
    /**
     * Cumulative count of WG-over-WebSocket carrier handshakes that failed before
     * the first datagram could be framed (protect rejection, connect, TLS, or
     * WS-upgrade failure). Additive defaulted field.
     */
    val wsCarrierHandshakeFailures: Long = 0,
    val tunnelStats: TunnelStats = TunnelStats(),
    val directPathLearningSignals: List<DirectPathLearningSignal> = emptyList(),
    val nativeEvents: List<NativeRuntimeEvent> = emptyList(),
    val nativeEventsDropped: Long = 0,
    val latencyDistributions: LatencyDistributions? = null,
    val connectionQuality: ConnectionQualitySnapshot? = null,
    val capturedAt: Long = 0,
) {
    companion object {
        fun idle(source: String): NativeRuntimeSnapshot = NativeRuntimeSnapshot(source = source)
    }
}

@Serializable
data class TunnelStats(
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val rxBytes: Long = 0,
    /** Process-local ICMPv4/ICMPv6 ingress counter, supplied by a dedicated JNI getter. */
    @Transient
    val icmpIngressPackets: Long = 0,
) {
    companion object {
        private const val RxBytesIndex = 3

        fun fromNative(stats: LongArray): TunnelStats =
            TunnelStats(
                txPackets = stats.getOrElse(0) { 0L },
                txBytes = stats.getOrElse(1) { 0L },
                rxPackets = stats.getOrElse(2) { 0L },
                rxBytes = stats.getOrElse(RxBytesIndex) { 0L },
            )
    }
}
