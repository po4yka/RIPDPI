package com.poyka.ripdpi.diagnostics

import java.io.File

/**
 * Public context projected from the internal archive selection so app-layer
 * collectors can read the bits they need without leaking internals of
 * [DiagnosticsArchiveSelection].
 */
data class DeveloperAnalyticsContext(
    val archiveCreatedAtMs: Long,
    val archiveFileName: String,
    val homeRunId: String? = null,
    val homeCompositeOutcome: DiagnosticsHomeCompositeOutcome? = null,
    val primarySessionId: String? = null,
    val primaryProfileId: String? = null,
    val pcapFiles: List<File> = emptyList(),
    val compositeSessionIds: List<String> = emptyList(),
    val stageTimings: List<DeveloperStageTimingEvidence> = emptyList(),
    val failureEnvelopes: List<DeveloperFailureEnvelopeEvidence> = emptyList(),
)

data class DeveloperStageTimingEvidence(
    val stageKey: String,
    val wallClockMs: Long? = null,
    val cpuMs: Long? = null,
    val dnsMs: Long? = null,
    val tcpHandshakeMs: Long? = null,
    val tlsHandshakeMs: Long? = null,
    val ttfbMs: Long? = null,
    val notes: List<String> = emptyList(),
)

data class DeveloperFailureEnvelopeEvidence(
    val stageKey: String,
    val facts: List<DeveloperFailureFactEvidence> = emptyList(),
)

data class DeveloperFailureFactEvidence(
    val category: DeveloperFailureCategory,
    val observationIndex: Int,
    val field: DeveloperFailureFactField,
    val value: Enum<*>,
) {
    init {
        require(observationIndex >= 0) { "Failure observation index must be non-negative" }
        require(category in field.allowedCategories) {
            "Failure category $category is invalid for ${field.name}"
        }
        require(field.accepts(value)) {
            "Failure value ${value.name} is invalid for ${field.name}"
        }
    }
}

enum class DeveloperFailureCategory {
    TCP,
    TLS,
    DNS,
    HTTP,
    QUIC,
}

private fun enumWireTokens(vararg values: Enum<*>): Map<Enum<*>, String> = values.associateWith { it.name }

private fun explicitWireTokens(vararg values: Pair<Enum<*>, String>): Map<Enum<*>, String> = values.toMap()

private val dnsFailureValues =
    enumWireTokens(
        DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
        DnsObservationStatus.SINKHOLE_SUBSTITUTION,
        DnsObservationStatus.NXDOMAIN_MISMATCH,
        DnsObservationStatus.ORACLE_UNAVAILABLE,
        DnsObservationStatus.UDP_BLOCKED,
        DnsObservationStatus.UNAVAILABLE,
    )
private val tcpFailureValues =
    explicitWireTokens(
        TcpProbeStatus.CONNECT_FAILED to TcpProbeStatus.CONNECT_FAILED.name,
        TcpProbeStatus.BLOCKED_16KB to "BLOCKED16_KB",
        TcpProbeStatus.FREEZE_AFTER_THRESHOLD to TcpProbeStatus.FREEZE_AFTER_THRESHOLD.name,
        TcpProbeStatus.ERROR to TcpProbeStatus.ERROR.name,
    )
private val transportFailureValues =
    TransportFailureKind.entries
        .filterNot { it == TransportFailureKind.NONE }
        .associateTo(linkedMapOf<Enum<*>, String>()) { failure -> failure to failure.name }
private val tlsFailureValues =
    enumWireTokens(
        TlsProbeStatus.HANDSHAKE_FAILED,
        TlsProbeStatus.VERSION_SPLIT,
        TlsProbeStatus.CERT_INVALID,
    )
private val httpFailureValues = enumWireTokens(HttpProbeStatus.BLOCKPAGE, HttpProbeStatus.UNREACHABLE)
private val quicFailureValues = enumWireTokens(QuicProbeStatus.EMPTY, QuicProbeStatus.ERROR)
private val endpointFailureValues = enumWireTokens(EndpointProbeStatus.FAILED, EndpointProbeStatus.BLOCKED)
private val strategyFailureValues = enumWireTokens(StrategyProbeStatus.PARTIAL, StrategyProbeStatus.FAILED)
private val throughputFailureValues = enumWireTokens(ThroughputProbeStatus.HTTP_UNREACHABLE)

enum class DeveloperFailureFactField(
    val reportPath: String,
    val allowedCategories: Set<DeveloperFailureCategory>,
    private val wireTokens: Map<Enum<*>, String>,
) {
    DNS_STATUS("dns/status", setOf(DeveloperFailureCategory.DNS), dnsFailureValues),
    TCP_STATUS("tcp/status", setOf(DeveloperFailureCategory.TCP), tcpFailureValues),
    DOMAIN_TRANSPORT_FAILURE("domain/transportFailure", setOf(DeveloperFailureCategory.TCP), transportFailureValues),
    DOMAIN_TLS13_STATUS("domain/tls13Status", setOf(DeveloperFailureCategory.TLS), tlsFailureValues),
    DOMAIN_TLS12_STATUS("domain/tls12Status", setOf(DeveloperFailureCategory.TLS), tlsFailureValues),
    DOMAIN_TLS_ECH_STATUS("domain/tlsEchStatus", setOf(DeveloperFailureCategory.TLS), tlsFailureValues),
    DOMAIN_HTTP_STATUS("domain/httpStatus", setOf(DeveloperFailureCategory.HTTP), httpFailureValues),
    QUIC_STATUS("quic/status", setOf(DeveloperFailureCategory.QUIC), quicFailureValues),
    QUIC_TRANSPORT_FAILURE("quic/transportFailure", setOf(DeveloperFailureCategory.QUIC), transportFailureValues),
    SERVICE_BOOTSTRAP_STATUS("service/bootstrapStatus", setOf(DeveloperFailureCategory.HTTP), httpFailureValues),
    SERVICE_MEDIA_STATUS("service/mediaStatus", setOf(DeveloperFailureCategory.HTTP), httpFailureValues),
    SERVICE_ENDPOINT_STATUS("service/endpointStatus", setOf(DeveloperFailureCategory.TCP), endpointFailureValues),
    SERVICE_ENDPOINT_FAILURE("service/endpointFailure", setOf(DeveloperFailureCategory.TCP), transportFailureValues),
    SERVICE_QUIC_STATUS("service/quicStatus", setOf(DeveloperFailureCategory.QUIC), quicFailureValues),
    SERVICE_QUIC_FAILURE("service/quicFailure", setOf(DeveloperFailureCategory.QUIC), transportFailureValues),
    CIRCUMVENTION_BOOTSTRAP_STATUS(
        "circumvention/bootstrapStatus",
        setOf(DeveloperFailureCategory.HTTP),
        httpFailureValues,
    ),
    CIRCUMVENTION_HANDSHAKE_STATUS(
        "circumvention/handshakeStatus",
        setOf(DeveloperFailureCategory.TCP),
        endpointFailureValues,
    ),
    CIRCUMVENTION_HANDSHAKE_FAILURE(
        "circumvention/handshakeFailure",
        setOf(DeveloperFailureCategory.TCP),
        transportFailureValues,
    ),
    STRATEGY_STATUS(
        "strategy/status",
        setOf(DeveloperFailureCategory.TCP, DeveloperFailureCategory.HTTP, DeveloperFailureCategory.QUIC),
        strategyFailureValues,
    ),
    STRATEGY_TRANSPORT_FAILURE(
        "strategy/transportFailure",
        setOf(DeveloperFailureCategory.TCP, DeveloperFailureCategory.HTTP, DeveloperFailureCategory.QUIC),
        transportFailureValues,
    ),
    STRATEGY_TLS_ECH_STATUS("strategy/tlsEchStatus", setOf(DeveloperFailureCategory.TLS), tlsFailureValues),
    THROUGHPUT_STATUS("throughput/status", setOf(DeveloperFailureCategory.HTTP), throughputFailureValues),
    ;

    val allowedWireValues: Set<String> = wireTokens.values.toSet()

    fun accepts(value: Enum<*>): Boolean = wireTokens.containsKey(value)

    fun wireToken(value: Enum<*>): String =
        requireNotNull(wireTokens[value]) {
            "Failure value ${value.name} is invalid for $name"
        }
}

/**
 * Collects developer-facing analytics that are serialized into the share archive as
 * `developer-analytics.json`. Implementations live in the app layer because most
 * signals require Android APIs, proc filesystem access, or BuildConfig constants.
 */
interface DeveloperAnalyticsSource {
    suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload
}

object NoopDeveloperAnalyticsSource : DeveloperAnalyticsSource {
    override suspend fun collect(context: DeveloperAnalyticsContext): DeveloperAnalyticsPayload =
        DeveloperAnalyticsPayload(
            notes = listOf("Developer analytics source not bound — noop payload."),
        )
}
