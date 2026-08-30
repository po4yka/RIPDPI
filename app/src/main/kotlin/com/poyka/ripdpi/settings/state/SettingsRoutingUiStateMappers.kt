package com.poyka.ripdpi.settings.state

import com.poyka.ripdpi.activities.HostAutolearnUiState
import com.poyka.ripdpi.activities.RoutingProtectionDetectedAppUiState
import com.poyka.ripdpi.activities.RoutingProtectionPresetUiState
import com.poyka.ripdpi.activities.RoutingProtectionSuggestionUiState
import com.poyka.ripdpi.activities.RoutingProtectionUiState
import com.poyka.ripdpi.activities.WarpUiState
import com.poyka.ripdpi.data.DhtMitigationModeOff
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.data.normalizeAppRoutingPolicyMode
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.toWarpSettingsModel
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import kotlinx.collections.immutable.toImmutableList

internal fun AppSettings.buildAutolearnUiState(
    proxyTelemetry: NativeRuntimeSnapshot,
    hostAutolearnStorePresent: Boolean,
    rememberedNetworkCount: Int,
): HostAutolearnUiState =
    HostAutolearnUiState(
        hostAutolearnEnabled = hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours),
        hostAutolearnMaxHosts = normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts),
        networkStrategyMemoryEnabled = networkStrategyMemoryEnabled,
        wsTunnelMode = wsTunnelMode.ifEmpty { if (wsTunnelEnabled) "always" else "off" },
        wsTunnelAllowInsecureSni = wsTunnelAllowInsecureSni,
        wsTunnelWorkerUrl = wsTunnelWorkerUrl,
        wsTunnelWorkerCredentialRef = wsTunnelWorkerCredentialRef,
        rememberedNetworkCount = rememberedNetworkCount,
        hostAutolearnRuntimeEnabled = proxyTelemetry.autolearnEnabled,
        hostAutolearnStorePresent = hostAutolearnStorePresent,
        hostAutolearnLearnedHostCount = proxyTelemetry.learnedHostCount,
        hostAutolearnPenalizedHostCount = proxyTelemetry.penalizedHostCount,
        hostAutolearnBlockedHostCount = proxyTelemetry.blockedHostCount,
        hostAutolearnLastBlockSignal = proxyTelemetry.lastBlockSignal,
        hostAutolearnLastBlockProvider = proxyTelemetry.lastBlockProvider,
        hostAutolearnLastHost = proxyTelemetry.lastAutolearnHost,
        hostAutolearnLastGroup = proxyTelemetry.lastAutolearnGroup,
        hostAutolearnLastAction = proxyTelemetry.lastAutolearnAction,
    )

internal fun AppSettings.buildWarpUiState(
    suggestedAmneziaPresetId: String = "",
    suggestedAmneziaPresetLabel: String = "",
): WarpUiState {
    val warp = toWarpSettingsModel()
    return WarpUiState(
        enabled = warp.enabled,
        routeMode = warp.routeMode,
        routeHosts = warp.routeHosts,
        builtInRulesEnabled = warp.builtInRulesEnabled,
        profileId = warp.profile.profileId,
        accountKind = warp.profile.accountKind,
        zeroTrustOrg = warp.profile.zeroTrustOrg,
        setupState = warp.profile.setupState,
        lastScannerMode = warp.profile.lastScannerMode,
        endpointSelectionMode = warp.endpointSelectionMode,
        manualEndpointHost = warp.manualEndpoint.host,
        manualEndpointIpv4 = warp.manualEndpoint.ipv4,
        manualEndpointIpv6 = warp.manualEndpoint.ipv6,
        manualEndpointPort = warp.manualEndpoint.port,
        scannerAvailable = false,
        scannerEnabled = warp.scannerEnabled,
        scannerParallelism = warp.scannerParallelism,
        scannerMaxRttMs = warp.scannerMaxRttMs,
        amneziaPreset = warp.amneziaPreset,
        amneziaEnabled = warp.amnezia.enabled,
        amneziaJc = warp.amnezia.jc,
        amneziaJmin = warp.amnezia.jmin,
        amneziaJmax = warp.amnezia.jmax,
        amneziaH1 = warp.amnezia.h1,
        amneziaH2 = warp.amnezia.h2,
        amneziaH3 = warp.amnezia.h3,
        amneziaH4 = warp.amnezia.h4,
        amneziaS1 = warp.amnezia.s1,
        amneziaS2 = warp.amnezia.s2,
        amneziaS3 = warp.amnezia.s3,
        amneziaS4 = warp.amnezia.s4,
        amneziaSuggestedPresetId = suggestedAmneziaPresetId,
        amneziaSuggestedPresetLabel = suggestedAmneziaPresetLabel,
    )
}

internal fun AppSettings.buildRoutingProtectionUiState(
    snapshot: RoutingProtectionCatalogSnapshot,
    serviceTelemetry: ServiceTelemetrySnapshot,
): RoutingProtectionUiState {
    val enabledPresetIds = effectiveAppRoutingEnabledPresetIds().toSet()
    val presets =
        snapshot.presets.map { preset ->
            val confirmedCount = snapshot.detectedApps.count { it.presetId == preset.id && it.vpnDetection }
            RoutingProtectionPresetUiState(
                id = preset.id,
                title = preset.title,
                enabled = preset.id in enabledPresetIds,
                matchedPackages = preset.matchedPackages.toImmutableList(),
                detectionMethod = preset.detectionMethod,
                fixCoverage = preset.fixCoverage,
                limitations = preset.limitations,
                confirmedDetectorCount = confirmedCount,
            )
        }
    val suggestions = buildRoutingProtectionSuggestions(presets, snapshot, serviceTelemetry)
    return RoutingProtectionUiState(
        policyMode = normalizeAppRoutingPolicyMode(appRoutingPolicyMode),
        enabledPresetIds = enabledPresetIds.toList().sorted().toImmutableList(),
        antiCorrelationEnabled = antiCorrelationEnabled,
        dhtMitigationMode = normalizeDhtMitigationMode(dhtMitigationMode),
        fullTunnelMode = fullTunnelMode,
        presets = presets.toImmutableList(),
        detectedApps =
            snapshot.detectedApps
                .map { app ->
                    RoutingProtectionDetectedAppUiState(
                        packageName = app.packageName,
                        presetTitle = app.presetTitle,
                        detectionMethod = app.detectionMethod,
                        fixCoverage = app.fixCoverage,
                        vpnDetection = app.vpnDetection,
                        severity = app.severity,
                    )
                }.toImmutableList(),
        suggestions = suggestions.toImmutableList(),
    )
}

private const val FullTunnelSuggestionDetectedAppsThreshold = 3

private fun AppSettings.buildRoutingProtectionSuggestions(
    presets: List<RoutingProtectionPresetUiState>,
    snapshot: RoutingProtectionCatalogSnapshot,
    serviceTelemetry: ServiceTelemetrySnapshot,
): List<RoutingProtectionSuggestionUiState> =
    buildList {
        if (!fullTunnelMode && presets.any { it.matchedPackages.isNotEmpty() && !it.enabled }) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "exact_app_routing",
                    title = "Suggest app routing presets",
                    body =
                        "Whitelist-sensitive apps are installed. Enable direct routing for the matched presets " +
                            "instead of assuming split tunneling stays hidden.",
                ),
            )
        }
        if (!fullTunnelMode && snapshot.detectedApps.size >= FullTunnelSuggestionDetectedAppsThreshold) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "full_tunnel",
                    title = "Suggest full tunnel mode",
                    body =
                        "Several risky apps are installed. Full tunnel mode removes per-app routing differences " +
                            "when exact exclusions are not enough.",
                ),
            )
        }
        if (!fullTunnelMode && !antiCorrelationEnabled && snapshot.detectedApps.isNotEmpty()) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "anti_correlation",
                    title = "Suggest anti-correlation mode",
                    body =
                        "Anti-correlation keeps domestic destinations direct while forcing CDN-heavy paths " +
                            "through VPN or relay. It is meant for mobile whitelist pressure, not generic " +
                            "split tunneling.",
                ),
            )
        }
        if (shouldSuggestDhtMitigation(presets, serviceTelemetry)) {
            add(
                RoutingProtectionSuggestionUiState(
                    id = "dht_mitigation",
                    title = "Suggest DHT trigger mitigation",
                    body = dhtMitigationSuggestionBody(serviceTelemetry),
                ),
            )
        }
    }

private fun AppSettings.shouldSuggestDhtMitigation(
    presets: List<RoutingProtectionPresetUiState>,
    serviceTelemetry: ServiceTelemetrySnapshot,
): Boolean =
    !fullTunnelMode &&
        normalizeDhtMitigationMode(dhtMitigationMode) == DhtMitigationModeOff &&
        (presets.any { it.enabled } || antiCorrelationEnabled || serviceTelemetry.hasRecentRoutingPressure())

private fun dhtMitigationSuggestionBody(serviceTelemetry: ServiceTelemetrySnapshot): String {
    val dhtCorrelationReason = serviceTelemetry.runtimeFieldTelemetry.dhtTriggerCorrelationReason
    return when {
        dhtCorrelationReason != null -> {
            "$dhtCorrelationReason Enable DHT trigger mitigation so known trigger CIDRs do not keep " +
                "destabilizing relay or WARP paths."
        }

        serviceTelemetry.hasRecentRoutingPressure() -> {
            "Recent runtime failures suggest control-plane instability while split routing is active. " +
                "Known DHT trigger CIDRs can destabilize WARP, relay, or control-plane paths on mobile networks."
        }

        else -> {
            "Split-routing protection is active, but DHT mitigation is still off. Known trigger CIDRs can " +
                "destabilize WARP, relay, or control-plane paths on mobile networks."
        }
    }
}

private fun ServiceTelemetrySnapshot.hasRecentRoutingPressure(): Boolean =
    runtimeFieldTelemetry.dhtTriggerCorrelationActive ||
        listOf(proxyTelemetry, relayTelemetry, warpTelemetry).any { telemetry ->
            telemetry.lastFailureClass?.isNotBlank() == true || telemetry.lastError?.isNotBlank() == true
        }
