package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.ActiveVpnApp
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.ExposureStatus
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.MatchedVpnApp
import com.poyka.ripdpi.core.detection.VerdictNarrativeRow
import com.poyka.ripdpi.core.detection.VpnAppKind
import com.poyka.ripdpi.core.detection.VpnAppTechnicalMetadata
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionAppExportPrivacyTest {
    @Test
    fun `private reports retain signal status while hiding all app identities`() {
        val result = resultWithAppIdentities()
        val shared = reports(result, privacyMode = false)
        val redactedReports = reports(result, privacyMode = true)
        val identityValues = listOf(AppLabel, PackageName, ActiveService, InstalledVersion, ApkEntry)
        shared.zip(redactedReports).forEach { (full, redacted) ->
            identityValues.forEach { value ->
                assertTrue("Public format lost $value", full.contains(value))
                assertFalse("Private format leaked $value", redacted.contains(value))
            }
            assertTrue(redacted.contains("App identity details hidden"))
        }
        assertTrue(shared[0].contains(CachedOwnerLabel))
        assertFalse(redactedReports.any { it.contains(CachedOwnerLabel) })
        assertFalse(redactedReports.any { it.contains("Owner app") })

        val results =
            Json
                .parseToJsonElement(redactedReports.last())
                .jsonObject
                .getValue("results")
                .jsonObject
        val directSigns = results.getValue("directSigns").jsonObject
        val evidence =
            directSigns
                .getValue("evidence")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("true", directSigns.getValue("needsReview").jsonPrimitive.content)
        assertEquals("LOCAL_INVENTORY", evidence.getValue("scope").jsonPrimitive.content)
        assertEquals(JsonNull, evidence.getValue("packageName"))
        assertTrue(directSigns.getValue("matchedApps").jsonArray.isEmpty())
        assertTrue(
            results
                .getValue("indirectSigns")
                .jsonObject
                .getValue("activeApps")
                .jsonArray
                .isEmpty(),
        )
    }

    private fun reports(
        result: DetectionCheckResult,
        privacyMode: Boolean,
    ): List<String> {
        val metadata = DetectionExportMetadata("2026-11-15T00:00:00Z", "test", "debug", privacyMode)
        return listOf(
            DetectionReportFormatter.format(result, privacyMode),
            DetectionDebugFormatter.format(result, privacyModeEnabled = privacyMode),
            DetectionMarkdownExportFormatter.format(result, metadata),
            DetectionJsonExportFormatter.format(result, metadata),
        )
    }

    private fun resultWithAppIdentities(): DetectionCheckResult {
        val installedApp = installedApp()
        val fixture = detectionExportFixture()
        val description = "$AppLabel ($PackageName) version=$InstalledVersion path=$ApkEntry"
        val serviceDescription = "$PackageName/$ActiveService"
        val narrative = requireNotNull(fixture.verdictNarrative)
        return fixture.copy(
            directSigns =
                fixture.directSigns.copy(
                    needsReview = true,
                    findings = listOf(Finding(description, source = EvidenceSource.INSTALLED_APP)),
                    evidence =
                        listOf(
                            EvidenceItem(
                                source = EvidenceSource.INSTALLED_APP,
                                detected = true,
                                confidence = EvidenceConfidence.MEDIUM,
                                description = description,
                                packageName = PackageName,
                            ),
                        ),
                    matchedApps = listOf(installedApp),
                ),
            indirectSigns =
                fixture.indirectSigns.copy(
                    detected = true,
                    findings = listOf(Finding(serviceDescription, source = EvidenceSource.ACTIVE_VPN)),
                    activeApps =
                        listOf(
                            ActiveVpnApp(
                                packageName = PackageName,
                                serviceName = ActiveService,
                                family = null,
                                kind = null,
                                source = EvidenceSource.ACTIVE_VPN,
                                confidence = EvidenceConfidence.MEDIUM,
                            ),
                        ),
                ),
            verdictNarrative =
                narrative.copy(
                    discoveredRows =
                        narrative.discoveredRows +
                            VerdictNarrativeRow("Owner app", CachedOwnerLabel, ExposureStatus.TECHNICAL_SIGNAL_ONLY),
                ),
        )
    }

    private fun installedApp(): MatchedVpnApp =
        MatchedVpnApp(
            packageName = PackageName,
            appName = AppLabel,
            family = null,
            kind = VpnAppKind.GENERIC_VPN,
            source = EvidenceSource.INSTALLED_APP,
            active = false,
            confidence = EvidenceConfidence.MEDIUM,
            technicalMetadata = VpnAppTechnicalMetadata(versionName = InstalledVersion, apkLibraryNameHint = ApkEntry),
        )

    private companion object {
        const val AppLabel = "Inventory Fixture VPN"
        const val PackageName = "example.inventory.fixture"
        const val ActiveService = "FixtureTunnelService"
        const val InstalledVersion = "inventory-version-fixture"
        const val ApkEntry = "lib/arm64-v8a/libinventory-fixture.so"
        const val CachedOwnerLabel = "cached-owner-fixture"
    }
}
