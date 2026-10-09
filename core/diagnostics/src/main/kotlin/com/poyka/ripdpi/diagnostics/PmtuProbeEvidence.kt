package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.json.Json

fun parsePmtuProbeEvidence(details: List<ProbeDetail>): PmtuProbeEvidence? =
    parsePmtuProbeEvidence(details.singleOrNull { it.key == "pmtuEvidence" }?.value)

fun parsePmtuProbeEvidence(value: String?): PmtuProbeEvidence? =
    value?.takeIf { it.length <= PmtuMaxEvidenceCharacters }?.let {
        runCatching { PmtuEvidenceJson.decodeFromString(PmtuProbeEvidence.serializer(), it) }
            .getOrNull()
            ?.validatedPmtuProbeEvidence()
    }

fun PmtuProbeEvidence.validatedPmtuProbeEvidence(): PmtuProbeEvidence? =
    takeIf { validBounds() && validProgress() && validOutcome() }

private fun PmtuProbeEvidence.validBounds(): Boolean =
    version == 1 && method == "QUIC_DPLPMTUD" && initialUdpPayloadBytes == PmtuInitialPayloadBytes &&
        validSizes() && validCountersAndTimes() &&
        (alpn == null || alpn == "h3") && (reason == null || reason in PmtuReasons) && validPeer()

private fun PmtuProbeEvidence.validSizes(): Boolean {
    val upper = configuredUpperBoundUdpPayloadBytes
    val acknowledged = acknowledgedUdpPayloadLowerBoundBytes
    return upper in (PmtuInitialPayloadBytes + 1)..PmtuMaxPayloadBytes &&
        (currentUdpPayloadBytes == null || currentUdpPayloadBytes in initialUdpPayloadBytes..upper) &&
        (acknowledged == null || acknowledged in (initialUdpPayloadBytes + 1)..upper)
}

private fun PmtuProbeEvidence.validCountersAndTimes(): Boolean =
    sentProbeCount in 0..PmtuMaxProbeCount && lostProbeCount in 0..sentProbeCount &&
        blackHoleCount in 0..PmtuMaxProbeCount &&
        listOfNotNull(durationMs, handshakeElapsedMs).all { it in 0..PmtuMaxElapsedMs } &&
        (handshakeElapsedMs == null || durationMs == null || handshakeElapsedMs <= durationMs)

private fun PmtuProbeEvidence.validPeer(): Boolean =
    peerAddress == null ||
        when (addressFamily) {
            PmtuAddressFamily.IPV4 -> isIpProbeV4(peerAddress)
            PmtuAddressFamily.IPV6 -> isIpProbeV6(peerAddress)
        }

private fun PmtuProbeEvidence.validProgress(): Boolean = validTransportProgress() && validConfirmationProgress()

private fun PmtuProbeEvidence.validTransportProgress(): Boolean =
    (!tlsValidated || (fragmentationPrevented && alpn == "h3" && handshakeElapsedMs != null)) &&
        (currentUdpPayloadBytes == null || tlsValidated) &&
        (sentProbeCount == 0L || tlsValidated) &&
        (blackHoleCount == 0L || tlsValidated) &&
        (!tlsValidated || pathScope == PmtuPathScope.RAW_PATH)

private fun PmtuProbeEvidence.validConfirmationProgress(): Boolean =
    (
        acknowledgedUdpPayloadLowerBoundBytes == null ||
            (tlsValidated && sentProbeCount > lostProbeCount && confirmedSizeMatches())
    ) &&
        (!configuredUpperBoundReached || confirmedCeilingMatches()) &&
        (!observationWindowComplete || tlsValidated)

private fun PmtuProbeEvidence.confirmedCeilingMatches(): Boolean =
    acknowledgedUdpPayloadLowerBoundBytes == configuredUpperBoundUdpPayloadBytes

private fun PmtuProbeEvidence.confirmedSizeMatches(): Boolean =
    currentUdpPayloadBytes == acknowledgedUdpPayloadLowerBoundBytes

private fun PmtuProbeEvidence.validOutcome(): Boolean =
    when (status) {
        PmtuProbeStatus.OBSERVED -> {
            acknowledgedUdpPayloadLowerBoundBytes != null && observationWindowComplete
        }

        PmtuProbeStatus.UNSUPPORTED, PmtuProbeStatus.INVALID -> {
            !tlsValidated && currentUdpPayloadBytes == null && acknowledgedUdpPayloadLowerBoundBytes == null &&
                sentProbeCount == 0L && lostProbeCount == 0L && blackHoleCount == 0L &&
                !observationWindowComplete && !configuredUpperBoundReached && handshakeElapsedMs == null
        }

        else -> {
            true
        }
    }

internal fun List<ProbeDetail>.withoutPmtuScopeAuthority(): List<ProbeDetail> {
    val evidence =
        parsePmtuProbeEvidence(
            this,
        )?.copy(status = PmtuProbeStatus.NOT_OBSERVED, reason = "network_scope_unverified")
    return filterNot { it.key == "pmtuEvidence" } +
        listOfNotNull(
            evidence?.let {
                ProbeDetail(
                    "pmtuEvidence",
                    PmtuEvidenceJson.encodeToString(PmtuProbeEvidence.serializer(), it),
                )
            },
        )
}

internal fun canonicalPmtuProbeEvidence(value: String?): String? =
    parsePmtuProbeEvidence(value)?.let {
        PmtuEvidenceJson.encodeToString(PmtuProbeEvidence.serializer(), it.copy(peerAddress = null))
    }

internal fun List<ProbeResult>.pmtuSummaryLines(): List<String> =
    asSequence()
        .filter { it.probeType == "pmtu" }
        .take(2)
        .mapNotNull { result ->
            parsePmtuProbeEvidence(result.details)?.let {
                "method=${it.method}; family=${it.addressFamily}; status=${it.status}; " +
                    "acknowledgedUdpPayloadLowerBoundBytes=${it.acknowledgedUdpPayloadLowerBoundBytes ?: "unknown"}; " +
                    "ceiling=${it.configuredUpperBoundUdpPayloadBytes}; " +
                    "sent=${it.sentProbeCount}; lost=${it.lostProbeCount}; " +
                    "windowComplete=${it.observationWindowComplete}; scope=${it.pathScope}; " +
                    "networkScopeUnverified=${it.reason == "network_scope_unverified"}"
            }
        }.toList()

internal fun bucketPmtu(outcome: String): DiagnosticsOutcomeBucket =
    if (outcome == "pmtu_observed") DiagnosticsOutcomeBucket.Healthy else DiagnosticsOutcomeBucket.Inconclusive

private val PmtuEvidenceJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
private const val PmtuMaxEvidenceCharacters = 4_096
private const val PmtuMaxProbeCount = 10_000L
private const val PmtuMaxElapsedMs = 60_000L
private val PmtuReasons =
    setOf(
        "invalid_config",
        "unsupported_path",
        "cancelled",
        "deadline_exceeded",
        "timeout",
        "dns_error",
        "dns_timeout",
        "no_address",
        "no_family_address",
        "protect_failed",
        "io_error",
        "tls_error",
        "quic_error",
        "alpn_mismatch",
        "fragmentation_possible",
        "fragmentation_unsupported",
        "no_acknowledged_probe",
        "observation_complete",
        "connection_closed",
        "network_scope_unverified",
    )
