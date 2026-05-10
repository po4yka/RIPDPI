package com.poyka.ripdpi.core.detection.consensus

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.CdnPullingEndpointStatus
import com.poyka.ripdpi.core.detection.CdnPullingResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.IpComparisonResult

enum class IpConsensusChannel {
    GEO_IP,
    IP_COMPARISON_RU,
    IP_COMPARISON_NON_RU,
    CDN_PULLING,
    BYPASS_DIRECT,
    BYPASS_PROXY,
    BYPASS_STUN,
    ;

    fun label(): String =
        name
            .lowercase()
            .replace('_', ' ')
}

data class IpObservation(
    val channel: IpConsensusChannel,
    val ip: String,
)

data class IpAsnInfo(
    val ip: String,
    val asn: String?,
    val countryCode: String?,
    val org: String? = null,
)

fun interface IpAsnResolver {
    fun resolve(ip: String): IpAsnInfo?
}

data class ChannelConflict(
    val channel: IpConsensusChannel,
    val ips: List<String>,
    val confidence: EvidenceConfidence,
)

data class CrossChannelMismatch(
    val leftChannel: IpConsensusChannel,
    val leftIps: List<String>,
    val rightChannel: IpConsensusChannel,
    val rightIps: List<String>,
    val confidence: EvidenceConfidence,
)

data class IpConsensusResult(
    val observedIps: Map<IpConsensusChannel, List<String>>,
    val channelConflicts: List<ChannelConflict>,
    val crossChannelMismatches: List<CrossChannelMismatch>,
    val foreignIps: List<String>,
    val warpIndicator: Boolean,
    val asnByIp: Map<String, IpAsnInfo> = emptyMap(),
) {
    fun toCategoryResult(): CategoryResult {
        val findings =
            buildList {
                if (observedIps.isEmpty()) {
                    add(Finding("No public IP observations collected"))
                } else {
                    observedIps.forEach { (channel, ips) ->
                        add(Finding("${channel.label()}: ${ips.joinToString()}"))
                    }
                }
                channelConflicts.forEach { conflict ->
                    add(
                        Finding(
                            description =
                                "Channel conflict in ${conflict.channel.label()}: " +
                                    conflict.ips.joinToString(),
                            needsReview = true,
                            source = EvidenceSource.IP_CONSENSUS,
                            confidence = conflict.confidence,
                        ),
                    )
                }
                crossChannelMismatches.forEach { mismatch ->
                    add(
                        Finding(
                            description =
                                "Cross-channel IP mismatch: ${mismatch.leftChannel.label()}=" +
                                    "${mismatch.leftIps.joinToString()} vs ${mismatch.rightChannel.label()}=" +
                                    mismatch.rightIps.joinToString(),
                            needsReview = true,
                            source = EvidenceSource.IP_CONSENSUS,
                            confidence = mismatch.confidence,
                        ),
                    )
                }
                if (foreignIps.isNotEmpty()) {
                    add(Finding("Foreign public IPs: ${foreignIps.joinToString()}"))
                }
                if (warpIndicator) {
                    add(
                        Finding(
                            description = "Warp-like path indicator: Cloudflare ASN on underlying path",
                            needsReview = true,
                            source = EvidenceSource.IP_CONSENSUS,
                            confidence = EvidenceConfidence.HIGH,
                        ),
                    )
                }
            }
        val needsReview = channelConflicts.isNotEmpty() || crossChannelMismatches.isNotEmpty() || warpIndicator
        val evidence =
            if (needsReview) {
                listOf(
                    EvidenceItem(
                        source = EvidenceSource.IP_CONSENSUS,
                        detected = warpIndicator,
                        confidence = EvidenceConfidence.HIGH,
                        description = "IP consensus found channel divergence or Warp-like path signals",
                    ),
                )
            } else {
                emptyList()
            }
        return CategoryResult(
            name = "IP consensus",
            detected = warpIndicator,
            findings = findings,
            needsReview = needsReview && !warpIndicator,
            evidence = evidence,
        )
    }
}

object IpConsensusBuilder {
    fun build(
        geoIp: CategoryResult,
        bypassResult: BypassResult,
        ipComparison: IpComparisonResult?,
        cdnPulling: CdnPullingResult?,
    ): IpConsensusResult {
        val geoIpInfo = geoIp.asnInfoFromFindings()
        val observations =
            buildList {
                geoIpInfo?.ip?.let { add(IpObservation(IpConsensusChannel.GEO_IP, it)) }
                ipComparison?.ruReflectedIps?.forEach { add(IpObservation(IpConsensusChannel.IP_COMPARISON_RU, it)) }
                ipComparison?.nonRuReflectedIps?.forEach {
                    add(IpObservation(IpConsensusChannel.IP_COMPARISON_NON_RU, it))
                }
                cdnPulling
                    ?.endpoints
                    .orEmpty()
                    .filter { it.status == CdnPullingEndpointStatus.OK && it.reflectedIp != null }
                    .forEach { add(IpObservation(IpConsensusChannel.CDN_PULLING, it.reflectedIp.orEmpty())) }
                bypassResult.directIp?.let { add(IpObservation(IpConsensusChannel.BYPASS_DIRECT, it)) }
                bypassResult.proxyIp?.let { add(IpObservation(IpConsensusChannel.BYPASS_PROXY, it)) }
                bypassResult.stunReflexiveAddresses.forEach { add(IpObservation(IpConsensusChannel.BYPASS_STUN, it)) }
            }
        val knownInfo = geoIpInfo?.let { mapOf(it.ip to it) }.orEmpty()
        return build(observations = observations, asnResolver = MapBackedIpAsnResolver(knownInfo))
    }

    fun build(
        observations: List<IpObservation>,
        asnResolver: IpAsnResolver = IpAsnResolver { null },
    ): IpConsensusResult {
        val observedIps =
            observations
                .groupBy(IpObservation::channel, IpObservation::ip)
                .mapValues { (_, ips) -> ips.distinct().sorted() }
                .filterValues { it.isNotEmpty() }
        val asnByIp =
            observedIps
                .values
                .flatten()
                .distinct()
                .associateWith { ip -> asnResolver.resolve(ip) ?: IpAsnInfo(ip, null, null) }

        return IpConsensusResult(
            observedIps = observedIps,
            channelConflicts = channelConflicts(observedIps),
            crossChannelMismatches = crossChannelMismatches(observedIps),
            foreignIps = foreignIps(asnByIp),
            warpIndicator = warpIndicator(observedIps, asnByIp),
            asnByIp = asnByIp,
        )
    }

    private fun channelConflicts(observedIps: Map<IpConsensusChannel, List<String>>): List<ChannelConflict> =
        observedIps
            .filterValues { it.size >= 2 }
            .map { (channel, ips) ->
                ChannelConflict(
                    channel = channel,
                    ips = ips,
                    confidence = EvidenceConfidence.HIGH,
                )
            }

    private fun crossChannelMismatches(
        observedIps: Map<IpConsensusChannel, List<String>>,
    ): List<CrossChannelMismatch> {
        val ruIps = observedIps[IpConsensusChannel.IP_COMPARISON_RU].orEmpty()
        val nonRuIps = observedIps[IpConsensusChannel.IP_COMPARISON_NON_RU].orEmpty()
        if (ruIps.isEmpty() || nonRuIps.isEmpty() || ruIps.toSet() == nonRuIps.toSet()) return emptyList()
        return listOf(
            CrossChannelMismatch(
                leftChannel = IpConsensusChannel.IP_COMPARISON_RU,
                leftIps = ruIps,
                rightChannel = IpConsensusChannel.IP_COMPARISON_NON_RU,
                rightIps = nonRuIps,
                confidence = EvidenceConfidence.HIGH,
            ),
        )
    }

    private fun foreignIps(asnByIp: Map<String, IpAsnInfo>): List<String> =
        asnByIp
            .values
            .filter { !it.countryCode.isNullOrBlank() && !it.countryCode.equals("RU", ignoreCase = true) }
            .map(IpAsnInfo::ip)
            .sorted()

    private fun warpIndicator(
        observedIps: Map<IpConsensusChannel, List<String>>,
        asnByIp: Map<String, IpAsnInfo>,
    ): Boolean {
        val directAsns =
            observedIps[IpConsensusChannel.BYPASS_DIRECT]
                .orEmpty()
                .mapNotNull { ip -> asnByIp[ip]?.asn?.cleanAsn() }
        val proxyAsns =
            observedIps[IpConsensusChannel.BYPASS_PROXY]
                .orEmpty()
                .mapNotNull { ip -> asnByIp[ip]?.asn?.cleanAsn() }
        return directAsns.any { asn -> asn == CloudflareAsn } &&
            proxyAsns.any { asn -> asn != CloudflareAsn }
    }

    private fun CategoryResult.asnInfoFromFindings(): IpAsnInfo? {
        val ip = findings.firstValue("IP: ") ?: return null
        val asn = findings.firstValue("ASN: ")
        val countryCode =
            COUNTRY_CODE_REGEX
                .find(findings.firstValue("Country: ").orEmpty())
                ?.groupValues
                ?.get(1)
        val org = findings.firstValue("Org: ")
        return IpAsnInfo(ip = ip, asn = asn, countryCode = countryCode, org = org)
    }

    private fun List<Finding>.firstValue(prefix: String): String? =
        firstNotNullOfOrNull { finding ->
            finding.description
                .takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()
                ?.takeIf { value -> value.isNotBlank() && value != "N/A" }
        }

    private fun String.cleanAsn(): String = removePrefix("AS").trim()

    private class MapBackedIpAsnResolver(
        private val infoByIp: Map<String, IpAsnInfo>,
    ) : IpAsnResolver {
        override fun resolve(ip: String): IpAsnInfo? = infoByIp[ip]
    }

    private const val CloudflareAsn = "13335"
    private val COUNTRY_CODE_REGEX = Regex("""\(([A-Z]{2})\)""")
}
