@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.HistoryConnectionRowUiModel
import com.poyka.ripdpi.activities.connectionStateTitle
import com.poyka.ripdpi.activities.displayTriggerClassification
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.RipDpiCompactMetrics
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenSurface
import com.poyka.ripdpi.ui.theme.screens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ConnectionSessionCard(
    session: HistoryConnectionRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    RipDpiCard(
        modifier = modifier.semantics { if (selected) this.selected = true },
        onClick = onClick,
        variant = if (selected) RipDpiCardVariant.Selected else RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = AndroidStringResolver(LocalContext.current).connectionStateTitle(session.connectionState),
            tone = statusTone(session.tone),
        )
        session.rememberedPolicyBadge?.let { badge ->
            Box(
                modifier =
                    Modifier
                        .ripDpiScreenSurface(RipDpiThemeTokens.styles.screens.inputBadge, RipDpiThemeTokens.shapes.xxl)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .ripDpiTestTag(RipDpiTestTags.historyConnectionRememberedBadge(session.id)),
            ) {
                Text(
                    text = badge,
                    style = RipDpiThemeTokens.type.smallLabel,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
        Text(
            text = session.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = session.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        session.summary?.let { summary ->
            Text(
                text = summary,
                style = RipDpiThemeTokens.type.body,
                color = RipDpiThemeTokens.colors.foreground,
            )
        }
        SessionMetrics(session.metrics)
    }
}

@Composable
internal fun DiagnosticsSessionCard(
    session: DiagnosticsSessionRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    RipDpiCard(
        modifier = modifier.semantics { if (selected) this.selected = true },
        onClick = onClick,
        variant = if (selected) RipDpiCardVariant.Selected else RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = session.completionLabel ?: session.status,
            tone = statusTone(session.tone),
        )
        if (session.launchOrigin == DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND) {
            Box(
                modifier =
                    Modifier
                        .ripDpiScreenSurface(RipDpiThemeTokens.styles.screens.inputBadge, RipDpiThemeTokens.shapes.xxl)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .ripDpiTestTag(RipDpiTestTags.historyDiagnosticsAutomaticBadge(session.id)),
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_history_background_probe_badge),
                    style = RipDpiThemeTokens.type.smallLabel,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
        Text(
            text = session.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = session.subtitle,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        if (
            session.launchOrigin == DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND &&
            session.triggerClassification != null
        ) {
            Text(
                text =
                    stringResource(
                        R.string.diagnostics_scan_trigger_handover_summary_format,
                        session.triggerClassification.displayTriggerClassification(
                            AndroidStringResolver(LocalContext.current),
                        ),
                    ),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        Text(
            text = session.summary,
            style = RipDpiThemeTokens.type.body,
            color = RipDpiThemeTokens.colors.foreground,
        )
        SessionMetrics(session.metrics)
    }
}

// Session cards are list rows; one compact line of stats keeps each card short.
@Composable
private fun SessionMetrics(metrics: ImmutableList<DiagnosticsMetricUiModel>) {
    RipDpiCompactMetrics(metrics = metrics.map { it.label to it.value }.toImmutableList())
}

@Composable
internal fun EventRow(
    event: DiagnosticsEventUiModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    occurrenceCount: Int = 1,
    lastTimestampLabel: String? = null,
    selected: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier.semantics { if (selected) this.selected = true },
        onClick = onClick,
        variant = if (selected) RipDpiCardVariant.Selected else RipDpiCardVariant.Outlined,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIndicator(
                label = event.severity,
                tone = statusTone(event.tone),
            )
            if (occurrenceCount > 1) {
                Box(
                    modifier =
                        Modifier
                            .ripDpiScreenSurface(
                                RipDpiThemeTokens.styles.screens.inputBadge,
                                RipDpiThemeTokens.shapes.xxl,
                            ).padding(
                                horizontal = components.rows.compactPillHorizontalPadding,
                                vertical = components.rows.compactPillVerticalPadding,
                            ),
                ) {
                    Text(
                        text = stringResource(R.string.history_event_occurrence_format, occurrenceCount),
                        style = type.smallLabel,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        val timestampText =
            if (lastTimestampLabel != null) {
                stringResource(
                    R.string.history_event_timestamp_range,
                    event.source,
                    event.createdAtLabel,
                    lastTimestampLabel,
                )
            } else {
                stringResource(
                    R.string.history_event_timestamp_single,
                    event.source,
                    event.createdAtLabel,
                )
            }
        Text(
            text = timestampText,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        Text(
            text = event.message,
            style = type.body,
            color = colors.foreground,
        )
    }
}
