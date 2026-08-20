package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire

private val ArchiveCapabilityTokens =
    setOf(
        "ttl_write",
        "raw_tcp_fake_send",
        "raw_udp_fragmentation",
        "replacement_socket",
        "root_helper_available",
        "vpn_protect_callback",
        "network_binding",
    )

internal fun EngineScanReportWire.projectStrategyEvidenceForArchive(): EngineScanReportWire {
    val strategyProbe = strategyProbeReport ?: return this
    val tcpAliases = strategyProbe.tcpCandidates.archiveAliases("tcp")
    val quicAliases = strategyProbe.quicCandidates.archiveAliases("quic")
    val aliases = tcpAliases + quicAliases
    return copy(
        strategyProbeReport = strategyProbe.projectForArchive(tcpAliases, quicAliases),
        observations = observations.map { it.projectStrategyForArchive(aliases) },
    )
}

private fun List<StrategyProbeCandidateSummary>.archiveAliases(lane: String): Map<String, String> =
    mapIndexed { index, candidate -> candidate.id to "$lane-candidate-${index + 1}" }.toMap()

private fun StrategyProbeReport.projectForArchive(
    tcpAliases: Map<String, String>,
    quicAliases: Map<String, String>,
): StrategyProbeReport {
    val aliases = tcpAliases + quicAliases
    return copy(
        tcpCandidates = tcpCandidates.map { it.projectForArchive("tcp", tcpAliases.getValue(it.id)) },
        quicCandidates = quicCandidates.map { it.projectForArchive("quic", quicAliases.getValue(it.id)) },
        recommendation =
            recommendation?.let { current ->
                current.copy(
                    tcpCandidateId = aliases[current.tcpCandidateId] ?: "unavailable",
                    tcpCandidateLabel = aliases[current.tcpCandidateId] ?: "unavailable",
                    tcpCandidateFamily = current.tcpCandidateFamily?.let { "tcp_candidate" },
                    quicCandidateId = aliases[current.quicCandidateId] ?: "unavailable",
                    quicCandidateLabel = aliases[current.quicCandidateId] ?: "unavailable",
                    quicCandidateFamily = current.quicCandidateFamily?.let { "quic_candidate" },
                    quicCandidateLayoutFamily = current.quicCandidateLayoutFamily?.let { "quic_layout" },
                    rationale = "candidate_recommendation",
                    recommendedProxyConfigJson = "redacted",
                )
            },
        domainStrategySeeds =
            domainStrategySeeds.map { seed ->
                val alias = aliases[seed.candidateId] ?: "unavailable"
                seed.copy(
                    domain = "redacted",
                    candidateId = alias,
                    candidateLabel = alias,
                    family = "candidate",
                )
            },
        targetSelection =
            targetSelection?.let { selection ->
                selection.copy(
                    cohortId = "cohort",
                    cohortLabel = "cohort",
                    domainHosts = selection.domainHosts.map { "redacted" },
                    quicHosts = selection.quicHosts.map { "redacted" },
                )
            },
        pilotBucketLabels = pilotBucketLabels.mapIndexed { index, _ -> "pilot-bucket-${index + 1}" },
    )
}

private fun StrategyProbeCandidateSummary.projectForArchive(
    lane: String,
    alias: String,
): StrategyProbeCandidateSummary =
    copy(
        id = alias,
        label = alias,
        family = "${lane}_candidate",
        quicLayoutFamily = quicLayoutFamily?.let { "quic_layout" },
        outcome = archiveStrategyOutcome(outcome),
        rationale = archiveStrategyOutcome(outcome),
        proxyConfigJson = proxyConfigJson?.let { "redacted" },
        notes =
            notes
                .flatMap { note -> ArchiveCapabilityTokens.filter(note::contains) }
                .distinct()
                .sorted(),
        domainOutcomes = domainOutcomes.map { it.copy(domain = "redacted") },
    )

private fun ObservationFact.projectStrategyForArchive(aliases: Map<String, String>): ObservationFact =
    copy(
        strategy =
            strategy?.let { fact ->
                val alias = fact.candidateId?.let(aliases::get) ?: "unavailable"
                fact.copy(
                    candidateId = alias,
                    candidateLabel = alias,
                    candidateFamily = fact.candidateFamily?.let { "candidate" },
                )
            },
    )

private fun archiveStrategyOutcome(value: String): String =
    value.lowercase().takeIf {
        it in
            setOf(
                "success",
                "partial",
                "failed",
                "skipped",
                "not_applicable",
                "eliminated",
                "unverified_execution",
            )
    } ?: "unavailable"
