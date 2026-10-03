package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.RecommendationDestination
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.detection.RoutingProtectionRecommendationStrings
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot

private const val HighRiskDetectedAppsThreshold = 3

internal fun buildRoutingProtectionRecommendations(
    result: DetectionCheckResult,
    settings: AppSettings,
    snapshot: RoutingProtectionCatalogSnapshot,
    strings: RoutingProtectionRecommendationStrings = RoutingProtectionRecommendationStrings(),
): List<Recommendation> {
    if (snapshot.detectedApps.isEmpty()) {
        return emptyList()
    }
    val enabledPresetIds = settings.effectiveAppRoutingEnabledPresetIds().toSet()
    val hasTransportVpn =
        result.directSigns.evidence.any {
            it.source == EvidenceSource.NETWORK_CAPABILITIES && it.detected && it.confidence == EvidenceConfidence.HIGH
        }
    val hasSplitBypass =
        result.bypassResult.evidence.any {
            it.source == EvidenceSource.SPLIT_TUNNEL_BYPASS && it.detected
        }
    val hasDisabledPresetMatches =
        snapshot.presets.any { preset ->
            preset.matchedPackages.isNotEmpty() && preset.id !in enabledPresetIds
        }
    return buildList {
        if (!settings.fullTunnelMode && hasDisabledPresetMatches) {
            add(
                Recommendation(
                    title = strings.appRoutingAvailableTitle,
                    description = strings.appRoutingAvailableDescription,
                    destination = RecommendationDestination.ADVANCED_SETTINGS,
                ),
            )
        }
        if (!settings.fullTunnelMode && snapshot.detectedApps.size >= HighRiskDetectedAppsThreshold) {
            add(
                Recommendation(
                    title = strings.fullTunnelTitle,
                    description = strings.fullTunnelDescription,
                    destination = RecommendationDestination.GENERAL_SETTINGS,
                ),
            )
        }
        if (!settings.antiCorrelationEnabled && (hasTransportVpn || hasSplitBypass)) {
            add(
                Recommendation(
                    title = strings.antiCorrelationTitle,
                    description = strings.antiCorrelationDescription,
                    destination = RecommendationDestination.ADVANCED_SETTINGS,
                ),
            )
        }
        if (!settings.fullTunnelMode &&
            normalizeDhtMitigationMode(settings.dhtMitigationMode) == DhtMitigationModeOff &&
            hasSplitBypass
        ) {
            add(
                Recommendation(
                    title = strings.dhtMitigationTitle,
                    description = strings.dhtMitigationDescription,
                    destination = RecommendationDestination.ADVANCED_SETTINGS,
                ),
            )
        }
    }
}
