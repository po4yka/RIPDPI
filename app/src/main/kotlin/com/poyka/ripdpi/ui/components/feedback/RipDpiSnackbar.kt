package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
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
    testTag: String? = null,
    actionTestTag: String? = null,
    tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
) {
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val palette = ripDpiSnackbarPalette(tone)
    val icon = defaultSnackbarIcon(tone)

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .ripDpiTestTag(testTag)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RipDpiThemeTokens.shapes.xl,
        color = palette.container,
        contentColor = palette.content,
        border = palette.border?.let { BorderStroke(RipDpiStroke.Thin, it) },
        shadowElevation = palette.shadowElevation,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = components.inputs.controlHeight)
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = tone.name,
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
                    text = actionLabel,
                    style = type.smallLabel,
                    color = palette.action,
                    modifier =
                        Modifier
                            .ripDpiClickable(
                                role = Role.Button,
                                hapticFeedback = snackbarActionHapticFeedback(tone),
                                onClick = onAction,
                            ).ripDpiTestTag(actionTestTag),
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
    val spacing = RipDpiThemeTokens.spacing

    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = layout.horizontalPadding, vertical = spacing.sm),
                contentAlignment = Alignment.BottomCenter,
            ) {
                RipDpiSnackbar(
                    message = data.visuals.message,
                    actionLabel = data.visuals.actionLabel,
                    onAction = if (data.visuals.actionLabel != null) data::performAction else null,
                    testTag = data.visuals.ripDpiTestTagOrNull(),
                    actionTestTag = data.visuals.ripDpiActionTestTagOrNull(),
                    tone = data.visuals.ripDpiToneOrDefault(),
                    modifier = Modifier.widthIn(max = layout.snackbarMaxWidth),
                )
            }
        },
    )
}

suspend fun SnackbarHostState.showRipDpiSnackbar(
    message: String,
    tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
    actionLabel: String? = null,
    testTag: String? = null,
    actionTestTag: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = false,
): SnackbarResult =
    showSnackbar(
        RipDpiSnackbarVisuals(
            message = message,
            tone = tone,
            actionLabel = actionLabel,
            testTag = testTag,
            actionTestTag = actionTestTag,
            duration = duration,
            withDismissAction = withDismissAction,
        ),
    )

@Immutable
internal data class RipDpiSnackbarVisuals(
    override val message: String,
    val tone: RipDpiSnackbarTone = RipDpiSnackbarTone.Default,
    override val actionLabel: String? = null,
    val testTag: String? = null,
    val actionTestTag: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

internal fun SnackbarVisuals.ripDpiToneOrDefault(): RipDpiSnackbarTone =
    (this as? RipDpiSnackbarVisuals)?.tone ?: RipDpiSnackbarTone.Default

internal fun SnackbarVisuals.ripDpiTestTagOrNull(): String? = (this as? RipDpiSnackbarVisuals)?.testTag

internal fun SnackbarVisuals.ripDpiActionTestTagOrNull(): String? = (this as? RipDpiSnackbarVisuals)?.actionTestTag

private fun snackbarActionHapticFeedback(tone: RipDpiSnackbarTone): RipDpiHapticFeedback =
    when (tone) {
        RipDpiSnackbarTone.Error,
        RipDpiSnackbarTone.Warning,
        RipDpiSnackbarTone.Restricted,
        -> RipDpiHapticFeedback.Acknowledge

        RipDpiSnackbarTone.Default,
        RipDpiSnackbarTone.Info,
        -> RipDpiHapticFeedback.Action
    }

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
    val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiSurfaceRole.Snackbar)

    return when (tone) {
        RipDpiSnackbarTone.Default -> {
            RipDpiSnackbarPalette(
                container = surfaceStyle.container,
                content = surfaceStyle.content,
                icon = surfaceStyle.content,
                action = surfaceStyle.content,
                border = if (surfaceStyle.border == Color.Transparent) null else surfaceStyle.border,
                shadowElevation = surfaceStyle.shadowElevation,
            )
        }

        RipDpiSnackbarTone.Warning -> {
            RipDpiSnackbarPalette(
                container = colors.warningContainer,
                content = colors.warningContainerForeground,
                icon = colors.warning,
                action = colors.warningContainerForeground,
                border = colors.warning.copy(alpha = 0.52f),
                shadowElevation = surfaceStyle.shadowElevation,
            )
        }

        RipDpiSnackbarTone.Error -> {
            RipDpiSnackbarPalette(
                container = colors.destructiveContainer,
                content = colors.destructiveContainerForeground,
                icon = colors.destructive,
                action = colors.destructiveContainerForeground,
                border = colors.destructive.copy(alpha = 0.52f),
                shadowElevation = surfaceStyle.shadowElevation,
            )
        }

        RipDpiSnackbarTone.Info -> {
            RipDpiSnackbarPalette(
                container = colors.infoContainer,
                content = colors.infoContainerForeground,
                icon = colors.info,
                action = colors.infoContainerForeground,
                border = colors.info.copy(alpha = 0.48f),
                shadowElevation = surfaceStyle.shadowElevation,
            )
        }

        RipDpiSnackbarTone.Restricted -> {
            RipDpiSnackbarPalette(
                container = colors.restrictedContainer,
                content = colors.restrictedContainerForeground,
                icon = colors.restricted,
                action = colors.restrictedContainerForeground,
                border = colors.restricted.copy(alpha = 0.52f),
                shadowElevation = surfaceStyle.shadowElevation,
            )
        }
    }
}

private fun defaultSnackbarIcon(tone: RipDpiSnackbarTone) =
    when (tone) {
        RipDpiSnackbarTone.Default -> null
        RipDpiSnackbarTone.Warning -> RipDpiIcons.Warning
        RipDpiSnackbarTone.Error -> RipDpiIcons.Error
        RipDpiSnackbarTone.Info -> RipDpiIcons.Info
        RipDpiSnackbarTone.Restricted -> RipDpiIcons.Lock
    }

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun RipDpiSnackbarPreview() {
    RipDpiComponentPreview {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun RipDpiSnackbarDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
