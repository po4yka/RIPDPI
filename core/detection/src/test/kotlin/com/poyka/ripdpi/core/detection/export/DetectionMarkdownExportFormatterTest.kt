package com.poyka.ripdpi.core.detection.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionMarkdownExportFormatterTest {
    @Test
    fun `output contains summary code block`() {
        val output =
            DetectionMarkdownExportFormatter.format(
                result = detectionExportFixture(),
                metadata = exportMetadata(),
            )

        assertTrue(output.contains("```\nVERDICT: "))
        assertTrue(output.contains("EXPOSURE: "))
        assertTrue(output.contains("PRIVACY MODE: false"))
        assertTrue(output.contains("TIMESTAMP: 2026-05-10T10:15:30Z"))
    }

    @Test
    fun `output contains all required section headers`() {
        val output =
            DetectionMarkdownExportFormatter.format(
                result = detectionExportFixture(),
                metadata = exportMetadata(),
            )

        listOf(
            "## Verdict",
            "## Section Summary",
            "## GeoIp",
            "## IpComparison",
            "## CdnPulling",
            "## DirectSigns",
            "## IndirectSigns",
            "## NativeSigns",
            "## IcmpSpoofing",
            "## RttTriangulation",
            "## LocationSignals",
            "## IpChannels",
            "## TunProbeDiagnostics",
            "## Bypass",
        ).forEach { header ->
            assertTrue("missing $header", output.contains(header))
        }
    }

    @Test
    fun `xray uuid not present in output`() {
        val output =
            DetectionMarkdownExportFormatter.format(
                result = detectionExportFixture(),
                metadata = exportMetadata(),
            )

        assertFalse(output.contains(FixtureUuid))
        assertTrue(output.contains("uuidPresent: true"))
    }

    @Test
    fun `privacy mode masks ips in markdown output`() {
        val output =
            DetectionMarkdownExportFormatter.format(
                result = detectionExportFixture(),
                metadata = exportMetadata(privacyMode = true),
            )

        assertFalse(output.contains(FixturePublicIp))
        assertTrue(output.contains("5.6.*.*"))
        assertTrue(output.contains("127.0.0.1"))
    }

    private fun exportMetadata(privacyMode: Boolean = false): DetectionExportMetadata =
        DetectionExportMetadata(
            timestamp = "2026-05-10T10:15:30Z",
            appVersion = "0.0.7-test",
            buildType = "debug",
            privacyMode = privacyMode,
        )
}
