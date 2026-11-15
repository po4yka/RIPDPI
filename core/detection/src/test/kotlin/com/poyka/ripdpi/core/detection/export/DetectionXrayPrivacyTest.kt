package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.VerdictNarrativeBuilder
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import com.poyka.ripdpi.core.detection.privacy.DetectionPrivacyMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionXrayPrivacyTest {
    @Test
    fun `copy debug and UI mask outbound fields while retaining local API reachability`() {
        val fixture = detectionExportFixture()
        val scan = requireNotNull(fixture.bypassResult.xrayApiScanResult)
        val outbound = scan.outbounds.single().copy(tag = "office-tunnel", address = "workstation", sni = "relay-name")
        val result =
            fixture.copy(
                bypassResult = fixture.bypassResult.copy(xrayApiScanResult = scan.copy(outbounds = listOf(outbound))),
                verdictNarrative = null,
            )
        val narrative = VerdictNarrativeBuilder.build(result)

        fun render(enabled: Boolean): List<String> =
            listOf(
                DetectionReportFormatter.format(result, privacyModeEnabled = enabled),
                DetectionDebugFormatter.format(result, privacyModeEnabled = enabled),
                DetectionPrivacyMask.bypassFindings(result.bypassResult, enabled).joinToString { it.description },
            )

        val privateText = render(true)
        val publicText = render(false)
        listOf("office-tunnel", "workstation", "relay-name").forEach { value ->
            assertTrue(value, publicText.all { value in it })
            assertFalse(value, privateText.any { value in it })
        }
        val endpointRow = narrative.discoveredRows.single { it.privateXrayEndpoint }
        assertTrue("workstation:443" == DetectionPrivacyMask.narrativeValue(endpointRow, enabled = false))
        assertTrue("***" == DetectionPrivacyMask.narrativeValue(endpointRow, enabled = true))
        assertTrue("127.0.0.1:10085" in privateText.first())
    }
}
