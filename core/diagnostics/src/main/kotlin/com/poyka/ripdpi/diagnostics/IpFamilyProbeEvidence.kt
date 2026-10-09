package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.serialization.RipDpiJson

fun parseIpFamilyProbeEvidence(details: List<ProbeDetail>): IpFamilyProbeEvidence? =
    parseIpFamilyProbeEvidence(details.singleOrNull { it.key == "ipFamilyEvidence" }?.value)

fun parseIpFamilyProbeEvidence(value: String?): IpFamilyProbeEvidence? =
    value?.takeIf { it.length <= IpEvidenceMaxCharacters }?.let {
        runCatching { IpEvidenceJson.decodeFromString(IpFamilyProbeEvidence.serializer(), it) }
            .getOrNull()
            ?.validatedIpFamilyProbeEvidence()
    }

fun IpFamilyProbeEvidence.validatedIpFamilyProbeEvidence(): IpFamilyProbeEvidence? =
    takeIf { hasValidIpBounds() && hasValidIpDestination() && hasValidIpOutcome() }

private fun IpFamilyProbeEvidence.hasValidIpBounds(): Boolean =
    version == 1 && destinationPort in 1..IpProbeMaxPort && attemptCount in 0..IpEvidenceMaxAttempts &&
        (durationMs == null || durationMs in 0..IpEvidenceMaxDurationMs) &&
        (reason == null || reason in IpEvidenceReasons) &&
        (resolverSource == null || resolverSource == "NETWORK_SNAPSHOT") &&
        (prefixLength == null || prefixLength in IpEvidencePrefixLengths)

private fun IpFamilyProbeEvidence.hasValidIpDestination(): Boolean {
    val destinationValid =
        destinationAddress?.let {
            if (family == IpProbeFamily.IPV4) isIpProbeV4(it) else isIpProbeV6(it)
        } ?: true
    val prefixValid = prefix == null || (prefixLength != null && isIpProbeV6(prefix))
    return destinationValid && prefixValid
}

private fun IpFamilyProbeEvidence.hasValidIpOutcome(): Boolean =
    status != IpProbeStatus.REACHABLE ||
        (
            stage == IpProbeStage.TCP_CONNECT && pathScope == IpProbePathScope.RAW_PATH && attemptCount > 0 &&
                (family != IpProbeFamily.NAT64 || discoveryStatus == Nat64DiscoveryStatus.DISCOVERED)
        )

internal fun List<ProbeDetail>.withoutIpFamilyScopeAuthority(): List<ProbeDetail> {
    val evidence =
        parseIpFamilyProbeEvidence(this)?.copy(
            status = IpProbeStatus.NOT_OBSERVED,
            reason = "network_scope_unverified",
        )
    return filterNot { it.key == "ipFamilyEvidence" } +
        listOfNotNull(
            evidence?.let {
                ProbeDetail("ipFamilyEvidence", IpEvidenceJson.encodeToString(IpFamilyProbeEvidence.serializer(), it))
            },
        )
}

internal fun canonicalIpFamilyProbeEvidence(value: String?): String? =
    parseIpFamilyProbeEvidence(value)?.let {
        IpEvidenceJson.encodeToString(
            IpFamilyProbeEvidence.serializer(),
            it.copy(destinationAddress = null, prefix = null),
        )
    }

internal fun List<ProbeResult>.ipFamilySummaryLines(): List<String> =
    asSequence()
        .filter { it.probeType == "ip_family" }
        .take(IpEvidenceMaxRows)
        .mapNotNull { result ->
            parseIpFamilyProbeEvidence(result.details)?.let {
                "ipFamily=${it.family}; stage=${it.stage}; status=${it.status}; " +
                    "discovery=${it.discoveryStatus ?: "NOT_RUN"}; attempts=${it.attemptCount}; " +
                    "scope=${it.pathScope}; providerInterference=unverified"
            }
        }.toList()

private const val IpEvidenceMaxCharacters = 2_048
private const val IpEvidenceMaxAttempts = 16
private const val IpEvidenceMaxDurationMs = 120_000L
private const val IpEvidenceMaxRows = 32
private val IpEvidenceJson = RipDpiJson
private val IpEvidencePrefixLengths = setOf(32, 40, 48, 56, 64, 96)
private val IpEvidenceReasons =
    setOf(
        "timeout",
        "connection_refused",
        "network_unreachable",
        "host_unreachable",
        "permission_denied",
        "io_error",
        "no_network_dns",
        "dns_timeout",
        "dns_error",
        "dns_no_data",
        "invalid_dns64_response",
        "ambiguous_prefix",
        "unsupported_path",
        "invalid_config",
        "cancelled",
        "deadline_exceeded",
        "no_prefix",
        "network_scope_unverified",
    )

internal fun bucketIpFamily(outcome: String): DiagnosticsOutcomeBucket =
    when (outcome) {
        "ip_family_reachable" -> DiagnosticsOutcomeBucket.Healthy
        "ip_family_unavailable" -> DiagnosticsOutcomeBucket.Attention
        else -> DiagnosticsOutcomeBucket.Inconclusive
    }
