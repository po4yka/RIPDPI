package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

@Serializable
internal data class AppSettingsRoutingSnapshot(
    val appRoutingPolicyMode: String = defaultSettings.appRoutingPolicyMode,
    val appRoutingEnabledPresetIds: List<String> = defaultSettings.appRoutingEnabledPresetIdsList,
    val antiCorrelationEnabled: Boolean = defaultSettings.antiCorrelationEnabled,
    val dhtMitigationMode: String = defaultSettings.dhtMitigationMode,
)
