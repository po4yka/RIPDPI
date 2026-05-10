package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettingsSnapshot.withRoutingSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        routing =
            AppSettingsRoutingSnapshot(
                appRoutingPolicyMode = normalizeAppRoutingPolicyMode(settings.appRoutingPolicyMode),
                appRoutingEnabledPresetIds = settings.effectiveAppRoutingEnabledPresetIds(),
                antiCorrelationEnabled = settings.antiCorrelationEnabled,
                dhtMitigationMode = normalizeDhtMitigationMode(settings.dhtMitigationMode),
            ),
    )

internal fun AppSettings.Builder.applyRoutingSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder {
    val routing = snapshot.routing
    return setAppRoutingPolicyMode(normalizeAppRoutingPolicyMode(routing.appRoutingPolicyMode))
        .clearAppRoutingEnabledPresetIds()
        .addAllAppRoutingEnabledPresetIds(
            routing.appRoutingEnabledPresetIds
                .map(String::trim)
                .filter(String::isNotEmpty),
        ).setAntiCorrelationEnabled(routing.antiCorrelationEnabled)
        .setDhtMitigationMode(normalizeDhtMitigationMode(routing.dhtMitigationMode))
        .setGroupActivationFilterCompat(normalizeActivationFilter(snapshot.proxyDesync.groupActivationFilter))
}
