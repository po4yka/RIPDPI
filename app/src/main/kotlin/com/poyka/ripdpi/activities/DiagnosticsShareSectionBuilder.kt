package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.ui.diagnostics.uiEvidenceNote
import com.poyka.ripdpi.ui.diagnostics.uiVerificationLabel
import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.buildShareUiModel(
    latestCompletedSession: DiagnosticScanSession?,
    latestSnapshot: DiagnosticsNetworkSnapshotUiModel?,
    latestContext: DiagnosticContextModel?,
    currentTelemetry: DiagnosticTelemetrySample?,
    nativeEvents: List<DiagnosticEvent>,
    latestReport: DiagnosticsSessionProjection?,
    approachStats: List<BypassApproachSummary>,
    selectedSessionDetail: DiagnosticsSessionDetailUiModel?,
    archiveActionState: ArchiveActionState,
    exports: List<DiagnosticExportRecord>,
): DiagnosticsShareUiModel {
    val sharePreview =
        buildSharePreview(
            latestSession = latestCompletedSession,
            latestSnapshot = latestSnapshot,
            latestContext = latestContext,
            telemetry = currentTelemetry,
            nativeEvents = nativeEvents,
            latestReport = latestReport,
        )
    return DiagnosticsShareUiModel(
        targetSessionId = selectedSessionDetail?.session?.id ?: latestCompletedSession?.id,
        previewTitle = sharePreview.title,
        previewBody =
            buildString {
                append(sharePreview.body)
                approachStats
                    .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                    ?.let { summary ->
                        append("\n\n")
                        append(
                            context.getString(
                                R.string.diagnostics_share_approach_format,
                                summary.displayName,
                                summary.uiVerificationLabel(context),
                            ),
                        )
                        append('\n')
                        append(summary.uiEvidenceNote(context))
                    }
            },
        metrics =
            (
                sharePreview.compactMetrics.map { DiagnosticsMetricUiModel(it.label, it.value) } +
                    listOfNotNull(
                        approachStats
                            .firstOrNull { it.approachId.kind == BypassApproachKind.Strategy }
                            ?.let { summary ->
                                DiagnosticsMetricUiModel(
                                    label = context.getString(R.string.diagnostics_metric_approach),
                                    value = summary.displayName,
                                    tone = summary.toDiagnosticsTone(),
                                )
                            },
                    )
            ).toImmutableList(),
        latestArchiveFileName = archiveActionState.latestArchiveFileName ?: exports.firstOrNull()?.fileName,
        archiveStateMessage = archiveActionState.message,
        archiveStateTone = archiveActionState.tone,
        isArchiveBusy = archiveActionState.isBusy,
    )
}
