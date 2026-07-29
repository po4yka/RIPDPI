package com.poyka.ripdpi.ui.screens.history

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionDetailUiModel
import com.poyka.ripdpi.activities.HistoryConnectionDetailUiModel
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
            SnapshotCard(snapshot = snapshot)
        }
        if (detail.events.isNotEmpty()) {
            DetailEvents(events = detail.events)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsDetailSheet(
    detail: DiagnosticsSessionDetailUiModel,
    onDismissRequest: () -> Unit,
) {
    RipDpiBottomSheet(
        onDismissRequest = onDismissRequest,
        title = detail.session.title,
        message = detail.session.subtitle,
        icon = RipDpiIcons.Search,
        testTag = RipDpiTestTags.HistoryDiagnosticsDetailSheet,
    ) {
        StatusIndicator(
            label = detail.session.completionLabel ?: detail.session.status,
            tone = statusTone(detail.session.tone),
        )
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
        detail.contextGroups.forEach { group ->
            ContextGroupCard(group = group)
        }
        detail.probeGroups.forEach { group ->
            ProbeGroupCard(group = group)
        }
        detail.snapshots.forEach { snapshot ->
            SnapshotCard(snapshot = snapshot)
        }
        if (detail.events.isNotEmpty()) {
            DetailEvents(events = detail.events)
        }
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
            onClick = {},
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.historyEvent(event.id)),
        )
    }
}
