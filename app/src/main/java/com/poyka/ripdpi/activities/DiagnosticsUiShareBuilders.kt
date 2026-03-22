package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.SummaryMetric
import java.util.Locale

private const val ShareSessionIdPreviewLength = 8

internal fun DiagnosticsUiFactorySupport.buildSharePreview(
    latestSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    telemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
    latestReport: ScanReport?,
): ShareSummary {
    val warningHeadline =
        nativeEvents.firstOrNull {
            it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
        }
    val body =
        buildSharePreviewBody(
            latestSession = latestSession,
            latestSnapshot = latestSnapshot,
            latestContext = latestContext,
            telemetry = telemetry,
            latestReport = latestReport,
            warningHeadline = warningHeadline,
        )
    return ShareSummary(
        title = context.getString(R.string.diagnostics_share_title),
        body = body.ifBlank { context.getString(R.string.diagnostics_share_no_session) },
        compactMetrics = buildShareMetrics(latestSession, latestContext, telemetry),
    )
}

private fun DiagnosticsUiFactorySupport.buildSharePreviewBody(
    latestSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    telemetry: DiagnosticTelemetrySample?,
    latestReport: ScanReport?,
    warningHeadline: DiagnosticEvent?,
): String =
    buildString {
        appendShareIntro(this)
        latestSession?.let { appendShareSession(it) }
        latestSnapshot?.let { appendShareNetwork(it) }
        latestContext?.let { appendShareContext(it) }
        telemetry?.let { appendShareTelemetry(it) }
        latestReport?.let { appendShareReport(this, it) }
        warningHeadline?.let { appendShareWarning(it) }
    }.trim()

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

private fun DiagnosticsUiFactorySupport.appendShareReport(
    builder: StringBuilder,
    report: ScanReport,
) {
    builder.appendLine("${report.results.size} probe results in the latest report")
    if (report.diagnoses.isNotEmpty()) {
        builder.appendLine("Diagnoses:")
        report.diagnoses.take(4).forEach { diagnosis ->
            builder.appendLine("  - ${diagnosis.code}: ${diagnosis.summary}")
        }
    }
    report.classifierVersion?.let { builder.appendLine("Classifier: $it") }
    if (report.packVersions.isNotEmpty()) {
        builder.appendLine(
            "Packs: ${report.packVersions.entries.joinToString(" · ") { (packId, version) -> "$packId@$version" }}",
        )
    }
    report.results.firstOrNull { it.probeType == "telegram_availability" }?.let {
        appendTelegramProbeSummary(builder, it)
    }
}

private fun DiagnosticsUiFactorySupport.appendTelegramProbeSummary(
    builder: StringBuilder,
    result: ProbeResult,
) {
    val details = result.details.associate { it.key to it.value }
    builder.appendLine("Telegram: ${details["verdict"] ?: result.outcome}")
    details["downloadAvgBps"]?.toLongOrNull()?.let { bps ->
        builder.appendLine(
            "  Download: ${formatBps(bps)} avg, ${formatBytes(details["downloadBytes"]?.toLongOrNull() ?: 0)}",
        )
    }
    details["uploadAvgBps"]?.toLongOrNull()?.let { bps ->
        builder.appendLine(
            "  Upload: ${formatBps(bps)} avg, ${formatBytes(details["uploadBytes"]?.toLongOrNull() ?: 0)}",
        )
    }
    builder.appendLine("  DCs: ${details["dcReachable"] ?: "?"}/${details["dcTotal"] ?: "?"} reachable")
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
