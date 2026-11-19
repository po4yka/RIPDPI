package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.FailureClass

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

/** Connection-path mapping: each service failure class reports exactly one field failure stage. */
val FailureClass.fieldFailureStage: FieldFailureStage
    get() =
        when (this) {
            FailureClass.DnsInterference -> FieldFailureStage.Dns

            FailureClass.TlsInterference -> FieldFailureStage.TlsHandshake

            FailureClass.Timeout -> FieldFailureStage.TcpConnect

            FailureClass.ResetAbort -> FieldFailureStage.TcpReset

            FailureClass.Compatibility -> FieldFailureStage.ProtocolError

            FailureClass.TunnelEstablish,
            FailureClass.WarpProvisioning,
            FailureClass.WarpEndpoint,
            FailureClass.FingerprintPolicy,
            FailureClass.NetworkHandover,
            FailureClass.NativeIo,
            FailureClass.Unexpected,
            -> FieldFailureStage.Unknown
        }
