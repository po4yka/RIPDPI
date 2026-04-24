package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

private const val timingBreakdownDisplayCount = 4

@Composable
@Suppress("LongMethod")
internal fun ToolsSection(
    approaches: DiagnosticsApproachesUiModel,
    share: DiagnosticsShareUiModel,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
    onOpenDetectionCheck: () -> Unit = {},
    pcapRecording: Boolean = false,
    onTogglePcapRecording: () -> Unit = {},
) {
    TrackRecomposition("ToolsSection")
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.diagnostics_approaches_title).uppercase(),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_profiles),
                        selected = approaches.selectedMode == DiagnosticsApproachMode.Profiles,
                        onClick = { onSelectApproachMode(DiagnosticsApproachMode.Profiles) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Profiles),
                            ),
                    )
                    RipDpiChip(
                        text = stringResource(R.string.diagnostics_approaches_strategies),
                        selected = approaches.selectedMode == DiagnosticsApproachMode.Strategies,
                        onClick = { onSelectApproachMode(DiagnosticsApproachMode.Strategies) },
                        modifier =
                            Modifier.ripDpiTestTag(
                                RipDpiTestTags.diagnosticsApproachMode(DiagnosticsApproachMode.Strategies),
                            ),
                    )
                }
            }
        }
        items(items = approaches.rows, key = { it.id }, contentType = { "approach" }) { row ->
            RipDpiCard(
                onClick = { onSelectApproach(row.id) },
                variant =
                    if (row.id == approaches.focusedApproachId) {
                        RipDpiCardVariant.Elevated
                    } else {
                        RipDpiCardVariant.Outlined
                    },
            ) {
                StatusIndicator(label = row.verificationState, tone = statusTone(row.tone))
                androidx.compose.material3.Text(
                    text = row.title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                androidx.compose.material3.Text(
                    text = row.subtitle,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                MetricsRow(metrics = row.metrics)
                androidx.compose.material3.Text(
                    text = row.lastValidatedResult,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                androidx.compose.material3.Text(
                    text = row.dominantFailurePattern,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
        item {
            ShareActionCard(
                title = "Packet Capture",
                body =
                    if (pcapRecording) {
                        "Recording packets for diagnostics..."
                    } else {
                        "Record raw packets to a pcap file for analysis in Wireshark."
                    },
                buttonLabel = if (pcapRecording) "Stop Recording" else "Start Recording",
                onClick = onTogglePcapRecording,
                iconTint = if (pcapRecording) RipDpiThemeTokens.colors.destructive else RipDpiThemeTokens.colors.info,
                variant = if (pcapRecording) RipDpiButtonVariant.Destructive else RipDpiButtonVariant.Outline,
            )
        }
        item {
            DiagnosticsPreviewCard(
                title = share.previewTitle,
                body = share.previewBody,
                metrics = share.metrics,
                archiveStateMessage = share.archiveStateMessage,
                archiveStateTone = share.archiveStateTone,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_share_archive_title),
                body = stringResource(R.string.diagnostics_share_archive_body),
                buttonLabel = stringResource(R.string.diagnostics_share_archive_action),
                onClick = { onShareArchive(share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.foreground,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsShareArchive),
                variant = RipDpiButtonVariant.Primary,
                enabled = !share.isArchiveBusy,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_archive_title),
                body =
                    stringResource(
                        R.string.diagnostics_save_archive_body,
                        share.latestArchiveFileName ?: "latest archive",
                    ),
                buttonLabel = stringResource(R.string.diagnostics_save_archive_action),
                onClick = { onSaveArchive(share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSaveArchive),
                variant = RipDpiButtonVariant.Outline,
                enabled = !share.isArchiveBusy,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_share_summary_title),
                body = stringResource(R.string.diagnostics_share_summary_body),
                buttonLabel = stringResource(R.string.diagnostics_share_summary_action),
                onClick = { onShareSummary(share.targetSessionId) },
                iconTint = RipDpiThemeTokens.colors.info,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsShareSummary),
                variant = RipDpiButtonVariant.Outline,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.diagnostics_save_logs_title),
                body = stringResource(R.string.diagnostics_save_logs_body),
                buttonLabel = stringResource(R.string.save_logs),
                onClick = onSaveLogs,
                iconTint = RipDpiThemeTokens.colors.warning,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSaveLogs),
                variant = RipDpiButtonVariant.Outline,
            )
        }
        item {
            ShareActionCard(
                title = stringResource(R.string.title_detection_check),
                body = stringResource(R.string.detection_check_subtitle),
                buttonLabel = stringResource(R.string.detection_check_start),
                onClick = onOpenDetectionCheck,
                iconTint = RipDpiThemeTokens.colors.foreground,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
internal fun DiagnosticsPerformanceCard(
    performance: DiagnosticsPerformanceUiModel,
    selectedSection: DiagnosticsSection,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("DiagnosticsPerformanceCard")
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    var expanded by remember { mutableStateOf(false) }
    val timingBreakdown =
        remember(performance) {
            listOf(
                "resolve" to performance.resolveDurationMillis,
                "overview" to performance.overviewDurationMillis,
                "scan" to performance.scanDurationMillis,
                "live" to performance.liveDurationMillis,
                "sessions" to performance.sessionsDurationMillis,
                "approaches" to performance.approachesDurationMillis,
                "events" to performance.eventsDurationMillis,
                "share" to performance.shareDurationMillis,
                "event-map" to performance.eventMappingDurationMillis,
            ).sortedByDescending { it.second }
        }
    val slowestStage = timingBreakdown.firstOrNull()
    val timingSummary =
        remember(timingBreakdown) {
            timingBreakdown.take(timingBreakdownDisplayCount).joinToString("  ") { (label, duration) ->
                "$label ${formatDuration(duration)}"
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
        onClick = { expanded = !expanded },
    ) {
        androidx.compose.material3.Text(
            text =
                "Debug #${performance.buildSequence} · ${selectedSection.name.lowercase(Locale.US)} · " +
                    formatDuration(performance.totalDurationMillis),
            style = RipDpiThemeTokens.type.monoSmall,
            color = colors.mutedForeground,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                androidx.compose.material3.HorizontalDivider(color = colors.divider)
                slowestStage?.let { (label, duration) ->
                    androidx.compose.material3.Text(
                        text = "Slowest stage: $label ${formatDuration(duration)}",
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                }
                androidx.compose.material3.Text(
                    text =
                        "Input: ${performance.telemetryCount} telemetry · " +
                            "${performance.nativeEventCount} events · " +
                            "${performance.sessionCount} sessions",
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
                androidx.compose.material3.Text(
                    text = timingSummary,
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.foreground,
                )
            }
        }
    }
}

private fun formatDuration(durationMillis: Double): String = String.format(Locale.US, "%.1f ms", durationMillis)
