package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.BypassPortRange
import com.poyka.ripdpi.core.detection.BypassScanOptions
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionRecommendations
import com.poyka.ripdpi.core.detection.DetectionReportFormatter
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.debug.DetectionDebugFormatter
import com.poyka.ripdpi.core.detection.debug.DetectionDebugSettings
import com.poyka.ripdpi.detection.RoutingProtectionRecommendationStrings
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot

/**
 * Pure, stateless mapping from a [DetectionCheckResult] (+ the [AppSettings] snapshot and the
 * routing-protection catalog) to the presentation artifacts the UI renders: runner/debug config,
 * report text, debug report text, recommendations and suggested fixes.
 *
 * No dependencies; [StringResolver] is passed per-call. Behavior is byte-identical to the original
 * inline VM logic.
 */
internal object DetectionResultPresenter {
    fun runnerConfig(
        settings: AppSettings,
        packageName: String,
    ): DetectionRunnerConfig = settings.toDetectionRunnerConfig(packageName)

    fun debugSettings(settings: AppSettings): DetectionDebugSettings = settings.toDetectionDebugSettings()

    fun reportText(
        result: DetectionCheckResult,
        privacyModeEnabled: Boolean,
    ): String =
        DetectionReportFormatter.format(
            result = result,
            privacyModeEnabled = privacyModeEnabled,
        )

    fun debugReportText(
        result: DetectionCheckResult,
        settings: AppSettings,
        privacyModeEnabled: Boolean,
    ): String? =
        result
            .takeIf { settings.detectionCheckDebugModeEnabled }
            ?.let {
                DetectionDebugFormatter.format(
                    result = it,
                    settings = settings.toDetectionDebugSettings(),
                    privacyModeEnabled = privacyModeEnabled,
                )
            }

    fun recommendations(
        result: DetectionCheckResult,
        settings: AppSettings,
        snapshot: RoutingProtectionCatalogSnapshot,
        stringResolver: StringResolver,
    ): List<Recommendation> =
        DetectionRecommendations.generate(result) +
            buildRoutingProtectionRecommendations(
                result = result,
                settings = settings,
                snapshot = snapshot,
                strings = RoutingProtectionRecommendationStrings.resolve(stringResolver),
            )

    fun suggestedFixes(
        settings: AppSettings,
        result: DetectionCheckResult,
    ): List<DetectionSuggestedFix> = settings.suggestDetectionFixes(result)
}

private fun AppSettings.toDetectionRunnerConfig(packageName: String): DetectionRunnerConfig =
    DetectionRunnerConfig(
        ownProxyPort = proxyPort.takeIf { it > 0 },
        ownPackageName = packageName,
        includeBypassCheck = detectionCheckIncludeBypass,
        includeLocationCheck = detectionCheckIncludeLocation,
        includeRttTriangulationCheck =
            detectionCheckNetworkRequestsEnabled && detectionCheckRttTriangulationEnabled,
        includeCdnPullingCheck =
            detectionCheckNetworkRequestsEnabled && detectionCheckCdnPullingEnabled,
        includeCallTransportCheck =
            detectionCheckNetworkRequestsEnabled && detectionCheckCallTransportProbeEnabled,
        bypassScanOptions =
            BypassScanOptions(
                proxyScanEnabled = detectionCheckIncludeBypass,
                xrayApiScanEnabled = detectionCheckXrayApiScanEnabled,
                callTransportProbeEnabled = false,
                portRange = toBypassPortRange(),
            ),
        resolverConfig = toDetectionResolverConfig(),
        encryptedDnsEnabled = dnsMode == "encrypted" || detectionCheckDnsResolverMode == "doh",
        webRtcProtectionEnabled = webrtcProtectionEnabled,
        tlsFingerprintProfile = tlsFingerprintProfile.ifEmpty { "chrome_stable" },
    )

private fun AppSettings.toDetectionDebugSettings(): DetectionDebugSettings =
    DetectionDebugSettings(
        cdnPullingEnabled = detectionCheckCdnPullingEnabled,
        dnsResolverMode = detectionCheckDnsResolverMode.ifEmpty { dnsMode },
        portRange = detectionCheckPortRangeMode.ifEmpty { "popular" },
        debugModeEnabled = detectionCheckDebugModeEnabled,
    )

private fun AppSettings.toBypassPortRange(): BypassPortRange =
    when (DetectionPortRangeMode.fromWire(detectionCheckPortRangeMode)) {
        DetectionPortRangeMode.POPULAR -> {
            BypassPortRange.Popular
        }

        DetectionPortRangeMode.EXTENDED -> {
            BypassPortRange.Extended
        }

        DetectionPortRangeMode.FULL -> {
            BypassPortRange.Full
        }

        DetectionPortRangeMode.CUSTOM -> {
            BypassPortRange.Custom(
                start = detectionCheckCustomPortStart.takeIf { it > 0 } ?: 1080,
                end = detectionCheckCustomPortEnd.takeIf { it > 0 } ?: 1090,
            )
        }
    }
