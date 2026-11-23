package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = StrategyEmitterTierSerializer::class)
enum class StrategyEmitterTier(
    val isProductionEmitter: Boolean,
) {
    PRODUCTION(true),
    LAB_DIAGNOSTICS_ONLY(false),
    UNAVAILABLE(false),
}

internal object StrategyEmitterTierSerializer : KSerializer<StrategyEmitterTier> {
    override val descriptor = PrimitiveSerialDescriptor("StrategyEmitterTier", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): StrategyEmitterTier =
        when (decoder.decodeString()) {
            StrategyEmitterTier.PRODUCTION.name -> StrategyEmitterTier.PRODUCTION
            StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY.name -> StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY
            else -> StrategyEmitterTier.UNAVAILABLE
        }

    override fun serialize(
        encoder: Encoder,
        value: StrategyEmitterTier,
    ) {
        encoder.encodeString(value.name)
    }
}

internal val defaultStrategyEmitterTier = StrategyEmitterTier.UNAVAILABLE
internal const val RequiresExternalEmitterWireName = "requiresExternalEmitter"

internal val StrategyEmitterTier.isExternalEmitter: Boolean
    get() = false

@Suppress("UNUSED_PARAMETER")
internal fun StrategyProbeCandidateSummary.hasSupportedEmitter(settings: AppSettings): Boolean =
    emitterTier.isProductionEmitter && !requiresExternalEmitter
