@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.AnalysisProgressIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenSurface
import com.poyka.ripdpi.ui.theme.screens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Composable
fun StatusModeCard(
    uiState: StatusModeCardUiState,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    onPrimaryAction: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
    primaryActionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    configureActionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Outline,
) {
    val primaryEnabled = uiState.primaryActionEnabled && (!uiState.isLoading || uiState.mode == StatusMode.Diagnostic)
    val statusLabel = statusModeStatusLabel(uiState)
    val primaryActionLabel = uiState.primaryActionLabel.ifBlank { stringResource(R.string.status_mode_card_run_scan) }
    val configureLabel = uiState.configureLabel.ifBlank { stringResource(R.string.status_mode_card_configure) }

    RipDpiCard(
        modifier =
            modifier.ripDpiTestTag(
                RipDpiTestTags.statusModeCard(uiState.mode.name),
            ),
        variant =
            when {
                uiState.isLoading -> RipDpiCardVariant.Tonal

                // Active card uses the Selected surface (accent fill + bold
                // foreground border + elevation) so it is unmistakably the live
                // card, rather than receding behind the flat Outlined cards.
                uiState.isActive -> RipDpiCardVariant.Selected

                else -> RipDpiCardVariant.Outlined
            },
    ) {
        StatusModeCardContent(
            uiState = uiState,
            statusLabel = statusLabel,
            onCardClick = onCardClick,
        )
        StatusModeCardActions(
            uiState = uiState,
            primaryActionLabel = primaryActionLabel,
            configureLabel = configureLabel,
            primaryEnabled = primaryEnabled,
            onPrimaryAction = onPrimaryAction,
            onConfigure = onConfigure,
            primaryActionVariant = primaryActionVariant,
            configureActionVariant = configureActionVariant,
        )
    }
}

@Composable
private fun StatusModeCardContent(
    uiState: StatusModeCardUiState,
    statusLabel: String,
    onCardClick: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.statusModeCardBody(uiState.mode.name))
                .then(
                    if (onCardClick != null) {
                        Modifier.ripDpiClickable(role = Role.Button, onClick = onCardClick)
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        StatusModeCardHeader(uiState = uiState, statusLabel = statusLabel)
        StatusModeCardBody(uiState = uiState, statusLabel = statusLabel)
    }
}

@Composable
private fun StatusModeCardHeader(
    uiState: StatusModeCardUiState,
    statusLabel: String,
) {
    val stackHeader = LocalDensity.current.fontScale >= StatusModeStackedSummaryFontScale
    if (stackHeader) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            StatusModeCardTitle(title = uiState.title)
            StatusModeCardStatus(uiState = uiState, statusLabel = statusLabel)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusModeCardTitle(title = uiState.title, modifier = Modifier.weight(1f))
            StatusModeCardStatus(uiState = uiState, statusLabel = statusLabel)
        }
    }
}

@Composable
private fun StatusModeCardTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = RipDpiThemeTokens.type.cardTitle,
        color = RipDpiThemeTokens.colors.foreground,
        modifier = modifier,
    )
}

@Composable
private fun StatusModeCardStatus(
    uiState: StatusModeCardUiState,
    statusLabel: String,
) {
    StatusIndicator(
        label = statusLabel,
        tone = statusModeStatusTone(uiState),
        // isActive is a steady connected state; only the transition into it is in flight.
        pulsing = uiState.isLoading,
    )
}

@Composable
private fun StatusModeCardBody(
    uiState: StatusModeCardUiState,
    statusLabel: String,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    // Falling back to the header badge's own label spent a whole line repeating
    // a word the user had just read one line above, and pushed the facets that
    // actually differ per mode further down. With nothing distinct to add, add
    // nothing.
    // The header badge already names the state; a body line that only repeats it says nothing new.
    if (uiState.statusLine.isNotBlank() && !uiState.statusLine.equals(statusLabel, ignoreCase = true)) {
        Text(
            text = uiState.statusLine,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
    }

    val analysisProgress = uiState.analysisProgress
    if (analysisProgress != null) {
        // A running scan has real per-stage progress; showing a static "Busy" line for the whole run
        // was the only thing standing between this component and the data it was built for.
        AnalysisProgressIndicator(
            stages = analysisProgress.stages,
            activeStageIndex = analysisProgress.activeStageIndex,
            stageLabel = uiState.primaryLabel,
        )
    } else if (uiState.summaryFacets.isNotEmpty()) {
        StatusModeSummaryStrip(facets = uiState.summaryFacets)
    } else if (uiState.primaryLabel.isNotBlank()) {
        Text(
            text = uiState.primaryLabel,
            style = type.bodyEmphasis,
            color = colors.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    uiState.secondaryLabel
        ?.takeIf { it.isNotBlank() && it != uiState.statusLine }
        ?.let { label ->
            Text(
                text = label,
                style = type.caption,
                color = colors.mutedForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
}

/**
 * Renders a card's configuration summary as a structured key/value panel (one row per
 * [StatusModeSummaryFacet]) instead of a single dense line. The fixed-weight label/value columns
 * keep labels aligned across rows; the panel's subtle fill + outline read it as a discrete element.
 */
@Composable
private fun StatusModeSummaryStrip(facets: ImmutableList<StatusModeSummaryFacet>) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.md
    val stackFacets = LocalDensity.current.fontScale >= StatusModeStackedSummaryFontScale
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiScreenSurface(RipDpiThemeTokens.styles.screens.mutedPanel, shape)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        facets.forEach { facet ->
            if (stackFacets) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    StatusModeSummaryLabel(facet = facet)
                    StatusModeSummaryValue(facet = facet)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusModeSummaryLabel(
                        facet = facet,
                        modifier = Modifier.weight(StatusModeSummaryLabelWeight),
                    )
                    StatusModeSummaryValue(
                        facet = facet,
                        modifier = Modifier.weight(StatusModeSummaryValueWeight),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusModeSummaryLabel(
    facet: StatusModeSummaryFacet,
    modifier: Modifier = Modifier,
) {
    Text(
        text = facet.label,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
        modifier = modifier,
    )
}

@Composable
private fun StatusModeSummaryValue(
    facet: StatusModeSummaryFacet,
    modifier: Modifier = Modifier,
) {
    Text(
        text = facet.value,
        style = RipDpiThemeTokens.type.monoSmall,
        color = RipDpiThemeTokens.colors.foreground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// Labels are short ("Strategy", "DNS"); give the mono value column the extra
// width so long values (e.g. "Encrypted DNS · AdGuard DNS (DoH)") fit on fewer
// lines instead of wrapping under a half-empty label column.
private const val StatusModeSummaryLabelWeight = 0.25f
private const val StatusModeSummaryValueWeight = 0.75f
private const val StatusModeStackedSummaryFontScale = 1.3f
private val StatusModeSideBySideActionsMinWidth = 320.dp

@Composable
private fun StatusModeCardActions(
    uiState: StatusModeCardUiState,
    primaryActionLabel: String,
    configureLabel: String,
    primaryEnabled: Boolean,
    onPrimaryAction: (() -> Unit)?,
    onConfigure: () -> Unit,
    primaryActionVariant: RipDpiButtonVariant,
    configureActionVariant: RipDpiButtonVariant,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackActions =
            onPrimaryAction == null ||
                maxWidth < StatusModeSideBySideActionsMinWidth * LocalDensity.current.fontScale
        val primaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            if (onPrimaryAction != null) {
                RipDpiButton(
                    wrapLabel = true,
                    text = primaryActionLabel,
                    onClick = onPrimaryAction,
                    modifier = buttonModifier.ripDpiTestTag(RipDpiTestTags.statusModePrimaryAction(uiState.mode.name)),
                    variant = primaryActionVariant,
                    enabled = primaryEnabled,
                    loading = uiState.isLoading && uiState.mode != StatusMode.Diagnostic,
                )
            }
        }
        val configureButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            RipDpiButton(
                wrapLabel = true,
                text = configureLabel,
                onClick = onConfigure,
                modifier = buttonModifier.ripDpiTestTag(RipDpiTestTags.statusModeConfigureAction(uiState.mode.name)),
                variant = configureActionVariant,
            )
        }
        if (stackActions) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                primaryButton(Modifier.fillMaxWidth())
                configureButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
            ) {
                primaryButton(Modifier.weight(1f))
                configureButton(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun statusModeStatusLabel(uiState: StatusModeCardUiState): String =
    stringResource(
        when {
            uiState.isLoading -> R.string.status_mode_card_status_busy
            uiState.isActive -> R.string.status_mode_card_status_active
            else -> R.string.status_mode_card_status_inactive
        },
    )

private fun statusModeStatusTone(uiState: StatusModeCardUiState): StatusIndicatorTone =
    when {
        uiState.isLoading -> StatusIndicatorTone.Warning
        uiState.isActive -> StatusIndicatorTone.Active
        else -> StatusIndicatorTone.Idle
    }

@Preview(showBackground = true)
@Composable
private fun StatusModeCardLocalActivePreview() {
    StatusModeCardPreviewContent(card = previewCard(StatusMode.LocalDpiBypass, active = true))
}

@Preview(showBackground = true)
@Composable
private fun StatusModeCardLocalInactivePreview() {
    StatusModeCardPreviewContent(card = previewCard(StatusMode.LocalDpiBypass))
}

@Preview(showBackground = true)
@Composable
private fun StatusModeCardVpnActivePreview() {
    StatusModeCardPreviewContent(card = previewCard(StatusMode.RemoteVpn, active = true))
}

@Preview(showBackground = true)
@Composable
private fun StatusModeCardVpnInactivePreview() {
    StatusModeCardPreviewContent(card = previewCard(StatusMode.RemoteVpn))
}

@Preview(showBackground = true)
@Composable
private fun StatusModeCardDiagnosticActivePreview() {
    StatusModeCardPreviewContent(card = previewCard(StatusMode.Diagnostic, active = true))
}

@Preview(showBackground = true)
@Composable
private fun StatusModeCardDiagnosticInactivePreview() {
    StatusModeCardPreviewContent(card = previewCard(StatusMode.Diagnostic))
}

@Composable
private fun StatusModeCardPreviewContent(card: StatusModeCardUiState) {
    RipDpiComponentPreview {
        StatusModeCard(
            uiState = card,
            onPrimaryAction = if (card.mode == StatusMode.Diagnostic) ({}) else null,
            onConfigure = {},
            onCardClick = {},
        )
    }
}

private fun previewCard(
    mode: StatusMode,
    active: Boolean = false,
): StatusModeCardUiState =
    StatusModeCardUiState(
        mode = mode,
        title =
            when (mode) {
                StatusMode.LocalDpiBypass -> "Local bypass"
                StatusMode.RemoteVpn -> "VPN"
                StatusMode.Diagnostic -> "Diagnostic Scan"
            },
        primaryLabel =
            when (mode) {
                StatusMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                StatusMode.RemoteVpn -> "relay.example"
                StatusMode.Diagnostic -> "No analysis yet"
            },
        summaryFacets =
            if (mode == StatusMode.LocalDpiBypass) {
                persistentListOf(
                    StatusModeSummaryFacet(label = "Strategy", value = "tcp: split(host+1)"),
                    StatusModeSummaryFacet(label = "DNS", value = "Encrypted DNS · AdGuard DNS (DoH)"),
                )
            } else {
                persistentListOf()
            },
        secondaryLabel = if (active) "Connected 00:18:42" else null,
        statusLine = if (active) "Connected 00:18:42" else "Inactive",
        primaryActionLabel =
            when {
                mode == StatusMode.Diagnostic -> "Run Scan"
                active -> "Disable"
                else -> "Enable"
            },
        configureLabel = "Configure",
        primaryActionEnabled = true,
        isActive = active,
    )
