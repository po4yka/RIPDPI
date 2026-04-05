package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val StrategyPackSignatureAlgorithmSha256WithEcdsa = "SHA256withECDSA"
const val DefaultStrategyPackSigningKeyId = "ripdpi-prod-p256"

data class StrategyPackSettingsModel(
    val channel: String = DefaultStrategyPackChannel,
    val pinnedPackId: String = DefaultStrategyPackPinnedId,
    val pinnedPackVersion: String = DefaultStrategyPackPinnedVersion,
    val refreshPolicy: String = DefaultStrategyPackRefreshPolicy,
)

fun AppSettings.toStrategyPackSettingsModel(): StrategyPackSettingsModel =
    StrategyPackSettingsModel(
        channel = normalizeStrategyPackChannel(strategyPackChannel),
        pinnedPackId = strategyPackPinnedId,
        pinnedPackVersion = strategyPackPinnedVersion,
        refreshPolicy = normalizeStrategyPackRefreshPolicy(strategyPackRefreshPolicy),
    )
