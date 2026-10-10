package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.dpi.DpiProbeError
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockLayerDiagnosisMapperTest {
    @Test
    fun `dns failure does not establish poisoning`() {
        val diagnosis = BlockLayerDiagnosisMapper.fromDpiError(DpiProbeError.DnsFail)

        assertEquals(BlockLayer.UNKNOWN, diagnosis?.layer)
        assertEquals(BypassStrategyClass.ENCRYPTED_DNS, diagnosis?.bypassClass)
        assertEquals(EvidenceConfidence.LOW, diagnosis?.confidence)
    }

    @Test
    fun `single tls failures do not establish a blocking mechanism`() {
        listOf(
            DpiProbeError.TlsRstTls,
            DpiProbeError.TlsRst,
            DpiProbeError.TlsAlertSni,
            DpiProbeError.TlsSpoof,
            DpiProbeError.TlsAlertHandshake,
            DpiProbeError.TlsBlockVersion,
            DpiProbeError.TlsEof,
            DpiProbeError.TlsDrop,
            DpiProbeError.TlsMitm,
        ).forEach { error ->
            val diagnosis = BlockLayerDiagnosisMapper.fromDpiError(error)
            assertEquals(error.toString(), BlockLayer.UNKNOWN, diagnosis?.layer)
            assertEquals(EvidenceConfidence.LOW, diagnosis?.confidence)
            assertEquals(BypassStrategyClass.TLS_RECORD_SPLIT, diagnosis?.bypassClass)
        }
    }

    @Test
    fun `single tcp failures do not establish an IP block`() {
        listOf(
            DpiProbeError.SynDrop,
            DpiProbeError.TcpRst,
            DpiProbeError.TcpAbort,
            DpiProbeError.Refused,
            DpiProbeError.NetUnreach,
            DpiProbeError.HostUnreach,
        ).forEach { error ->
            val diagnosis = BlockLayerDiagnosisMapper.fromDpiError(error)
            assertEquals(error.toString(), BlockLayer.UNKNOWN, diagnosis?.layer)
            assertEquals(EvidenceConfidence.LOW, diagnosis?.confidence)
            assertEquals(BypassStrategyClass.FAKE_PACKET_TTL, diagnosis?.bypassClass)
        }
    }

    @Test
    fun `http blockpage maps to host header split class`() {
        val diagnosis = BlockLayerDiagnosisMapper.forHttpBlockpage()

        assertEquals(BlockLayer.HTTP_BLOCKPAGE, diagnosis.layer)
        assertEquals(BypassStrategyClass.HOST_HEADER_SPLIT, diagnosis.bypassClass)
    }
}
