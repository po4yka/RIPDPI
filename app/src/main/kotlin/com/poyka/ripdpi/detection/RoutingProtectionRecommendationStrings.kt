package com.poyka.ripdpi.detection

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver

/**
 * Localized copy used by the routing-protection recommendation builder.
 *
 * Holding the resolved strings in one value object keeps the recommendation
 * builder's parameter list short and moves the resource lookups out of the
 * detection view model.
 */
data class RoutingProtectionRecommendationStrings(
    val appRoutingAvailableTitle: String = "App routing protection is available",
    val appRoutingAvailableDescription: String =
        "Known whitelist-sensitive apps are installed. Enable direct routing for " +
            "the matched presets before assuming split tunneling remains invisible.",
    val fullTunnelTitle: String = "Full tunnel remains the strongest routing fix",
    val fullTunnelDescription: String =
        "Several risky apps are installed. Full tunnel mode removes per-app routing " +
            "differences when exact exclusions are not enough.",
    val antiCorrelationTitle: String = "Anti-correlation mode may reduce route fingerprints",
    val antiCorrelationDescription: String =
        "Application exclusion does not hide TRANSPORT_VPN checks. Anti-correlation " +
            "is the next step when app routing alone is still visible.",
    val dhtMitigationTitle: String = "DHT mitigation can protect split routes",
    val dhtMitigationDescription: String =
        "Split routing is visible in the current checks. Enabling DHT trigger " +
            "mitigation helps avoid known UDP trigger CIDRs that can destabilize relay " +
            "or WARP control-plane paths.",
) {
    companion object {
        fun resolve(stringResolver: StringResolver): RoutingProtectionRecommendationStrings =
            RoutingProtectionRecommendationStrings(
                appRoutingAvailableTitle =
                    stringResolver.getString(R.string.routing_protection_available_title),
                appRoutingAvailableDescription =
                    stringResolver.getString(R.string.routing_protection_available_description),
                fullTunnelTitle =
                    stringResolver.getString(R.string.routing_protection_full_tunnel_title),
                fullTunnelDescription =
                    stringResolver.getString(R.string.routing_protection_full_tunnel_description),
                antiCorrelationTitle =
                    stringResolver.getString(
                        R.string.routing_protection_anti_correlation_recommendation_title,
                    ),
                antiCorrelationDescription =
                    stringResolver.getString(R.string.routing_protection_anti_correlation_description),
                dhtMitigationTitle =
                    stringResolver.getString(R.string.routing_protection_dht_mitigation_title),
                dhtMitigationDescription =
                    stringResolver.getString(R.string.routing_protection_dht_mitigation_description),
            )
    }
}
