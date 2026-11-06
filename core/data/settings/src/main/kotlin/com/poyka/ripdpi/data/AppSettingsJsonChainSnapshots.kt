package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

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

@Serializable
internal data class AppSettingsChainSnapshot(
    val tcpChainSteps: List<AppSettingsTcpChainSnapshot> = emptyList(),
    val udpChainSteps: List<AppSettingsUdpChainSnapshot> = emptyList(),
)
