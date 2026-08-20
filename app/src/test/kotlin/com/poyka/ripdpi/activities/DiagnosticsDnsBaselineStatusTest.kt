package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsDnsBaselineStatusTest {
    @Test
    fun `system resolver failure is not displayed as clean or tampered`() {
        assertEquals(
            DnsBaselineStatus.RESOLUTION_FAILED,
            dnsBaselineStatusForOutcome("dns_resolution_failure"),
        )
    }

    @Test
    fun `tampering and clean outcomes retain their existing statuses`() {
        assertEquals(DnsBaselineStatus.TAMPERED, dnsBaselineStatusForOutcome("dns_tampering"))
        assertEquals(DnsBaselineStatus.CLEAN, dnsBaselineStatusForOutcome("dns_match"))
    }
}
