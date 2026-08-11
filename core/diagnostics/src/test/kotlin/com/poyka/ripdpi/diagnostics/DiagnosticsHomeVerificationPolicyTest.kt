package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsHomeVerificationPolicyTest {
    @Test
    fun `tls version split alone does not confirm VPN access`() {
        val report =
            ScanReport(
                sessionId = "verify-session",
                profileId = "default",
                pathMode = ScanPathMode.IN_PATH,
                startedAt = 10L,
                finishedAt = 20L,
                summary = "TLS versions produced different outcomes",
                results =
                    listOf(
                        ProbeResult(
                            probeType = "https",
                            target = "example.com",
                            outcome = "tls_version_split",
                        ),
                    ),
            )

        assertFalse(isVpnAccessConfirmed(report))
    }
}
