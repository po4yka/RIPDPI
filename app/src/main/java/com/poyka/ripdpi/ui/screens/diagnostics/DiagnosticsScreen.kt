package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun DiagnosticsRoute(
    onExport: (String?) -> Unit,
    onSaveLogs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        uiState = uiState,
        onRunRawScan = viewModel::startRawScan,
        onRunInPathScan = viewModel::startInPathScan,
        onCancelScan = viewModel::cancelScan,
        onExport = {
            viewModel.exportLatest { path -> onExport(path) }
        },
        onSaveLogs = onSaveLogs,
        modifier = modifier,
    )
}

@Composable
fun DiagnosticsScreen(
    uiState: DiagnosticsUiState,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
    onExport: () -> Unit,
    onSaveLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
    ) {
        RipDpiTopAppBar(title = stringResource(R.string.diagnostics_title))

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = layout.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Spacer(modifier = Modifier.height(spacing.sm))
            DiagnosticsOverviewCard(uiState)
            DiagnosticsScanCard(
                uiState = uiState,
                onRunRawScan = onRunRawScan,
                onRunInPathScan = onRunInPathScan,
                onCancelScan = onCancelScan,
            )
            DiagnosticsMonitorCard(uiState)
            DiagnosticsHistoryCard(uiState)
            DiagnosticsExportCard(
                uiState = uiState,
                onExport = onExport,
                onSaveLogs = onSaveLogs,
            )
            Spacer(modifier = Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun DiagnosticsOverviewCard(uiState: DiagnosticsUiState) {
    RipDpiCard {
        Text(text = stringResource(R.string.diagnostics_overview_section), style = RipDpiThemeTokens.type.sectionTitle)
        Text(
            text = uiState.activeProfile?.name ?: stringResource(R.string.diagnostics_profiles_title),
            style = RipDpiThemeTokens.type.screenTitle,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = uiState.snapshots.firstOrNull()?.payloadJson ?: stringResource(R.string.diagnostics_snapshot_title),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun DiagnosticsScanCard(
    uiState: DiagnosticsUiState,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Text(text = stringResource(R.string.diagnostics_scan_section), style = RipDpiThemeTokens.type.sectionTitle)
        Text(
            text = uiState.activeScanMessage ?: stringResource(R.string.diagnostics_status_idle),
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_action_raw),
                onClick = onRunRawScan,
                modifier = Modifier.weight(1f),
                enabled = !uiState.activeScanRunning,
            )
            RipDpiButton(
                text = stringResource(R.string.diagnostics_action_in_path),
                onClick = onRunInPathScan,
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Outline,
                enabled = !uiState.activeScanRunning,
            )
        }
        RipDpiButton(
            text = stringResource(R.string.diagnostics_action_cancel),
            onClick = onCancelScan,
            variant = RipDpiButtonVariant.Destructive,
            enabled = uiState.activeScanRunning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiagnosticsMonitorCard(uiState: DiagnosticsUiState) {
    RipDpiCard {
        Text(text = stringResource(R.string.diagnostics_monitor_section), style = RipDpiThemeTokens.type.sectionTitle)
        if (uiState.telemetry.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostics_monitor_empty),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        } else {
            uiState.telemetry.take(3).forEach { sample ->
                Text(
                    text = "${sample.networkType} tx=${sample.txBytes} rx=${sample.rxBytes}",
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = RipDpiThemeTokens.colors.foreground,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsHistoryCard(uiState: DiagnosticsUiState) {
    RipDpiCard {
        Text(text = stringResource(R.string.diagnostics_history_section), style = RipDpiThemeTokens.type.sectionTitle)
        if (uiState.sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostics_history_empty),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        } else {
            uiState.sessions.take(5).forEach { session ->
                Text(
                    text = "${session.pathMode}: ${session.summary}",
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.foreground,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsExportCard(
    uiState: DiagnosticsUiState,
    onExport: () -> Unit,
    onSaveLogs: () -> Unit,
) {
    RipDpiCard {
        Text(text = stringResource(R.string.diagnostics_export_section), style = RipDpiThemeTokens.type.sectionTitle)
        RipDpiButton(
            text = stringResource(R.string.diagnostics_action_export),
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
        )
        RipDpiButton(
            text = stringResource(R.string.save_logs),
            onClick = onSaveLogs,
            variant = RipDpiButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
        Text(text = stringResource(R.string.diagnostics_events_title), style = RipDpiThemeTokens.type.bodyEmphasis)
        if (uiState.events.isEmpty()) {
            Text(
                text = stringResource(R.string.logs_empty_body),
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        } else {
            uiState.events.take(5).forEach { event ->
                Text(
                    text = "${event.source}: ${event.message}",
                    style = RipDpiThemeTokens.type.body,
                    color = RipDpiThemeTokens.colors.foreground,
                )
            }
        }
    }
}
