package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.StrategyProbeTargetCohortSpec
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local evidence. A restart or expired history restores the catalog order. */
@Singleton
class DiagnosticsTargetOptimizer
    @Inject
    constructor() {
        private data class Scope(
            val fingerprint: String,
            val profile: String,
            val path: ScanPathMode,
            val settings: com.poyka.ripdpi.proto.AppSettings,
        ) {
            fun seed() = "$fingerprint|$profile|${path.name}".hashCode()
        }

        private data class Evidence(
            val finishedAt: Long,
            val results: Map<String, List<ProbeResult>>,
        )

        private val evidence = linkedMapOf<Scope, List<Evidence>>()

        @Synchronized
        internal fun record(
            fingerprint: String,
            report: ScanReport,
            settings: com.poyka.ripdpi.proto.AppSettings,
        ) {
            val scope = Scope(fingerprint, report.profileId, report.pathMode, settings)
            val partialStrategy =
                report.strategyProbeReport?.completionKind?.let {
                    it !=
                        StrategyProbeCompletionKind.NORMAL
                }
            if (report.completionKind != ScanCompletionKind.NORMAL || partialStrategy == true ||
                report.results.size > MaxResults
            ) {
                evidence.remove(scope)
                return
            }
            val history = evidence[scope].orEmpty()
            if (history.any { it.finishedAt == report.finishedAt }) return
            val results =
                report.results
                    .filter { it.probeType in DomainProbeTypes || it.probeType in QuicProbeTypes }
                    .map { result ->
                        val host = result.detailValue("targetHost") ?: result.inferEdgeHost().orEmpty()
                        result.copy(
                            target = host,
                            details = result.details.filter { it.key == "candidateId" || it.key in CostKeys },
                        )
                    }.groupBy { it.target.lowercase(Locale.ROOT) }
            evidence.remove(scope)
            evidence[scope] =
                (history + Evidence(report.finishedAt, results))
                    .sortedByDescending(Evidence::finishedAt)
                    .take(HistorySize)
            while (evidence.size > MaxScopes) evidence.remove(evidence.keys.first())
        }

        internal fun compact(
            intent: DiagnosticsIntent,
            fingerprint: String?,
            origin: DiagnosticsScanOrigin,
            policy: ActiveProbeSafetyPolicy,
            now: Long,
        ): DiagnosticsIntent {
            val dedicatedMeasurement =
                listOfNotNull(
                    intent.selectiveMatrix,
                    intent.http3Probe,
                    intent.pmtuProbe,
                    intent.ipFamilyProbe,
                ).isNotEmpty()
            return if (origin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND && fingerprint != null &&
                !dedicatedMeasurement
            ) {
                val scope = Scope(fingerprint, intent.profileId, intent.requestedPathMode, intent.settings)
                val history = synchronized(this) { evidence[scope].orEmpty() }
                if (history.size == HistorySize && history.all { now - it.finishedAt in 0..EvidenceTtlMs }) {
                    compactWithEvidence(intent, history, scope, policy) ?: intent
                } else {
                    intent
                }
            } else {
                intent
            }
        }

        private fun compactWithEvidence(
            intent: DiagnosticsIntent,
            history: List<Evidence>,
            scope: Scope,
            policy: ActiveProbeSafetyPolicy,
        ): DiagnosticsIntent? {
            val cohorts = policy.validAutomaticAuditCohorts(intent)
            return if (cohorts.isNotEmpty()) {
                selectCohort(cohorts, history, scope)?.let { intent.copy(strategyProbeTargetCohorts = listOf(it)) }
            } else {
                compactIndividualTargets(intent, history, scope, policy)
            }
        }

        private fun compactIndividualTargets(
            intent: DiagnosticsIntent,
            history: List<Evidence>,
            scope: Scope,
            policy: ActiveProbeSafetyPolicy,
        ): DiagnosticsIntent? {
            val domains =
                intent.domainTargets.distinctBy { it.host.lowercase(Locale.ROOT) }.map {
                    probe(it.host, it.isControl || it.concurrencyProbe != null, history, scope.path, quic = false)
                }
            val quic =
                intent.quicTargets
                    .distinctBy { it.host.lowercase(Locale.ROOT) }
                    .map { probe(it.host, false, history, scope.path, quic = true) }
            val domainIds =
                CompactProbeSearch.select(
                    domains,
                    policy.automaticAuditDomainTargets,
                    scope.seed(),
                    minimum = minOf(domains.size, policy.automaticAuditDomainTargets).coerceAtLeast(1),
                )
            val quicIds =
                if (quic.isEmpty()) {
                    emptySet()
                } else {
                    CompactProbeSearch.select(
                        quic,
                        policy.automaticAuditQuicTargets,
                        scope.seed(),
                        minimum = minOf(quic.size, policy.automaticAuditQuicTargets),
                    )
                }
            return if (domainIds != null && quicIds != null) {
                intent.copy(
                    domainTargets = intent.domainTargets.filter { it.host.lowercase(Locale.ROOT) in domainIds },
                    quicTargets = intent.quicTargets.filter { it.host.lowercase(Locale.ROOT) in quicIds },
                )
            } else {
                null
            }
        }

        private fun selectCohort(
            cohorts: List<StrategyProbeTargetCohortSpec>,
            history: List<Evidence>,
            scope: Scope,
        ): StrategyProbeTargetCohortSpec? {
            val probes =
                cohorts.map { cohort ->
                    val members =
                        cohort.domainTargets.map {
                            probe(
                                it.host,
                                // A complete registered cohort retains its own concurrency contract.
                                it.isControl,
                                history,
                                scope.path,
                                quic = false,
                            ).let { probe -> probe.copy(id = "web:${probe.id}") }
                        } +
                            cohort.quicTargets.map {
                                probe(it.host, false, history, scope.path, quic = true)
                                    .let { probe -> probe.copy(id = "quic:${probe.id}") }
                            }
                    val signals =
                        members
                            .flatMap { member ->
                                member.signals + if (member.mandatory) setOf("required:${member.id}") else emptySet()
                            }.toSet()
                    CompactProbe(cohort.id, signals, members.sumOf(CompactProbe::cost), mandatory = false)
                }
            val selected = CompactProbeSearch.select(probes, 1, scope.seed())?.singleOrNull() ?: return null
            return cohorts.firstOrNull { it.id == selected }
        }

        private fun probe(
            host: String,
            required: Boolean,
            history: List<Evidence>,
            path: ScanPathMode,
            quic: Boolean,
        ): CompactProbe {
            val id = host.lowercase(Locale.ROOT)
            val observations =
                history.map {
                    it.results[id].orEmpty().filter { result ->
                        result.probeType in (if (quic) QuicProbeTypes else DomainProbeTypes)
                    }
                }
            val signals =
                observations
                    .flatMapIndexed { index, results ->
                        results.map {
                            "$index:${it.probeType}:${it.detailValue(
                                "candidateId",
                            ).orEmpty()}:${it.outcome}"
                        }
                    }.toSet()
            val uncertain =
                observations.any { results ->
                    results.none { it.probeType in (if (quic) QuicProbeTypes else DomainTransportTypes) } ||
                        (
                            !quic &&
                                results.none {
                                    it.probeType == "domain_reachability" ||
                                        it.detailValue("candidateId") == "baseline_plain_direct"
                                }
                        ) ||
                        results.any { result ->
                            !DiagnosticsOutcomeTaxonomy
                                .classifyProbeOutcome(result.probeType, path, result.outcome)
                                .healthyEnoughForSummary
                        }
                }
            val cost =
                observations
                    .flatten()
                    .sumOf { result ->
                        result.details
                            .firstOrNull { it.key in CostKeys }
                            ?.value
                            ?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?.coerceAtMost(MaxMeasuredCostMs)
                            ?: DefaultCost
                    }.coerceAtLeast(1.0)
            return CompactProbe(id, signals, cost, required || uncertain)
        }

        private companion object {
            const val HistorySize = 3
            const val MaxScopes = 16
            const val MaxResults = 256
            const val EvidenceTtlMs = 30 * 60 * 1_000L
            const val DefaultCost = 1_000.0
            const val MaxMeasuredCostMs = 360_000.0
            val DomainTransportTypes = setOf("domain_reachability", "strategy_http", "strategy_https")
            val DomainProbeTypes = setOf("domain_reachability", "strategy_http", "strategy_https", "dns_integrity")
            val QuicProbeTypes = setOf("quic_reachability", "strategy_quic")
            val CostKeys = setOf("latencyMs", "elapsedMs", "durationMs", "encryptedLatencyMs", "udpLatencyMs")
        }
    }
