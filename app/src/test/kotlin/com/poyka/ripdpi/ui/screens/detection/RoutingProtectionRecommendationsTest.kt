package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.services.RoutingProtectionDetectedApp
import com.poyka.ripdpi.services.RoutingProtectionMatchedPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingProtectionRecommendationsTest {
    @Test
    fun `transport vpn with risky apps suggests anti correlation`() {
        val recommendations =
            buildRoutingProtectionRecommendations(
                result = detectionResultWithTransportVpn(),
                settings = AppSettings.getDefaultInstance(),
                snapshot =
                    RoutingProtectionCatalogSnapshot(
                        presets =
                            listOf(
                                RoutingProtectionMatchedPreset(
                                    id = "ru-apps",
                                    title = "Russian apps",
                                    detectionMethod = "transport_vpn",
                                    fixCoverage = "direct routing",
                                    limitations = "",
                                    matchedPackages = listOf("ru.example.bank"),
                                ),
                            ),
                        detectedApps =
                            listOf(
                                RoutingProtectionDetectedApp(
                                    packageName = "ru.example.bank",
                                    presetId = "ru-apps",
                                    presetTitle = "Russian apps",
                                    detectionMethod = "transport_vpn",
                                    fixCoverage = "direct routing",
                                ),
                            ),
                    ),
            )

        assertTrue(recommendations.any { it.title.contains("Anti-correlation") })
    }

    @Test
    fun `split bypass with dht mitigation disabled suggests dht protection`() {
        val recommendations =
            buildRoutingProtectionRecommendations(
                result =
                    detectionResultWithTransportVpn().copy(
                        bypassResult =
                            BypassResult(
                                proxyEndpoint = null,
                                directIp = null,
                                proxyIp = null,
                                xrayApiScanResult = null,
                                findings = emptyList(),
                                detected = true,
                                evidence =
                                    listOf(
                                        EvidenceItem(
                                            source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                                            detected = true,
                                            confidence = EvidenceConfidence.HIGH,
                                            description = "split bypass detected",
                                        ),
                                    ),
                            ),
                    ),
                settings =
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setDhtMitigationMode(DhtMitigationModeOff)
                        .build(),
                snapshot =
                    RoutingProtectionCatalogSnapshot(
                        presets =
                            listOf(
                                RoutingProtectionMatchedPreset(
                                    id = "ru-apps",
                                    title = "Russian apps",
                                    detectionMethod = "transport_vpn",
                                    fixCoverage = "direct routing",
                                    limitations = "",
                                    matchedPackages = listOf("ru.example.bank"),
                                ),
                            ),
                        detectedApps =
                            listOf(
                                RoutingProtectionDetectedApp(
                                    packageName = "ru.example.bank",
                                    presetId = "ru-apps",
                                    presetTitle = "Russian apps",
                                    detectionMethod = "transport_vpn",
                                    fixCoverage = "direct routing",
                                ),
                            ),
                    ),
            )

        assertEquals(
            1,
            recommendations.count { it.title.contains("DHT mitigation") },
        )
    }

    private fun detectionResultWithTransportVpn(): DetectionCheckResult =
        DetectionCheckResult(
            geoIp = emptyCategory("GeoIP"),
            directSigns =
                emptyCategory("Direct").copy(
                    evidence =
                        listOf(
                            EvidenceItem(
                                source = EvidenceSource.NETWORK_CAPABILITIES,
                                detected = true,
                                confidence = EvidenceConfidence.HIGH,
                                description = "TRANSPORT_VPN",
                            ),
                        ),
                ),
            indirectSigns = emptyCategory("Indirect"),
            locationSignals = emptyCategory("Location"),
            bypassResult =
                BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = emptyList(),
                    detected = false,
                ),
            verdict = Verdict.DETECTED,
        )

    private fun emptyCategory(name: String) =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )
}
