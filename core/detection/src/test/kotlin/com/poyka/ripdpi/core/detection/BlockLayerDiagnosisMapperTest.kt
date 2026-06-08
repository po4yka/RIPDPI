package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockLayerDiagnosisMapperTest {
    @Test
    fun `dns failure maps to encrypted dns`() {
        val diagnosis = BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.DnsFail)

        assertEquals(BlockLayer.DNS_POISONING, diagnosis?.layer)
        assertEquals(BypassStrategyClass.ENCRYPTED_DNS, diagnosis?.bypassClass)
        assertEquals(EvidenceConfidence.HIGH, diagnosis?.confidence)
    }

    @Test
    fun `tls reset and sni alert map to tls record split`() {
        val rstDiagnosis = BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.TlsRstTls)
        val sniDiagnosis = BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.TlsAlertSni)

        assertEquals(BlockLayer.SNI_BASED_RESET, rstDiagnosis?.layer)
        assertEquals(BypassStrategyClass.TLS_RECORD_SPLIT, rstDiagnosis?.bypassClass)
        assertEquals(BlockLayer.SNI_BASED_RESET, sniDiagnosis?.layer)
        assertEquals(BypassStrategyClass.TLS_RECORD_SPLIT, sniDiagnosis?.bypassClass)
    }

    @Test
    fun `tcp path failures map to fake packet ttl class`() {
        val diagnosis = BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.SynDrop)

        assertEquals(BlockLayer.IP_BLOCK, diagnosis?.layer)
        assertEquals(BypassStrategyClass.FAKE_PACKET_TTL, diagnosis?.bypassClass)
    }

    @Test
    fun `tls handshake anomalies map to tls handshake interference`() {
        val diagnosis = BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.TlsSpoof)

        assertEquals(BlockLayer.TLS_HANDSHAKE_INTERFERENCE, diagnosis?.layer)
        assertEquals(BypassStrategyClass.TLS_RECORD_SPLIT, diagnosis?.bypassClass)
    }

    @Test
    fun `http blockpage maps to host header split class`() {
        val diagnosis = BlockLayerDiagnosisMapper.forHttpBlockpage()

        assertEquals(BlockLayer.HTTP_BLOCKPAGE, diagnosis.layer)
        assertEquals(BypassStrategyClass.HOST_HEADER_SPLIT, diagnosis.bypassClass)
    }
}
