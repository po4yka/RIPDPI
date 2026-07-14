package com.poyka.ripdpi.ui.screens.history

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsNetworkSnapshotUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.chrome.RipDpiTelemetryEntry
import com.poyka.ripdpi.ui.components.chrome.RipDpiTelemetryRows
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ProbeGroupCard(group: DiagnosticsProbeGroupUiModel) {
    RipDpiCard {
        Text(
            text = group.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        group.items.forEach { probe ->
            SettingsRow(
                title = probe.target,
                subtitle = probe.probeType,
                value = probe.outcome,
                showDivider = probe != group.items.last(),
            )
        }
    }
}

@Composable
internal fun ContextGroupCard(group: DiagnosticsContextGroupUiModel) {
    RipDpiCard {
        Text(
            text = group.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        group.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                showDivider = index != group.fields.lastIndex,
            )
        }
    }
}

@Composable
internal fun SnapshotCard(snapshot: DiagnosticsNetworkSnapshotUiModel) {
    RipDpiCard {
        Text(
            text = snapshot.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = snapshot.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        snapshot.fields.forEachIndexed { index, field ->
            SettingsRow(
                title = field.label,
                value = field.value,
                showDivider = index != snapshot.fields.lastIndex,
            )
        }
    }
}

@Composable
internal fun MetricList(metrics: ImmutableList<DiagnosticsMetricUiModel>) {
    val entries =
        remember(metrics) {
            metrics
                .map { metric ->
                    RipDpiTelemetryEntry(
                        label = metric.label,
                        value = metric.value,
                        monospaceValue = metric.value.length > 18,
                    )
                }.toImmutableList()
        }
    RipDpiTelemetryRows(
        entries = entries,
    )
}

internal fun statusTone(tone: DiagnosticsTone): StatusIndicatorTone =
    com.poyka.ripdpi.ui.screens.diagnostics
        .statusTone(tone)
