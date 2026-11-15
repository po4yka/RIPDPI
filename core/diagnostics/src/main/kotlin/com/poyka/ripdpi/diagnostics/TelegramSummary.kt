package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument

/** Project port numbers and known states before archive redaction removes dial addresses. */
internal fun DiagnosticsSummaryDocument.withTelegramEvidence(
    report: EngineScanReportWire?,
): DiagnosticsSummaryDocument {
    val result = report?.results?.firstOrNull { it.probeType == "telegram_availability" } ?: return this
    val details = result.details.associate { it.key to it.value }
    val lines =
        buildList {
            val verdict = details["verdict"] ?: result.outcome
            add("telegramOutcome=${telegramSummaryState(verdict)}")
            add("telegramDownload=${telegramSummaryState(details["downloadStatus"])}")
            add("telegramUpload=${telegramSummaryState(details["uploadStatus"])}")
            val reachable = details["dcReachable"].toTelegramCount()
            val attempted = details["dcTotal"].toTelegramCount()
            val planned = details["dcPlanned"].toTelegramCount()
            if (reachable != null && attempted != null && reachable <= attempted) {
                if (planned != null && attempted <= planned) {
                    add("telegramTcpEndpoints reachable=$reachable attempted=$attempted planned=$planned")
                } else if (planned == null) {
                    add("telegramDataCenters reachable=$reachable measured=$attempted")
                }
            }
            val sockets =
                details["dcResults"]
                    .orEmpty()
                    .splitToSequence('|')
                    .flatMap(::telegramPortFacts)
                    .take(MaxTelegramSummaryPorts + 1)
                    .toList()
            sockets.take(MaxTelegramSummaryPorts).forEachIndexed { index, fact ->
                add("telegramTcp[$index] $fact")
            }
            if (sockets.size > MaxTelegramSummaryPorts) add("telegramTcpTruncated=true")
            val wsPath = telegramPathFact(details["wsTunnelPath"])
            add("telegramWssOpening status=${telegramSummaryState(details["wsTunnelStatus"])}$wsPath")
        }
    return copy(reportMetadata = reportMetadata.copy(lines = reportMetadata.lines + lines))
}

private fun telegramPortFacts(row: String): Sequence<String> {
    val parts = row.split(':', limit = 4)
    val evidence = parts.getOrNull(TelegramEvidenceIndex).orEmpty()
    return when {
        evidence.startsWith("ports=") -> {
            evidence
                .removePrefix("ports=")
                .splitToSequence(',')
                .mapNotNull { wire ->
                    val values = wire.split(':', limit = 3)
                    val port = values[0].toTelegramPort() ?: return@mapNotNull null
                    "port=$port status=${telegramSummaryState(values.getOrNull(1))}"
                }
        }

        evidence.startsWith("address=") -> {
            telegramAddressPortFacts(parts, evidence)
        }

        else -> {
            emptySequence()
        }
    }
}

private fun telegramAddressPortFacts(
    parts: List<String>,
    evidence: String,
): Sequence<String> {
    val fields = evidence.splitToSequence(';').iterator()
    val port = fields.next().substringAfterLast(':', "").toTelegramPort() ?: return emptySequence()
    val family = fields.asSequence().firstOrNull()?.removePrefix("family=")
    // The final fields may contain untrusted report text. Emit only known tokens.
    val path = evidence.substringAfter(";path=", "").substringBefore(';')
    val familyFact = if (family in TelegramFamilies) " family=$family" else ""
    return sequenceOf("port=$port status=${telegramSummaryState(parts[1])}$familyFact${telegramPathFact(path)}")
}

private fun telegramSummaryState(value: String?): String =
    when {
        value == null -> "not_run"
        value in TelegramSummaryStates -> value
        else -> "unknown"
    }

private fun telegramPathFact(value: String?): String = if (value in TelegramPaths) " path=$value" else ""

private fun String?.toTelegramCount(): Int? = this?.toIntOrNull()?.takeIf { it >= 0 }

private fun String.toTelegramPort(): Int? = toIntOrNull()?.takeIf { it in 1..IpProbeMaxPort }

private const val TelegramEvidenceIndex = 3
private const val MaxTelegramSummaryPorts = 32
private val TelegramFamilies = setOf("ipv4", "ipv6")
private val TelegramPaths = setOf("direct", "direct_route_experiment", "socks5")
private val TelegramSummaryStates =
    setOf(
        "ok",
        "slow",
        "partial",
        "stalled",
        "blocked",
        "fail",
        "error",
        "unreachable",
        "unknown",
        "not_run",
        "capability_skipped",
        "invalid_target",
        "cancelled",
        "deadline_exceeded",
        "open_timed_out",
        "subprotocol_not_negotiated",
        "upgrade_rejected",
        "upgrade_failed",
    )
