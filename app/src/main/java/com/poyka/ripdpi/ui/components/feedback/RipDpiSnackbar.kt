package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiSnackbarTone {
    Default,
    Warning,
    Error,
    Info,
    Restricted,
}

@Composable
fun RipDpiSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val palette = ripDpiSnackbarPalette(tone)
    val icon = defaultSnackbarIcon(tone)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.container,
        contentColor = palette.content,
        border = palette.border?.let { androidx.compose.foundation.BorderStroke(RipDpiStroke.Thin, it) },
        shadowElevation = palette.shadowElevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = palette.icon,
                    modifier = Modifier.size(RipDpiIconSizes.Default),
                )
            }

            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = type.body,
                color = palette.content,
            )

            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel.uppercase(),
                    style = type.smallLabel,
                    color = palette.action,
                    modifier = Modifier.clickable(onClick = onAction),
                )
            }
        }
    }
}

@Composable
fun RipDpiSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val layout = RipDpiThemeTokens.layout

    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                RipDpiSnackbar(
                    message = data.visuals.message,
                    actionLabel = data.visuals.actionLabel,
                    onAction = if (data.visuals.actionLabel != null) data::performAction else null,
                    tone = data.visuals.ripDpiToneOrDefault(),
                    modifier = Modifier.widthIn(max = layout.safeContentWidth),
                )
            }
        },
    )
}

suspend fun SnackbarHostState.showRipDpiSnackbar(
    message: String,
    tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = false,
): SnackbarResult = showSnackbar(
    RipDpiSnackbarVisuals(
        message = message,
        tone = tone,
        actionLabel = actionLabel,
        duration = duration,
        withDismissAction = withDismissAction,
    ),
)

@Immutable
internal data class RipDpiSnackbarVisuals(
    override val message: String,
    val tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

internal fun SnackbarVisuals.ripDpiToneOrDefault(): RipDpiSnackbarTone =
    (this as? RipDpiSnackbarVisuals)?.tone ?: RipDpiSnackbarTone.Default

@Immutable
private data class RipDpiSnackbarPalette(
    val container: Color,
    val content: Color,
    val icon: Color,
    val action: Color,
    val border: Color? = null,
    val shadowElevation: Dp = 16.dp,
)

@Composable
private fun ripDpiSnackbarPalette(tone: RipDpiSnackbarTone): RipDpiSnackbarPalette {
    val colors = RipDpiThemeTokens.colors

    return when (tone) {
        RipDpiSnackbarTone.Default -> RipDpiSnackbarPalette(
            container = MaterialTheme.colorScheme.inverseSurface,
            content = MaterialTheme.colorScheme.inverseOnSurface,
            icon = MaterialTheme.colorScheme.inverseOnSurface,
            action = MaterialTheme.colorScheme.inverseOnSurface,
        )
        RipDpiSnackbarTone.Warning -> RipDpiSnackbarPalette(
            container = colors.warning,
            content = colors.warningForeground,
            icon = colors.warningForeground,
            action = colors.warningForeground,
        )
        RipDpiSnackbarTone.Error -> RipDpiSnackbarPalette(
            container = colors.destructive,
            content = colors.destructiveForeground,
            icon = colors.destructiveForeground,
            action = colors.destructiveForeground,
        )
        RipDpiSnackbarTone.Info -> RipDpiSnackbarPalette(
            container = colors.info,
            content = colors.infoForeground,
            icon = colors.infoForeground,
            action = colors.infoForeground,
        )
        RipDpiSnackbarTone.Restricted -> RipDpiSnackbarPalette(
            container = colors.restricted,
            content = colors.restrictedForeground,
            icon = colors.restrictedForeground,
            action = colors.restrictedForeground,
        )
    }
}

private fun defaultSnackbarIcon(tone: RipDpiSnackbarTone) = when (tone) {
    RipDpiSnackbarTone.Default -> null
    RipDpiSnackbarTone.Warning -> RipDpiIcons.Warning
    RipDpiSnackbarTone.Error -> RipDpiIcons.Error
    RipDpiSnackbarTone.Info -> RipDpiIcons.Info
    RipDpiSnackbarTone.Restricted -> RipDpiIcons.Lock
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSnackbarPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiSnackbar(message = "Logs exported successfully")
            RipDpiSnackbar(
                message = "Double-check the current values before saving.",
                actionLabel = "Open",
                onAction = {},
                tone = RipDpiSnackbarTone.Warning,
            )
            RipDpiSnackbar(
                message = "The service stopped before the tunnel could be established.",
                tone = RipDpiSnackbarTone.Error,
            )
            RipDpiSnackbar(
                message = "VPN permission still needs to be granted.",
                tone = RipDpiSnackbarTone.Info,
            )
            RipDpiSnackbar(
                message = "This option only applies when command-line mode is enabled.",
                tone = RipDpiSnackbarTone.Restricted,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSnackbarDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiSnackbar(message = "Logs exported successfully")
            RipDpiSnackbar(
                message = "Double-check the current values before saving.",
                actionLabel = "Open",
                onAction = {},
                tone = RipDpiSnackbarTone.Warning,
            )
            RipDpiSnackbar(
                message = "The service stopped before the tunnel could be established.",
                tone = RipDpiSnackbarTone.Error,
            )
            RipDpiSnackbar(
                message = "VPN permission still needs to be granted.",
                tone = RipDpiSnackbarTone.Info,
            )
            RipDpiSnackbar(
                message = "This option only applies when command-line mode is enabled.",
                tone = RipDpiSnackbarTone.Restricted,
            )
        }
    }
}
