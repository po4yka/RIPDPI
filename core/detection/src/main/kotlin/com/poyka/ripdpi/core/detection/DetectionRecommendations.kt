@file:Suppress("LongMethod")

package com.poyka.ripdpi.core.detection

enum class RecommendationDestination {
    MODE_SETTINGS,
    PROXY_SETTINGS,
    DNS_SETTINGS,
    GENERAL_SETTINGS,
    ADVANCED_SETTINGS,
}

data class Recommendation(
    val title: String,
    val description: String,
    val destination: RecommendationDestination? = null,
)

object DetectionRecommendations {
    fun generate(result: DetectionCheckResult): List<Recommendation> =
        buildList {
            val hasTransportVpn =
                result.directSigns.evidence.any {
                    it.source == EvidenceSource.NETWORK_CAPABILITIES && it.detected &&
                        it.confidence == EvidenceConfidence.HIGH
                }
            if (hasTransportVpn) {
                add(
                    Recommendation(
                        title = "VPN transport is visible",
                        description =
                            "Android reports VPN routing on the active network. " +
                                "Use a non-VPN mode if this local signal must be absent.",
                        destination = RecommendationDestination.MODE_SETTINGS,
                    ),
                )
            }

            val hasLocalProxy =
                result.bypassResult.evidence.any {
                    it.source == EvidenceSource.LOCAL_PROXY && it.detected
                }
            if (hasLocalProxy) {
                add(
                    Recommendation(
                        title = "Open localhost proxy detected",
                        description =
                            "A proxy responds on localhost. Review the listener and disable it when it is not needed.",
                        destination = RecommendationDestination.PROXY_SETTINGS,
                    ),
                )
            }

            val hasXrayApi =
                result.bypassResult.evidence.any {
                    it.source == EvidenceSource.XRAY_API && it.detected
                }
            if (hasXrayApi) {
                add(
                    Recommendation(
                        title = "Xray gRPC API exposed",
                        description =
                            "The Xray HandlerService API is accessible on localhost, " +
                                "leaking VPN server addresses and credentials. Disable the API in " +
                                "your Xray client settings.",
                    ),
                )
            }

            val hasLoopbackDns =
                result.indirectSigns.evidence.any {
                    it.source == EvidenceSource.DNS && it.detected && it.confidence == EvidenceConfidence.HIGH
                }
            if (hasLoopbackDns) {
                add(
                    Recommendation(
                        title = "DNS points to localhost",
                        description =
                            "The active network lists a loopback DNS resolver. " +
                                "Check the DNS configuration if this is unexpected.",
                        destination = RecommendationDestination.DNS_SETTINGS,
                    ),
                )
            }

            val hasTargetedApp =
                result.directSigns.matchedApps.any {
                    it.kind == VpnAppKind.TARGETED_BYPASS
                }
            if (hasTargetedApp) {
                add(
                    Recommendation(
                        title = "Known bypass app installed",
                        description =
                            "A known targeted bypass application is installed. " +
                                "Consider using a work profile or private space to isolate it.",
                    ),
                )
            }

            if (result.verdict == Verdict.NOT_DETECTED && isEmpty()) {
                add(
                    Recommendation(
                        title = "No issues detected",
                        description =
                            "Your current setup does not trigger any detection signals " +
                                "based on the methodology checks.",
                    ),
                )
            }
        }
}
