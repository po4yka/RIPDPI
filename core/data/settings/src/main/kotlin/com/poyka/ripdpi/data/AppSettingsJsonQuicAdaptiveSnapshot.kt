package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsQuicAdaptiveSnapshot(
    val quicInitialMode: String = defaultSettings.quicInitialMode,
    val quicSupportV1: Boolean = defaultSettings.quicSupportV1,
    val quicSupportV2: Boolean = defaultSettings.quicSupportV2,
    val quicFakeProfile: String = defaultSettings.quicFakeProfile,
    val quicFakeHost: String = defaultSettings.quicFakeHost,
    val quicBindLowPort: Boolean = defaultSettings.quicBindLowPort,
    val quicMigrateAfterHandshake: Boolean = defaultSettings.quicMigrateAfterHandshake,
    val hostAutolearnEnabled: Boolean = defaultSettings.hostAutolearnEnabled,
    val hostAutolearnPenaltyTtlHours: Int = defaultSettings.hostAutolearnPenaltyTtlHours,
    val hostAutolearnMaxHosts: Int = defaultSettings.hostAutolearnMaxHosts,
    val networkStrategyMemoryEnabled: Boolean = defaultSettings.networkStrategyMemoryEnabled,
    val strategyEvolution: Boolean = defaultSettings.strategyEvolution,
    val evolutionEpsilon: Double = defaultSettings.evolutionEpsilon,
    val evolutionExperimentTtlMs: Long = DefaultEvolutionExperimentTtlMs,
    val evolutionDecayHalfLifeMs: Long = DefaultEvolutionDecayHalfLifeMs,
    val evolutionCooldownAfterFailures: Int = DefaultEvolutionCooldownAfterFailures,
    val evolutionCooldownMs: Long = DefaultEvolutionCooldownMs,
    val entropyPaddingTargetPermil: Int = defaultSettings.entropyPaddingTargetPermil,
    val entropyPaddingMax: Int = defaultSettings.entropyPaddingMax,
    val entropyMode: String = entropyModeFromProto(defaultSettings.entropyMode),
    val shannonEntropyTargetPermil: Int = defaultSettings.shannonEntropyTargetPermil,
    val tlsFingerprintProfile: String = normalizeTlsFingerprintProfile(defaultSettings.tlsFingerprintProfile),
    val strategyPackChannel: String = defaultSettings.strategyPackChannel,
    val strategyPackPinnedId: String = defaultSettings.strategyPackPinnedId,
    val strategyPackPinnedVersion: String = defaultSettings.strategyPackPinnedVersion,
    val strategyPackRefreshPolicy: String = defaultSettings.strategyPackRefreshPolicy,
    val strategyPackAllowRollbackOverride: Boolean = defaultSettings.strategyPackAllowRollbackOverride,
    val adaptiveFallbackEnabled: Boolean = defaultSettings.adaptiveFallbackEnabled,
    val adaptiveFallbackTorst: Boolean = defaultSettings.adaptiveFallbackTorst,
    val adaptiveFallbackTlsErr: Boolean = defaultSettings.adaptiveFallbackTlsErr,
    val adaptiveFallbackHttpRedirect: Boolean = defaultSettings.adaptiveFallbackHttpRedirect,
    val adaptiveFallbackConnectFailure: Boolean = defaultSettings.adaptiveFallbackConnectFailure,
    val adaptiveFallbackAutoSort: Boolean = defaultSettings.adaptiveFallbackAutoSort,
    val adaptiveFallbackCacheTtlSeconds: Int = defaultSettings.adaptiveFallbackCacheTtlSeconds,
    val adaptiveFallbackCachePrefixV4: Int = defaultSettings.adaptiveFallbackCachePrefixV4,
    val wsTunnelEnabled: Boolean = defaultSettings.wsTunnelEnabled,
    val wsTunnelMode: String = defaultSettings.wsTunnelMode,
    val wsTunnelFakeSni: String = defaultSettings.wsTunnelFakeSni,
    val wsTunnelAllowInsecureSni: Boolean = defaultSettings.wsTunnelAllowInsecureSni,
    val wsTunnelWorkerUrl: String = defaultSettings.wsTunnelWorkerUrl,
    val wsTunnelWorkerCredentialRef: String = defaultSettings.wsTunnelWorkerCredentialRef,
)

internal fun JsonObjectBuilder.writeQuicAdaptiveSnapshot(snapshot: AppSettingsQuicAdaptiveSnapshot) {
    put("quicInitialMode", snapshot.quicInitialMode)
    put("quicSupportV1", snapshot.quicSupportV1)
    put("quicSupportV2", snapshot.quicSupportV2)
    put("quicFakeProfile", snapshot.quicFakeProfile)
    put("quicFakeHost", snapshot.quicFakeHost)
    put("quicBindLowPort", snapshot.quicBindLowPort)
    put("quicMigrateAfterHandshake", snapshot.quicMigrateAfterHandshake)
    put("hostAutolearnEnabled", snapshot.hostAutolearnEnabled)
    put("hostAutolearnPenaltyTtlHours", snapshot.hostAutolearnPenaltyTtlHours)
    put("hostAutolearnMaxHosts", snapshot.hostAutolearnMaxHosts)
    put("networkStrategyMemoryEnabled", snapshot.networkStrategyMemoryEnabled)
    put("strategyEvolution", snapshot.strategyEvolution)
    put("evolutionEpsilon", snapshot.evolutionEpsilon)
    put("evolutionExperimentTtlMs", snapshot.evolutionExperimentTtlMs)
    put("evolutionDecayHalfLifeMs", snapshot.evolutionDecayHalfLifeMs)
    put("evolutionCooldownAfterFailures", snapshot.evolutionCooldownAfterFailures)
    put("evolutionCooldownMs", snapshot.evolutionCooldownMs)
    put("entropyPaddingTargetPermil", snapshot.entropyPaddingTargetPermil)
    put("entropyPaddingMax", snapshot.entropyPaddingMax)
    put("entropyMode", snapshot.entropyMode)
    put("shannonEntropyTargetPermil", snapshot.shannonEntropyTargetPermil)
    put("tlsFingerprintProfile", snapshot.tlsFingerprintProfile)
    put("strategyPackChannel", snapshot.strategyPackChannel)
    put("strategyPackPinnedId", snapshot.strategyPackPinnedId)
    put("strategyPackPinnedVersion", snapshot.strategyPackPinnedVersion)
    put("strategyPackRefreshPolicy", snapshot.strategyPackRefreshPolicy)
    put("strategyPackAllowRollbackOverride", snapshot.strategyPackAllowRollbackOverride)
    put("adaptiveFallbackEnabled", snapshot.adaptiveFallbackEnabled)
    put("adaptiveFallbackTorst", snapshot.adaptiveFallbackTorst)
    put("adaptiveFallbackTlsErr", snapshot.adaptiveFallbackTlsErr)
    put("adaptiveFallbackHttpRedirect", snapshot.adaptiveFallbackHttpRedirect)
    put("adaptiveFallbackConnectFailure", snapshot.adaptiveFallbackConnectFailure)
    put("adaptiveFallbackAutoSort", snapshot.adaptiveFallbackAutoSort)
    put("adaptiveFallbackCacheTtlSeconds", snapshot.adaptiveFallbackCacheTtlSeconds)
    put("adaptiveFallbackCachePrefixV4", snapshot.adaptiveFallbackCachePrefixV4)
    put("wsTunnelEnabled", snapshot.wsTunnelEnabled)
    put("wsTunnelMode", snapshot.wsTunnelMode)
    put("wsTunnelFakeSni", snapshot.wsTunnelFakeSni)
    put("wsTunnelAllowInsecureSni", snapshot.wsTunnelAllowInsecureSni)
    put("wsTunnelWorkerUrl", snapshot.wsTunnelWorkerUrl)
    put("wsTunnelWorkerCredentialRef", snapshot.wsTunnelWorkerCredentialRef)
}

internal fun JsonObject.readQuicAdaptiveSnapshot(
    defaults: AppSettingsQuicAdaptiveSnapshot,
): AppSettingsQuicAdaptiveSnapshot =
    AppSettingsQuicAdaptiveSnapshot(
        quicInitialMode = stringValue("quicInitialMode", defaults.quicInitialMode),
        quicSupportV1 = booleanValue("quicSupportV1", defaults.quicSupportV1),
        quicSupportV2 = booleanValue("quicSupportV2", defaults.quicSupportV2),
        quicFakeProfile = stringValue("quicFakeProfile", defaults.quicFakeProfile),
        quicFakeHost = stringValue("quicFakeHost", defaults.quicFakeHost),
        quicBindLowPort = booleanValue("quicBindLowPort", defaults.quicBindLowPort),
        quicMigrateAfterHandshake = booleanValue("quicMigrateAfterHandshake", defaults.quicMigrateAfterHandshake),
        hostAutolearnEnabled = booleanValue("hostAutolearnEnabled", defaults.hostAutolearnEnabled),
        hostAutolearnPenaltyTtlHours = intValue("hostAutolearnPenaltyTtlHours", defaults.hostAutolearnPenaltyTtlHours),
        hostAutolearnMaxHosts = intValue("hostAutolearnMaxHosts", defaults.hostAutolearnMaxHosts),
        networkStrategyMemoryEnabled =
            booleanValue(
                "networkStrategyMemoryEnabled",
                defaults.networkStrategyMemoryEnabled,
            ),
        strategyEvolution = booleanValue("strategyEvolution", defaults.strategyEvolution),
        evolutionEpsilon = doubleValue("evolutionEpsilon", defaults.evolutionEpsilon),
        evolutionExperimentTtlMs = longValue("evolutionExperimentTtlMs", defaults.evolutionExperimentTtlMs),
        evolutionDecayHalfLifeMs = longValue("evolutionDecayHalfLifeMs", defaults.evolutionDecayHalfLifeMs),
        evolutionCooldownAfterFailures =
            intValue("evolutionCooldownAfterFailures", defaults.evolutionCooldownAfterFailures),
        evolutionCooldownMs = longValue("evolutionCooldownMs", defaults.evolutionCooldownMs),
        entropyPaddingTargetPermil = intValue("entropyPaddingTargetPermil", defaults.entropyPaddingTargetPermil),
        entropyPaddingMax = intValue("entropyPaddingMax", defaults.entropyPaddingMax),
        entropyMode = stringValue("entropyMode", defaults.entropyMode),
        shannonEntropyTargetPermil = intValue("shannonEntropyTargetPermil", defaults.shannonEntropyTargetPermil),
        tlsFingerprintProfile = stringValue("tlsFingerprintProfile", defaults.tlsFingerprintProfile),
        strategyPackChannel = stringValue("strategyPackChannel", defaults.strategyPackChannel),
        strategyPackPinnedId = stringValue("strategyPackPinnedId", defaults.strategyPackPinnedId),
        strategyPackPinnedVersion = stringValue("strategyPackPinnedVersion", defaults.strategyPackPinnedVersion),
        strategyPackRefreshPolicy = stringValue("strategyPackRefreshPolicy", defaults.strategyPackRefreshPolicy),
        strategyPackAllowRollbackOverride =
            booleanValue("strategyPackAllowRollbackOverride", defaults.strategyPackAllowRollbackOverride),
        adaptiveFallbackEnabled = booleanValue("adaptiveFallbackEnabled", defaults.adaptiveFallbackEnabled),
        adaptiveFallbackTorst = booleanValue("adaptiveFallbackTorst", defaults.adaptiveFallbackTorst),
        adaptiveFallbackTlsErr = booleanValue("adaptiveFallbackTlsErr", defaults.adaptiveFallbackTlsErr),
        adaptiveFallbackHttpRedirect =
            booleanValue("adaptiveFallbackHttpRedirect", defaults.adaptiveFallbackHttpRedirect),
        adaptiveFallbackConnectFailure =
            booleanValue("adaptiveFallbackConnectFailure", defaults.adaptiveFallbackConnectFailure),
        adaptiveFallbackAutoSort = booleanValue("adaptiveFallbackAutoSort", defaults.adaptiveFallbackAutoSort),
        adaptiveFallbackCacheTtlSeconds =
            intValue("adaptiveFallbackCacheTtlSeconds", defaults.adaptiveFallbackCacheTtlSeconds),
        adaptiveFallbackCachePrefixV4 =
            intValue("adaptiveFallbackCachePrefixV4", defaults.adaptiveFallbackCachePrefixV4),
        wsTunnelEnabled = booleanValue("wsTunnelEnabled", defaults.wsTunnelEnabled),
        wsTunnelMode = stringValue("wsTunnelMode", defaults.wsTunnelMode),
        wsTunnelFakeSni = stringValue("wsTunnelFakeSni", defaults.wsTunnelFakeSni),
        wsTunnelAllowInsecureSni = booleanValue("wsTunnelAllowInsecureSni", defaults.wsTunnelAllowInsecureSni),
        wsTunnelWorkerUrl = stringValue("wsTunnelWorkerUrl", defaults.wsTunnelWorkerUrl),
        wsTunnelWorkerCredentialRef =
            stringValue("wsTunnelWorkerCredentialRef", defaults.wsTunnelWorkerCredentialRef),
    )
