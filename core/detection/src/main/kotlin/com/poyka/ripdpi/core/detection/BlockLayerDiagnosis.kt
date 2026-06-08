package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.dpi.DpiProbeError

enum class BlockLayer {
    DNS_POISONING,
    SNI_BASED_RESET,
    IP_BLOCK,
    TLS_HANDSHAKE_INTERFERENCE,
    HTTP_BLOCKPAGE,
    UNKNOWN,
}

enum class BypassStrategyClass {
    ENCRYPTED_DNS,
    TLS_RECORD_SPLIT,
    FAKE_PACKET_TTL,
    HOST_HEADER_SPLIT,
    STRATEGY_PROBE_WINNER,
}

data class BlockLayerDiagnosis(
    val layer: BlockLayer,
    val bypassClass: BypassStrategyClass,
    val confidence: EvidenceConfidence,
    val reasonCode: String,
)

object BlockLayerDiagnosisMapper {
    fun fromDpiError(error: DpiProbeError?): BlockLayerDiagnosis? =
        when (error) {
            DpiProbeError.DnsFail -> {
                diagnosis(
                    layer = BlockLayer.DNS_POISONING,
                    bypassClass = BypassStrategyClass.ENCRYPTED_DNS,
                    confidence = EvidenceConfidence.HIGH,
                    reasonCode = "dns_resolution_failed",
                )
            }

            DpiProbeError.TlsAlertSni,
            DpiProbeError.TlsRst,
            DpiProbeError.TlsRstTls,
            -> {
                diagnosis(
                    layer = BlockLayer.SNI_BASED_RESET,
                    bypassClass = BypassStrategyClass.TLS_RECORD_SPLIT,
                    confidence = EvidenceConfidence.HIGH,
                    reasonCode = "tls_handshake_reset_or_sni_alert",
                )
            }

            DpiProbeError.SynDrop,
            DpiProbeError.TcpRst,
            DpiProbeError.TcpAbort,
            DpiProbeError.Refused,
            DpiProbeError.NetUnreach,
            DpiProbeError.HostUnreach,
            -> {
                diagnosis(
                    layer = BlockLayer.IP_BLOCK,
                    bypassClass = BypassStrategyClass.FAKE_PACKET_TTL,
                    confidence = EvidenceConfidence.MEDIUM,
                    reasonCode = "tcp_connect_or_ip_path_failed",
                )
            }

            DpiProbeError.TlsSpoof,
            DpiProbeError.TlsAlertHandshake,
            DpiProbeError.TlsBlockVersion,
            DpiProbeError.TlsEof,
            DpiProbeError.TlsDrop,
            DpiProbeError.TlsMitm,
            -> {
                diagnosis(
                    layer = BlockLayer.TLS_HANDSHAKE_INTERFERENCE,
                    bypassClass = BypassStrategyClass.TLS_RECORD_SPLIT,
                    confidence = EvidenceConfidence.MEDIUM,
                    reasonCode = "tls_handshake_interference",
                )
            }

            DpiProbeError.Unknown -> {
                diagnosis(
                    layer = BlockLayer.UNKNOWN,
                    bypassClass = BypassStrategyClass.STRATEGY_PROBE_WINNER,
                    confidence = EvidenceConfidence.LOW,
                    reasonCode = "unknown_transport_failure",
                )
            }

            null -> {
                null
            }
        }

    fun forDnsTampering(): BlockLayerDiagnosis =
        diagnosis(
            layer = BlockLayer.DNS_POISONING,
            bypassClass = BypassStrategyClass.ENCRYPTED_DNS,
            confidence = EvidenceConfidence.HIGH,
            reasonCode = "dns_answer_diverged_from_encrypted_resolver",
        )

    fun forHttpBlockpage(): BlockLayerDiagnosis =
        diagnosis(
            layer = BlockLayer.HTTP_BLOCKPAGE,
            bypassClass = BypassStrategyClass.HOST_HEADER_SPLIT,
            confidence = EvidenceConfidence.MEDIUM,
            reasonCode = "http_blockpage_or_redirect",
        )

    fun forTlsVersionBlock(): BlockLayerDiagnosis =
        diagnosis(
            layer = BlockLayer.TLS_HANDSHAKE_INTERFERENCE,
            bypassClass = BypassStrategyClass.TLS_RECORD_SPLIT,
            confidence = EvidenceConfidence.MEDIUM,
            reasonCode = "tls_version_specific_failure",
        )

    fun forUnknownBlocked(): BlockLayerDiagnosis =
        diagnosis(
            layer = BlockLayer.UNKNOWN,
            bypassClass = BypassStrategyClass.STRATEGY_PROBE_WINNER,
            confidence = EvidenceConfidence.LOW,
            reasonCode = "blocked_without_specific_layer",
        )

    private fun diagnosis(
        layer: BlockLayer,
        bypassClass: BypassStrategyClass,
        confidence: EvidenceConfidence,
        reasonCode: String,
    ): BlockLayerDiagnosis =
        BlockLayerDiagnosis(
            layer = layer,
            bypassClass = bypassClass,
            confidence = confidence,
            reasonCode = reasonCode,
        )
}
