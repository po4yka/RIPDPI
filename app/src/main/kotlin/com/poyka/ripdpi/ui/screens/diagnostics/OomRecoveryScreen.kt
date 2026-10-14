package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * UI state for the OOM-recovery banner surface.
 *
 * @param killTimeLabel   Human-readable UTC kill time, e.g. "12:42 UTC".
 * @param downtimeLabel   Duration the tunnel was down, e.g. "4 m 18 s".
 * @param isDismissed     Whether the banner has been dismissed by the user.
 */
data class OomRecoveryState(
    val killTimeLabel: String,
    val downtimeLabel: String,
    val isDismissed: Boolean = false,
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Presentation-only OOM-recovery screen matching
 * `docs/design/rds/preview/vpn-oom-recovery.html`.
 *
 * Shows a warning-tone banner with the kill time, downtime duration,
 * and two recovery actions (Reconnect / View incident). The banner is
 * dismissible via the close icon. All tokens come from [RipDpiThemeTokens];
 * no literal colours, dp values outside `ui/theme/`, or animation-spec
 * constants appear in this file.
 */
@Composable
fun OomRecoveryScreen(
    state: OomRecoveryState,
    onReconnect: () -> Unit,
    onViewIncident: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.oom_recovery_title),
        modifier = modifier,
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        if (!state.isDismissed) {
            OomRecoveryBanner(
                state = state,
                onReconnect = onReconnect,
                onViewIncident = onViewIncident,
                onDismiss = onDismiss,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Banner
// ---------------------------------------------------------------------------

@Composable
private fun OomRecoveryBanner(
    state: OomRecoveryState,
    onReconnect: () -> Unit,
    onViewIncident: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.lg,
        color = colors.warningContainer,
        contentColor = colors.warningContainerForeground,
        border = BorderStroke(RipDpiStroke.Thin, colors.warning),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            OomRecoveryHeader(onDismiss)
            Text(
                text = stringResource(R.string.oom_recovery_banner_body, state.killTimeLabel, state.downtimeLabel),
                style = type.body,
                color = colors.warningContainerForeground,
            )
            RipDpiButton(
                text = stringResource(R.string.oom_recovery_action_reconnect),
                onClick = onReconnect,
                variant = RipDpiButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
                wrapLabel = true,
            )
            RipDpiButton(
                text = stringResource(R.string.oom_recovery_action_view_incident),
                onClick = onViewIncident,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
                wrapLabel = true,
            )
        }
    }
}

@Composable
private fun OomRecoveryHeader(onDismiss: () -> Unit) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val title = stringResource(R.string.oom_recovery_banner_title)
    val titleWidth = minimumUnbrokenTextWidth(title, type.bodyEmphasis)
    val components = RipDpiThemeTokens.components
    val dismiss: @Composable () -> Unit = {
        RipDpiIconButton(
            icon = RipDpiIcons.Close,
            contentDescription = stringResource(R.string.oom_recovery_action_dismiss),
            onClick = onDismiss,
            style = RipDpiIconButtonStyle.Warning,
            density = RipDpiControlDensity.Compact,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val titleBesideControls =
            maxWidth - components.feedback.decorativeBadgeSize -
                components.buttons.iconButtonSize - spacing.md * 2
        if (titleWidth > titleBesideControls) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OomRecoveryWarningBadge()
                    dismiss()
                }
                Text(title, Modifier.fillMaxWidth(), colors.warningContainerForeground, style = type.bodyEmphasis)
            }
        } else {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing.md), Alignment.Top) {
                OomRecoveryWarningBadge()
                Text(
                    title,
                    Modifier.weight(1f).fillMaxWidth(),
                    colors.warningContainerForeground,
                    style = type.bodyEmphasis,
                )
                dismiss()
            }
        }
    }
}

@Composable
private fun OomRecoveryWarningBadge() {
    val colors = RipDpiThemeTokens.colors
    Surface(
        modifier = Modifier.size(RipDpiThemeTokens.components.feedback.decorativeBadgeSize),
        shape = CircleShape,
        color = colors.warning,
        contentColor = colors.warningForeground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(RipDpiIcons.Warning, null, Modifier.size(RipDpiIconSizes.Large))
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
private fun OomRecoveryScreenLightPreview() {
    RipDpiComponentPreview {
        OomRecoveryScreen(
            state =
                OomRecoveryState(
                    killTimeLabel = "12:42 UTC",
                    downtimeLabel = "4 m 18 s",
                ),
            onReconnect = {},
            onViewIncident = {},
            onDismiss = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OomRecoveryScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        OomRecoveryScreen(
            state =
                OomRecoveryState(
                    killTimeLabel = "12:42 UTC",
                    downtimeLabel = "4 m 18 s",
                ),
            onReconnect = {},
            onViewIncident = {},
            onDismiss = {},
            onBack = {},
        )
    }
}
