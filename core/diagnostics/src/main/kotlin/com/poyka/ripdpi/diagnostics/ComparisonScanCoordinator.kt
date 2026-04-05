package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ComparisonScanCoordinator
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun compareReports(
            rawPathSessionId: String,
            inPathSessionId: String,
        ): PathComparisonResult {
            val rawSession = scanRecordStore.getScanSession(rawPathSessionId)
            val inPathSession = scanRecordStore.getScanSession(inPathSessionId)

            val rawReport = rawSession?.let { DiagnosticsSessionQueries.decodeScanReport(json, it.reportJson) }
            val inPathReport = inPathSession?.let { DiagnosticsSessionQueries.decodeScanReport(json, it.reportJson) }

            if (rawReport == null || inPathReport == null) {
                return emptyComparison(rawPathSessionId, inPathSessionId)
            }

            val rawOutcomes = extractDomainOutcomes(rawReport)
            val inPathOutcomes = extractDomainOutcomes(inPathReport)

            val allDomains = (rawOutcomes.keys + inPathOutcomes.keys).sorted()
            val comparisons =
                allDomains.map { domain ->
                    val rawOutcome = rawOutcomes[domain] ?: NOT_TESTED
                    val inPathOutcome = inPathOutcomes[domain] ?: NOT_TESTED
                    DomainPathComparison(
                        domain = domain,
                        rawPathOutcome = rawOutcome,
                        inPathOutcome = inPathOutcome,
                        vpnBypasses = isFailure(rawOutcome) && isSuccess(inPathOutcome),
                    )
                }

            val bypassed = comparisons.count { it.vpnBypasses }
            val total = comparisons.size
            val summary =
                when {
                    bypassed == total && total > 0 -> "VPN tunnel bypasses all blocking"
                    bypassed > 0 -> "VPN tunnel bypasses blocking for $bypassed of $total domains"
                    else -> "VPN tunnel does not improve reachability"
                }

            return PathComparisonResult(
                rawPathSessionId = rawPathSessionId,
                inPathSessionId = inPathSessionId,
                domainComparisons = comparisons,
                summary = summary,
            )
        }

        companion object {
            private const val NOT_TESTED = "not_tested"

            private val FAILURE_OUTCOMES =
                setOf(
                    "tls_handshake_failed",
                    "tls_cert_invalid",
                    "http_unreachable",
                    "quic_error",
                    NOT_TESTED,
                )

            private val SUCCESS_OUTCOMES =
                setOf(
                    "tls_ok",
                    "tls_version_split",
                    "http_ok",
                    "http_redirect",
                )

            internal fun extractDomainOutcomes(report: ScanReport): Map<String, String> =
                report.observations
                    .filter {
                        it.kind == ObservationKind.STRATEGY &&
                            it.strategy?.protocol == StrategyProbeProtocol.HTTPS
                    }.groupBy { parseDomain(it.target) }
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                    .mapValues { (_, facts) ->
                        if (facts.any { it.strategy?.status == StrategyProbeStatus.SUCCESS }) {
                            "tls_ok"
                        } else {
                            facts.firstOrNull()?.evidence?.firstOrNull() ?: "unknown"
                        }
                    }

            internal fun parseDomain(target: String): String? {
                val separator = " \u00b7 "
                val index = target.indexOf(separator)
                return if (index >= 0) target.substring(index + separator.length) else null
            }

            internal fun isFailure(outcome: String): Boolean = outcome in FAILURE_OUTCOMES

            internal fun isSuccess(outcome: String): Boolean = outcome in SUCCESS_OUTCOMES
        }

        private fun emptyComparison(
            rawId: String,
            inPathId: String,
        ) = PathComparisonResult(rawId, inPathId, summary = "Comparison unavailable: missing report data")
    }
