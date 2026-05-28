package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire schema for `replay-results.json` inside the diagnostics archive
 * zip.
 *
 * Deliberately decoupled from the in-memory `ReplayProbeResult` /
 * `ReplayStepEvent` orchestration types so the orchestrator can evolve
 * without dragging the on-disk format with it. The mapping is performed
 * by [ReplayArchiveEntryBuilder]; the redaction is performed by
 * [ReplayArchiveRedactor] before serialization.
 *
 * Schema version is pinned to [DiagnosticsArchiveFormat.schemaVersion]
 * so that any archive-level schema bump rolls forward replay records
 * too.
 */
@Serializable
internal data class ReplayArchivePayload(
    val schemaVersion: Int,
    val generatedAt: Long,
    val replays: List<ReplayArchiveEntry>,
)

@Serializable
internal data class ReplayArchiveEntry(
    val request: ReplayArchiveRequest,
    val verdict: ReplayArchiveVerdict,
    val terminalStep: ReplayArchiveStepKind? = null,
    val recommendationKey: String,
    val events: List<ReplayArchiveStepEvent>,
)

@Serializable
internal data class ReplayArchiveRequest(
    val domain: String,
    val strategyId: String,
    val timeoutMs: Long,
)

@Serializable
internal enum class ReplayArchiveVerdict {
    @SerialName("success")
    Success,

    @SerialName("failure")
    Failure,

    @SerialName("cancelled")
    Cancelled,
}

@Serializable
internal enum class ReplayArchiveStepKind {
    @SerialName("dns_resolve")
    DnsResolve,

    @SerialName("tcp_open")
    TcpOpen,

    @SerialName("tls_client_hello")
    TlsClientHello,

    @SerialName("tls_handshake")
    TlsHandshake,

    @SerialName("first_byte")
    FirstByte,
}

@Serializable
internal enum class ReplayArchiveErrorKind {
    @SerialName("dns_tampered")
    DnsTampered,

    @SerialName("connection_refused")
    ConnectionRefused,

    @SerialName("connection_reset")
    ConnectionReset,

    @SerialName("timeout")
    Timeout,

    @SerialName("tls_handshake_failed")
    TlsHandshakeFailed,

    @SerialName("unknown")
    Unknown,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
internal sealed class ReplayArchiveStepEvent {
    @Serializable
    @SerialName("step_started")
    data class StepStarted(
        val step: ReplayArchiveStepKind,
        val timestampMs: Long,
    ) : ReplayArchiveStepEvent()

    @Serializable
    @SerialName("step_completed")
    data class StepCompleted(
        val step: ReplayArchiveStepKind,
        val durationMs: Long,
        val detail: String,
    ) : ReplayArchiveStepEvent()

    @Serializable
    @SerialName("step_failed")
    data class StepFailed(
        val step: ReplayArchiveStepKind,
        val errorKind: ReplayArchiveErrorKind,
        val detail: String,
    ) : ReplayArchiveStepEvent()

    @Serializable
    @SerialName("finished")
    data class Finished(
        val verdict: ReplayArchiveVerdict,
        val terminalStep: ReplayArchiveStepKind? = null,
        val recommendationKey: String,
    ) : ReplayArchiveStepEvent()
}
