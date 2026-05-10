package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withQuicAdaptiveSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        quicInitialMode = settings.effectiveQuicInitialMode(),
        quicSupportV1 = settings.effectiveQuicSupportV1(),
        quicSupportV2 = settings.effectiveQuicSupportV2(),
        quicFakeProfile = settings.effectiveQuicFakeProfile(),
        quicFakeHost = settings.effectiveQuicFakeHost(),
        quicBindLowPort = settings.quicBindLowPort,
        quicMigrateAfterHandshake = settings.quicMigrateAfterHandshake,
        hostAutolearnEnabled = settings.hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(settings.hostAutolearnPenaltyTtlHours),
        hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(settings.hostAutolearnMaxHosts),
        networkStrategyMemoryEnabled = settings.networkStrategyMemoryEnabled,
        strategyEvolution = settings.strategyEvolution,
        evolutionEpsilon = settings.evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
        evolutionExperimentTtlMs =
            settings.evolutionExperimentTtlMs.takeIf { it > 0 } ?: DefaultEvolutionExperimentTtlMs,
        evolutionDecayHalfLifeMs =
            settings.evolutionDecayHalfLifeMs.takeIf { it > 0 } ?: DefaultEvolutionDecayHalfLifeMs,
        evolutionCooldownAfterFailures =
            settings.evolutionCooldownAfterFailures.takeIf { it > 0 } ?: DefaultEvolutionCooldownAfterFailures,
        evolutionCooldownMs = settings.evolutionCooldownMs.takeIf { it > 0 } ?: DefaultEvolutionCooldownMs,
        entropyPaddingTargetPermil = settings.entropyPaddingTargetPermil.coerceAtLeast(0),
        entropyPaddingMax = settings.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        entropyMode = entropyModeFromProto(settings.entropyMode),
        shannonEntropyTargetPermil = settings.shannonEntropyTargetPermil.coerceAtLeast(0),
        tlsFingerprintProfile = normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile),
        strategyPackChannel = normalizeStrategyPackChannel(settings.strategyPackChannel),
        strategyPackPinnedId = settings.strategyPackPinnedId,
        strategyPackPinnedVersion = settings.strategyPackPinnedVersion,
        strategyPackRefreshPolicy = normalizeStrategyPackRefreshPolicy(settings.strategyPackRefreshPolicy),
        strategyPackAllowRollbackOverride = settings.strategyPackAllowRollbackOverride,
        adaptiveFallbackEnabled = settings.adaptiveFallbackEnabled,
        adaptiveFallbackTorst = settings.adaptiveFallbackTorst,
        adaptiveFallbackTlsErr = settings.adaptiveFallbackTlsErr,
        adaptiveFallbackHttpRedirect = settings.adaptiveFallbackHttpRedirect,
        adaptiveFallbackConnectFailure = settings.adaptiveFallbackConnectFailure,
        adaptiveFallbackAutoSort = settings.adaptiveFallbackAutoSort,
        adaptiveFallbackCacheTtlSeconds =
            normalizeAdaptiveFallbackCacheTtlSeconds(settings.adaptiveFallbackCacheTtlSeconds),
        adaptiveFallbackCachePrefixV4 = normalizeAdaptiveFallbackCachePrefixV4(settings.adaptiveFallbackCachePrefixV4),
        wsTunnelEnabled = settings.wsTunnelEnabled,
        wsTunnelMode = settings.effectiveWsTunnelMode(),
    )

internal fun AppSettings.Builder.applyQuicAdaptiveSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setQuicInitialMode(snapshot.quicInitialMode)
        .setQuicSupportV1(snapshot.quicSupportV1)
        .setQuicSupportV2(snapshot.quicSupportV2)
        .setQuicFakeProfile(normalizeQuicFakeProfile(snapshot.quicFakeProfile))
        .setQuicFakeHost(normalizeQuicFakeHost(snapshot.quicFakeHost))
        .setQuicBindLowPort(snapshot.quicBindLowPort)
        .setQuicMigrateAfterHandshake(snapshot.quicMigrateAfterHandshake)
        .setHostAutolearnEnabled(snapshot.hostAutolearnEnabled)
        .setHostAutolearnPenaltyTtlHours(normalizeHostAutolearnPenaltyTtlHours(snapshot.hostAutolearnPenaltyTtlHours))
        .setHostAutolearnMaxHosts(normalizeHostAutolearnMaxHosts(snapshot.hostAutolearnMaxHosts))
        .setNetworkStrategyMemoryEnabled(snapshot.networkStrategyMemoryEnabled)
        .setStrategyEvolution(snapshot.strategyEvolution)
        .setEvolutionEpsilon(snapshot.evolutionEpsilon.coerceIn(0.0, 1.0))
        .setEvolutionExperimentTtlMs(snapshot.evolutionExperimentTtlMs.coerceAtLeast(0))
        .setEvolutionDecayHalfLifeMs(snapshot.evolutionDecayHalfLifeMs.coerceAtLeast(0))
        .setEvolutionCooldownAfterFailures(snapshot.evolutionCooldownAfterFailures.coerceAtLeast(0))
        .setEvolutionCooldownMs(snapshot.evolutionCooldownMs.coerceAtLeast(0))
        .setEntropyPaddingTargetPermil(snapshot.entropyPaddingTargetPermil.coerceAtLeast(0))
        .setEntropyPaddingMax(snapshot.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax)
        .setEntropyMode(entropyModeToProto(snapshot.entropyMode))
        .setShannonEntropyTargetPermil(snapshot.shannonEntropyTargetPermil.coerceAtLeast(0))
        .setTlsFingerprintProfile(normalizeTlsFingerprintProfile(snapshot.tlsFingerprintProfile))
        .setStrategyPackChannel(normalizeStrategyPackChannel(snapshot.strategyPackChannel))
        .setStrategyPackPinnedId(snapshot.strategyPackPinnedId)
        .setStrategyPackPinnedVersion(snapshot.strategyPackPinnedVersion)
        .setStrategyPackRefreshPolicy(normalizeStrategyPackRefreshPolicy(snapshot.strategyPackRefreshPolicy))
        .setStrategyPackAllowRollbackOverride(snapshot.strategyPackAllowRollbackOverride)
        .setAdaptiveFallbackEnabled(snapshot.adaptiveFallbackEnabled)
        .setAdaptiveFallbackTorst(snapshot.adaptiveFallbackTorst)
        .setAdaptiveFallbackTlsErr(snapshot.adaptiveFallbackTlsErr)
        .setAdaptiveFallbackHttpRedirect(snapshot.adaptiveFallbackHttpRedirect)
        .setAdaptiveFallbackConnectFailure(snapshot.adaptiveFallbackConnectFailure)
        .setAdaptiveFallbackAutoSort(snapshot.adaptiveFallbackAutoSort)
        .setAdaptiveFallbackCacheTtlSeconds(
            normalizeAdaptiveFallbackCacheTtlSeconds(snapshot.adaptiveFallbackCacheTtlSeconds),
        ).setAdaptiveFallbackCachePrefixV4(
            normalizeAdaptiveFallbackCachePrefixV4(snapshot.adaptiveFallbackCachePrefixV4),
        ).setWsTunnelEnabled(snapshot.wsTunnelEnabled)
        .setWsTunnelMode(snapshot.wsTunnelMode)

private fun AppSettings.effectiveWsTunnelMode(): String =
    wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" }
