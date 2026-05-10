package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsRoutingSnapshot(
    val appRoutingPolicyMode: String = defaultSettings.appRoutingPolicyMode,
    val appRoutingEnabledPresetIds: List<String> = defaultSettings.appRoutingEnabledPresetIdsList,
    val antiCorrelationEnabled: Boolean = defaultSettings.antiCorrelationEnabled,
    val dhtMitigationMode: String = defaultSettings.dhtMitigationMode,
)

internal fun JsonObjectBuilder.writeRoutingSnapshot(snapshot: AppSettingsRoutingSnapshot) {
    put("appRoutingPolicyMode", snapshot.appRoutingPolicyMode)
    putStringList("appRoutingEnabledPresetIds", snapshot.appRoutingEnabledPresetIds)
    put("antiCorrelationEnabled", snapshot.antiCorrelationEnabled)
    put("dhtMitigationMode", snapshot.dhtMitigationMode)
}

internal fun JsonObject.readRoutingSnapshot(defaults: AppSettingsRoutingSnapshot): AppSettingsRoutingSnapshot =
    AppSettingsRoutingSnapshot(
        appRoutingPolicyMode = stringValue("appRoutingPolicyMode", defaults.appRoutingPolicyMode),
        appRoutingEnabledPresetIds =
            stringListValue("appRoutingEnabledPresetIds", defaults.appRoutingEnabledPresetIds),
        antiCorrelationEnabled = booleanValue("antiCorrelationEnabled", defaults.antiCorrelationEnabled),
        dhtMitigationMode = stringValue("dhtMitigationMode", defaults.dhtMitigationMode),
    )
