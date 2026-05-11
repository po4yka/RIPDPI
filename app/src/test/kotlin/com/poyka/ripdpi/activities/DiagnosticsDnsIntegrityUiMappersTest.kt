package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpich.BootstrapVerdict
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapResult
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsDnsIntegrityUiMappersTest {
    @Test
    fun dnsIntegrityMapperSurfacesDohBootstrapTrustRows() {
        val uiModel =
            DnsIntegrityResult(
                domains = emptyList(),
                stubIps = emptySet(),
                dohBlocked = 0,
                dohBootstrapResults =
                    listOf(
                        DohBootstrapResult(
                            providerName = "Google",
                            dohHostname = "dns.google",
                            bootstrapIp = "1.2.3.4",
                            discoveredAsn = 12389,
                            discoveredOrg = "Rostelecom",
                            verdict = BootstrapVerdict.SPOOFED,
                            expectedFilter = "as(15169)",
                        ),
                    ),
            ).toUiModel()

        val row = uiModel.dohBootstrapRows.single()
        assertEquals("Google", row.provider)
        assertEquals("dns.google", row.hostname)
        assertEquals("spoofed", row.verdict)
        assertEquals("1.2.3.4 · AS12389 · Rostelecom", row.detail)
        assertEquals(DiagnosticsTone.Warning, row.tone)
    }
}
