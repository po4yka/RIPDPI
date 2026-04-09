package com.poyka.ripdpi.core.detection

data class Recommendation(
    val title: String,
    val description: String,
    val actionRoute: String? = null,
)

object DetectionRecommendations {
    fun generate(result: DetectionCheckResult): List<Recommendation> {
        val recommendations = mutableListOf<Recommendation>()

        val hasTransportVpn =
            result.directSigns.evidence.any {
                it.source == EvidenceSource.NETWORK_CAPABILITIES && it.confidence == EvidenceConfidence.HIGH
            }
        if (hasTransportVpn) {
            recommendations.add(
                Recommendation(
                    title = "VPN transport is visible",
                    description =
                        "TRANSPORT_VPN flag is detectable. Consider enabling TLS fingerprint " +
                            "randomization and entropy padding in Detection Resistance settings.",
                    actionRoute = "advanced_settings",
                ),
            )
        }

        val hasLocalProxy =
            result.bypassResult.evidence.any {
                it.source == EvidenceSource.LOCAL_PROXY && it.detected
            }
        if (hasLocalProxy) {
            recommendations.add(
                Recommendation(
                    title = "Open localhost proxy detected",
                    description =
                        "An unauthenticated proxy was found on localhost. " +
                            "Enable full tunnel mode to route all traffic through VPN.",
                    actionRoute = "settings",
                ),
            )
        }

        val hasXrayApi =
            result.bypassResult.evidence.any {
                it.source == EvidenceSource.XRAY_API && it.detected
            }
        if (hasXrayApi) {
            recommendations.add(
                Recommendation(
                    title = "Xray gRPC API exposed",
                    description =
                        "The Xray HandlerService API is accessible on localhost, " +
                            "leaking VPN server addresses and credentials. Disable the API in " +
                            "your Xray client settings.",
                ),
            )
        }

        val hasSplitBypass =
            result.bypassResult.evidence.any {
                it.source == EvidenceSource.SPLIT_TUNNEL_BYPASS && it.detected
            }
        if (hasSplitBypass) {
            recommendations.add(
                Recommendation(
                    title = "Per-app split tunnel bypass confirmed",
                    description =
                        "Direct IP differs from proxy IP, proving the proxy tunnels " +
                            "traffic to a different exit. This is a HIGH confidence detection signal.",
                ),
            )
        }

        val hasDnsLeak =
            result.indirectSigns.evidence.any {
                it.source == EvidenceSource.DNS && it.confidence == EvidenceConfidence.HIGH
            }
        if (hasDnsLeak) {
            recommendations.add(
                Recommendation(
                    title = "DNS points to localhost",
                    description =
                        "DNS resolver uses a loopback address, which is typical for " +
                            "VPN configurations. Consider using encrypted DNS to reduce this signal.",
                    actionRoute = "dns_settings",
                ),
            )
        }

        val hasTargetedApp =
            result.directSigns.matchedApps.any {
                it.kind == VpnAppKind.TARGETED_BYPASS
            }
        if (hasTargetedApp) {
            recommendations.add(
                Recommendation(
                    title = "Known bypass app installed",
                    description =
                        "A known targeted bypass application is installed. " +
                            "Consider using a work profile or private space to isolate it.",
                ),
            )
        }

        if (result.verdict == Verdict.NOT_DETECTED && recommendations.isEmpty()) {
            recommendations.add(
                Recommendation(
                    title = "No issues detected",
                    description =
                        "Your current setup does not trigger any detection signals " +
                            "based on the methodology checks.",
                ),
            )
        }

        return recommendations
    }
}
