package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
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

    @Test
    fun `output contains scoped verdict provenance and evidence scopes`() {
        val fixture = detectionExportFixture()
        val result =
            fixture.copy(
                directSigns =
                    fixture.directSigns.copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.INSTALLED_APP,
                                    detected = true,
                                    confidence = EvidenceConfidence.MEDIUM,
                                    description = "Targeted bypass app installed",
                                ),
                            ),
                    ),
                ipComparison =
                    fixture.ipComparison?.copy(
                        category =
                            fixture.ipComparison.category.copy(
                                evidence =
                                    listOf(
                                        EvidenceItem(
                                            source = EvidenceSource.IP_COMPARISON,
                                            detected = true,
                                            confidence = EvidenceConfidence.HIGH,
                                            description = "Regional paths report different public IPs",
                                        ),
                                    ),
                            ),
                    ),
                verdictExplanation =
                    fixture.verdictExplanation?.copy(
                        appliedScopes =
                            listOf(
                                DetectionScope.LOCAL_INVENTORY,
                                DetectionScope.NETWORK_OBSERVATION,
                            ),
                        uniqueSignalCount = 2,
                    ),
            )

        val output = DetectionMarkdownExportFormatter.format(result, exportMetadata())

        assertTrue(
            output,
            listOf(
                "- appliedRule: R1",
                "- appliedScopes: LOCAL_INVENTORY, NETWORK_OBSERVATION",
                "- uniqueSignalCount: 2",
                "  - [LOCAL_INVENTORY] Targeted bypass app installed",
                "  - [NETWORK_OBSERVATION] Regional paths report different public IPs",
            ).all(output::contains),
        )
    }

    private fun exportMetadata(privacyMode: Boolean = false): DetectionExportMetadata =
        DetectionExportMetadata(
            timestamp = "2026-05-10T10:15:30Z",
            appVersion = "0.0.7-test",
            buildType = "debug",
            privacyMode = privacyMode,
        )
}
