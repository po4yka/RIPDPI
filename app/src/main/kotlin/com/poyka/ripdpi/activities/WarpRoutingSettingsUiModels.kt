package com.poyka.ripdpi.activities

import androidx.compose.runtime.Stable
import com.poyka.ripdpi.data.AppRoutingPolicyModePrompt
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultWarpManualEndpointPort
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.DefaultWarpScannerMaxRttMs
import com.poyka.ripdpi.data.DefaultWarpScannerParallelism
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpAmneziaPresetCustom
import com.poyka.ripdpi.data.WarpEndpointSelectionAutomatic
import com.poyka.ripdpi.data.WarpEndpointSelectionManual
import com.poyka.ripdpi.data.WarpRouteModeOff
import com.poyka.ripdpi.data.WarpRouteModeRules
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpSetupStateNotConfigured
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class HostAutolearnUiState(
    val hostAutolearnEnabled: Boolean = false,
    val hostAutolearnPenaltyTtlHours: Int = DefaultHostAutolearnPenaltyTtlHours,
    val hostAutolearnMaxHosts: Int = DefaultHostAutolearnMaxHosts,
    val networkStrategyMemoryEnabled: Boolean = false,
    val wsTunnelMode: String = "off",
    val wsTunnelAllowInsecureSni: Boolean = false,
    val wsTunnelWorkerUrl: String = "",
    val wsTunnelWorkerCredentialRef: String = "",
    val rememberedNetworkCount: Int = 0,
    val hostAutolearnRuntimeEnabled: Boolean = false,
    val hostAutolearnStorePresent: Boolean = false,
    val hostAutolearnLearnedHostCount: Int = 0,
    val hostAutolearnPenalizedHostCount: Int = 0,
    val hostAutolearnBlockedHostCount: Int = 0,
    val hostAutolearnLastBlockSignal: String? = null,
    val hostAutolearnLastBlockProvider: String? = null,
    val hostAutolearnLastHost: String? = null,
    val hostAutolearnLastGroup: Int? = null,
    val hostAutolearnLastAction: String? = null,
) {
    val wsTunnelWorkerConfigured: Boolean
        get() = wsTunnelWorkerUrl.isNotBlank() && wsTunnelWorkerCredentialRef.isNotBlank()
}

@Stable
data class WarpUiState(
    val enabled: Boolean = false,
    val routeMode: String = WarpRouteModeOff,
    val routeHosts: String = "",
    val builtInRulesEnabled: Boolean = true,
    val profileId: String = DefaultWarpProfileId,
    val accountKind: String = WarpAccountKindConsumerFree,
    val zeroTrustOrg: String = "",
    val setupState: String = WarpSetupStateNotConfigured,
    val lastScannerMode: String = WarpScannerModeAutomatic,
    val endpointSelectionMode: String = WarpEndpointSelectionAutomatic,
    val manualEndpointHost: String = "",
    val manualEndpointIpv4: String = "",
    val manualEndpointIpv6: String = "",
    val manualEndpointPort: Int = DefaultWarpManualEndpointPort,
    val scannerAvailable: Boolean = false,
    val scannerEnabled: Boolean = true,
    val scannerParallelism: Int = DefaultWarpScannerParallelism,
    val scannerMaxRttMs: Int = DefaultWarpScannerMaxRttMs,
    val amneziaPreset: String = "off",
    val amneziaEnabled: Boolean = false,
    val amneziaJc: Int = 0,
    val amneziaJmin: Int = 0,
    val amneziaJmax: Int = 0,
    val amneziaH1: Long = 0L,
    val amneziaH2: Long = 0L,
    val amneziaH3: Long = 0L,
    val amneziaH4: Long = 0L,
    val amneziaS1: Int = 0,
    val amneziaS2: Int = 0,
    val amneziaS3: Int = 0,
    val amneziaS4: Int = 0,
    val amneziaSuggestedPresetId: String = "",
    val amneziaSuggestedPresetLabel: String = "",
) {
    val routeRulesEnabled: Boolean
        get() = enabled && routeMode == WarpRouteModeRules

    val manualEndpointEnabled: Boolean
        get() = enabled && endpointSelectionMode == WarpEndpointSelectionManual

    val scannerControlsEnabled: Boolean
        get() = scannerAvailable && enabled && scannerEnabled

    val amneziaControlsEnabled: Boolean
        get() = enabled && amneziaPreset == WarpAmneziaPresetCustom && amneziaEnabled

    val hasManualEndpointOverride: Boolean
        get() =
            manualEndpointHost.isNotBlank() ||
                manualEndpointIpv4.isNotBlank() ||
                manualEndpointIpv6.isNotBlank() ||
                manualEndpointPort != DefaultWarpManualEndpointPort

    val hasZeroTrustOrganization: Boolean
        get() = zeroTrustOrg.isNotBlank()

    val hasSuggestedAmneziaPreset: Boolean
        get() = amneziaSuggestedPresetId.isNotBlank() && amneziaSuggestedPresetLabel.isNotBlank()
}

@Stable
data class RoutingProtectionDetectedAppUiState(
    val packageName: String,
    val presetTitle: String,
    val detectionMethod: String,
    val fixCoverage: String,
    val vpnDetection: Boolean = false,
    val severity: String = "none",
)

@Stable
data class RoutingProtectionPresetUiState(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val matchedPackages: ImmutableList<String> = persistentListOf(),
    val detectionMethod: String = "",
    val fixCoverage: String = "",
    val limitations: String = "",
    val confirmedDetectorCount: Int = 0,
)

@Stable
data class RoutingProtectionSuggestionUiState(
    val id: String,
    val title: String,
    val body: String,
)

@Stable
data class RoutingProtectionUiState(
    val policyMode: String = AppRoutingPolicyModePrompt,
    val enabledPresetIds: ImmutableList<String> = persistentListOf(),
    val antiCorrelationEnabled: Boolean = false,
    val dhtMitigationMode: String = DhtMitigationModeOff,
    val fullTunnelMode: Boolean = false,
    val presets: ImmutableList<RoutingProtectionPresetUiState> = persistentListOf(),
    val detectedApps: ImmutableList<RoutingProtectionDetectedAppUiState> = persistentListOf(),
    val suggestions: ImmutableList<RoutingProtectionSuggestionUiState> = persistentListOf(),
    val transportVpnDisclosure: String =
        "Application exclusion does not hide TRANSPORT_VPN checks made by the app itself.",
) {
    val detectedAppCount: Int
        get() = detectedApps.size

    val enabledPresetCount: Int
        get() = enabledPresetIds.size

    val hasDetectedRiskyApps: Boolean
        get() = detectedApps.isNotEmpty()
}
