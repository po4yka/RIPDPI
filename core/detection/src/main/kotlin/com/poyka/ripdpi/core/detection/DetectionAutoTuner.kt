package com.poyka.ripdpi.core.detection

data class AutoTuneFix(
    val id: String,
    val title: String,
    val description: String,
    val settingsKey: String,
    val currentlyEnabled: Boolean,
)

object DetectionAutoTuner {
    @Suppress("UnusedParameter")
    fun suggestFixes(
        result: DetectionCheckResult,
        tlsFingerprintEnabled: Boolean = false,
        entropyPaddingEnabled: Boolean = false,
        encryptedDnsEnabled: Boolean = false,
        fullTunnelEnabled: Boolean = false,
        strategyEvolutionEnabled: Boolean = false,
    ): List<AutoTuneFix> {
        // Current checks do not prove that these settings remove an observed local signal.
        return emptyList()
    }
}
