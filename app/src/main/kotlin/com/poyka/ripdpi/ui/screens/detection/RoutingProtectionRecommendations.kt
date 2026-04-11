package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot

internal fun buildRoutingProtectionRecommendations(
    result: DetectionCheckResult,
    settings: AppSettings,
    snapshot: RoutingProtectionCatalogSnapshot,
): List<Recommendation> {
    if (snapshot.detectedApps.isEmpty()) {
        return emptyList()
    }
    val enabledPresetIds = settings.effectiveAppRoutingEnabledPresetIds().toSet()
    val hasTransportVpn =
        result.directSigns.evidence.any {
            it.source == EvidenceSource.NETWORK_CAPABILITIES && it.confidence == EvidenceConfidence.HIGH
        }
    val hasSplitBypass =
        result.bypassResult.evidence.any {
            it.source == EvidenceSource.SPLIT_TUNNEL_BYPASS && it.detected
        }
    return buildList {
        if (!settings.fullTunnelMode &&
            snapshot.presets.any { it.matchedPackages.isNotEmpty() && it.id !in enabledPresetIds }
        ) {
            add(
                Recommendation(
                    title = "App routing protection is available",
                    description =
                        "Known whitelist-sensitive apps are installed. Enable direct routing for the matched presets before assuming split tunneling remains invisible.",
                    actionRoute = "advanced_settings",
                ),
            )
        }
        if (!settings.fullTunnelMode && snapshot.detectedApps.size >= 3) {
            add(
                Recommendation(
                    title = "Full tunnel remains the strongest routing fix",
                    description =
                        "Several risky apps are installed. Full tunnel mode removes per-app routing differences when exact exclusions are not enough.",
                    actionRoute = "settings",
                ),
            )
        }
        if (!settings.antiCorrelationEnabled && (hasTransportVpn || hasSplitBypass)) {
            add(
                Recommendation(
                    title = "Anti-correlation mode may reduce route fingerprints",
                    description =
                        "Application exclusion does not hide TRANSPORT_VPN checks. Anti-correlation is the next step when app routing alone is still visible.",
                    actionRoute = "advanced_settings",
                ),
            )
        }
    }
}
