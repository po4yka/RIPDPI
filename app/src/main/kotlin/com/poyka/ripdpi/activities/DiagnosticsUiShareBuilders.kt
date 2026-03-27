package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryTextRenderer
import com.poyka.ripdpi.diagnostics.ShareSummary
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
            it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
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
                title = "Header",
                lines =
                    stringLines {
                        appendShareIntro(this)
                        latestSession?.let { appendShareSession(it) }
                    },
            ),
        reportMetadata =
            DiagnosticsSummarySection(
                title = "Report",
                lines = latestReport?.let(::shareReportLines).orEmpty(),
            ),
        environment =
            DiagnosticsSummarySection(
                title = "Environment",
                lines =
                    stringLines {
                        latestSnapshot?.let { appendShareNetwork(it) }
                        latestContext?.let { appendShareContext(it) }
                    },
            ),
        telemetry =
            DiagnosticsSummarySection(
                title = "Telemetry",
                lines =
                    stringLines {
                        telemetry?.let { appendShareTelemetry(it) }
                    },
            ),
        warnings =
            DiagnosticsSummarySection(
                title = "Warnings",
                lines =
                    stringLines {
                        warningHeadline?.let { appendShareWarning(it) }
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

private fun StringBuilder.appendShareSession(session: DiagnosticScanSession) {
    appendLine(
        "Session ${session.id.take(ShareSessionIdPreviewLength)} · ${session.pathMode} · ${session.status}",
    )
}

private fun StringBuilder.appendShareNetwork(snapshot: DiagnosticsNetworkSnapshotUiModel) {
    appendLine("Network ${snapshot.subtitle}")
}

private fun StringBuilder.appendShareContext(contextModel: DiagnosticContextModel) {
    appendLine(
        "Context ${contextModel.service.activeMode.lowercase(Locale.US)} · " +
            "${contextModel.device.manufacturer} ${contextModel.device.model} · " +
            "Android ${contextModel.device.androidVersion}",
    )
    appendLine(
        "Permissions ${contextModel.permissions.vpnPermissionState} VPN · " +
            "${contextModel.permissions.notificationPermissionState} notifications",
    )
}

private fun StringBuilder.appendShareTelemetry(telemetry: DiagnosticTelemetrySample) {
    appendLine("Live ${telemetry.connectionState.lowercase(Locale.US)} · ${telemetry.networkType}")
}

private fun DiagnosticsUiFactorySupport.shareReportLines(report: DiagnosticsSessionProjection): List<String> =
    buildList {
        add("${report.results.size} probe results in the latest report")
        report.engineAnalysisVersion?.let { add("Engine analysis: $it") }
        report.classifierVersion?.let { add("Classifier: $it") }
        report.strategyProbeReport?.targetSelection?.let { selection ->
            add("Audit cohort: ${selection.cohortLabel} (${selection.cohortId})")
            add("Audit domains: ${selection.domainHosts.joinToString(" · ")}")
            add("Audit QUIC hosts: ${selection.quicHosts.joinToString(" · ")}")
        }
        report.strategyProbeReport?.auditAssessment?.let { assessment ->
            add("Audit confidence: ${assessment.confidence.level.name} (${assessment.confidence.score}/100)")
            add("Matrix coverage: ${assessment.coverage.matrixCoveragePercent}%")
            add("Winner coverage: ${assessment.coverage.winnerCoveragePercent}%")
        }
        if (report.packVersions.isNotEmpty()) {
            add("Packs: ${report.packVersions.entries.joinToString(" · ") { (packId, version) -> "$packId@$version" }}")
        }
        report.results.firstOrNull { it.probeType == "telegram_availability" }?.let { result ->
            val details = result.details.associate { it.key to it.value }
            add("Telegram: ${details["verdict"] ?: result.outcome}")
            details["downloadAvgBps"]?.toLongOrNull()?.let { bps ->
                add("Download: ${formatBps(bps)} avg, ${formatBytes(details["downloadBytes"]?.toLongOrNull() ?: 0)}")
            }
            details["uploadAvgBps"]?.toLongOrNull()?.let { bps ->
                add("Upload: ${formatBps(bps)} avg, ${formatBytes(details["uploadBytes"]?.toLongOrNull() ?: 0)}")
            }
            add("DCs: ${details["dcReachable"] ?: "?"}/${details["dcTotal"] ?: "?"} reachable")
        }
    }

private fun StringBuilder.appendShareWarning(warningHeadline: DiagnosticEvent) {
    appendLine("Top warning: ${warningHeadline.message}")
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
