@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.consensus.IpConsensusChannel
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.XrayOutboundSummary

enum class ExposureStatus {
    REMOTE_ENDPOINT_DISCOVERED,
    PUBLIC_IP_ONLY,
    LOCAL_PROXY_OR_API_ONLY,
    TECHNICAL_SIGNAL_ONLY,
    INSUFFICIENT_DATA,
}

data class VerdictNarrativeRow(
    val label: String,
    val value: String,
    val exposureStatus: ExposureStatus,
)

data class VerdictNarrative(
    val exposureStatus: ExposureStatus,
    val discoveredRows: List<VerdictNarrativeRow>,
    val reasonRows: List<VerdictNarrativeRow>,
    val homeRoutedRoamingNote: String? = null,
)

object VerdictNarrativeBuilder {
    fun build(result: DetectionCheckResult): VerdictNarrative {
        val exposureStatus = exposureStatus(result)
        return VerdictNarrative(
            exposureStatus = exposureStatus,
            discoveredRows = discoveredRows(result, exposureStatus),
            reasonRows = reasonRows(result, exposureStatus),
            homeRoutedRoamingNote =
                if (result.locationSignals.isHomeRoutedRoaming()) {
                    "Home-routed roaming can make a foreign SIM on a Russian network look exposed."
                } else {
                    null
                },
        )
    }

    private fun exposureStatus(result: DetectionCheckResult): ExposureStatus =
        when {
            result.hasRemoteEndpointSignal() -> ExposureStatus.REMOTE_ENDPOINT_DISCOVERED
            result.hasPublicIpSignal() -> ExposureStatus.PUBLIC_IP_ONLY
            result.hasLocalProxyOrApiSignal() -> ExposureStatus.LOCAL_PROXY_OR_API_ONLY
            result.hasTechnicalSignal() -> ExposureStatus.TECHNICAL_SIGNAL_ONLY
            else -> ExposureStatus.INSUFFICIENT_DATA
        }

    private fun discoveredRows(
        result: DetectionCheckResult,
        exposureStatus: ExposureStatus,
    ): List<VerdictNarrativeRow> =
        buildList {
            add(row("Exposure level", exposureStatus.displayLabel(), exposureStatus))
            result.bypassResult.xrayApiScanResult?.endpoint?.let { endpoint ->
                add(
                    row(
                        "Xray API endpoint",
                        "${endpoint.host}:${endpoint.port}",
                        ExposureStatus.LOCAL_PROXY_OR_API_ONLY,
                    ),
                )
            }
            result.bypassResult.xrayApiScanResult
                ?.outbounds
                .orEmpty()
                .mapNotNull { it.displayEndpoint() }
                .distinct()
                .take(REMOTE_ENDPOINT_LIMIT)
                .forEachIndexed { index, endpoint ->
                    add(row("Remote endpoint ${index + 1}", endpoint, ExposureStatus.REMOTE_ENDPOINT_DISCOVERED))
                }
            result.bypassResult.proxyEndpoint?.let { endpoint ->
                add(row("Local proxy", endpoint.displayEndpoint(), ExposureStatus.LOCAL_PROXY_OR_API_ONLY))
            }
            result.ownerAppLabel()?.let { app ->
                add(row("Owner app", app, ExposureStatus.TECHNICAL_SIGNAL_ONLY))
            }
            result.networkInterfaceLabel()?.let { networkIp ->
                add(row("VPN network IP", networkIp, ExposureStatus.PUBLIC_IP_ONLY))
            }
            result.bypassResult.directIp?.let { ip ->
                add(row("Direct IP", ip, ExposureStatus.PUBLIC_IP_ONLY))
            }
            result.bypassResult.proxyIp?.let { ip ->
                add(row("Proxy IP", ip, ExposureStatus.PUBLIC_IP_ONLY))
            }
            result.ipConsensus
                ?.observedIps
                ?.get(IpConsensusChannel.IP_COMPARISON_RU)
                .orEmpty()
                .take(IP_ROW_LIMIT)
                .forEach { ip ->
                    add(row("RU checker IP", ip, ExposureStatus.PUBLIC_IP_ONLY))
                }
            result.ipConsensus
                ?.observedIps
                ?.get(IpConsensusChannel.IP_COMPARISON_NON_RU)
                .orEmpty()
                .take(IP_ROW_LIMIT)
                .forEach { ip ->
                    add(row("Non-RU checker IP", ip, ExposureStatus.PUBLIC_IP_ONLY))
                }
            result.ipConsensus
                ?.observedIps
                ?.get(IpConsensusChannel.GEO_IP)
                .orEmpty()
                .firstOrNull()
                ?.let { ip ->
                    add(row("Geo IP", ip, ExposureStatus.PUBLIC_IP_ONLY))
                }
        }.take(DISCOVERED_ROW_LIMIT)

    private fun reasonRows(
        result: DetectionCheckResult,
        exposureStatus: ExposureStatus,
    ): List<VerdictNarrativeRow> {
        val rows =
            buildList {
                if (result.hasEvidence(EvidenceSource.XRAY_API) || result.bypassResult.xrayApiScanResult != null) {
                    add(row("Xray API", "Local API or outbound configuration was reachable.", exposureStatus))
                }
                if (result.hasEvidence(EvidenceSource.SPLIT_TUNNEL_BYPASS)) {
                    add(row("Split tunnel", "Direct and proxied network paths disagree.", exposureStatus))
                }
                if (result.bypassResult.directIp != null && result.bypassResult.proxyIp != null) {
                    add(row("VPN gateway leak", "Direct and proxy IP observations were both visible.", exposureStatus))
                }
                if (result.hasEvidence(EvidenceSource.NETWORK_CAPABILITIES)) {
                    add(row("VPN network binding", "Android network capabilities expose VPN routing.", exposureStatus))
                }
                if (result.bypassResult.mtProtoReachable || result.bypassResult.stunReflexiveAddresses.isNotEmpty()) {
                    add(
                        row(
                            "Bypass transport",
                            "Transport probes found reachable bypass-related paths.",
                            exposureStatus,
                        ),
                    )
                }
                if (result.ipConsensus?.crossChannelMismatches?.isNotEmpty() == true) {
                    add(row("IP comparison", "Observed IPs diverge across checker channels.", exposureStatus))
                } else if (result.ipConsensus?.channelConflicts?.isNotEmpty() == true) {
                    add(row("IP comparison", "One checker channel returned conflicting IPs.", exposureStatus))
                }
                if (result.hasGeoLocationConflict()) {
                    add(row("Geo/location conflict", "Russian network context conflicts with GeoIP.", exposureStatus))
                } else if (result.geoIp.detected || result.geoIp.needsReview) {
                    add(row("GeoIP", "GeoIP signal indicates exposure or needs review.", exposureStatus))
                }
                if (result.directSigns.hasSignal()) {
                    add(row("Direct signs", "Local direct checks found proxy or VPN artifacts.", exposureStatus))
                }
                if (result.indirectSigns.hasSignal()) {
                    add(row("Indirect signs", "Installed or active app signals need attention.", exposureStatus))
                }
                if (result.nativeSigns?.category.hasSignal()) {
                    add(row("Native signs", "Native runtime checks found technical artifacts.", exposureStatus))
                }
                if (result.icmpSpoofing?.category.hasSignal()) {
                    add(row("ICMP signal", "ICMP spoofing probe needs review.", exposureStatus))
                }
            }.take(REASON_ROW_LIMIT)

        return rows.ifEmpty {
            listOf(row("No matching rule", "No positive detection signal was strong enough.", exposureStatus))
        }
    }

    private fun DetectionCheckResult.hasRemoteEndpointSignal(): Boolean =
        hasEvidence(EvidenceSource.XRAY_API) || bypassResult.xrayApiScanResult != null

    private fun DetectionCheckResult.hasPublicIpSignal(): Boolean =
        geoIp.detected ||
            geoIp.needsReview ||
            hasEvidence(EvidenceSource.GEO_IP, EvidenceSource.IP_COMPARISON, EvidenceSource.IP_CONSENSUS) ||
            ipConsensus?.observedIps?.values?.any { it.isNotEmpty() } == true ||
            bypassResult.directIp != null ||
            bypassResult.proxyIp != null

    private fun DetectionCheckResult.hasLocalProxyOrApiSignal(): Boolean =
        bypassResult.proxyEndpoint != null ||
            hasEvidence(EvidenceSource.LOCAL_PROXY, EvidenceSource.SYSTEM_PROXY) ||
            directSigns.evidence.any {
                it.source == EvidenceSource.LOCAL_PROXY || it.source == EvidenceSource.SYSTEM_PROXY
            }

    private fun DetectionCheckResult.hasTechnicalSignal(): Boolean =
        listOf(
            geoIp,
            directSigns,
            indirectSigns,
            locationSignals,
            dnsLeak,
            webRtcLeak,
            tlsFingerprint,
            timingAnalysis,
            icmpSpoofing?.category,
            ipComparison?.category,
            rttTriangulation?.category,
            cdnPulling?.category,
            nativeSigns?.category,
        ).any { it.hasSignal() } ||
            bypassResult.detected ||
            bypassResult.needsReview ||
            bypassResult.evidence.any(EvidenceItem::detected)

    private fun DetectionCheckResult.hasEvidence(vararg sources: EvidenceSource): Boolean =
        allEvidence().any { it.detected && it.source in sources }

    private fun DetectionCheckResult.allEvidence(): List<EvidenceItem> =
        buildList {
            addAll(geoIp.evidence)
            addAll(directSigns.evidence)
            addAll(indirectSigns.evidence)
            addAll(locationSignals.evidence)
            addAll(bypassResult.evidence)
            dnsLeak?.evidence?.let(::addAll)
            webRtcLeak?.evidence?.let(::addAll)
            tlsFingerprint?.evidence?.let(::addAll)
            timingAnalysis?.evidence?.let(::addAll)
            icmpSpoofing?.category?.evidence?.let(::addAll)
            ipComparison?.category?.evidence?.let(::addAll)
            rttTriangulation?.category?.evidence?.let(::addAll)
            cdnPulling?.category?.evidence?.let(::addAll)
            nativeSigns?.category?.evidence?.let(::addAll)
        }

    private fun DetectionCheckResult.ownerAppLabel(): String? =
        (directSigns.matchedApps + indirectSigns.matchedApps)
            .firstOrNull()
            ?.let { app ->
                listOfNotNull(app.appName, app.packageName).joinToString(" / ")
            }
            ?: (directSigns.activeApps + indirectSigns.activeApps)
                .firstOrNull()
                ?.let { app ->
                    listOfNotNull(app.family, app.packageName, app.serviceName).joinToString(" / ")
                }

    private fun DetectionCheckResult.networkInterfaceLabel(): String? =
        (directSigns.findings + indirectSigns.findings)
            .firstOrNull { it.source == EvidenceSource.NETWORK_INTERFACE && (it.detected || it.needsReview) }
            ?.description

    private fun DetectionCheckResult.hasGeoLocationConflict(): Boolean =
        locationSignals.findings.any { it.description == "network_mcc_ru:true" } &&
            (geoIp.detected || geoIp.needsReview || hasEvidence(EvidenceSource.GEO_IP))

    private fun CategoryResult?.hasSignal(): Boolean =
        this?.detected == true ||
            this?.needsReview == true ||
            this?.evidence?.any(EvidenceItem::detected) == true

    private fun CategoryResult.isHomeRoutedRoaming(): Boolean {
        if (findings.any { it.description == "home_routed_roaming:true" }) return true
        val hasRussianNetwork = findings.any { it.description == "network_mcc_ru:true" }
        val hasRoaming = findings.any { it.description == "Roaming: yes" }
        val simMcc =
            findings.firstNotNullOfOrNull { finding ->
                SIM_MCC_REGEX.find(finding.description)?.groupValues?.get(1)
            }
        return hasRussianNetwork && hasRoaming && simMcc != null && simMcc != RUSSIA_MCC
    }

    private fun ExposureStatus.displayLabel(): String =
        when (this) {
            ExposureStatus.REMOTE_ENDPOINT_DISCOVERED -> "Remote endpoint discovered"
            ExposureStatus.PUBLIC_IP_ONLY -> "Public IP only"
            ExposureStatus.LOCAL_PROXY_OR_API_ONLY -> "Local proxy or API only"
            ExposureStatus.TECHNICAL_SIGNAL_ONLY -> "Technical signal only"
            ExposureStatus.INSUFFICIENT_DATA -> "Insufficient data"
        }

    private fun ProxyEndpoint.displayEndpoint(): String = "${type.name.lowercase()}://$host:$port"

    private fun XrayOutboundSummary.displayEndpoint(): String? {
        val address = address?.takeIf(String::isNotBlank) ?: return null
        return port?.let { "$address:$it" } ?: address
    }

    private fun row(
        label: String,
        value: String,
        exposureStatus: ExposureStatus,
    ): VerdictNarrativeRow =
        VerdictNarrativeRow(
            label = label,
            value = value,
            exposureStatus = exposureStatus,
        )

    private const val DISCOVERED_ROW_LIMIT = 10
    private const val REASON_ROW_LIMIT = 5
    private const val REMOTE_ENDPOINT_LIMIT = 3
    private const val IP_ROW_LIMIT = 2
    private const val RUSSIA_MCC = "250"
    private val SIM_MCC_REGEX = Regex("""SIM MCC:\s*(\d+)""")
}
