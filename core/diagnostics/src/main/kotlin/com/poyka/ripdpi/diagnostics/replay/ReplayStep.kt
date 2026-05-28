package com.poyka.ripdpi.diagnostics.replay

/**
 * Canonical step taxonomy for probe replay.
 *
 * Each kind maps 1:1 to an OkHttp EventListener boundary in
 * [DefaultProbeReplayService]. The UI module renders these via
 * a separate ReplayStep presentation data class - the orchestration
 * model (this) and the presentation model are deliberately distinct
 * so the UI can change its rendering vocabulary without invalidating
 * the orchestrator's contract.
 */
enum class ReplayStepKind {
    DnsResolve,
    TcpOpen,
    TlsClientHello,
    TlsHandshake,
    FirstByte,
}

/** Lifecycle status of a single [ReplayStepKind] within a replay session. */
enum class ReplayStepStatus {
    Pending,
    Success,
    Failure,
}

/**
 * Causes of failure mapped from OkHttp / java.net exceptions encountered
 * during the corresponding [ReplayStepKind]. Drives the recommendation
 * lookup in [ReplayRecommendationEngine] and the human-readable
 * detail line on the failure step.
 */
enum class ReplayErrorKind {
    DnsTampered,
    ConnectionRefused,
    ConnectionReset,
    Timeout,
    TlsHandshakeFailed,
    Unknown,
}

/** Terminal outcome of a replay session. */
enum class ReplayVerdict {
    Success,
    Failure,
    Cancelled,
}
