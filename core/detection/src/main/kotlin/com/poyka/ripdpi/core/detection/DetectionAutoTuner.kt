package com.poyka.ripdpi.core.detection

data class AutoTuneFix(
    val id: String,
    val title: String,
    val description: String,
    val settingsKey: String,
    val currentlyEnabled: Boolean,
)

object DetectionAutoTuner {
    fun suggestFixes(
        result: DetectionCheckResult,
        tlsFingerprintEnabled: Boolean = false,
        entropyPaddingEnabled: Boolean = false,
        encryptedDnsEnabled: Boolean = false,
        fullTunnelEnabled: Boolean = false,
        strategyEvolutionEnabled: Boolean = false,
    ): List<AutoTuneFix> {
        val fixes = mutableListOf<AutoTuneFix>()

        val hasTransportVpn =
            result.directSigns.evidence.any {
                it.source == EvidenceSource.NETWORK_CAPABILITIES && it.confidence == EvidenceConfidence.HIGH
            }

        if (hasTransportVpn && !tlsFingerprintEnabled) {
            fixes.add(
                AutoTuneFix(
                    id = "tls_fingerprint",
                    title = "Enable TLS fingerprint",
                    description = "Randomize TLS ClientHello to mimic browser traffic",
                    settingsKey = "tls_fingerprint_profile",
                    currentlyEnabled = false,
                ),
            )
        }

        if (hasTransportVpn && !entropyPaddingEnabled) {
            fixes.add(
                AutoTuneFix(
                    id = "entropy_padding",
                    title = "Enable entropy padding",
                    description = "Add padding to normalize traffic entropy patterns",
                    settingsKey = "entropy_mode",
                    currentlyEnabled = false,
                ),
            )
        }

        val hasDnsLeak =
            result.indirectSigns.evidence.any {
                it.source == EvidenceSource.DNS && it.confidence == EvidenceConfidence.HIGH
            }
        if (hasDnsLeak && !encryptedDnsEnabled) {
            fixes.add(
                AutoTuneFix(
                    id = "encrypted_dns",
                    title = "Enable encrypted DNS",
                    description = "Use DoH/DoT to prevent DNS resolver detection",
                    settingsKey = "dns_mode",
                    currentlyEnabled = false,
                ),
            )
        }

        val hasLocalProxy =
            result.bypassResult.evidence.any {
                it.source == EvidenceSource.LOCAL_PROXY && it.detected
            }
        if (hasLocalProxy && !fullTunnelEnabled) {
            fixes.add(
                AutoTuneFix(
                    id = "full_tunnel",
                    title = "Enable full tunnel mode",
                    description = "Route all traffic through VPN with no app exclusions",
                    settingsKey = "full_tunnel_mode",
                    currentlyEnabled = false,
                ),
            )
        }

        if (!strategyEvolutionEnabled && result.verdict != Verdict.NOT_DETECTED) {
            fixes.add(
                AutoTuneFix(
                    id = "strategy_evolution",
                    title = "Enable strategy evolution",
                    description = "Automatically explore DPI evasion strategy variations",
                    settingsKey = "strategy_evolution",
                    currentlyEnabled = false,
                ),
            )
        }

        return fixes
    }
}
