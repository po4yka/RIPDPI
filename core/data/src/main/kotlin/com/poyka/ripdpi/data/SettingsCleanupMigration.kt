package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val CurrentSettingsMigrationLevel = 1
const val LegacyDefaultDesyncMethod = "disorder"

fun canonicalDefaultTcpChainSteps(): List<TcpChainStepModel> =
    listOf(
        TcpChainStepModel(
            kind = TcpChainStepKind.Split,
            marker = CanonicalDefaultSplitMarker,
        ),
    )

fun AppSettings.Builder.setCanonicalDefaultStrategyChains(): AppSettings.Builder =
    setStrategyChains(canonicalDefaultTcpChainSteps(), emptyList())

fun AppSettings.applySettingsCleanupMigration(): AppSettings {
    if (settingsMigrationLevel >= CurrentSettingsMigrationLevel) {
        return this
    }

    val builder = toBuilder()
    if (enableCmdSettings) {
        return builder
            .setSettingsMigrationLevel(CurrentSettingsMigrationLevel)
            .build()
    }

    val requiresChainCanonicalization = tcpChainStepsCount == 0 || udpChainStepsCount == 0
    if (!requiresChainCanonicalization) {
        return builder
            .setSettingsMigrationLevel(CurrentSettingsMigrationLevel)
            .build()
    }

    val migratedTcpSteps =
        if (matchesLegacyDefaultDisorderProfileForCleanup()) {
            canonicalDefaultTcpChainSteps()
        } else {
            effectiveTcpChainSteps()
        }

    return builder
        .setStrategyChains(
            tcpSteps = migratedTcpSteps,
            udpSteps = effectiveUdpChainSteps(),
        ).setSettingsMigrationLevel(CurrentSettingsMigrationLevel)
        .build()
}

fun AppSettings.matchesLegacyDefaultDisorderProfileForCleanup(): Boolean =
    !enableCmdSettings &&
        tcpChainStepsCount == 0 &&
        udpChainStepsCount == 0 &&
        effectiveTcpChainSteps() == legacyDefaultDisorderTcpChainSteps() &&
        effectiveUdpChainSteps().isEmpty() &&
        !tlsrecEnabled &&
        defaultTtl == 0 &&
        !customTtl &&
        desyncHttp &&
        desyncHttps &&
        !desyncUdp &&
        effectiveGroupActivationFilter().isEmpty &&
        windowClamp == 0 &&
        !stripTimestamps

private fun legacyDefaultDisorderTcpChainSteps(): List<TcpChainStepModel> =
    listOf(
        TcpChainStepModel(
            kind = TcpChainStepKind.Disorder,
            marker = DefaultSplitMarker,
        ),
    )
