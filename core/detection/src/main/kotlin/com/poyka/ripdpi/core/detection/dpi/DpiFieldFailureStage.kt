package com.poyka.ripdpi.core.detection.dpi

import com.poyka.ripdpi.data.diagnostics.FieldFailureStage

val DpiProbeError.fieldFailureStage: FieldFailureStage
    get() =
        when (this) {
            DpiProbeError.DnsFail -> {
                FieldFailureStage.Dns
            }

            DpiProbeError.SynDrop,
            DpiProbeError.Refused,
            DpiProbeError.NetUnreach,
            DpiProbeError.HostUnreach,
            -> {
                FieldFailureStage.TcpConnect
            }

            DpiProbeError.TcpRst,
            DpiProbeError.TlsRst,
            DpiProbeError.TlsRstTls,
            DpiProbeError.TcpAbort,
            -> {
                FieldFailureStage.TcpReset
            }

            DpiProbeError.TlsSpoof,
            DpiProbeError.TlsAlertSni,
            DpiProbeError.TlsAlertHandshake,
            DpiProbeError.TlsBlockVersion,
            DpiProbeError.TlsEof,
            DpiProbeError.TlsDrop,
            DpiProbeError.TlsMitm,
            -> {
                FieldFailureStage.TlsHandshake
            }

            DpiProbeError.Unknown -> {
                FieldFailureStage.Unknown
            }
        }

val ProbeStage.fieldFailureStage: FieldFailureStage
    get() =
        when (this) {
            ProbeStage.TCP_CONNECT -> FieldFailureStage.TcpConnect
            ProbeStage.TLS_HANDSHAKE -> FieldFailureStage.TlsHandshake
            ProbeStage.SENDING_DATA, ProbeStage.READING_DATA -> FieldFailureStage.Unknown
        }

fun fieldFailureStage(
    error: DpiProbeError,
    stage: ProbeStage,
): FieldFailureStage = error.fieldFailureStage.takeUnless { it == FieldFailureStage.Unknown } ?: stage.fieldFailureStage
