package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.diagnostics.dpi.DpiSuiteProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsDpiSuiteUiMapperTest {
    @Test
    fun quicH3RowIncludesPerTargetFingerprintMatrix() {
        val row =
            DpiSuiteProbeResult
                .QuicH3(
                    listOf(
                        QuicProbeResult(
                            target = "cloudflare.com",
                            verdict = QuicProbeVerdict.QUIC_DPI_FINGERPRINT_BLOCK,
                            chromeOk = false,
                            firefoxOk = true,
                            genericOk = true,
                            vnOk = true,
                            udpReachable = true,
                            serverInitialLatencyMs = 42,
                        ),
                    ),
                ).toDpiSuiteProbeRowUiModel()

        assertEquals(DpiProbeKind.QUIC_H3, row.kind)
        assertEquals("QUIC/H3 fingerprint", row.label)
        assertEquals("flagged", row.status)
        assertEquals(1, row.detailRows.size)
        assertEquals("cloudflare.com", row.detailRows.single().label)
        assertEquals("Chrome blocked | Firefox ok | Generic ok | VN ok | 42 ms", row.detailRows.single().detail)
        assertEquals(DiagnosticsTone.Warning, row.detailRows.single().tone)
    }
}
