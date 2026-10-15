package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanLaunchOrigin
import com.poyka.ripdpi.platform.AndroidStringResolver
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
    expertMode: Boolean = true,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiClickable(role = Role.Button, onClick = onClick)
                .heightIn(min = ProbeRowMinHeightDp.dp)
                .padding(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = probe.pmtu?.title ?: probe.http3?.title ?: probe.ipFamily?.title ?: probe.target,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = diagnosticProbeTitle(probe, expertMode),
                style = RipDpiThemeTokens.type.smallLabel,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        StatusIndicator(
            label = diagnosticProbeOutcome(probe, expertMode),
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
            group.fields.filter {
                it.value.isNotBlank() && (group.stackedFields || !it.value.equals("Unknown", ignoreCase = true))
            }
        }
    if (visibleFields.isEmpty()) return
    RipDpiCard {
        RipDpiScreenSectionHeader(title = group.title)
        if (group.stackedFields) {
            visibleFields.forEach { field ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    Text(
                        field.label,
                        modifier = Modifier.fillMaxWidth(),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                    Text(
                        field.value,
                        modifier = Modifier.fillMaxWidth(),
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                }
            }
            return@RipDpiCard
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

@Composable
internal fun SessionRow(
    session: DiagnosticsSessionRowUiModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("SessionRow")
    val backgroundProbe = session.launchOrigin == DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND
    val backgroundBadge =
        if (backgroundProbe) {
            stringResource(
                R.string.diagnostics_history_background_probe_badge,
            )
        } else {
            null
        }
    val triggerSummary =
        if (backgroundProbe && session.triggerClassification != null) {
            stringResource(
                R.string.diagnostics_scan_trigger_handover_summary_format,
                session.triggerClassification.displayTriggerClassification(AndroidStringResolver(LocalContext.current)),
            )
        } else {
            null
        }
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.layout.cardPadding,
                vertical = ProbeRowVerticalPaddingDp.dp,
            ),
    ) {
        DiagnosticRowHeader(
            contentTexts =
                listOfNotNull(
                    session.title to RipDpiThemeTokens.type.bodyEmphasis,
                    session.subtitle to RipDpiThemeTokens.type.secondaryBody,
                    backgroundBadge?.let { it to RipDpiThemeTokens.type.smallLabel },
                    triggerSummary?.let { it to RipDpiThemeTokens.type.secondaryBody },
                ),
            metadata = (session.completionLabel ?: session.status) to RipDpiThemeTokens.type.brandStatus,
            contentDecorationWidth = if (backgroundBadge != null) RipDpiThemeTokens.spacing.sm * 2 else 0.dp,
            metadataDecorationWidth =
                RipDpiThemeTokens.components.indicators.statusMarkerLarge +
                    RipDpiThemeTokens.components.indicators.statusMarkerSpacing,
            trailing = { metadataModifier ->
                StatusIndicator(
                    modifier = metadataModifier,
                    label = session.completionLabel ?: session.status,
                    tone = statusTone(session.tone),
                )
            },
        ) { textModifier ->
            DiagnosticSessionText(session, backgroundBadge, triggerSummary, textModifier)
        }
        if (session.metrics.isNotEmpty()) {
            HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
        }
        MetricsRow(metrics = session.metrics)
    }
}

@Composable
private fun DiagnosticSessionText(
    session: DiagnosticsSessionRowUiModel,
    backgroundBadge: String?,
    triggerSummary: String?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (backgroundBadge != null) {
            RipDpiMetricPill(
                text = backgroundBadge,
                tone = RipDpiMetricTone.Muted,
                shape = RipDpiThemeTokens.shapes.xxl,
                textStyle = RipDpiThemeTokens.type.smallLabel,
                paddingValues = PaddingValues(horizontal = RipDpiThemeTokens.spacing.sm, vertical = 2.dp),
            )
        }
        Text(
            text = session.title,
            modifier = Modifier.fillMaxWidth(),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = session.subtitle,
            modifier = Modifier.fillMaxWidth(),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        if (triggerSummary != null) {
            Text(
                text = triggerSummary,
                modifier = Modifier.fillMaxWidth(),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

@Composable
internal fun ProbeResultRow(
    probe: DiagnosticsProbeResultUiModel,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("ProbeResultRow")
    val colors = RipDpiThemeTokens.colors
    val title = probe.pmtu?.title ?: probe.http3?.title ?: probe.ipFamily?.title ?: probe.target
    val subtitle =
        when {
            probe.pmtu != null -> probe.pmtu.title
            probe.http3 != null -> probe.http3.title
            probe.ipFamily != null -> stringResource(R.string.diagnostics_ip_title)
            else -> probe.probeType
        }
    val outcome = (probe.pmtu ?: probe.http3 ?: probe.ipFamily)?.fields?.firstOrNull()?.value ?: probe.outcome
    RipDpiCard(
        modifier = modifier,
        onClick = onClick,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.layout.cardPadding,
                vertical = RipDpiThemeTokens.spacing.sm,
            ),
    ) {
        DiagnosticRowHeader(
            contentTexts =
                listOf(
                    title to RipDpiThemeTokens.type.bodyEmphasis,
                    subtitle to RipDpiThemeTokens.type.secondaryBody,
                ),
            metadata = outcome to RipDpiThemeTokens.type.brandStatus,
            metadataDecorationWidth =
                RipDpiThemeTokens.components.indicators.statusMarkerLarge +
                    RipDpiThemeTokens.components.indicators.statusMarkerSpacing,
            alignment = Alignment.CenterVertically,
            trailing = { metadataModifier ->
                StatusIndicator(
                    modifier = metadataModifier,
                    label = outcome,
                    tone = statusTone(probe.tone),
                )
            },
        ) { textModifier ->
            Column(
                modifier = textModifier,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = subtitle,
                    modifier = Modifier.fillMaxWidth(),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
        }
        probe.connectionStages?.let { DiagnosticsConnectionStageCard(it) }
        probe.transferEvidence?.let { DiagnosticsTransferCard(transfer = it) }
        probe.ipFamily?.let { ContextGroupCard(it) }
        probe.http3?.let { ContextGroupCard(it) }
        probe.pmtu?.let { ContextGroupCard(it) }
        DiagnosticsDnsResponseCards(probe.dnsResponses)
    }
}

@Composable
internal fun EventRow(
    event: DiagnosticsEventUiModel,
    onClick: (() -> Unit)?,
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
        DiagnosticRowHeader(
            contentTexts =
                listOfNotNull(
                    friendlySummary?.let { it to RipDpiThemeTokens.type.bodyEmphasis },
                    event.message to RipDpiThemeTokens.type.secondaryBody,
                    event.source to RipDpiThemeTokens.type.monoSmall,
                    event.severity to RipDpiThemeTokens.type.monoSmall,
                ),
            metadata = event.createdAtLabel to RipDpiThemeTokens.type.monoSmall,
            contentDecorationWidth = RipDpiThemeTokens.spacing.sm * 2,
            trailing = { metadataModifier ->
                Text(
                    text = event.createdAtLabel,
                    modifier = metadataModifier,
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            },
        ) { textModifier ->
            Column(
                modifier = textModifier,
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
                ) {
                    EventBadge(text = event.source, tone = event.tone)
                    EventBadge(text = event.severity, tone = event.tone)
                }
                if (friendlySummary != null) {
                    Text(
                        text = friendlySummary,
                        modifier = Modifier.fillMaxWidth(),
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                }
                Text(
                    text = event.message,
                    modifier = Modifier.fillMaxWidth(),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRowHeader(
    contentTexts: List<Pair<String, TextStyle>>,
    metadata: Pair<String, TextStyle>,
    metadataDecorationWidth: Dp = 0.dp,
    contentDecorationWidth: Dp = 0.dp,
    alignment: Alignment.Vertical = Alignment.Top,
    trailing: @Composable (Modifier) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val spacing = RipDpiThemeTokens.spacing
    val contentWordWidth =
        contentTexts.maxOf { (text, style) ->
            measurer
                .measure(AnnotatedString(text), style = style.copy(hyphens = Hyphens.None))
                .multiParagraph.minIntrinsicWidth
        }
    val metadataWidth = measurer.measure(AnnotatedString(metadata.first), style = metadata.second).size.width
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep the text budget large enough for whole words before placing metadata beside it.
        val inlineWidth =
            with(density) { (contentWordWidth + metadataWidth).toDp() } +
                metadataDecorationWidth + contentDecorationWidth + spacing.sm
        if (inlineWidth > maxWidth) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                content(Modifier.fillMaxWidth())
                trailing(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = alignment,
            ) {
                content(Modifier.weight(1f))
                trailing(Modifier)
            }
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
