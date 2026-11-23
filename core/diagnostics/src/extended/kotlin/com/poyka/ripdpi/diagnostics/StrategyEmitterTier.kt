package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.externalPacketOperationsEnabled
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.Serializable

@Serializable
enum class StrategyEmitterTier(
    val isProductionEmitter: Boolean,
) {
    NON_ROOT_PRODUCTION(true),
    ROOTED_PRODUCTION(true),
    LAB_DIAGNOSTICS_ONLY(false),
}

internal val defaultStrategyEmitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION
internal const val RequiresExternalEmitterWireName = "exactEmitterRequiresRoot"

internal val StrategyEmitterTier.isExternalEmitter: Boolean
    get() = this == StrategyEmitterTier.ROOTED_PRODUCTION

internal fun StrategyProbeCandidateSummary.hasSupportedEmitter(settings: AppSettings): Boolean {
    val needsExternalEmitter = requiresExternalEmitter || emitterTier.isExternalEmitter
    return emitterTier.isProductionEmitter &&
        (!requiresExternalEmitter || !emitterDowngraded) &&
        (!needsExternalEmitter || settings.externalPacketOperationsEnabled())
}
