package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.proto.AppSettings
import kotlin.math.max

private const val DisabledControlAlpha = 0.38f
private const val EnabledControlAlpha = 1f
private const val ExtendedPortStart = 1024
private const val ExtendedPortEnd = 49151
private const val FullPortStart = 1
private const val FullPortEnd = 65535
private const val DefaultCustomPortStart = 1080
private const val DefaultCustomPortEnd = 1090

enum class DetectionTunProbeMode(
    val wireValue: String,
    val displayName: String,
) {
    AUTO("auto", "Auto"),
    STRICT_SAME_PATH("strict_same_path", "StrictSamePath"),
    CURL_COMPATIBLE("curl_compatible", "CurlCompatible"),
    ;

    companion object {
        fun fromWire(value: String): DetectionTunProbeMode = entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}

enum class DetectionPortRangeMode(
    val wireValue: String,
    val displayName: String,
) {
    POPULAR("popular", "Popular"),
    EXTENDED("extended", "Extended"),
    FULL("full", "Full"),
    CUSTOM("custom", "Custom"),
    ;

    companion object {
        fun fromWire(value: String): DetectionPortRangeMode = entries.firstOrNull { it.wireValue == value } ?: POPULAR
    }
}

enum class DetectionDnsResolverMode(
    val wireValue: String,
    val displayName: String,
) {
    SYSTEM("system", "System"),
    DIRECT("direct", "Direct"),
    DOH("doh", "DoH"),
    ;

    companion object {
        fun fromWire(value: String): DetectionDnsResolverMode = entries.firstOrNull { it.wireValue == value } ?: SYSTEM
    }
}

enum class DetectionDnsPreset(
    val wireValue: String,
    val displayName: String,
    val directServers: String,
    val dohUrl: String,
) {
    CUSTOM("custom", "Custom", "", ""),
    CLOUDFLARE("cloudflare", "Cloudflare", "1.1.1.1, 1.0.0.1", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("google", "Google", "8.8.8.8, 8.8.4.4", "https://dns.google/dns-query"),
    YANDEX("yandex", "Yandex", "77.88.8.8, 77.88.8.1", "https://common.dot.dns.yandex.net/dns-query"),
    ;

    companion object {
        fun fromWire(value: String): DetectionDnsPreset = entries.firstOrNull { it.wireValue == value } ?: CUSTOM
    }
}

data class DetectionSettingsUiState(
    val networkRequestsEnabled: Boolean = true,
    val cdnPullingEnabled: Boolean = false,
    val cdnPullingMeduzaEnabled: Boolean = true,
    val callTransportProbeEnabled: Boolean = false,
    val rttTriangulationEnabled: Boolean = false,
    val proxyScanEnabled: Boolean = true,
    val xrayApiScanEnabled: Boolean = true,
    val tunProbeMode: DetectionTunProbeMode = DetectionTunProbeMode.AUTO,
    val portRangeMode: DetectionPortRangeMode = DetectionPortRangeMode.POPULAR,
    val customPortStart: Int = 1080,
    val customPortEnd: Int = 1090,
    val dnsResolverMode: DetectionDnsResolverMode = DetectionDnsResolverMode.SYSTEM,
    val dnsPreset: DetectionDnsPreset = DetectionDnsPreset.CUSTOM,
    val dnsDirectServers: String = "",
    val dnsDohUrl: String = "",
    val dnsDohBootstrapIps: String = "",
    val diagnosticRandomHostnamesEnabled: Boolean = false,
    val tlsKeylogPath: String = "",
    val privacyModeEnabled: Boolean = false,
    val debugModeEnabled: Boolean = false,
    val colorVisionMode: DetectionColorVisionMode = DetectionColorVisionMode.OFF,
    val protanopiaVariantUnlocked: Boolean = false,
) {
    val networkDependentEnabled: Boolean
        get() = networkRequestsEnabled

    val networkDependentAlpha: Float
        get() = if (networkRequestsEnabled) EnabledControlAlpha else DisabledControlAlpha

    val dnsFieldsEditable: Boolean
        get() = dnsPreset == DetectionDnsPreset.CUSTOM

    val tlsKeylogPathVisible: Boolean
        get() = debugModeEnabled

    val effectiveTlsKeylogPath: String?
        get() = tlsKeylogPath.takeUnless { !debugModeEnabled || privacyModeEnabled || it.isBlank() }

    val customPortRangeValid: Boolean
        get() =
            customPortStart in FullPortStart..FullPortEnd &&
                customPortEnd in FullPortStart..FullPortEnd &&
                customPortStart <= customPortEnd

    val customPortCount: Int
        get() = if (customPortRangeValid) customPortEnd - customPortStart + 1 else 0

    val selectedPortCount: Int?
        get() =
            when (portRangeMode) {
                DetectionPortRangeMode.POPULAR -> null
                DetectionPortRangeMode.EXTENDED -> ExtendedPortEnd - ExtendedPortStart + 1
                DetectionPortRangeMode.FULL -> FullPortEnd
                DetectionPortRangeMode.CUSTOM -> customPortCount
            }

    fun selectDnsPreset(preset: DetectionDnsPreset): DetectionSettingsUiState =
        if (preset == DetectionDnsPreset.CUSTOM) {
            copy(dnsPreset = preset)
        } else {
            copy(
                dnsPreset = preset,
                dnsDirectServers = preset.directServers,
                dnsDohUrl = preset.dohUrl,
                dnsDohBootstrapIps = preset.directServers,
            )
        }

    fun withCustomPortStart(value: String): DetectionSettingsUiState = copy(customPortStart = value.toPortOrZero())

    fun withCustomPortEnd(value: String): DetectionSettingsUiState = copy(customPortEnd = value.toPortOrZero())

    companion object {
        fun from(settings: AppSettings): DetectionSettingsUiState {
            val preset = DetectionDnsPreset.fromWire(settings.detectionCheckDnsPreset)
            return DetectionSettingsUiState(
                networkRequestsEnabled = settings.detectionCheckNetworkRequestsEnabled,
                cdnPullingEnabled = settings.detectionCheckCdnPullingEnabled,
                cdnPullingMeduzaEnabled = settings.detectionCheckCdnPullingMeduzaEnabled,
                callTransportProbeEnabled = settings.detectionCheckCallTransportProbeEnabled,
                rttTriangulationEnabled = settings.detectionCheckRttTriangulationEnabled,
                proxyScanEnabled = settings.detectionCheckIncludeBypass,
                xrayApiScanEnabled = settings.detectionCheckXrayApiScanEnabled,
                tunProbeMode = DetectionTunProbeMode.fromWire(settings.detectionCheckTunProbeMode),
                portRangeMode = DetectionPortRangeMode.fromWire(settings.detectionCheckPortRangeMode),
                customPortStart = settings.detectionCheckCustomPortStart.nonZeroOr(DefaultCustomPortStart),
                customPortEnd = settings.detectionCheckCustomPortEnd.nonZeroOr(DefaultCustomPortEnd),
                dnsResolverMode = DetectionDnsResolverMode.fromWire(settings.detectionCheckDnsResolverMode),
                dnsPreset = preset,
                dnsDirectServers = settings.detectionCheckDnsDirectServers,
                dnsDohUrl = settings.detectionCheckDnsDohUrl,
                dnsDohBootstrapIps = settings.detectionCheckDnsDohBootstrapIps,
                diagnosticRandomHostnamesEnabled = settings.detectionDiagnosticRandomHostnamesEnabled,
                tlsKeylogPath = settings.detectionDiagnosticTlsKeylogPath,
                privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled,
                debugModeEnabled = settings.detectionCheckDebugModeEnabled,
                colorVisionMode = DetectionColorVisionMode.fromWire(settings.detectionCheckColorVisionMode),
                protanopiaVariantUnlocked = settings.detectionCheckProtanopiaVariantUnlocked,
            ).selectDnsPresetIfNeeded(preset)
        }
    }
}

private fun DetectionSettingsUiState.selectDnsPresetIfNeeded(preset: DetectionDnsPreset): DetectionSettingsUiState =
    if (preset == DetectionDnsPreset.CUSTOM) this else selectDnsPreset(preset)

private fun Int.nonZeroOr(defaultValue: Int): Int = if (this == 0) defaultValue else this

private fun String.toPortOrZero(): Int = max(0, toIntOrNull() ?: 0)
