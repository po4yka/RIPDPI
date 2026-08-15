package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsNetworkSnapshotUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.displayTriggerClassification
import com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.chrome.RipDpiScreenSectionHeader
import com.poyka.ripdpi.ui.components.chrome.RipDpiTelemetryEntry
import com.poyka.ripdpi.ui.components.chrome.RipDpiTelemetryRows
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricPill
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private const val ProbeRowMinHeightDp = 52
private const val MonospaceThresholdChars = 18
private const val ProbeRowVerticalPaddingDp = 10
private const val ProbeDetailMaxHeightDp = 260

@Composable
internal fun CompactProbeRow(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .heightIn(min = ProbeRowMinHeightDp.dp)
                .padding(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = RipDpiThemeTokens.spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = probe.target,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = probe.probeType,
                style = RipDpiThemeTokens.type.smallLabel,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        StatusIndicator(
            label = probe.outcome,
            tone = statusTone(probe.tone),
        )
    }
}

@Composable
internal fun SnapshotCard(snapshot: DiagnosticsNetworkSnapshotUiModel) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        RipDpiPanelHeader(
            title = snapshot.title,
            supporting = snapshot.subtitle,
        )
        snapshot.fieldGroups.forEachIndexed { groupIndex, group ->
            val visibleFields =
                remember(group) {
                    group.fields.filter {
                        it.value.isNotBlank() &&
                            !it.value.equals("Unknown", ignoreCase = true)
                    }
                }
            if (visibleFields.isEmpty()) return@forEachIndexed
            if (groupIndex > 0) {
                Spacer(modifier = Modifier.height(spacing.sm))
            }
            if (snapshot.fieldGroups.size > 1) {
                SettingsCategoryHeader(title = group.header, showDivider = true)
            }
            RipDpiTelemetryRows(
                entries =
                    remember(visibleFields) {
                        visibleFields
                            .map { field ->
                                RipDpiTelemetryEntry(
                                    label = field.label,
                                    value = field.value,
                                    monospaceValue = field.value.length > MonospaceThresholdChars,
                                )
                            }.toImmutableList()
                    },
            )
        }
    }
}

@Composable
internal fun ContextGroupCard(group: DiagnosticsContextGroupUiModel) {
    val visibleFields =
        remember(group) {
            group.fields.filter { it.value.isNotBlank() && !it.value.equals("Unknown", ignoreCase = true) }
        }
    if (visibleFields.isEmpty()) return
    RipDpiCard {
        RipDpiScreenSectionHeader(title = group.title)
        RipDpiTelemetryRows(
            entries =
                remember(visibleFields) {
                    visibleFields
                        .map { field ->
                            RipDpiTelemetryEntry(
                                label = field.label,
                                value = field.value,
                                monospaceValue = field.value.length > MonospaceThresholdChars,
                            )
                        }.toImmutableList()
                },
        )
    }
}

@Composable
internal fun SessionRow(
    session: DiagnosticsSessionRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("SessionRow")
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.layout.cardPadding,
                vertical = ProbeRowVerticalPaddingDp.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (session.launchOrigin == DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND) {
                    RipDpiMetricPill(
                        text = stringResource(R.string.diagnostics_history_background_probe_badge),
                        tone = RipDpiMetricTone.Muted,
                        shape = RipDpiThemeTokens.shapes.xxl,
                        textStyle = RipDpiThemeTokens.type.smallLabel,
                        paddingValues = PaddingValues(horizontal = RipDpiThemeTokens.spacing.sm, vertical = 2.dp),
                    )
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
                                session.triggerClassification.displayTriggerClassification(),
                            ),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }
            StatusIndicator(
                label = session.completionLabel ?: session.status,
                tone = statusTone(session.tone),
            )
        }
        if (session.metrics.isNotEmpty()) {
            HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
        }
        MetricsRow(metrics = session.metrics)
    }
}

@Composable
internal fun ProbeResultRow(
    probe: DiagnosticsProbeResultUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("ProbeResultRow")
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.layout.cardPadding,
                vertical = RipDpiThemeTokens.spacing.sm,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = probe.target,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = probe.probeType,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            StatusIndicator(
                label = probe.outcome,
                tone = statusTone(probe.tone),
            )
        }
    }
}

@Composable
internal fun EventRow(
    event: DiagnosticsEventUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("EventRow")
    val colors = RipDpiThemeTokens.colors
    val friendlySummary = friendlyErrorSummary(event.message)
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        paddingValues =
            androidx.compose.foundation.layout
                .PaddingValues(RipDpiThemeTokens.layout.cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EventBadge(text = event.source, tone = event.tone)
                    EventBadge(text = event.severity, tone = event.tone)
                }
                if (friendlySummary != null) {
                    Text(
                        text = friendlySummary,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                }
                Text(
                    text = event.message,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.createdAtLabel,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

private val errorClassPattern = Regex("""class=(\S+)""")

@Composable
private fun friendlyErrorSummary(message: String): String? {
    val match = errorClassPattern.find(message) ?: return null
    val errorClass = match.groupValues[1]
    return when (errorClass) {
        "connect_failure" -> stringResource(R.string.diagnostics_error_connect_failure)
        "silent_drop" -> stringResource(R.string.diagnostics_error_silent_drop)
        "timeout" -> stringResource(R.string.diagnostics_error_timeout)
        "dns_failure" -> stringResource(R.string.diagnostics_error_dns_failure)
        else -> stringResource(R.string.diagnostics_error_unknown)
    }
}

@Composable
internal fun EventBadge(
    text: String,
    tone: DiagnosticsTone,
) {
    RipDpiMetricPill(
        text = text,
        tone = metricTone(tone),
        shape = RipDpiThemeTokens.shapes.md,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.spacing.sm,
                vertical = RipDpiThemeTokens.spacing.xs,
            ),
    )
}

@Composable
internal fun DiagnosticsPreviewCard(
    title: String,
    body: String,
    metrics: ImmutableList<DiagnosticsMetricUiModel>,
    archiveStateMessage: String?,
    archiveStateTone: DiagnosticsTone,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val paragraphs = body.split("\n\n").filter { it.isNotBlank() }
    val description = paragraphs.firstOrNull() ?: ""
    val detailParagraphs = paragraphs.drop(1)
    val descriptionLines = description.lines()
    val headerLines = descriptionLines.takeWhile { line -> !isDataLine(line) }
    val dataLines = descriptionLines.drop(headerLines.size)

    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsSharePreviewCard),
        variant = RipDpiCardVariant.Elevated,
    ) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        if (headerLines.isNotEmpty()) {
            Text(
                text = headerLines.joinToString("\n"),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        if (dataLines.isNotEmpty()) {
            HorizontalDivider(color = colors.divider)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = ProbeDetailMaxHeightDp.dp)
                        .background(colors.inputBackground, RipDpiThemeTokens.shapes.md)
                        .horizontalScroll(rememberScrollState())
                        .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                dataLines.forEach { line ->
                    Text(
                        text = line,
                        style = RipDpiThemeTokens.type.monoSmall,
                        color = colors.mutedForeground,
                        softWrap = false,
                    )
                }
            }
        }
        detailParagraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        MetricsRow(metrics = metrics)
        archiveStateMessage?.let { message ->
            StatusIndicator(
                label = message,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsArchiveStateIndicator),
                tone = statusTone(archiveStateTone),
            )
        }
    }
}

@Composable
internal fun ShareActionCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    variant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    enabled: Boolean = true,
) {
    RipDpiCard(
        modifier =
            modifier.semantics {
                if (!enabled) {
                    disabled()
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = body,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            androidx.compose.material3.Icon(
                imageVector = RipDpiIcons.Share,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(RipDpiIconSizes.Medium),
            )
        }
        RipDpiButton(
            text = buttonLabel,
            onClick = onClick,
            variant = variant,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val dataLinePrefixes =
    listOf(
        "Session ",
        "Network ",
        "Context ",
        "Permissions ",
        "Live ",
        "Top warning",
        "Telegram",
    )

private fun isDataLine(line: String): Boolean =
    dataLinePrefixes.any { line.startsWith(it) } || line.contains("probe results")
