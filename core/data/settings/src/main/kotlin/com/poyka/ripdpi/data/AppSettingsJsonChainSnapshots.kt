package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

@Serializable
internal data class AppSettingsTcpChainSnapshot(
    val kind: String,
    val marker: String,
    val midhostMarker: String? = null,
    val fakeHostTemplate: String? = null,
    val overlapSize: Int? = null,
    val fakeMode: String? = null,
    val fragmentCount: Int = 0,
    val minFragmentSize: Int = 0,
    val maxFragmentSize: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
    val ipv6ExtensionProfile: String = StrategyIpv6ExtensionProfileNone,
    val tcpFlagsSet: String? = null,
    val tcpFlagsUnset: String? = null,
    val tcpFlagsOrigSet: String? = null,
    val tcpFlagsOrigUnset: String? = null,
)

@Serializable
internal data class AppSettingsUdpChainSnapshot(
    val kind: String,
    val count: Int,
    val splitBytes: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
    val ipv6ExtensionProfile: String = StrategyIpv6ExtensionProfileNone,
)

internal data class AppSettingsChainSnapshot(
    val tcpChainSteps: List<AppSettingsTcpChainSnapshot> = emptyList(),
    val udpChainSteps: List<AppSettingsUdpChainSnapshot> = emptyList(),
)

internal fun JsonObjectBuilder.writeChainSnapshot(snapshot: AppSettingsChainSnapshot) {
    putSerializable("tcpChainSteps", snapshot.tcpChainSteps, ListSerializer(AppSettingsTcpChainSnapshot.serializer()))
    putSerializable("udpChainSteps", snapshot.udpChainSteps, ListSerializer(AppSettingsUdpChainSnapshot.serializer()))
}

internal fun JsonObject.readChainSnapshot(defaults: AppSettingsChainSnapshot): AppSettingsChainSnapshot =
    AppSettingsChainSnapshot(
        tcpChainSteps =
            serializableValue(
                "tcpChainSteps",
                defaults.tcpChainSteps,
                ListSerializer(AppSettingsTcpChainSnapshot.serializer()),
            ),
        udpChainSteps =
            serializableValue(
                "udpChainSteps",
                defaults.udpChainSteps,
                ListSerializer(AppSettingsUdpChainSnapshot.serializer()),
            ),
    )
