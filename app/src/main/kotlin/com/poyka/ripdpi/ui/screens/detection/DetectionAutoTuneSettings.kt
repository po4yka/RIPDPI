package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.AutoTuneFix
import com.poyka.ripdpi.core.detection.DetectionAutoTuner
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.proto.AppSettings

private const val EntropyModeBalanced = 3
private const val EntropyPaddingTargetPermilValue = 3400
private const val ShannonEntropyTargetPermilValue = 7920
private const val StrategyEvolutionRecommendedEpsilon = 0.1

internal typealias DetectionSuggestedFix = AutoTuneFix

internal fun AppSettings.suggestDetectionFixes(result: DetectionCheckResult): List<DetectionSuggestedFix> =
    DetectionAutoTuner.suggestFixes(
        result = result,
        tlsFingerprintEnabled = true,
        entropyPaddingEnabled = entropyMode != 0,
        encryptedDnsEnabled = dnsMode == "encrypted",
        fullTunnelEnabled = fullTunnelMode,
        strategyEvolutionEnabled = strategyEvolution,
    )

internal fun AppSettings.Builder.applyDetectionFixes(fixes: List<DetectionSuggestedFix>) {
    for (fix in fixes) {
        when (fix.id) {
            "tls_fingerprint" -> {
                tlsFingerprintProfile = "chrome_stable"
            }

            "entropy_padding" -> {
                entropyMode = EntropyModeBalanced
                entropyPaddingTargetPermil = EntropyPaddingTargetPermilValue
                shannonEntropyTargetPermil = ShannonEntropyTargetPermilValue
            }

            "encrypted_dns" -> {
                dnsMode = "encrypted"
            }

            "full_tunnel" -> {
                fullTunnelMode = true
            }

            "strategy_evolution" -> {
                strategyEvolution = true
                evolutionEpsilon = StrategyEvolutionRecommendedEpsilon
            }
        }
    }
}
