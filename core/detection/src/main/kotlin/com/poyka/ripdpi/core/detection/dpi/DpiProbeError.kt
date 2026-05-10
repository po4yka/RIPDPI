package com.poyka.ripdpi.core.detection.dpi

sealed class DpiProbeError(
    val label: String,
) {
    data object TlsSpoof : DpiProbeError("TLS_SPOOF")

    data object TlsAlertSni : DpiProbeError("TLS_ALERT_SNI")

    data object TlsAlertHandshake : DpiProbeError("TLS_ALERT_HANDSHAKE")

    data object TlsBlockVersion : DpiProbeError("TLS_BLOCK_VERSION")

    data object TlsRst : DpiProbeError("TLS_RST")

    data object TlsEof : DpiProbeError("TLS_EOF")

    data object TlsDrop : DpiProbeError("TLS_DROP")

    data object TlsMitm : DpiProbeError("TLS_MITM")

    data object SynDrop : DpiProbeError("SYN_DROP")

    data object TcpRst : DpiProbeError("TCP_RST")

    data object TlsRstTls : DpiProbeError("TLS_RST_TLS")

    data object TcpAbort : DpiProbeError("TCP_ABORT")

    data object DnsFail : DpiProbeError("DNS_FAIL")

    data object Refused : DpiProbeError("REFUSED")

    data object NetUnreach : DpiProbeError("NET_UNREACH")

    data object HostUnreach : DpiProbeError("HOST_UNREACH")

    data object Unknown : DpiProbeError("UNKNOWN")
}
