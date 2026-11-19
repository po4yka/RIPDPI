package com.poyka.ripdpi.data.diagnostics

/** Version of the vendored `client-field-report` contract (its `x-contract-version`). */
const val ClientFieldReportContractVersion = 1

/**
 * The `failure_stage` of a `client-field-report` v1 bucket. Wire values equal the vendored schema enum
 * and the Rust `ripdpi_diagnostics_contracts::FieldFailureStage` serialization.
 */
enum class FieldFailureStage(
    val wireValue: String,
) {
    Dns("dns"),
    TcpConnect("tcp_connect"),
    TcpReset("tcp_reset"),
    TlsHandshake("tls_handshake"),
    RealityAuth("reality_auth"),
    QuicHandshake("quic_handshake"),
    ProxyAuth("proxy_auth"),
    ProtocolError("protocol_error"),
    IdleTimeout("idle_timeout"),
    Throttled("throttled"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String): FieldFailureStage? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Connection-path mapping from the typed native failure: [failureClass] is a Rust `FailureClass::as_str` code
 * (`NativeRuntimeSnapshot.lastFailureClass`) and [failureStage] a Rust `FailureStage::as_str` code. It equals
 * the Rust `field_failure_stage`; both are tested against `contract-fixtures/native_failure_stage_mapping.tsv`.
 * An unknown code, and a stage-dependent class without a known stage, report [FieldFailureStage.Unknown].
 */
fun nativeFieldFailureStage(
    failureClass: String,
    failureStage: String?,
): FieldFailureStage =
    if (failureClass == SilentDropCode) {
        failureStage?.let(SilentDropStageByNativeStage::get) ?: FieldFailureStage.Unknown
    } else {
        StageByNativeClass[failureClass] ?: FieldFailureStage.Unknown
    }

private const val SilentDropCode = "silent_drop"

private val StageByNativeClass =
    mapOf(
        "unknown" to FieldFailureStage.Unknown,
        "dns_tampering" to FieldFailureStage.Dns,
        "dns_resolution_failure" to FieldFailureStage.Dns,
        "tcp_reset" to FieldFailureStage.TcpReset,
        "tls_alert" to FieldFailureStage.TlsHandshake,
        "http_blockpage" to FieldFailureStage.ProtocolError,
        "quic_breakage" to FieldFailureStage.QuicHandshake,
        "redirect" to FieldFailureStage.ProtocolError,
        "tls_handshake_failure" to FieldFailureStage.TlsHandshake,
        "connect_failure" to FieldFailureStage.TcpConnect,
        "strategy_execution_failure" to FieldFailureStage.Unknown,
        "connection_freeze" to FieldFailureStage.IdleTimeout,
        "capability_skipped" to FieldFailureStage.Unknown,
        "tuic_version_unsupported" to FieldFailureStage.ProtocolError,
        "shadowtls_version_mismatch" to FieldFailureStage.ProtocolError,
        "ip_block_suspect" to FieldFailureStage.TcpConnect,
        "masque_h3_tcp_unsupported" to FieldFailureStage.ProtocolError,
        "confirm_good_dpi_suspected" to FieldFailureStage.IdleTimeout,
    )

/** A silent drop is reported at the stage where the traffic stopped. */
private val SilentDropStageByNativeStage =
    mapOf(
        "dns" to FieldFailureStage.Dns,
        "connect" to FieldFailureStage.TcpConnect,
        "first_write" to FieldFailureStage.IdleTimeout,
        "first_response" to FieldFailureStage.IdleTimeout,
        "tls_handshake" to FieldFailureStage.TlsHandshake,
        "http_response" to FieldFailureStage.IdleTimeout,
        "quic_probe" to FieldFailureStage.QuicHandshake,
        "diagnostic" to FieldFailureStage.Unknown,
        "relay" to FieldFailureStage.IdleTimeout,
        "post_handshake" to FieldFailureStage.IdleTimeout,
    )
