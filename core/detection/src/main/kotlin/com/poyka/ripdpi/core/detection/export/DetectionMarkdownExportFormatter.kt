@file:Suppress("TooManyFunctions")

package com.poyka.ripdpi.core.detection.export

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.ExposureStatus
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.VerdictNarrative
import com.poyka.ripdpi.core.detection.VerdictNarrativeBuilder
import com.poyka.ripdpi.core.detection.consensus.IpConsensusChannel
import com.poyka.ripdpi.core.detection.privacy.DetectionPrivacyMask
import com.poyka.ripdpi.core.detection.probe.XrayOutboundSummary

object DetectionMarkdownExportFormatter {
    fun format(
        result: DetectionCheckResult,
        metadata: DetectionExportMetadata,
    ): String =
        DetectionPrivacyMask.maskIpsInText(
            buildString {
                val narrative = result.verdictNarrative ?: VerdictNarrativeBuilder.build(result)
                appendLine("# RipDPI Detection Report")
                appendLine()
                appendSummary(result, narrative, metadata)
                appendVerdict(result, narrative)
                appendSectionSummary(result)
                appendCategory("GeoIp", result.geoIp)
                appendIpComparison(result)
                appendCdnPulling(result)
                appendCategory("DirectSigns", result.directSigns)
                appendCategory("IndirectSigns", result.indirectSigns)
                appendCategory("NativeSigns", result.nativeSigns?.category)
                appendCategory("IcmpSpoofing", result.icmpSpoofing?.category)
                appendCategory("RttTriangulation", result.rttTriangulation?.category)
                appendCategory("CallTransport", result.callTransport?.category)
                appendCategory("LocationSignals", result.locationSignals)
                appendIpChannels(result)
                appendLine("## TunProbeDiagnostics")
                appendLine()
                appendLine("- not collected")
                appendLine()
                appendBypass(result.bypassResult)
                appendFooter(metadata)
            },
            enabled = metadata.privacyMode,
        )

    private fun StringBuilder.appendSummary(
        result: DetectionCheckResult,
        narrative: VerdictNarrative,
        metadata: DetectionExportMetadata,
    ) {
        appendLine("```")
        appendLine("VERDICT: ${result.verdict}")
        appendLine("EXPOSURE: ${narrative.exposureStatus.exportLabel()}")
        appendLine("PRIVACY MODE: ${metadata.privacyMode}")
        appendLine("TIMESTAMP: ${metadata.timestamp}")
        appendLine("```")
        appendLine()
    }

    private fun StringBuilder.appendVerdict(
        result: DetectionCheckResult,
        narrative: VerdictNarrative,
    ) {
        appendLine("## Verdict")
        appendLine()
        appendLine("- status: ${result.verdict}")
        result.verdictExplanation?.let {
            appendLine("- explanation: ${it.ruleApplied}: ${it.summary}")
            appendLine("- appliedRule: ${it.ruleApplied}")
            appendLine("- appliedScopes: ${it.appliedScopes.joinToString()}")
            appendLine("- uniqueSignalCount: ${it.uniqueSignalCount}")
        }
        appendLine("- exposureStatus: ${narrative.exposureStatus.exportLabel()}")
        appendLine()
        appendLine("### What this means")
        appendLine()
        appendLine("- ${narrative.exposureStatus.meaning()}")
        appendNarrativeRows("### What was discovered", narrative.discoveredRows.map { it.label to it.value })
        appendNarrativeRows("### Why this verdict", narrative.reasonRows.map { it.label to it.value })
        narrative.homeRoutedRoamingNote?.let {
            appendLine("### Home-routed roaming")
            appendLine()
            appendLine("- $it")
            appendLine()
        }
    }

    private fun StringBuilder.appendSectionSummary(result: DetectionCheckResult) {
        appendLine("## Section Summary")
        appendLine()
        appendLine("| Section | Status | Summary |")
        appendLine("| --- | --- | --- |")
        sectionRows(result).forEach { row ->
            appendLine("| ${row.name} | ${row.status} | ${row.summary} |")
        }
        appendLine()
    }

    private fun StringBuilder.appendCategory(
        title: String,
        category: CategoryResult?,
    ) {
        appendLine("## $title")
        appendLine()
        if (category == null) {
            appendLine("- skipped")
            appendLine()
            return
        }
        appendLine("- status: ${category.statusTag()}")
        appendRows("findings", category.findings.map(Finding::description))
        appendRows("evidence", category.evidence.map { "[${it.scope}] ${it.description}" })
        appendLine()
    }

    private fun StringBuilder.appendIpComparison(result: DetectionCheckResult) {
        appendCategory("IpComparison", result.ipComparison?.category)
        result.ipComparison?.endpoints?.forEach { endpoint ->
            appendLine("- ${endpoint.descriptor.label}: ${endpoint.status} ${endpoint.reflectedIp.orEmpty()}")
        }
        appendLine()
    }

    private fun StringBuilder.appendCdnPulling(result: DetectionCheckResult) {
        appendCategory("CdnPulling", result.cdnPulling?.category)
        result.cdnPulling?.endpoints?.forEach { endpoint ->
            appendLine("- ${endpoint.descriptor.label}: ${endpoint.status} ${endpoint.reflectedIp.orEmpty()}")
        }
        appendLine()
    }

    private fun StringBuilder.appendIpChannels(result: DetectionCheckResult) {
        appendLine("## IpChannels")
        appendLine()
        appendLine("| Channel | IPs |")
        appendLine("| --- | --- |")
        IpConsensusChannel.entries.forEach { channel ->
            val ips =
                result.ipConsensus
                    ?.observedIps
                    ?.get(channel)
                    .orEmpty()
                    .joinToString(", ")
                    .ifBlank { "-" }
            appendLine("| ${channel.name} | $ips |")
        }
        appendLine()
    }

    private fun StringBuilder.appendBypass(bypass: BypassResult) {
        appendLine("## Bypass")
        appendLine()
        appendLine("- status: ${bypass.statusTag()}")
        appendLine("- proxyEndpoint: ${bypass.proxyEndpoint?.let { "${it.type}:${it.host}:${it.port}" } ?: "-"}")
        appendLine("- directIp: ${bypass.directIp ?: "-"}")
        appendLine("- proxyIp: ${bypass.proxyIp ?: "-"}")
        appendRows("stunReflexiveAddresses", bypass.stunReflexiveAddresses)
        appendRows("findings", bypass.findings.map(Finding::description))
        bypass.xrayApiScanResult?.let { scan ->
            appendLine("### Xray outbounds")
            scan.outbounds.forEach { outbound ->
                appendXrayOutbound(outbound)
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendXrayOutbound(outbound: XrayOutboundSummary) {
        appendLine(
            "- tag: ${outbound.tag}, protocol: ${outbound.protocolName ?: "-"}, " +
                "address: ${outbound.address ?: "-"}, port: ${outbound.port ?: "-"}, sni: ${outbound.sni ?: "-"}, " +
                "senderSettingsType: ${outbound.senderSettingsType ?: "-"}, " +
                "proxySettingsType: ${outbound.proxySettingsType ?: "-"}, " +
                "uuidPresent: ${!outbound.uuid.isNullOrBlank()}, " +
                "publicKeyPresent: ${!outbound.publicKey.isNullOrBlank()}",
        )
    }

    private fun StringBuilder.appendFooter(metadata: DetectionExportMetadata) {
        appendLine("---")
        appendLine("timestamp: ${metadata.timestamp}")
        appendLine("appVersion: ${metadata.appVersion}")
        appendLine("buildType: ${metadata.buildType}")
        appendLine("privacyMode: ${metadata.privacyMode}")
    }

    private fun StringBuilder.appendNarrativeRows(
        title: String,
        rows: List<Pair<String, String>>,
    ) {
        appendLine(title)
        appendLine()
        rows.forEach { (label, value) ->
            appendLine("- $label: $value")
        }
        appendLine()
    }

    private fun StringBuilder.appendRows(
        title: String,
        rows: List<String>,
    ) {
        appendLine("- $title:")
        rows.ifEmpty { listOf("-") }.forEach { row ->
            appendLine("  - $row")
        }
    }

    private fun sectionRows(result: DetectionCheckResult): List<SectionRow> =
        listOf(
            SectionRow("GeoIp", result.geoIp.statusTag(), result.geoIp.summary()),
            SectionRow(
                "IpComparison",
                result.ipComparison?.category.statusTag(),
                result.ipComparison?.category.summary(),
            ),
            SectionRow("CdnPulling", result.cdnPulling?.category.statusTag(), result.cdnPulling?.category.summary()),
            SectionRow("DirectSigns", result.directSigns.statusTag(), result.directSigns.summary()),
            SectionRow("IndirectSigns", result.indirectSigns.statusTag(), result.indirectSigns.summary()),
            SectionRow("NativeSigns", result.nativeSigns?.category.statusTag(), result.nativeSigns?.category.summary()),
            SectionRow(
                "IcmpSpoofing",
                result.icmpSpoofing?.category.statusTag(),
                result.icmpSpoofing?.category.summary(),
            ),
            SectionRow(
                "RttTriangulation",
                result.rttTriangulation?.category.statusTag(),
                result.rttTriangulation?.category.summary(),
            ),
            SectionRow(
                "CallTransport",
                result.callTransport?.category.statusTag(),
                result.callTransport?.category.summary(),
            ),
            SectionRow("LocationSignals", result.locationSignals.statusTag(), result.locationSignals.summary()),
            SectionRow(
                "Bypass",
                result.bypassResult.statusTag(),
                result.bypassResult.findings
                    .firstOrNull()
                    ?.description ?: "-",
            ),
        )

    private fun CategoryResult?.statusTag(): String =
        when {
            this == null -> "[OK]"
            detected -> "[DETECTED]"
            needsReview -> "[REVIEW]"
            findings.any { it.detected } || evidence.any { it.detected } -> "[DETECTED]"
            findings.any { it.needsReview } -> "[REVIEW]"
            else -> "[OK]"
        }

    private fun BypassResult.statusTag(): String =
        when {
            detected -> "[DETECTED]"
            needsReview -> "[REVIEW]"
            else -> "[OK]"
        }

    private fun CategoryResult?.summary(): String =
        this?.findings?.firstOrNull()?.description ?: this?.evidence?.firstOrNull()?.description ?: "-"

    private fun ExposureStatus.exportLabel(): String = name.lowercase().replace('_', ' ')

    private fun ExposureStatus.meaning(): String =
        when (this) {
            ExposureStatus.REMOTE_ENDPOINT_DISCOVERED -> "Remote proxy or API endpoint data was exposed."
            ExposureStatus.PUBLIC_IP_ONLY -> "Public IP observations were visible without endpoint credentials."
            ExposureStatus.LOCAL_PROXY_OR_API_ONLY -> "Only local proxy or local API artifacts were visible."
            ExposureStatus.TECHNICAL_SIGNAL_ONLY -> "Only local technical signals were visible."
            ExposureStatus.INSUFFICIENT_DATA -> "No positive signal was strong enough for a richer conclusion."
        }
}

private data class SectionRow(
    val name: String,
    val status: String,
    val summary: String?,
)
