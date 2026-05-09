package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultEvolutionCooldownAfterFailures
import com.poyka.ripdpi.data.DefaultEvolutionCooldownMs
import com.poyka.ripdpi.data.DefaultEvolutionDecayHalfLifeMs
import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.DefaultEvolutionExperimentTtlMs
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveIpIdMode
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.entropyModeFromProto
import com.poyka.ripdpi.data.normalizeFakeTlsSource
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.toAdaptiveFallbackSettingsModel
import com.poyka.ripdpi.proto.AppSettings

internal fun buildChainConfig(settings: AppSettings): RipDpiChainConfig =
    RipDpiChainConfig(
        groupActivationFilter = settings.effectiveGroupActivationFilter(),
        tcpSteps = settings.effectiveTcpChainSteps(),
        udpSteps = settings.effectiveUdpChainSteps(),
        anyProtocol = settings.desyncAnyProtocol,
    )

@Suppress("LongMethod")
internal fun buildFakePacketConfig(settings: AppSettings): RipDpiFakePacketConfig =
    RipDpiFakePacketConfig(
        fakeTtl = settings.fakeTtl.takeIf { it > 0 } ?: 8,
        adaptiveFakeTtlEnabled = settings.adaptiveFakeTtlEnabled,
        adaptiveFakeTtlDelta = settings.effectiveAdaptiveFakeTtlDelta(),
        adaptiveFakeTtlMin = settings.effectiveAdaptiveFakeTtlMin(),
        adaptiveFakeTtlMax = settings.effectiveAdaptiveFakeTtlMax(),
        adaptiveFakeTtlFallback = settings.effectiveAdaptiveFakeTtlFallback(),
        fakeSni = settings.fakeSni.ifEmpty { DefaultFakeSni },
        httpFakeProfile = settings.effectiveHttpFakeProfile(),
        fakeTlsSource = normalizeFakeTlsSource(settings.fakeTlsSource),
        fakeTlsSecondaryProfile = settings.fakeTlsSecondaryProfile,
        fakeTcpTimestampEnabled = settings.fakeTcpTimestampEnabled,
        fakeTcpTimestampDeltaTicks = settings.fakeTcpTimestampDeltaTicks,
        fakeTlsUseOriginal = settings.fakeTlsUseOriginal,
        fakeTlsRandomize = settings.fakeTlsRandomize,
        fakeTlsDupSessionId = settings.fakeTlsDupSessionId,
        fakeTlsPadEncap = settings.fakeTlsPadEncap,
        fakeTlsSize = settings.fakeTlsSize,
        fakeTlsSniMode = settings.effectiveFakeTlsSniMode(),
        tlsFakeProfile = settings.effectiveTlsFakeProfile(),
        udpFakeProfile = settings.effectiveUdpFakeProfile(),
        fakeOffsetMarker = settings.effectiveFakeOffsetMarker(),
        oobChar = settings.oobData.firstOrNull() ?: 'a',
        dropSack = settings.dropSack,
        windowClamp = settings.windowClamp.takeIf { it > 0 },
        wsizeWindow = settings.wsizeWindow.takeIf { it > 0 },
        wsizeScale = settings.wsizeScale.takeIf { it >= -1 },
        stripTimestamps = settings.stripTimestamps,
        ipIdMode = settings.effectiveIpIdMode(),
        quicBindLowPort = settings.quicBindLowPort,
        quicMigrateAfterHandshake = settings.quicMigrateAfterHandshake,
        entropyMode = entropyModeFromProto(settings.entropyMode),
        entropyPaddingTargetPermil =
            settings.entropyPaddingTargetPermil.takeIf { it > 0 } ?: DefaultEntropyPaddingTargetPermil,
        entropyPaddingMax = settings.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        shannonEntropyTargetPermil =
            settings.shannonEntropyTargetPermil.takeIf { it > 0 } ?: DefaultShannonEntropyTargetPermil,
        tlsFingerprintProfile = normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile),
    )

internal fun buildAdaptiveFallbackConfig(settings: AppSettings): RipDpiAdaptiveFallbackConfig {
    val adaptive = settings.toAdaptiveFallbackSettingsModel()
    return RipDpiAdaptiveFallbackConfig(
        enabled = adaptive.enabled,
        torst = adaptive.torst,
        tlsErr = adaptive.tlsErr,
        httpRedirect = adaptive.httpRedirect,
        connectFailure = adaptive.connectFailure,
        autoSort = adaptive.autoSort,
        cacheTtlSeconds = adaptive.cacheTtlSeconds,
        cachePrefixV4 = adaptive.cachePrefixV4,
        strategyEvolution = settings.strategyEvolution,
        evolutionEpsilon = settings.evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
        evolutionExperimentTtlMs =
            settings.evolutionExperimentTtlMs.takeIf { it > 0 } ?: DefaultEvolutionExperimentTtlMs,
        evolutionDecayHalfLifeMs =
            settings.evolutionDecayHalfLifeMs.takeIf { it > 0 } ?: DefaultEvolutionDecayHalfLifeMs,
        evolutionCooldownAfterFailures =
            settings.evolutionCooldownAfterFailures.takeIf { it > 0 } ?: DefaultEvolutionCooldownAfterFailures,
        evolutionCooldownMs = settings.evolutionCooldownMs.takeIf { it > 0 } ?: DefaultEvolutionCooldownMs,
    )
}

internal fun buildParserEvasionConfig(settings: AppSettings): RipDpiParserEvasionConfig =
    RipDpiParserEvasionConfig(
        hostMixedCase = settings.hostMixedCase,
        domainMixedCase = settings.domainMixedCase,
        hostRemoveSpaces = settings.hostRemoveSpaces,
        httpMethodEol = settings.httpMethodEol,
        httpMethodSpace = settings.httpMethodSpace,
        httpUnixEol = settings.httpUnixEol,
        httpHostPad = settings.httpHostPad,
        httpHostExtraSpace = settings.httpHostExtraSpace,
        httpHostTab = settings.httpHostTab,
    )
