package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.data.BundledDnsProviderCatalog
import com.poyka.ripdpi.data.DnsProviderCatalogEntry
import com.poyka.ripdpi.data.DnsProviderCustom
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

/**
 * A selectable DNS preset for the detection resolver. Keyed by the catalog id ([wireValue]); any id
 * present in [BundledDnsProviderCatalog] resolves to a catalog-backed preset, so the persisted
 * wireValue is no longer limited to a closed enum. The user-defined custom resolver keeps the stable
 * "custom" sentinel ([CUSTOM]).
 *
 * [directServers] is the IPv4-only, comma-joined bootstrap list used to pre-fill the editable direct
 * field (matching the historical preset strings); [dohBootstrapIps] mirrors it for the DoH bootstrap
 * field. The richer (IPv6-bearing) bootstrap set stays available via the catalog entry / loader.
 */
data class DetectionDnsPreset(
    val wireValue: String,
    val displayName: String,
    val directServers: String,
    val dohUrl: String,
    val dohBootstrapIps: String,
    val noLog: Boolean = false,
    val noFilter: Boolean = false,
    val dnssec: Boolean = false,
    val jurisdictionRu: Boolean = false,
) {
    val isCustom: Boolean
        get() = wireValue == DnsProviderCustom

    companion object {
        /** Stable sentinel for the user-defined custom resolver. */
        val CUSTOM: DetectionDnsPreset =
            DetectionDnsPreset(
                wireValue = DnsProviderCustom,
                displayName = "Custom",
                directServers = "",
                dohUrl = "",
                dohBootstrapIps = "",
            )

        /** Catalog-backed accessors for the historically curated short-list presets. */
        val CLOUDFLARE: DetectionDnsPreset = fromCatalogId("cloudflare")
        val GOOGLE: DetectionDnsPreset = fromCatalogId("google")
        val YANDEX: DetectionDnsPreset = fromCatalogId("yandex")

        /**
         * The curated short-list set surfaced in the preset chip selector — kept stable (custom +
         * the three historical providers) so the settings screen contract and its screenshots do not
         * churn as the bundled catalog grows. Selection of any other catalog id still resolves through
         * [fromWire] and [selectDnsPreset].
         */
        val entries: List<DetectionDnsPreset> = listOf(CUSTOM, CLOUDFLARE, GOOGLE, YANDEX)

        /**
         * The full picker set: the custom sentinel followed by every bundled catalog provider in
         * catalog order. This is what the catalog-driven provider picker surfaces (~15 providers +
         * Custom), as opposed to the curated [entries] short-list used by the legacy chip contract.
         */
        val catalogPresets: List<DetectionDnsPreset> =
            buildList {
                add(CUSTOM)
                BundledDnsProviderCatalog.providers.forEach { add(it.toPreset()) }
            }

        /**
         * Resolves any persisted wireValue: the curated short-list entries first, then any other
         * bundled catalog id, falling back to [CUSTOM] for unknown / user-defined resolvers.
         */
        fun fromWire(value: String): DetectionDnsPreset =
            entries.firstOrNull { it.wireValue == value }
                ?: BundledDnsProviderCatalog.byId(value)?.toPreset()
                ?: CUSTOM

        private fun fromCatalogId(id: String): DetectionDnsPreset =
            requireNotNull(BundledDnsProviderCatalog.byId(id)) { "$id missing from bundled DNS catalog" }.toPreset()

        private fun DnsProviderCatalogEntry.toPreset(): DetectionDnsPreset {
            val ipv4Bootstraps = bootstrapIps.filter { it.isIpv4Literal() }.joinToString(", ")
            return DetectionDnsPreset(
                wireValue = id,
                displayName = name,
                directServers = ipv4Bootstraps,
                dohUrl = dohUrl,
                dohBootstrapIps = ipv4Bootstraps,
                noLog = flags.noLog,
                noFilter = flags.noFilter,
                dnssec = flags.dnssec,
                jurisdictionRu = rf.jurisdictionRu,
            )
        }
    }
}

private fun String.isIpv4Literal(): Boolean = !contains(':') && IPV4_PRESET_LITERAL.matches(trim())

private val IPV4_PRESET_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

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
    val dnsTextReplacementRevision: Long = 0,
    val dnsRouteThroughProxy: Boolean = false,
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
        get() = dnsPreset.isCustom

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
        if (preset.isCustom) {
            copy(dnsPreset = preset)
        } else {
            copy(
                dnsPreset = preset,
                dnsDirectServers = preset.directServers,
                dnsDohUrl = preset.dohUrl,
                dnsDohBootstrapIps = preset.dohBootstrapIps,
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
                dnsRouteThroughProxy = settings.detectionCheckDnsRouteThroughProxy,
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
    if (preset.isCustom) this else selectDnsPreset(preset)

private fun Int.nonZeroOr(defaultValue: Int): Int = if (this == 0) defaultValue else this

private fun String.toPortOrZero(): Int = max(0, toIntOrNull() ?: 0)
