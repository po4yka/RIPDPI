package com.poyka.ripdpi.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniState
import com.poyka.ripdpi.activities.DiagnosticsAllowlistSniToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsApproachRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompatibleSniUiModel
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeState
import com.poyka.ripdpi.activities.DiagnosticsCompressionProbeToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityState
import com.poyka.ripdpi.activities.DiagnosticsDnsAvailabilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityState
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiToolsUiModel
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderState
import com.poyka.ripdpi.activities.DiagnosticsTcp16FatHeaderToolUiModel
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.screens.diagnostics.rkn.RknBlockDiagnosisScreen
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

private const val timingBreakdownDisplayCount = 4

internal data class DiagnosticsDpiToolActions(
    val onRunDnsIntegrityCheck: () -> Unit = {},
    val onRunDnsAvailabilitySurvey: () -> Unit = {},
    val onRunDomainReachabilityScan: () -> Unit = {},
    val onRunCompressionProbe: () -> Unit = {},
    val onRunTcp16FatHeaderProbe: () -> Unit = {},
    val onRunAllowlistSniFinder: () -> Unit = {},
    val onRunPluggableTransportProbe: () -> Unit = {},
    val onRunByohCompatibilityCheck: () -> Unit = {},
    val onRunRknBlockDiagnosis: () -> Unit = {},
    val onRknSelfInfoEnabledChange: (Boolean) -> Unit = {},
    val onCompressionProbeZstdEnabledChange: (Boolean) -> Unit = {},
    val onByohDstIpChange: (String) -> Unit = {},
    val onByohUrlPathChange: (String) -> Unit = {},
    val onByohSyntheticFixtureEnabledChange: (Boolean) -> Unit = {},
    val onDpiSuiteProbeEnabledChange: (DpiProbeKind, Boolean) -> Unit = { _, _ -> },
    val onDpiSuiteCustomDomainsChange: (String) -> Unit = {},
    val onDpiSuiteConcurrencyDelta: (Int) -> Unit = {},
    val onRunDpiProbeSuite: () -> Unit = {},
    val onCancelDpiProbeSuite: () -> Unit = {},
)

@Composable
internal fun ToolsSection(
    approaches: DiagnosticsApproachesUiModel,
    share: DiagnosticsShareUiModel,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
    dpiTools: DiagnosticsDpiToolsUiModel = DiagnosticsDpiToolsUiModel(),
    pluggableTransportTool: DiagnosticsPluggableTransportToolUiModel = DiagnosticsPluggableTransportToolUiModel(),
    dpiToolActions: DiagnosticsDpiToolActions = DiagnosticsDpiToolActions(),
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
        approachItems(approaches, onSelectApproachMode, onSelectApproach)
        captureItem(pcapRecording, onTogglePcapRecording)
        shareItems(share, onShareSummary, onShareArchive, onSaveArchive, onSaveLogs)
        dpiToolItems(
            dpiTools = dpiTools,
            pluggableTransportTool = pluggableTransportTool,
            actions = dpiToolActions,
        )
        detectionCheckItem(onOpenDetectionCheck)
    }
}

private fun LazyListScope.approachItems(
    approaches: DiagnosticsApproachesUiModel,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
) {
    item {
        ApproachModeCard(
            selectedMode = approaches.selectedMode,
            onSelectApproachMode = onSelectApproachMode,
        )
    }
    items(items = approaches.rows, key = { it.id }, contentType = { "approach" }) { row ->
        ApproachRowCard(
            row = row,
            focused = row.id == approaches.focusedApproachId,
            onSelectApproach = onSelectApproach,
        )
    }
}

private fun LazyListScope.captureItem(
    pcapRecording: Boolean,
    onTogglePcapRecording: () -> Unit,
) {
    item {
        PcapCaptureCard(
            pcapRecording = pcapRecording,
            onTogglePcapRecording = onTogglePcapRecording,
        )
    }
}

private fun LazyListScope.shareItems(
    share: DiagnosticsShareUiModel,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
) {
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
}

private fun LazyListScope.dpiToolItems(
    dpiTools: DiagnosticsDpiToolsUiModel,
    pluggableTransportTool: DiagnosticsPluggableTransportToolUiModel,
    actions: DiagnosticsDpiToolActions,
) {
    item {
        DpiProbeSuiteCard(
            tool = dpiTools.dpiSuite,
            onProbeEnabledChange = actions.onDpiSuiteProbeEnabledChange,
            onCustomDomainsChange = actions.onDpiSuiteCustomDomainsChange,
            onConcurrencyDelta = actions.onDpiSuiteConcurrencyDelta,
            onRun = actions.onRunDpiProbeSuite,
            onCancel = actions.onCancelDpiProbeSuite,
        )
    }
    item {
        DnsIntegrityToolCard(
            tool = dpiTools.dnsIntegrity,
            onRun = actions.onRunDnsIntegrityCheck,
        )
    }
    item {
        DnsAvailabilitySurveyCard(
            tool = dpiTools.dnsAvailability,
            onRun = actions.onRunDnsAvailabilitySurvey,
        )
    }
    item {
        DomainReachabilityToolCard(
            tool = dpiTools.domainReachability,
            onRun = actions.onRunDomainReachabilityScan,
        )
    }
    item {
        HttpCompressionProbeCard(
            tool = dpiTools.compressionProbe,
            onRun = actions.onRunCompressionProbe,
            onZstdEnabledChange = actions.onCompressionProbeZstdEnabledChange,
        )
    }
    item {
        PluggableTransportProbeCard(
            tool = pluggableTransportTool,
            onRun = actions.onRunPluggableTransportProbe,
        )
    }
    item {
        Tcp16FatHeaderProbeCard(
            tool = dpiTools.tcp16FatHeader,
            onRun = actions.onRunTcp16FatHeaderProbe,
        )
    }
    item {
        AllowlistSniFinderCard(
            tool = dpiTools.allowlistSni,
            onRun = actions.onRunAllowlistSniFinder,
        )
    }
    item {
        ByohCompatibilityCard(
            tool = dpiTools.byohCompatibility,
            onDstIpChange = actions.onByohDstIpChange,
            onUrlPathChange = actions.onByohUrlPathChange,
            onSyntheticFixtureEnabledChange = actions.onByohSyntheticFixtureEnabledChange,
            onRun = actions.onRunByohCompatibilityCheck,
        )
    }
    item {
        RknBlockDiagnosisScreen(
            tool = dpiTools.rknBlockDiagnosis,
            onRun = actions.onRunRknBlockDiagnosis,
            onSelfInfoEnabledChange = actions.onRknSelfInfoEnabledChange,
        )
    }
}

@Composable
private fun Tcp16FatHeaderProbeCard(
    tool: DiagnosticsTcp16FatHeaderToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        androidx.compose.material3.Text(
            text = "TCP16 fat-header",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.asn}: ${row.detected}/${row.checked} detected",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.providers} · dead ${row.dead} · errors ${row.errors}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsTcp16FatHeaderState.Running) {
                    "Probing..."
                } else {
                    "Run TCP16 probe"
                },
            enabled = tool.state != DiagnosticsTcp16FatHeaderState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AllowlistSniFinderCard(
    tool: DiagnosticsAllowlistSniToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val context = LocalContext.current
    val clipboardManager =
        remember(context) {
            context.getSystemService(ClipboardManager::class.java)
        }
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        androidx.compose.material3.Text(
            text = "SNI compatibility",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.asn}: ${row.compatibleSnis.size} compatible",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.provider} · ${row.ip} · tried ${row.triedCount}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                    CompatibleSniValues(row.compatibleSnis, clipboardManager)
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsAllowlistSniState.Running) {
                    "Checking..."
                } else {
                    "Run SNI compatibility"
                },
            enabled = tool.enabled && tool.state != DiagnosticsAllowlistSniState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompatibleSniValues(
    compatibleSnis: List<DiagnosticsCompatibleSniUiModel>,
    clipboardManager: ClipboardManager?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (compatibleSnis.isEmpty()) {
        androidx.compose.material3.Text(
            text = "No compatible entries found.",
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    } else {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            compatibleSnis.forEach { sni ->
                RipDpiChip(
                    text = sni.label,
                    onClick = {
                        clipboardManager?.setPrimaryClip(
                            ClipData.newPlainText("Compatible SNI", sni.value),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DnsAvailabilitySurveyCard(
    tool: DiagnosticsDnsAvailabilityToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        androidx.compose.material3.Text(
            text = "DNS availability",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.name}: ${row.availability}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.type} · ${row.latency}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsDnsAvailabilityState.Running) {
                    "Surveying..."
                } else {
                    "Run DNS survey"
                },
            enabled = tool.state != DiagnosticsDnsAvailabilityState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HttpCompressionProbeCard(
    tool: DiagnosticsCompressionProbeToolUiModel,
    onRun: () -> Unit,
    onZstdEnabledChange: (Boolean) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        androidx.compose.material3.Text(
            text = "HTTP compression",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        RipDpiSwitch(
            checked = tool.includeZstd,
            onCheckedChange = onZstdEnabledChange,
            enabled = tool.state != DiagnosticsCompressionProbeState.Running,
            label = "Zstd codec",
        )
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.codec}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.compressedBytes} B compressed · ${row.decompressedBytes} B decoded",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsCompressionProbeState.Running) {
                    "Checking..."
                } else {
                    "Run compression probe"
                },
            enabled = tool.state != DiagnosticsCompressionProbeState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun LazyListScope.detectionCheckItem(onOpenDetectionCheck: () -> Unit) {
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

@Composable
private fun ApproachModeCard(
    selectedMode: DiagnosticsApproachMode,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Elevated) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_approaches_title).uppercase(),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ApproachModeChip(
                text = stringResource(R.string.diagnostics_approaches_profiles),
                mode = DiagnosticsApproachMode.Profiles,
                selectedMode = selectedMode,
                onSelectApproachMode = onSelectApproachMode,
            )
            ApproachModeChip(
                text = stringResource(R.string.diagnostics_approaches_strategies),
                mode = DiagnosticsApproachMode.Strategies,
                selectedMode = selectedMode,
                onSelectApproachMode = onSelectApproachMode,
            )
        }
    }
}

@Composable
private fun ApproachModeChip(
    text: String,
    mode: DiagnosticsApproachMode,
    selectedMode: DiagnosticsApproachMode,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
) {
    RipDpiChip(
        text = text,
        selected = selectedMode == mode,
        onClick = { onSelectApproachMode(mode) },
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsApproachMode(mode)),
    )
}

@Composable
private fun ApproachRowCard(
    row: DiagnosticsApproachRowUiModel,
    focused: Boolean,
    onSelectApproach: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(
        onClick = { onSelectApproach(row.id) },
        variant = if (focused) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(label = row.verificationState, tone = statusTone(row.tone))
        androidx.compose.material3.Text(
            text = row.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = row.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        MetricsRow(metrics = row.metrics)
        androidx.compose.material3.Text(
            text = row.lastValidatedResult,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = row.dominantFailurePattern,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun PcapCaptureCard(
    pcapRecording: Boolean,
    onTogglePcapRecording: () -> Unit,
) {
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

@Composable
private fun DnsIntegrityToolCard(
    tool: DiagnosticsDnsIntegrityToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        androidx.compose.material3.Text(
            text = "DNS integrity",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.domain}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "UDP ${row.udpAnswer} · DoH ${row.dohAnswer}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        if (tool.doqRows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.doqRows.forEach { row ->
                    StatusIndicator(
                        label = "DoQ ${row.provider} ${row.domain}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "${row.endpoint} · ${row.resolvedIps} · ${row.detail}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsDnsIntegrityState.Running) {
                    "Checking..."
                } else {
                    "Run DNS integrity"
                },
            enabled = tool.state != DiagnosticsDnsIntegrityState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DomainReachabilityToolCard(
    tool: DiagnosticsDomainReachabilityToolUiModel,
    onRun: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = tool.state.name.lowercase(Locale.US),
            tone = statusTone(tool.state.tone()),
        )
        androidx.compose.material3.Text(
            text = "Domain reachability",
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        if (tool.rows.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tool.rows.forEach { row ->
                    StatusIndicator(
                        label = "${row.domain}: ${row.verdict}",
                        tone = statusTone(row.tone),
                    )
                    androidx.compose.material3.Text(
                        text = "TLS1.3 ${row.tls13} · TLS1.2 ${row.tls12} · HTTP ${row.http}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                    androidx.compose.material3.Text(
                        text = "A ${row.resolvedIps}",
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsDomainReachabilityState.Running) {
                    "Scanning..."
                } else {
                    "Run reachability scan"
                },
            enabled = tool.state != DiagnosticsDomainReachabilityState.Running,
            onClick = onRun,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
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

private fun DiagnosticsDnsIntegrityState.tone(): com.poyka.ripdpi.activities.DiagnosticsTone =
    when (this) {
        DiagnosticsDnsIntegrityState.Idle -> com.poyka.ripdpi.activities.DiagnosticsTone.Neutral
        DiagnosticsDnsIntegrityState.Running -> com.poyka.ripdpi.activities.DiagnosticsTone.Info
        DiagnosticsDnsIntegrityState.Complete -> com.poyka.ripdpi.activities.DiagnosticsTone.Positive
        DiagnosticsDnsIntegrityState.Failed -> com.poyka.ripdpi.activities.DiagnosticsTone.Negative
    }

private fun DiagnosticsDnsAvailabilityState.tone(): com.poyka.ripdpi.activities.DiagnosticsTone =
    when (this) {
        DiagnosticsDnsAvailabilityState.Idle -> com.poyka.ripdpi.activities.DiagnosticsTone.Neutral
        DiagnosticsDnsAvailabilityState.Running -> com.poyka.ripdpi.activities.DiagnosticsTone.Info
        DiagnosticsDnsAvailabilityState.Complete -> com.poyka.ripdpi.activities.DiagnosticsTone.Positive
        DiagnosticsDnsAvailabilityState.Failed -> com.poyka.ripdpi.activities.DiagnosticsTone.Negative
    }

private fun DiagnosticsDomainReachabilityState.tone(): com.poyka.ripdpi.activities.DiagnosticsTone =
    when (this) {
        DiagnosticsDomainReachabilityState.Idle -> com.poyka.ripdpi.activities.DiagnosticsTone.Neutral
        DiagnosticsDomainReachabilityState.Running -> com.poyka.ripdpi.activities.DiagnosticsTone.Info
        DiagnosticsDomainReachabilityState.Complete -> com.poyka.ripdpi.activities.DiagnosticsTone.Positive
        DiagnosticsDomainReachabilityState.Failed -> com.poyka.ripdpi.activities.DiagnosticsTone.Negative
    }

private fun DiagnosticsCompressionProbeState.tone(): com.poyka.ripdpi.activities.DiagnosticsTone =
    when (this) {
        DiagnosticsCompressionProbeState.Idle -> com.poyka.ripdpi.activities.DiagnosticsTone.Neutral
        DiagnosticsCompressionProbeState.Running -> com.poyka.ripdpi.activities.DiagnosticsTone.Info
        DiagnosticsCompressionProbeState.Complete -> com.poyka.ripdpi.activities.DiagnosticsTone.Positive
        DiagnosticsCompressionProbeState.Failed -> com.poyka.ripdpi.activities.DiagnosticsTone.Negative
    }

private fun DiagnosticsTcp16FatHeaderState.tone(): com.poyka.ripdpi.activities.DiagnosticsTone =
    when (this) {
        DiagnosticsTcp16FatHeaderState.Idle -> com.poyka.ripdpi.activities.DiagnosticsTone.Neutral
        DiagnosticsTcp16FatHeaderState.Running -> com.poyka.ripdpi.activities.DiagnosticsTone.Info
        DiagnosticsTcp16FatHeaderState.Complete -> com.poyka.ripdpi.activities.DiagnosticsTone.Positive
        DiagnosticsTcp16FatHeaderState.Failed -> com.poyka.ripdpi.activities.DiagnosticsTone.Negative
    }

private fun DiagnosticsAllowlistSniState.tone(): com.poyka.ripdpi.activities.DiagnosticsTone =
    when (this) {
        DiagnosticsAllowlistSniState.Idle -> com.poyka.ripdpi.activities.DiagnosticsTone.Neutral
        DiagnosticsAllowlistSniState.Running -> com.poyka.ripdpi.activities.DiagnosticsTone.Info
        DiagnosticsAllowlistSniState.Complete -> com.poyka.ripdpi.activities.DiagnosticsTone.Positive
        DiagnosticsAllowlistSniState.Failed -> com.poyka.ripdpi.activities.DiagnosticsTone.Negative
    }
