package com.poyka.ripdpi.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
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
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun HomeModeCard(
    uiState: HomeModeCardUiState,
    onPrimaryAction: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    onCardClick: (() -> Unit)? = null,
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
                uiState.isActive -> RipDpiCardVariant.Tonal
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
        HomeModeCardBody(uiState = uiState, statusLabel = statusLabel)
    }
}

@Composable
private fun HomeModeCardHeader(
    uiState: HomeModeCardUiState,
    statusLabel: String,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = uiState.title,
            style = type.bodyEmphasisBold,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusIndicator(
            label = statusLabel,
            tone = homeModeStatusTone(uiState),
        )
    }
}

@Composable
private fun HomeModeCardBody(
    uiState: HomeModeCardUiState,
    statusLabel: String,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Text(
        text = uiState.statusLine.ifBlank { statusLabel },
        style = type.secondaryBody,
        color = colors.mutedForeground,
    )

    if (uiState.primaryLabel.isNotBlank()) {
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

@Composable
private fun HomeModeCardActions(
    uiState: HomeModeCardUiState,
    primaryActionLabel: String,
    configureLabel: String,
    primaryEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    onConfigure: () -> Unit,
) {
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
            variant = RipDpiButtonVariant.Outline,
        )
    }
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

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeModeCardLocalActivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.LocalDpiBypass, active = true))
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeModeCardLocalInactivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.LocalDpiBypass))
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeModeCardVpnActivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.RemoteVpn, active = true))
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeModeCardVpnInactivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.RemoteVpn))
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun HomeModeCardDiagnosticActivePreview() {
    HomeModeCardPreviewContent(card = previewCard(HomeMode.Diagnostic, active = true))
}

@Suppress("UnusedPrivateMember")
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
                HomeMode.LocalDpiBypass -> "Local DPI Bypass"
                HomeMode.RemoteVpn -> "VPN with Remote Server"
                HomeMode.Diagnostic -> "Diagnostic Scan"
            },
        primaryLabel =
            when (mode) {
                HomeMode.LocalDpiBypass -> "tlsrec_split_host - AdGuard DoH"
                HomeMode.RemoteVpn -> "relay.example"
                HomeMode.Diagnostic -> "No analysis yet"
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
