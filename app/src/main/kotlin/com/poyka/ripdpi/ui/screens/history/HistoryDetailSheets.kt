package com.poyka.ripdpi.ui.screens.history

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.screens.diagnostics.DedicatedProbeEvidenceCards
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsSessionDiagnosesCard
import com.poyka.ripdpi.ui.screens.diagnostics.ProbeResultRow
import com.poyka.ripdpi.ui.screens.diagnostics.TelegramResultCard
import com.poyka.ripdpi.ui.screens.diagnostics.hasDedicatedEvidenceCard
import com.poyka.ripdpi.ui.screens.diagnostics.isSelectiveMatrixProbe
import com.poyka.ripdpi.ui.screens.diagnostics.isTelegramAvailabilityProbe
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsEventUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSessionDetailUiModel
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionDetailSheet(
    detail: HistoryConnectionDetailUiModel,
    onDismissRequest: () -> Unit,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismissRequest,
        title = detail.session.title,
        message = detail.session.subtitle,
        icon = RipDpiIcons.Logs,
        testTag = RipDpiTestTags.HistoryConnectionDetailSheet,
    ) {
        ConnectionDetailContent(detail)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsDetailSheet(
    detail: DiagnosticsSessionDetailUiModel,
    matrixPairing: SelectiveMatrixPairingUiModel?,
    onCompareMatrixSession: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismissRequest,
        title = detail.session.title,
        message = detail.session.subtitle,
        icon = RipDpiIcons.Search,
        testTag = RipDpiTestTags.HistoryDiagnosticsDetailSheet,
    ) {
        DiagnosticsDetailContent(detail, matrixPairing, onCompareMatrixSession)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventDetailSheet(
    event: DiagnosticsEventUiModel,
    onDismissRequest: () -> Unit,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismissRequest,
        title = event.source,
        message = event.createdAtLabel,
        icon = RipDpiIcons.Info,
        testTag = RipDpiTestTags.HistoryEventDetailSheet,
    ) {
        EventDetailContent(event)
    }
}

@Composable
private fun DetailEvents(events: List<DiagnosticsEventUiModel>) {
    Text(
        text = stringResource(R.string.history_events_section),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
    events.forEach { event ->
        EventRow(
            event = event,
            onClick = null,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyEvent(event.id)),
        )
    }
}

@Composable
internal fun ConnectionDetailContent(detail: HistoryConnectionDetailUiModel) {
    StatusIndicator(
        label = detail.session.connectionState,
        tone = statusTone(detail.session.tone),
    )
    if (detail.highlights.isNotEmpty()) {
        MetricList(detail.highlights)
    }
    detail.contextGroups.forEach { group ->
        ContextGroupCard(group = group)
    }
    detail.snapshots.forEach { snapshot ->
        HistorySnapshotCard(snapshot = snapshot)
    }
    if (detail.events.isNotEmpty()) {
        DetailEvents(events = detail.events)
    }
}

@Composable
internal fun DiagnosticsDetailContent(
    detail: DiagnosticsSessionDetailUiModel,
    matrixPairing: SelectiveMatrixPairingUiModel?,
    onCompareMatrixSession: (String) -> Unit,
) {
    StatusIndicator(
        label = detail.session.completionLabel ?: detail.session.status,
        tone = statusTone(detail.session.tone),
    )
    if (detail.diagnoses.isNotEmpty()) {
        DiagnosticsSessionDiagnosesCard(diagnoses = detail.diagnoses, reportMetadata = emptyList())
    }
    if (detail.reportMetadata.isNotEmpty()) {
        RipDpiCard {
            Text(
                text = stringResource(R.string.diagnostics_report_metadata_title),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            detail.reportMetadata.forEachIndexed { index, field ->
                SettingsRow(
                    title = field.label,
                    value = field.value,
                    showDivider = index != detail.reportMetadata.lastIndex,
                )
            }
        }
    }
    DedicatedProbeEvidenceCards(detail.probeGroups.flatMap { it.items })
    matrixPairing?.let { SelectiveMatrixPairCard(it, onCompareMatrixSession) }
    detail.contextGroups.forEach { group ->
        ContextGroupCard(group = group)
    }
    detail.probeGroups.forEach { group ->
        val (expandedProbes, otherProbes) =
            group.items.partition { it.isTelegramAvailabilityProbe() || it.fatHeaderEvidence.isNotEmpty() }
        expandedProbes.forEach { probe ->
            if (probe.isTelegramAvailabilityProbe()) {
                TelegramResultCard(probe)
            } else {
                ProbeResultRow(probe, onClick = null)
            }
        }
        val probes =
            otherProbes.filterNot {
                it.isSelectiveMatrixProbe() || it.hasDedicatedEvidenceCard()
            }
        if (probes.isNotEmpty()) ProbeGroupCard(group = group.copy(items = probes.toImmutableList()))
    }
    detail.snapshots.forEach { snapshot ->
        HistorySnapshotCard(snapshot = snapshot)
    }
    if (detail.events.isNotEmpty()) {
        DetailEvents(events = detail.events)
    }
}

@Composable
internal fun EventDetailContent(event: DiagnosticsEventUiModel) {
    StatusIndicator(
        label = event.severity,
        tone = statusTone(event.tone),
    )
    Text(
        text = event.message,
        style = RipDpiThemeTokens.type.body,
        color = RipDpiThemeTokens.colors.foreground,
    )
}
