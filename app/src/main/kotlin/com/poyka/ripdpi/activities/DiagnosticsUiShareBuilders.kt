package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryTextRenderer
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeTargetSelection
import com.poyka.ripdpi.diagnostics.SummaryMetric
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsHighlight
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummarySection
import java.util.Locale

private const val ShareSessionIdPreviewLength = 8
private const val MaxShareHighlights = 3

internal fun DiagnosticsUiFactorySupport.buildSharePreview(
    latestSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    telemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
    latestReport: DiagnosticsSessionProjection?,
): ShareSummary {
    val warningHeadline =
        nativeEvents.firstOrNull {
            (it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)) &&
                !isExpectedProcessExit(it.message)
        }
    val body =
        DiagnosticsSummaryTextRenderer.render(
            buildSharePreviewDocument(
                latestSession,
                latestSnapshot,
                latestContext,
                telemetry,
                latestReport,
                warningHeadline,
            ),
            highlightsTitle = context.getString(R.string.diagnostics_share_highlights_title),
            diagnosesTitle = context.getString(R.string.diagnostics_share_diagnoses_title),
        )
    return ShareSummary(
        title = context.getString(R.string.diagnostics_share_title),
        body = body.ifBlank { context.getString(R.string.diagnostics_share_no_session) },
        compactMetrics = buildShareMetrics(latestSession, latestContext, telemetry),
    )
}

private fun DiagnosticsUiFactorySupport.buildSharePreviewDocument(
    latestSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    telemetry: DiagnosticTelemetrySample?,
    latestReport: DiagnosticsSessionProjection?,
    warningHeadline: DiagnosticEvent?,
): DiagnosticsSummaryDocument =
    DiagnosticsSummaryDocument(
        header =
            DiagnosticsSummarySection(
                title = context.getString(R.string.diagnostics_share_section_header),
                lines =
                    stringLines {
                        appendShareIntro(this)
                        latestSession?.let { session ->
                            appendShareSession(this, session, latestReport ?: session.report)
                        }
                    },
            ),
        reportMetadata =
            DiagnosticsSummarySection(
                title = context.getString(R.string.diagnostics_share_section_report),
                lines = latestReport?.let(::shareReportLines).orEmpty(),
            ),
        environment =
            DiagnosticsSummarySection(
                title = context.getString(R.string.diagnostics_share_section_environment),
                lines =
                    stringLines {
                        latestSnapshot?.let { appendShareNetwork(this, it) }
                        latestContext?.let { appendShareContext(this, it) }
                    },
            ),
        telemetry =
            DiagnosticsSummarySection(
                title = context.getString(R.string.diagnostics_share_section_telemetry),
                lines =
                    stringLines {
                        telemetry?.let { appendShareTelemetry(this, it) }
                    },
            ),
        warnings =
            DiagnosticsSummarySection(
                title = context.getString(R.string.diagnostics_share_section_warnings),
                lines =
                    stringLines {
                        warningHeadline?.let { appendShareWarning(this, it) }
                    },
            ),
        diagnoses = latestReport?.diagnoses.orEmpty(),
        highlights =
            latestReport
                ?.diagnoses
                ?.take(MaxShareHighlights)
                ?.map { diagnosis -> DiagnosticsHighlight(title = diagnosis.code, summary = diagnosis.summary) }
                .orEmpty(),
        observations = latestReport?.observations.orEmpty(),
        engineAnalysisVersion = latestReport?.engineAnalysisVersion,
        classifierVersion = latestReport?.classifierVersion,
        packVersions = latestReport?.packVersions.orEmpty(),
    )

private fun stringLines(block: StringBuilder.() -> Unit): List<String> =
    buildString(block).lines().filter { it.isNotBlank() }

private fun DiagnosticsUiFactorySupport.buildShareMetrics(
    latestSession: DiagnosticScanSession?,
    latestContext: DiagnosticContextModel?,
    telemetry: DiagnosticTelemetrySample?,
): List<SummaryMetric> =
    listOfNotNull(
        latestSession?.pathMode?.let(::sharePathMetric),
        telemetry?.networkType?.let(::shareNetworkMetric),
        latestContext?.service?.activeMode?.let(::shareModeMetric),
        latestContext?.device?.appVersionName?.let(::shareAppMetric),
        telemetry?.txBytes?.let(::shareTxMetric),
        telemetry?.rxBytes?.let(::shareRxMetric),
    )

private fun DiagnosticsUiFactorySupport.appendShareIntro(builder: StringBuilder) {
    builder.appendLine(context.getString(R.string.diagnostics_share_archive_line))
    builder.appendLine(context.getString(R.string.diagnostics_share_telemetry_line))
    builder.appendLine(context.getString(R.string.diagnostics_share_redaction_line))
}

private fun DiagnosticsUiFactorySupport.appendShareSession(
    builder: StringBuilder,
    session: DiagnosticScanSession,
    report: DiagnosticsSessionProjection?,
) {
    val presentation = core.completionPresentation(report, session.status, session.summary)
    val semanticCompletion =
        when (report?.completionKind) {
            null, ScanCompletionKind.NORMAL -> presentation.completionLabel
            ScanCompletionKind.PARTIAL_RESULTS, ScanCompletionKind.TERMINATED -> presentation.summaryLabel
        }
    builder.appendLine(
        context.getString(
            R.string.diagnostics_share_session_format,
            session.id.take(ShareSessionIdPreviewLength),
            session.pathMode,
            semanticCompletion,
        ),
    )
}

private fun DiagnosticsUiFactorySupport.appendShareNetwork(
    builder: StringBuilder,
    snapshot: DiagnosticsNetworkSnapshotUiModel,
) {
    builder.appendLine(context.getString(R.string.diagnostics_share_network_format, snapshot.subtitle))
}

private fun DiagnosticsUiFactorySupport.appendShareContext(
    builder: StringBuilder,
    contextModel: DiagnosticContextModel,
) {
    builder.appendLine(
        context.getString(
            R.string.diagnostics_share_context_format,
            contextModel.service.activeMode.lowercase(Locale.US),
            "${contextModel.device.manufacturer} ${contextModel.device.model}",
            contextModel.device.androidVersion,
        ),
    )
    builder.appendLine(
        context.getString(
            R.string.diagnostics_share_permissions_format,
            sharePermissionLabel(contextModel.permissions.vpnPermissionState),
            sharePermissionLabel(contextModel.permissions.notificationPermissionState),
        ),
    )
}

private fun DiagnosticsUiFactorySupport.appendShareTelemetry(
    builder: StringBuilder,
    telemetry: DiagnosticTelemetrySample,
) {
    builder.appendLine(
        context.getString(
            R.string.diagnostics_share_live_format,
            shareConnectionStateLabel(telemetry.connectionState),
            telemetry.networkType,
        ),
    )
}

private fun DiagnosticsUiFactorySupport.shareReportLines(report: DiagnosticsSessionProjection): List<String> =
    buildList {
        add(
            "${context.getString(R.string.diagnostics_scan_metadata_completion)}: " +
                core.completionKindLabel(report.completionKind),
        )
        report.terminationReason?.let { reason ->
            add(
                "${context.getString(R.string.diagnostics_scan_metadata_termination_reason)}: " +
                    core.terminationReasonLabel(reason),
            )
        }
        add(
            context.getQuantityString(
                R.plurals.diagnostics_share_probe_results_format,
                report.results.size,
                report.results.size,
            ),
        )
        report.engineAnalysisVersion?.let {
            add(
                context.getString(R.string.diagnostics_share_engine_analysis_format, it),
            )
        }
        report.classifierVersion?.let { add(context.getString(R.string.diagnostics_share_classifier_format, it)) }
        report.strategyProbeReport?.let { strategyProbe ->
            add(
                context.getString(
                    R.string.diagnostics_share_strategy_completion_format,
                    strategyCompletionLabel(strategyProbe.completionKind),
                ),
            )
        }
        report.strategyProbeReport?.targetSelection?.let { selection ->
            addAll(shareTargetSelectionLines(selection))
        }
        report.strategyProbeReport?.auditAssessment?.let { assessment ->
            add(
                context.getString(
                    R.string.diagnostics_share_audit_confidence_format,
                    auditConfidenceLabel(assessment.confidence.level, context),
                    assessment.confidence.score,
                ),
            )
            add(
                context.getString(
                    R.string.diagnostics_share_matrix_coverage_format,
                    assessment.coverage.matrixCoveragePercent,
                ),
            )
            add(
                context.getString(
                    R.string.diagnostics_share_winner_coverage_format,
                    assessment.coverage.winnerCoveragePercent,
                ),
            )
        }
        if (report.packVersions.isNotEmpty()) {
            add(
                context.getString(
                    R.string.diagnostics_share_packs_format,
                    report.packVersions.entries.joinToString(" · ") { (packId, version) -> "$packId@$version" },
                ),
            )
        }
        report.results.firstOrNull { it.probeType == "telegram_availability" }?.let { result ->
            addAll(telegramShareLines(result))
        }
    }

private fun DiagnosticsUiFactorySupport.shareTargetSelectionLines(
    selection: StrategyProbeTargetSelection,
): List<String> =
    listOf(
        context.getString(
            R.string.diagnostics_share_audit_cohort_format,
            selection.cohortLabel,
            selection.cohortId,
        ),
        context.getString(
            R.string.diagnostics_share_audit_domains_format,
            selection.domainHosts.joinToString(" · "),
        ),
        context.getString(
            R.string.diagnostics_share_audit_quic_hosts_format,
            selection.quicHosts.joinToString(" · "),
        ),
    )

private fun DiagnosticsUiFactorySupport.telegramShareLines(result: ProbeResult): List<String> =
    buildList {
        val details = result.details.associate { it.key to it.value }
        val verdict = details["verdict"] ?: result.outcome
        val label = telegramStatusLabelRes(verdict)?.let { context.getString(it) } ?: verdict
        add(context.getString(R.string.diagnostics_share_telegram_format, label))
        details["downloadAvgBps"]?.toLongOrNull()?.let { bps ->
            add(
                context.getString(
                    R.string.diagnostics_share_download_format,
                    formatTelegramThroughput(bps, core.formatter.locale),
                    formatTelegramTransferBytes(details["downloadBytes"]?.toLongOrNull() ?: 0, core.formatter.locale),
                ),
            )
        }
        details["uploadAvgBps"]?.toLongOrNull()?.let { bps ->
            add(
                context.getString(
                    R.string.diagnostics_share_upload_format,
                    formatTelegramThroughput(bps, core.formatter.locale),
                    formatTelegramTransferBytes(details["uploadBytes"]?.toLongOrNull() ?: 0, core.formatter.locale),
                ),
            )
        }
        add(
            context.getString(
                R.string.diagnostics_share_data_centers_format,
                details["dcReachable"] ?: "?",
                details["dcTotal"] ?: "?",
            ),
        )
    }

private fun DiagnosticsUiFactorySupport.appendShareWarning(
    builder: StringBuilder,
    warningHeadline: DiagnosticEvent,
) {
    builder.appendLine(context.getString(R.string.diagnostics_share_top_warning_format, warningHeadline.message))
}

private fun DiagnosticsUiFactorySupport.strategyCompletionLabel(kind: StrategyProbeCompletionKind): String =
    context.getString(
        when (kind) {
            StrategyProbeCompletionKind.NORMAL -> {
                R.string.diagnostics_strategy_completion_normal
            }

            StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
                R.string.diagnostics_strategy_completion_dns_short_circuited
            }

            StrategyProbeCompletionKind.DNS_TAMPERING_WITH_FALLBACK -> {
                R.string.diagnostics_strategy_completion_dns_tampering_with_fallback
            }

            StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
                R.string.diagnostics_strategy_completion_partial_results
            }
        },
    )

private fun DiagnosticsUiFactorySupport.sharePermissionLabel(value: String): String =
    when (value) {
        "enabled", "granted" -> context.getString(R.string.diagnostics_share_permission_granted)
        "disabled", "denied" -> context.getString(R.string.diagnostics_local_value_permission_denied)
        "not_required" -> context.getString(R.string.diagnostics_share_permission_not_required)
        "unknown", "unavailable" -> context.getString(R.string.diagnostics_field_unknown)
        else -> value
    }

private fun DiagnosticsUiFactorySupport.shareConnectionStateLabel(value: String): String =
    when (value.lowercase(Locale.ROOT)) {
        "running" -> context.getString(R.string.history_connection_state_running)
        "failed" -> context.getString(R.string.history_connection_state_failed)
        "stopped" -> context.getString(R.string.history_connection_state_stopped)
        "halted" -> context.getString(R.string.history_connection_state_halted)
        "reconnecting" -> context.getString(R.string.history_connection_state_reconnecting)
        "connected" -> context.getString(R.string.vpn_connected)
        "connecting" -> context.getString(R.string.home_status_connecting)
        "disconnected" -> context.getString(R.string.vpn_disconnected)
        "unknown" -> context.getString(R.string.diagnostics_field_unknown)
        else -> value
    }

private fun DiagnosticsUiFactorySupport.sharePathMetric(pathMode: String) =
    SummaryMetric(
        context.getString(R.string.diagnostics_share_metric_path),
        pathMode,
    )

private fun DiagnosticsUiFactorySupport.shareNetworkMetric(networkType: String) =
    SummaryMetric(
        context.getString(R.string.diagnostics_share_metric_network),
        networkType,
    )

private fun DiagnosticsUiFactorySupport.shareModeMetric(mode: String) =
    SummaryMetric(
        context.getString(R.string.diagnostics_share_metric_mode),
        mode,
    )

private fun DiagnosticsUiFactorySupport.shareAppMetric(appVersion: String) =
    SummaryMetric(
        context.getString(R.string.diagnostics_share_metric_app),
        appVersion,
    )

private fun DiagnosticsUiFactorySupport.shareTxMetric(txBytes: Long) =
    SummaryMetric(
        context.getString(R.string.diagnostics_metric_tx),
        formatBytes(txBytes),
    )

private fun DiagnosticsUiFactorySupport.shareRxMetric(rxBytes: Long) =
    SummaryMetric(
        context.getString(R.string.diagnostics_metric_rx),
        formatBytes(rxBytes),
    )
