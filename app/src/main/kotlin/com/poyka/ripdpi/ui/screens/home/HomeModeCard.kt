package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.HomeModeSummaryFacet
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Composable
fun HomeModeCard(
    uiState: HomeModeCardUiState,
    onPrimaryAction: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    onCardClick: (() -> Unit)? = null,
    onDisabledHintClick: (() -> Unit)? = null,
    primaryActionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    configureActionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Outline,
) {
    val primaryEnabled = uiState.primaryActionEnabled && !uiState.isLoading
    val statusLabel = homeModeStatusLabel(uiState)
    val primaryActionLabel = uiState.primaryActionLabel.ifBlank { defaultPrimaryActionLabel(uiState) }
    val configureLabel = uiState.configureLabel.ifBlank { stringResource(R.string.home_mode_card_configure) }

    RipDpiCard(
        modifier =
            modifier.ripDpiTestTag(
                RipDpiTestTags.homeModeCard(uiState.mode.name),
            ),
        variant =
            when {
                uiState.isLoading -> RipDpiCardVariant.Elevated

                // Active card uses the Selected surface (accent fill + bold
                // foreground border + elevation) so it is unmistakably the live
                // card, rather than receding behind the flat Outlined cards.
                uiState.isActive -> RipDpiCardVariant.Selected

                else -> RipDpiCardVariant.Outlined
            },
    ) {
        HomeModeCardContent(
            uiState = uiState,
            statusLabel = statusLabel,
            onCardClick = onCardClick,
        )
        HomeModeCardActions(
            uiState = uiState,
            primaryActionLabel = primaryActionLabel,
            configureLabel = configureLabel,
            primaryEnabled = primaryEnabled,
            onPrimaryAction = onPrimaryAction,
            onConfigure = onConfigure,
            onDisabledHintClick = onDisabledHintClick,
            primaryActionVariant = primaryActionVariant,
            configureActionVariant = configureActionVariant,
        )
    }
}

@Composable
private fun HomeModeCardContent(
    uiState: HomeModeCardUiState,
    statusLabel: String,
    onCardClick: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.homeModeCardBody(uiState.mode.name))
                .then(
                    if (onCardClick != null) {
                        Modifier.ripDpiClickable(role = Role.Button, onClick = onCardClick)
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        HomeModeCardHeader(uiState = uiState, statusLabel = statusLabel)
        HomeModeCardBody(uiState = uiState)
    }
}

@Composable
private fun HomeModeCardHeader(
    uiState: HomeModeCardUiState,
    statusLabel: String,
) {
    val stackHeader = LocalDensity.current.fontScale >= HomeModeStackedSummaryFontScale
    if (stackHeader) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            HomeModeCardTitle(title = uiState.title)
            HomeModeCardStatus(uiState = uiState, statusLabel = statusLabel)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeModeCardTitle(title = uiState.title, modifier = Modifier.weight(1f))
            HomeModeCardStatus(uiState = uiState, statusLabel = statusLabel)
        }
    }
}

@Composable
private fun HomeModeCardTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = RipDpiThemeTokens.type.bodyEmphasisBold,
        color = RipDpiThemeTokens.colors.foreground,
        modifier = modifier,
    )
}

@Composable
private fun HomeModeCardStatus(
    uiState: HomeModeCardUiState,
    statusLabel: String,
) {
    StatusIndicator(
        label = statusLabel,
        tone = homeModeStatusTone(uiState),
    )
}

@Composable
private fun HomeModeCardBody(uiState: HomeModeCardUiState) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    // Falling back to the header badge's own label spent a whole line repeating
    // a word the user had just read one line above, and pushed the facets that
    // actually differ per mode further down. With nothing distinct to add, add
    // nothing.
    if (uiState.statusLine.isNotBlank()) {
        Text(
            text = uiState.statusLine,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
    }

    if (uiState.summaryFacets.isNotEmpty()) {
        HomeModeSummaryStrip(facets = uiState.summaryFacets)
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
 * [HomeModeSummaryFacet]) instead of a single dense line. The fixed-weight label/value columns
 * keep labels aligned across rows; the panel's subtle fill + outline read it as a discrete element.
 */
@Composable
private fun HomeModeSummaryStrip(facets: ImmutableList<HomeModeSummaryFacet>) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.md
    val stackFacets = LocalDensity.current.fontScale >= HomeModeStackedSummaryFontScale
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.muted, shape)
                .border(RipDpiStroke.Thin, colors.outline, shape)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        facets.forEach { facet ->
            if (stackFacets) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    HomeModeSummaryLabel(facet = facet)
                    HomeModeSummaryValue(facet = facet)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeModeSummaryLabel(
                        facet = facet,
                        modifier = Modifier.weight(HomeModeSummaryLabelWeight),
                    )
                    HomeModeSummaryValue(
                        facet = facet,
                        modifier = Modifier.weight(HomeModeSummaryValueWeight),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeModeSummaryLabel(
    facet: HomeModeSummaryFacet,
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
private fun HomeModeSummaryValue(
    facet: HomeModeSummaryFacet,
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
private const val HomeModeSummaryLabelWeight = 0.25f
private const val HomeModeSummaryValueWeight = 0.75f
private const val HomeModeStackedSummaryFontScale = 1.3f
private val HomeModeSideBySideActionsMinWidth = 320.dp

@Composable
private fun HomeModeCardActions(
    uiState: HomeModeCardUiState,
    primaryActionLabel: String,
    configureLabel: String,
    primaryEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    onConfigure: () -> Unit,
    onDisabledHintClick: (() -> Unit)?,
    primaryActionVariant: RipDpiButtonVariant,
    configureActionVariant: RipDpiButtonVariant,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackActions =
            maxWidth < HomeModeSideBySideActionsMinWidth * LocalDensity.current.fontScale
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
        ) {
            if (stackActions) {
                RipDpiButton(
                    text = primaryActionLabel,
                    onClick = onPrimaryAction,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.homeModePrimaryAction(uiState.mode.name)),
                    variant = primaryActionVariant,
                    enabled = primaryEnabled,
                    loading = uiState.isLoading,
                )
                RipDpiButton(
                    text = configureLabel,
                    onClick = onConfigure,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ripDpiTestTag(RipDpiTestTags.homeModeConfigureAction(uiState.mode.name)),
                    variant = configureActionVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
                ) {
                    RipDpiButton(
                        text = primaryActionLabel,
                        onClick = onPrimaryAction,
                        modifier =
                            Modifier
                                .weight(1f)
                                .ripDpiTestTag(RipDpiTestTags.homeModePrimaryAction(uiState.mode.name)),
                        variant = primaryActionVariant,
                        enabled = primaryEnabled,
                        loading = uiState.isLoading,
                    )
                    RipDpiButton(
                        text = configureLabel,
                        onClick = onConfigure,
                        modifier =
                            Modifier
                                .weight(1f)
                                .ripDpiTestTag(RipDpiTestTags.homeModeConfigureAction(uiState.mode.name)),
                        variant = configureActionVariant,
                    )
                }
            }
            HomeModeDisabledHint(
                hint = uiState.primaryActionDisabledHint.takeIf { !primaryEnabled },
                onClick = onDisabledHintClick ?: onConfigure,
            )
        }
    }
}

@Composable
private fun HomeModeDisabledHint(
    hint: String?,
    onClick: () -> Unit,
) {
    if (hint.isNullOrBlank()) return

    Text(
        text = hint,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
        modifier =
            Modifier
                .ripDpiTestTag(RipDpiTestTags.HomeModeDisabledHint)
                .ripDpiClickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun homeModeStatusLabel(uiState: HomeModeCardUiState): String =
    stringResource(
        when {
            uiState.isLoading -> R.string.home_mode_card_status_busy
            uiState.isActive -> R.string.home_mode_card_status_active
            else -> R.string.home_mode_card_status_inactive
        },
    )

@Composable
private fun defaultPrimaryActionLabel(uiState: HomeModeCardUiState): String =
    stringResource(
        when {
            uiState.mode == HomeMode.Diagnostic -> R.string.home_mode_card_run_scan
            uiState.isActive -> R.string.home_mode_card_disable
            else -> R.string.home_mode_card_enable
        },
    )

private fun homeModeStatusTone(uiState: HomeModeCardUiState): StatusIndicatorTone =
    when {
        uiState.isLoading -> StatusIndicatorTone.Warning
        uiState.isActive -> StatusIndicatorTone.Active
        else -> StatusIndicatorTone.Idle
    }

@Preview(showBackground = true)
@Composable
private fun HomeModeCardLocalActivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.LocalDpiBypass, active = true))
}

@Preview(showBackground = true)
@Composable
private fun HomeModeCardLocalInactivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.LocalDpiBypass))
}

@Preview(showBackground = true)
@Composable
private fun HomeModeCardVpnActivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.RemoteVpn, active = true))
}

@Preview(showBackground = true)
@Composable
private fun HomeModeCardVpnInactivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.RemoteVpn))
}

@Preview(showBackground = true)
@Composable
private fun HomeModeCardDiagnosticActivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.Diagnostic, active = true))
}

@Preview(showBackground = true)
@Composable
private fun HomeModeCardDiagnosticInactivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.Diagnostic))
}

@Composable
private fun HomeModeCardPreviewContent(card: HomeModeCardUiState) {
    RipDpiComponentPreview {
        HomeModeCard(
            uiState = card,
            onPrimaryAction = {},
            onConfigure = {},
            onCardClick = {},
        )
    }
}

private fun previewCard(
    mode: HomeMode,
    active: Boolean = false,
): HomeModeCardUiState =
    HomeModeCardUiState(
        mode = mode,
        title =
            when (mode) {
                HomeMode.LocalDpiBypass -> "Local bypass"
                HomeMode.RemoteVpn -> "VPN"
                HomeMode.Diagnostic -> "Diagnostic Scan"
            },
        primaryLabel =
            when (mode) {
                HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                HomeMode.RemoteVpn -> "relay.example"
                HomeMode.Diagnostic -> "No analysis yet"
            },
        summaryFacets =
            if (mode == HomeMode.LocalDpiBypass) {
                persistentListOf(
                    HomeModeSummaryFacet(label = "Strategy", value = "tcp: split(host+1)"),
                    HomeModeSummaryFacet(label = "DNS", value = "Encrypted DNS · AdGuard DNS (DoH)"),
                )
            } else {
                persistentListOf()
            },
        secondaryLabel = if (active) "Connected 00:18:42" else null,
        statusLine = if (active) "Connected 00:18:42" else "Inactive",
        primaryActionLabel =
            when {
                mode == HomeMode.Diagnostic -> "Run Scan"
                active -> "Disable"
                else -> "Enable"
            },
        configureLabel = "Configure",
        primaryActionEnabled = true,
        isActive = active,
    )
