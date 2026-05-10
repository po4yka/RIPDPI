package com.poyka.ripdpi.core.detection.debug

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionDebugFormatterTest {
    @Test
    fun outputContainsAllRequiredBracketSections() {
        val output = DetectionDebugFormatter.format(fixture())

        assertTrue(output.contains("[indirectSigns.performance]"))
        assertTrue(output.contains("[locationSignals.debug]"))
        assertTrue(output.contains("[nativeSigns.raw]"))
        assertTrue(output.contains("[tunProbe]"))
    }

    @Test
    fun settingsSectionListsRepresentativeKeys() {
        val output =
            DetectionDebugFormatter.format(
                result = fixture(),
                settings =
                    DetectionDebugSettings(
                        cdnPullingEnabled = true,
                        dnsResolverMode = "encrypted",
                        portRange = "1080-1090",
                        debugModeEnabled = true,
                    ),
            )

        assertTrue(output.contains("cdnPullingEnabled=true"))
        assertTrue(output.contains("dnsResolverMode=encrypted"))
        assertTrue(output.contains("portRange=1080-1090"))
        assertTrue(output.contains("debugModeEnabled=true"))
    }

    @Test
    fun nativeSocketsCappedAt60() {
        val output =
            DetectionDebugFormatter.format(
                result = fixture(),
                snapshot =
                    DetectionDebugSnapshot(
                        nativeSigns =
                            NativeSignsDebugSnapshot(
                                tcpSockets = (1..80).map { index -> "tcp 203.0.113.$index:443" },
                            ),
                    ),
            )

        assertEquals(60, Regex("""^tcpSocket=""", RegexOption.MULTILINE).findAll(output).count())
    }

    @Test
    fun privacyModeMasksIpsInDebugOutput() {
        val output =
            DetectionDebugFormatter.format(
                result =
                    fixture().copy(
                        geoIp = category("GeoIP").copy(findings = listOf(Finding("IP: 5.6.7.8"))),
                    ),
                privacyModeEnabled = true,
            )

        assertTrue(output.contains("5.6.*.*"))
        assertFalse(output.contains("5.6.7.8"))
    }

    private fun fixture(): DetectionCheckResult =
        DetectionCheckResult(
            geoIp = category("GeoIP"),
            directSigns = category("Direct"),
            indirectSigns = category("Indirect"),
            locationSignals = category("Location"),
            bypassResult =
                BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = emptyList(),
                    detected = false,
                ),
            verdict = Verdict.NOT_DETECTED,
        )

    private fun category(name: String): CategoryResult =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )
}
