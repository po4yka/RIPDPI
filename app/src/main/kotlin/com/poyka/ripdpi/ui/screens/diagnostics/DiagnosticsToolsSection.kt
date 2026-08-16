package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import com.poyka.ripdpi.activities.DiagnosticsApproachRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsApproachesUiModel
import com.poyka.ripdpi.activities.DiagnosticsCidrWhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.activities.DiagnosticsDnsIntegrityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityState
import com.poyka.ripdpi.activities.DiagnosticsDomainReachabilityToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsDpiToolsUiModel
import com.poyka.ripdpi.activities.DiagnosticsIpv4WhitelistToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsPerformanceUiModel
import com.poyka.ripdpi.activities.DiagnosticsPluggableTransportToolUiModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsShareUiModel
import com.poyka.ripdpi.diagnostics.dpi.DpiProbeKind
import com.poyka.ripdpi.services.RemoteDeviceAcceptanceReport
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.screens.diagnostics.rkn.RknBlockDiagnosisScreen
import com.poyka.ripdpi.ui.screens.xray.XrayProviderStatusCard
import com.poyka.ripdpi.ui.screens.xray.XrayProviderToolUiModel
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
    val onRunCidrWhitelistDetection: () -> Unit = {},
    val onCacheIpv4WhitelistSubnets: () -> Unit = {},
    val onCheckIpv4WhitelistSubnets: () -> Unit = {},
    val onSaveIpv4WhitelistCsv: () -> Unit = {},
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

internal data class DiagnosticsShareActions(
    val onShareSummary: (String?) -> Unit,
    val onShareArchive: (String?) -> Unit,
    val onSaveArchive: (String?) -> Unit,
    val onSaveLogs: () -> Unit,
    val onOpenLogs: () -> Unit = {},
)

internal data class DiagnosticsToolsNavActions(
    val onOpenDetectionCheck: () -> Unit = {},
    val onOpenPcapCaptureList: () -> Unit = {},
    val onOpenPastReplays: () -> Unit = {},
)

/**
 * Cohesive bundle of the per-tool UI models rendered by [ToolsSection] plus the
 * user-triggered Xray provider probe handler. Grouping these keeps the screen
 * surface narrow without flattening the tool models into one another.
 */
internal data class DiagnosticsToolsUiModel(
    val dpiTools: DiagnosticsDpiToolsUiModel = DiagnosticsDpiToolsUiModel(),
    val cidrWhitelistTool: DiagnosticsCidrWhitelistToolUiModel = DiagnosticsCidrWhitelistToolUiModel(),
    val ipv4WhitelistTool: DiagnosticsIpv4WhitelistToolUiModel = DiagnosticsIpv4WhitelistToolUiModel(),
    val pluggableTransportTool: DiagnosticsPluggableTransportToolUiModel = DiagnosticsPluggableTransportToolUiModel(),
    val xrayProvider: XrayProviderToolUiModel = XrayProviderToolUiModel(),
    val onRunXrayProviderProbe: () -> Unit = {},
    val remoteDeviceAcceptance: RemoteDeviceAcceptanceReport = RemoteDeviceAcceptanceReport(),
    val onRunRemoteDeviceAcceptance: () -> Unit = {},
    val onShareRemoteDeviceAcceptance: () -> Unit = {},
)

@Composable
internal fun ToolsSection(
    approaches: DiagnosticsApproachesUiModel,
    share: DiagnosticsShareUiModel,
    onSelectApproachMode: (DiagnosticsApproachMode) -> Unit,
    onSelectApproach: (String) -> Unit,
    shareActions: DiagnosticsShareActions,
    tools: DiagnosticsToolsUiModel = DiagnosticsToolsUiModel(),
    dpiToolActions: DiagnosticsDpiToolActions = DiagnosticsDpiToolActions(),
    navActions: DiagnosticsToolsNavActions = DiagnosticsToolsNavActions(),
    rootModeEnabled: Boolean = false,
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
        if (rootModeEnabled) {
            captureItem(pcapRecording, onTogglePcapRecording)
            if (pcapRecording) {
                disclosureItem()
            }
            pcapCaptureListItem(navActions.onOpenPcapCaptureList)
        }
        shareItems(
            share = share,
            onShareSummary = shareActions.onShareSummary,
            onShareArchive = shareActions.onShareArchive,
            onSaveArchive = shareActions.onSaveArchive,
            onSaveLogs = shareActions.onSaveLogs,
            onOpenLogs = shareActions.onOpenLogs,
        )
        xrayProviderItem(tools.xrayProvider, tools.onRunXrayProviderProbe)
        remoteDeviceAcceptanceItem(
            report = tools.remoteDeviceAcceptance,
            onRun = tools.onRunRemoteDeviceAcceptance,
            onShare = tools.onShareRemoteDeviceAcceptance,
        )
        dpiToolItems(
            dpiTools = tools.dpiTools,
            cidrWhitelistTool = tools.cidrWhitelistTool,
            ipv4WhitelistTool = tools.ipv4WhitelistTool,
            pluggableTransportTool = tools.pluggableTransportTool,
            actions = dpiToolActions,
        )
        detectionCheckItem(navActions.onOpenDetectionCheck)
        pastReplaysItem(navActions.onOpenPastReplays)
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

private fun LazyListScope.remoteDeviceAcceptanceItem(
    report: RemoteDeviceAcceptanceReport,
    onRun: () -> Unit,
    onShare: () -> Unit,
) {
    item {
        RemoteDeviceAcceptanceCard(report = report, onRun = onRun, onShare = onShare)
    }
}

private fun LazyListScope.disclosureItem() {
    item {
        RawPacketDisclosureCard()
    }
}

private fun LazyListScope.shareItems(
    share: DiagnosticsShareUiModel,
    onShareSummary: (String?) -> Unit,
    onShareArchive: (String?) -> Unit,
    onSaveArchive: (String?) -> Unit,
    onSaveLogs: () -> Unit,
    onOpenLogs: () -> Unit,
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
            body = diagnosticsArchiveBody(R.string.diagnostics_share_archive_body),
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
                diagnosticsArchiveBody(
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
            title = stringResource(R.string.logs),
            body = stringResource(R.string.diagnostics_open_logs_body),
            buttonLabel = stringResource(R.string.settings_manage_action),
            onClick = onOpenLogs,
            iconTint = RipDpiThemeTokens.colors.info,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsOpenLogs),
            variant = RipDpiButtonVariant.Outline,
        )
    }
}

private fun LazyListScope.dpiToolItems(
    dpiTools: DiagnosticsDpiToolsUiModel,
    cidrWhitelistTool: DiagnosticsCidrWhitelistToolUiModel,
    ipv4WhitelistTool: DiagnosticsIpv4WhitelistToolUiModel,
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
    whitelistToolItems(
        cidrWhitelistTool = cidrWhitelistTool,
        ipv4WhitelistTool = ipv4WhitelistTool,
        actions = actions,
    )
    transportProbeItems(
        dpiTools = dpiTools,
        pluggableTransportTool = pluggableTransportTool,
        actions = actions,
    )
}

private fun LazyListScope.whitelistToolItems(
    cidrWhitelistTool: DiagnosticsCidrWhitelistToolUiModel,
    ipv4WhitelistTool: DiagnosticsIpv4WhitelistToolUiModel,
    actions: DiagnosticsDpiToolActions,
) {
    item {
        CidrWhitelistDetectionCard(
            tool = cidrWhitelistTool,
            onRun = actions.onRunCidrWhitelistDetection,
        )
    }
    item {
        Ipv4WhitelistSubnetDiscoveryCard(
            tool = ipv4WhitelistTool,
            onCache = actions.onCacheIpv4WhitelistSubnets,
            onCheck = actions.onCheckIpv4WhitelistSubnets,
            onSaveCsv = actions.onSaveIpv4WhitelistCsv,
        )
    }
}

private fun LazyListScope.transportProbeItems(
    dpiTools: DiagnosticsDpiToolsUiModel,
    pluggableTransportTool: DiagnosticsPluggableTransportToolUiModel,
    actions: DiagnosticsDpiToolActions,
) {
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

/**
 * Embedded-Xray provider-path diagnostic. Rendered only when a provider session
 * is active (live snapshot present); the card itself shows the typed provider
 * stage + failure class DISTINCTLY from the DPI/tunnel cards and exposes the
 * user-triggered provider probe.
 */
private fun LazyListScope.xrayProviderItem(
    xrayProvider: XrayProviderToolUiModel,
    onRunProbe: () -> Unit,
) {
    val report = xrayProvider.report ?: return
    item {
        XrayProviderStatusCard(
            report = report,
            onRunProbe = onRunProbe,
            probeRunning = xrayProvider.probeRunning,
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
        title = stringResource(R.string.diagnostics_pcap_card_title),
        body =
            if (pcapRecording) {
                stringResource(R.string.diagnostics_pcap_card_body_recording)
            } else {
                stringResource(R.string.diagnostics_pcap_card_body_idle)
            },
        buttonLabel =
            if (pcapRecording) {
                stringResource(R.string.diagnostics_pcap_card_stop)
            } else {
                stringResource(R.string.diagnostics_pcap_card_start)
            },
        onClick = onTogglePcapRecording,
        iconTint = if (pcapRecording) RipDpiThemeTokens.colors.destructive else RipDpiThemeTokens.colors.info,
        variant = if (pcapRecording) RipDpiButtonVariant.Destructive else RipDpiButtonVariant.Outline,
    )
}

@Composable
private fun RawPacketDisclosureCard() {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_pcap_disclosure_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.warning,
        )
        Spacer(
            modifier = Modifier.height(spacing.xs),
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_pcap_disclosure_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.foreground,
        )
    }
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
            pulsing = tool.state.running(),
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_tool_dns_integrity_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        androidx.compose.material3.Text(
            text = tool.errorMessage ?: tool.summary,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = if (tool.errorMessage == null) colors.mutedForeground else colors.destructive,
        )
        MetricsRow(metrics = tool.metrics)
        DnsIntegrityDomainRows(rows = tool.rows)
        DnsIntegrityDoqRows(rows = tool.doqRows)
        DnsIntegrityDohBootstrapRows(rows = tool.dohBootstrapRows)
        RipDpiButton(
            text =
                if (tool.state == DiagnosticsDnsIntegrityState.Running) {
                    stringResource(R.string.diagnostics_tool_checking)
                } else {
                    stringResource(R.string.diagnostics_tool_dns_integrity_run)
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
            pulsing = tool.state.running(),
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.diagnostics_tool_domain_reachability_title),
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
                    stringResource(R.string.diagnostics_tool_scanning)
                } else {
                    stringResource(R.string.diagnostics_tool_domain_reachability_run)
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
