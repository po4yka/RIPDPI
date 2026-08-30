package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Refuse to restore a ws-tunnel fake-SNI cover that was not explicitly
 * acknowledged. The cover disables TLS certificate verification, so a backup
 * (or any untrusted snapshot) that carries `fakeSni` without
 * `allowInsecureSni` is sanitised to an empty cover rather than silently
 * persisting the insecure config. Reuses [WsProfileImportValidator] so the
 * import-time and restore-time rules stay in lockstep.
 */
internal fun sanitizeRestoredWsTunnelFakeSni(
    fakeSni: String,
    allowInsecureSni: Boolean,
): String {
    if (fakeSni.isEmpty()) return fakeSni
    val profile =
        buildJsonObject {
            put("fake_sni", fakeSni)
            put("allow_insecure_sni", allowInsecureSni)
        }
    return if (WsProfileImportValidator.validate(profile).isEmpty()) fakeSni else ""
}

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withQuicAdaptiveSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        quicAdaptive =
            AppSettingsQuicAdaptiveSnapshot(
                quicInitialMode = settings.effectiveQuicInitialMode(),
                quicSupportV1 = settings.effectiveQuicSupportV1(),
                quicSupportV2 = settings.effectiveQuicSupportV2(),
                quicFakeProfile = settings.effectiveQuicFakeProfile(),
                quicFakeHost = settings.effectiveQuicFakeHost(),
                quicBindLowPort = settings.quicBindLowPort,
                quicMigrateAfterHandshake = settings.quicMigrateAfterHandshake,
                hostAutolearnEnabled = settings.hostAutolearnEnabled,
                hostAutolearnPenaltyTtlHours =
                    normalizeHostAutolearnPenaltyTtlHours(settings.hostAutolearnPenaltyTtlHours),
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
                adaptiveFallbackCachePrefixV4 =
                    normalizeAdaptiveFallbackCachePrefixV4(settings.adaptiveFallbackCachePrefixV4),
                wsTunnelEnabled = settings.wsTunnelEnabled,
                wsTunnelMode = settings.effectiveWsTunnelMode(),
                wsTunnelFakeSni = settings.wsTunnelFakeSni,
                wsTunnelAllowInsecureSni = settings.wsTunnelAllowInsecureSni,
                wsTunnelWorkerUrl = settings.wsTunnelWorkerUrl,
                wsTunnelWorkerCredentialRef = settings.wsTunnelWorkerCredentialRef,
            ),
    )

internal fun AppSettings.Builder.applyQuicAdaptiveSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder {
    val adaptive = snapshot.quicAdaptive
    return setQuicInitialMode(adaptive.quicInitialMode)
        .setQuicSupportV1(adaptive.quicSupportV1)
        .setQuicSupportV2(adaptive.quicSupportV2)
        .setQuicFakeProfile(normalizeQuicFakeProfile(adaptive.quicFakeProfile))
        .setQuicFakeHost(normalizeQuicFakeHost(adaptive.quicFakeHost))
        .setQuicBindLowPort(adaptive.quicBindLowPort)
        .setQuicMigrateAfterHandshake(adaptive.quicMigrateAfterHandshake)
        .setHostAutolearnEnabled(adaptive.hostAutolearnEnabled)
        .setHostAutolearnPenaltyTtlHours(normalizeHostAutolearnPenaltyTtlHours(adaptive.hostAutolearnPenaltyTtlHours))
        .setHostAutolearnMaxHosts(normalizeHostAutolearnMaxHosts(adaptive.hostAutolearnMaxHosts))
        .setNetworkStrategyMemoryEnabled(adaptive.networkStrategyMemoryEnabled)
        .setStrategyEvolution(adaptive.strategyEvolution)
        .setEvolutionEpsilon(adaptive.evolutionEpsilon.coerceIn(0.0, 1.0))
        .setEvolutionExperimentTtlMs(adaptive.evolutionExperimentTtlMs.coerceAtLeast(0))
        .setEvolutionDecayHalfLifeMs(adaptive.evolutionDecayHalfLifeMs.coerceAtLeast(0))
        .setEvolutionCooldownAfterFailures(adaptive.evolutionCooldownAfterFailures.coerceAtLeast(0))
        .setEvolutionCooldownMs(adaptive.evolutionCooldownMs.coerceAtLeast(0))
        .setEntropyPaddingTargetPermil(adaptive.entropyPaddingTargetPermil.coerceAtLeast(0))
        .setEntropyPaddingMax(adaptive.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax)
        .setEntropyMode(entropyModeToProto(adaptive.entropyMode))
        .setShannonEntropyTargetPermil(adaptive.shannonEntropyTargetPermil.coerceAtLeast(0))
        .setTlsFingerprintProfile(normalizeTlsFingerprintProfile(adaptive.tlsFingerprintProfile))
        .setStrategyPackChannel(normalizeStrategyPackChannel(adaptive.strategyPackChannel))
        .setStrategyPackPinnedId(adaptive.strategyPackPinnedId)
        .setStrategyPackPinnedVersion(adaptive.strategyPackPinnedVersion)
        .setStrategyPackRefreshPolicy(normalizeStrategyPackRefreshPolicy(adaptive.strategyPackRefreshPolicy))
        .setStrategyPackAllowRollbackOverride(adaptive.strategyPackAllowRollbackOverride)
        .setAdaptiveFallbackEnabled(adaptive.adaptiveFallbackEnabled)
        .setAdaptiveFallbackTorst(adaptive.adaptiveFallbackTorst)
        .setAdaptiveFallbackTlsErr(adaptive.adaptiveFallbackTlsErr)
        .setAdaptiveFallbackHttpRedirect(adaptive.adaptiveFallbackHttpRedirect)
        .setAdaptiveFallbackConnectFailure(adaptive.adaptiveFallbackConnectFailure)
        .setAdaptiveFallbackAutoSort(adaptive.adaptiveFallbackAutoSort)
        .setAdaptiveFallbackCacheTtlSeconds(
            normalizeAdaptiveFallbackCacheTtlSeconds(adaptive.adaptiveFallbackCacheTtlSeconds),
        ).setAdaptiveFallbackCachePrefixV4(
            normalizeAdaptiveFallbackCachePrefixV4(adaptive.adaptiveFallbackCachePrefixV4),
        ).setWsTunnelEnabled(adaptive.wsTunnelEnabled)
        .setWsTunnelMode(adaptive.wsTunnelMode)
        .setWsTunnelFakeSni(
            sanitizeRestoredWsTunnelFakeSni(adaptive.wsTunnelFakeSni, adaptive.wsTunnelAllowInsecureSni),
        ).setWsTunnelAllowInsecureSni(adaptive.wsTunnelAllowInsecureSni)
        .setWsTunnelWorkerUrl(adaptive.wsTunnelWorkerUrl)
        .setWsTunnelWorkerCredentialRef(adaptive.wsTunnelWorkerCredentialRef)
}

private fun AppSettings.effectiveWsTunnelMode(): String =
    wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" }
