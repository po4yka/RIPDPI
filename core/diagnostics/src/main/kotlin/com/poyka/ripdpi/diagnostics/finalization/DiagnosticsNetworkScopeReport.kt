package com.poyka.ripdpi.diagnostics.finalization

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.QuicVersionMatrixProbeTypes
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.StrategyActivePathAuthority
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.decodeEngineScanReportWire
import com.poyka.ripdpi.diagnostics.hasAuthoritativeManualConflictCancellation
import com.poyka.ripdpi.diagnostics.quicVersionWithoutScopeAuthority
import com.poyka.ripdpi.diagnostics.toEngineScanReportWire
import com.poyka.ripdpi.diagnostics.toScanReport
import com.poyka.ripdpi.diagnostics.withoutHttp3ScopeAuthority
import com.poyka.ripdpi.diagnostics.withoutIpFamilyScopeAuthority
import com.poyka.ripdpi.diagnostics.withoutPmtuScopeAuthority
import com.poyka.ripdpi.diagnostics.withoutTlsHopScopeAuthority
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal const val NetworkScopeUnverifiedDiagnosis = "network_scope_unverified"

internal enum class DiagnosticsScopeLoss(
    val diagnosisCode: String,
    val summary: String,
) {
    NETWORK(
        NetworkScopeUnverifiedDiagnosis,
        "Network continuity could not be verified. Results are inconclusive; repeat the scan.",
    ),
    VPN_ROUTE(
        "in_path_route_unverified",
        "The VPN route changed during diagnostics. Results are inconclusive; repeat the scan.",
    ),
}

/** Preserve measurements, but do not let history or Home consumers apply mixed-network advice. */
internal fun EngineScanReportWire.withoutNetworkScopeAuthority(): EngineScanReportWire =
    withoutScopeAuthority(DiagnosticsScopeLoss.NETWORK)

private fun EngineScanReportWire.withoutScopeAuthority(loss: DiagnosticsScopeLoss): EngineScanReportWire =
    copy(
        summary = loss.summary,
        completionKind =
            if (completionKind ==
                ScanCompletionKind.NORMAL
            ) {
                ScanCompletionKind.PARTIAL_RESULTS
            } else {
                completionKind
            },
        results = results.map { it.withoutNetworkScopeAuthority() },
        resolverRecommendation = null,
        strategyRecommendation = null,
        directModeVerdict = null,
        confirmGoodDpiVerdict = null,
        strategyProbeReport =
            strategyProbeReport?.copy(
                recommendation = null,
                completionKind = StrategyProbeCompletionKind.PARTIAL_RESULTS,
                activePathObservation =
                    strategyProbeReport.activePathObservation
                        ?.takeIf { loss == DiagnosticsScopeLoss.VPN_ROUTE }
                        ?.copy(activePathAuthority = StrategyActivePathAuthority.UNVERIFIED),
                domainStrategySeeds = emptyList(),
            ),
        diagnoses =
            listOf(
                Diagnosis(
                    code = loss.diagnosisCode,
                    summary = loss.summary,
                    severity = "warning",
                ),
            ),
    )

internal fun ScanReport.withoutNetworkScopeAuthority(): ScanReport = withoutScopeAuthority(DiagnosticsScopeLoss.NETWORK)

internal fun ScanReport.withoutScopeAuthority(loss: DiagnosticsScopeLoss): ScanReport =
    toEngineScanReportWire().withoutScopeAuthority(loss).toScanReport()

/** Invalid JSON cannot carry actionable evidence and must not escape a failed finalization. */
internal fun PreparedDiagnosticsScan?.scopeReportJson(
    reportJson: String,
    json: Json,
): String? =
    if (this?.networkFingerprint != null && networkScope?.isCurrent() == true) {
        reportJson
    } else {
        runCatching {
            json.encodeToString(
                EngineScanReportWire.serializer(),
                json.decodeEngineScanReportWire(reportJson).withoutNetworkScopeAuthority(),
            )
        }.getOrNull()
    }

/** Revoke staged advice without inserting the measurement rows and native events a second time. */
internal suspend fun revokePersistedNetworkScope(
    sessionId: String,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
    loss: DiagnosticsScopeLoss = DiagnosticsScopeLoss.NETWORK,
    inPathFinishedAt: Long? = null,
) {
    val session = scanRecordStore.getScanSession(sessionId) ?: return
    val report = session.reportJson?.let { json.decodeEngineScanReportWire(it).withoutScopeAuthority(loss) }
    val manualConflict = session.hasAuthoritativeManualConflictCancellation()
    val finishScan = inPathFinishedAt != null && !manualConflict
    val scopedSession =
        session.copy(
            status = if (finishScan) "completed" else session.status,
            finishedAt = if (finishScan) inPathFinishedAt else session.finishedAt,
            summary =
                if (manualConflict) {
                    session.summary
                } else {
                    loss.summary
                },
            reportJson = report?.let { json.encodeToString(EngineScanReportWire.serializer(), it) },
            reportCompletionKind = report?.completionKind?.name ?: session.reportCompletionKind,
        )
    val results = scanRecordStore.getProbeResults(sessionId)
    val scopedResults = results.map { it.withoutNetworkScopeAuthority(json) }
    if (scopedResults != results) {
        scanRecordStore.persistCompletedScan(scopedSession, scopedResults)
    } else {
        scanRecordStore.upsertScanSession(scopedSession)
    }
}

private fun EngineProbeResultWire.withoutNetworkScopeAuthority(): EngineProbeResultWire =
    when {
        probeType == "tls_hop_comparison" && outcome == "tls_hop_divergence" -> {
            copy(outcome = "tls_hop_inconclusive", details = details.withoutTlsHopScopeAuthority())
        }

        probeType in QuicVersionMatrixProbeTypes -> {
            quicVersionWithoutScopeAuthority(probeType, details)?.let { (scopedOutcome, scopedDetails) ->
                copy(outcome = scopedOutcome, details = scopedDetails)
            } ?: this
        }

        probeType == "pmtu" -> {
            copy(outcome = "pmtu_not_observed", details = details.withoutPmtuScopeAuthority())
        }

        probeType == "http3" -> {
            copy(outcome = "http3_inconclusive", details = details.withoutHttp3ScopeAuthority())
        }

        probeType == "ip_family" -> {
            copy(outcome = "ip_family_inconclusive", details = details.withoutIpFamilyScopeAuthority())
        }

        probeType == "selective_availability_summary" -> {
            copy(
                outcome = "matrix_inconclusive",
                details = details.filterNot { it.key == "reason" } + ProbeDetail("reason", "network_scope_unverified"),
            )
        }

        probeType == "throughput_window" && outcome.startsWith("verified_download_") -> {
            copy(
                outcome = "verified_download_inconclusive",
                details = details.withoutTransferScopeAuthority(),
            )
        }

        probeType == "throughput_window" && outcome.startsWith("verified_upload_") -> {
            copy(
                outcome = "verified_upload_inconclusive",
                details = details.withoutTransferScopeAuthority(),
            )
        }

        probeType == "throughput_window" && outcome.startsWith("ech_transfer_comparison_") -> {
            copy(
                outcome = "ech_transfer_comparison_inconclusive",
                details = details.withoutTransferScopeAuthority(),
            )
        }

        probeType == "throughput_window" && details.any { it.key == "transferEvidence" } -> {
            copy(
                details = details.withoutTransferScopeAuthority(),
            )
        }

        else -> {
            this
        }
    }

private fun List<ProbeDetail>.withoutTransferScopeAuthority(): List<ProbeDetail> =
    filterNot { it.key == "transferNetworkScope" } + ProbeDetail("transferNetworkScope", "unverified")

private fun ProbeResultEntity.withoutNetworkScopeAuthority(json: Json): ProbeResultEntity {
    if (probeType !in
        setOf(
            "selective_availability_summary",
            "throughput_window",
            "ip_family",
            "http3",
            "pmtu",
            "tls_hop_comparison",
        ) && probeType !in QuicVersionMatrixProbeTypes
    ) {
        return this
    }
    val details =
        runCatching {
            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
        }.getOrDefault(emptyList())
    val observed = EngineProbeResultWire(probeType, target, outcome, details)
    val scoped = observed.withoutNetworkScopeAuthority()
    return if (scoped == observed) {
        this
    } else {
        copy(
            outcome = scoped.outcome,
            detailJson = json.encodeToString(ListSerializer(ProbeDetail.serializer()), scoped.details),
        )
    }
}
