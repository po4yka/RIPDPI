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
        // Evidence is per host, so profiles sharing a probe suite share it; unseen hosts stay mandatory.
        private data class Scope(
            val fingerprint: String,
            val path: ScanPathMode,
            val suite: String?,
            val settings: com.poyka.ripdpi.proto.AppSettings,
        ) {
            fun seed() = "$fingerprint|${path.name}".hashCode()
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
            val scope = scope(fingerprint, report.pathMode, report.strategyProbeReport?.suiteId, settings)
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
                    .filter { it.probeType in EvidenceProbeTypes }
                    .map { result ->
                        val host =
                            if (result.probeType == ConcurrencyProbeType) {
                                result.target
                            } else {
                                result.detailValue("targetHost") ?: result.inferEdgeHost().orEmpty()
                            }
                        result.copy(
                            target = host,
                            details = result.details.filter { it.key == "candidateId" || it.key in CostKeys },
                        )
                    }.groupBy {
                        val host = it.target.lowercase(Locale.ROOT)
                        if (it.probeType == ConcurrencyProbeType) ConcurrencyPrefix + host else host
                    }
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
                val suite = intent.strategyProbe?.suiteId
                val scope = scope(fingerprint, intent.requestedPathMode, suite, intent.settings)
                val history =
                    synchronized(this) { evidence[scope].orEmpty() }.filter { now - it.finishedAt in 0..EvidenceTtlMs }
                if (history.isNotEmpty()) {
                    compactWithEvidence(intent, history, scope, policy) ?: intent
                } else {
                    intent
                }
            } else {
                intent
            }
        }

        // Selecting a profile to run it manually must not hide that run from the automatic profile.
        private fun scope(
            fingerprint: String,
            path: ScanPathMode,
            suite: String?,
            settings: com.poyka.ripdpi.proto.AppSettings,
        ) = Scope(fingerprint, path, suite, settings.toBuilder().clearDiagnosticsActiveProfileId().build())

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
            val targets = intent.domainTargets.distinctBy { it.host.lowercase(Locale.ROOT) }
            val unitOfHost = targets.associate { it.host.lowercase(Locale.ROOT) to unitId(it) }
            val domains = domainUnits(targets, history, scope.path)
            val domainLimit = policy.automaticAuditDomainTargets
            val quic =
                intent.quicTargets
                    .distinctBy { it.host.lowercase(Locale.ROOT) }
                    .map { probe(it.host, false, history, scope.path, quic = true) }
            val domainIds =
                CompactProbeSearch.select(
                    domains,
                    domainLimit,
                    scope.seed(),
                    minimum = largestFit(domains, domainLimit),
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
                    domainTargets =
                        intent.domainTargets.filter { unitOfHost[it.host.lowercase(Locale.ROOT)] in domainIds },
                    quicTargets = intent.quicTargets.filter { it.host.lowercase(Locale.ROOT) in quicIds },
                )
            } else {
                null
            }
        }

        private fun unitId(target: DomainTarget) =
            target.concurrencyProbe?.let { "cohort:${it.cohortId}" } ?: target.host.lowercase(Locale.ROOT)

        /** A concurrency cohort is one unit, dropped only if each member had healthy concurrency in every report. */
        private fun domainUnits(
            targets: List<DomainTarget>,
            history: List<Evidence>,
            path: ScanPathMode,
        ): List<CompactProbe> =
            targets.groupBy(::unitId).map { (id, unit) ->
                val members = unit.map { probe(it.host, it.isControl, history, path, quic = false) }
                val concurrencyUnknown =
                    unit[0].concurrencyProbe != null &&
                        unit.any { target ->
                            history.any { evidence ->
                                val results = evidence.results[ConcurrencyPrefix + target.host.lowercase(Locale.ROOT)]
                                results.isNullOrEmpty() || results.any { !healthy(it, path) }
                            }
                        }
                CompactProbe(
                    id,
                    members.flatMapTo(mutableSetOf(), CompactProbe::signals),
                    members.sumOf(CompactProbe::cost),
                    concurrencyUnknown || members.any(CompactProbe::mandatory),
                    size = members.size,
                )
            }

        /** Largest target count within [limit] that a set holding every mandatory unit can reach. */
        private fun largestFit(
            units: List<CompactProbe>,
            limit: Int,
        ): Int {
            val base = units.filter(CompactProbe::mandatory).sumOf(CompactProbe::size)
            var reachable = setOf(base)
            units.filterNot(CompactProbe::mandatory).forEach { unit ->
                reachable = reachable + reachable.map { it + unit.size }.filter { it <= limit }
            }
            return (reachable.filter { it <= limit }.maxOrNull() ?: limit).coerceIn(1, limit)
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
                        results.any { !healthy(it, path) }
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

        private fun healthy(
            result: ProbeResult,
            path: ScanPathMode,
        ) = DiagnosticsOutcomeTaxonomy
            .classifyProbeOutcome(result.probeType, path, result.outcome)
            .healthyEnoughForSummary

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
            const val ConcurrencyProbeType = "connection_concurrency"
            const val ConcurrencyPrefix = "@concurrency:"
            val EvidenceProbeTypes = DomainProbeTypes + QuicProbeTypes + ConcurrencyProbeType
            val CostKeys = setOf("latencyMs", "elapsedMs", "durationMs", "encryptedLatencyMs", "udpLatencyMs")
        }
    }
