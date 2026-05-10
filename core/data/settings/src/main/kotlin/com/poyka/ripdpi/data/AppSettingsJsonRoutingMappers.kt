package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettingsSnapshot.withRoutingSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        appRoutingPolicyMode = normalizeAppRoutingPolicyMode(settings.appRoutingPolicyMode),
        appRoutingEnabledPresetIds = settings.effectiveAppRoutingEnabledPresetIds(),
        antiCorrelationEnabled = settings.antiCorrelationEnabled,
        dhtMitigationMode = normalizeDhtMitigationMode(settings.dhtMitigationMode),
    )

internal fun AppSettings.Builder.applyRoutingSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setAppRoutingPolicyMode(normalizeAppRoutingPolicyMode(snapshot.appRoutingPolicyMode))
        .clearAppRoutingEnabledPresetIds()
        .addAllAppRoutingEnabledPresetIds(
            snapshot.appRoutingEnabledPresetIds
                .map(String::trim)
                .filter(String::isNotEmpty),
        ).setAntiCorrelationEnabled(snapshot.antiCorrelationEnabled)
        .setDhtMitigationMode(normalizeDhtMitigationMode(snapshot.dhtMitigationMode))
        .setGroupActivationFilterCompat(normalizeActivationFilter(snapshot.groupActivationFilter))
