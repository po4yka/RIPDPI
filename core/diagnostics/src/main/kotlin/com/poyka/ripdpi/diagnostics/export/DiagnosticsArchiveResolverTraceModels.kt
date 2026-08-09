package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.Serializable

@Serializable
internal data class DiagnosticsArchiveResolverTraceRecord(
    val traceVersion: String = "resolver_trace_v1",
    val sessionId: String? = null,
    val profileId: String? = null,
    val sequence: Int,
    val recordType: DiagnosticsArchiveResolverTraceRecordType,
    val targetAlias: String? = null,
    val recordedAt: Long? = null,
    val resolverId: String? = null,
    val protocol: String? = null,
    val endpointStatus: DiagnosticsArchiveResolverEndpointStatus? = null,
    val resolverRole: DiagnosticsArchiveResolverRole? = null,
    val attemptStatus: DiagnosticsArchiveResolverAttemptStatus? = null,
    val attemptOutcome: String? = null,
    val answerCount: Int? = null,
    val resolverLatencyMs: Long? = null,
    val resolverRttBand: String? = null,
    val dnsFailuresTotal: Long? = null,
    val fallbackActive: Boolean? = null,
    val fallbackReason: String? = null,
    val dnsStatus: String? = null,
    val systemAddressCount: Int? = null,
    val encryptedAddressCount: Int? = null,
    val systemLatencyMs: Long? = null,
    val encryptedLatencyMs: Long? = null,
    val tamperingScore: Int? = null,
    val triggerOutcome: String? = null,
    val appliedTemporarily: Boolean? = null,
    val persistable: Boolean? = null,
    val evidenceRefs: List<String>,
)

@Serializable
internal enum class DiagnosticsArchiveResolverTraceRecordType {
    DNS_OBSERVATION,
    RESOLVER_ATTEMPT,
    RESOLVER_RECOMMENDATION,
    RUNTIME_SAMPLE,
}

@Serializable
internal enum class DiagnosticsArchiveResolverRole {
    PRIMARY,
    FALLBACK,
}

@Serializable
internal enum class DiagnosticsArchiveResolverAttemptStatus {
    SUCCEEDED,
    FAILED,
}

@Serializable
internal enum class DiagnosticsArchiveResolverEndpointStatus {
    REDACTED,
    UNAVAILABLE,
}
