package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiSurfaceStyle

enum class RipDpiDialogTone {
    Default,
    Destructive,
    Info,
}

@Composable
fun RipDpiDialog(
    onDismissRequest: () -> Unit,
    title: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dialogTestTag: String? = null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
    message: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    tone: RipDpiDialogTone = RipDpiDialogTone.Default,
    actionLayout: RipDpiActionLayout = RipDpiActionLayout.Adaptive,
    icon: ImageVector? = defaultDialogIcon(tone),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val layout = RipDpiThemeTokens.layout

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiAutomationTreeRoot()
                    .padding(horizontal = layout.horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            RipDpiDialogCard(
                title = title,
                dismissLabel = dismissLabel,
                onDismiss = onDismiss,
                modifier =
                    modifier
                        .ripDpiTestTag(dialogTestTag)
                        .fillMaxWidth()
                        .widthIn(max = layout.dialogMaxWidth),
                confirmTestTag = confirmTestTag,
                dismissTestTag = dismissTestTag,
                message = message,
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                tone = tone,
                actionLayout = actionLayout,
                icon = icon,
                content = content,
            )
        }
    }
}

@Composable
fun RipDpiDialogCard(
    title: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
    message: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    tone: RipDpiDialogTone = RipDpiDialogTone.Default,
    actionLayout: RipDpiActionLayout = RipDpiActionLayout.Adaptive,
    icon: ImageVector? = defaultDialogIcon(tone),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val hasConfirmAction = confirmLabel != null && onConfirm != null
    val surfaceStyle = ripDpiSurfaceStyle(RipDpiSurfaceRole.Sheet)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = surfaceStyle.container,
        contentColor = surfaceStyle.content,
        shadowElevation = surfaceStyle.shadowElevation,
        border = BorderStroke(RipDpiStroke.Thin, surfaceStyle.border),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xxl, vertical = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            icon?.let {
                RipDpiModalIconBadge(
                    icon = it,
                    tone = tone,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = title,
                    style = type.sheetTitle,
                    color = colors.foreground,
                )
                message?.let {
                    Text(
                        text = it,
                        style = type.body,
                        color = colors.mutedForeground,
                    )
                }
            }

            content()

            DialogActionRow(
                dismissLabel = dismissLabel,
                onDismiss = onDismiss,
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                confirmTestTag = confirmTestTag,
                dismissTestTag = dismissTestTag,
                tone = tone,
                actionLayout = actionLayout,
                hasConfirmAction = hasConfirmAction,
            )
        }
    }
}

@Composable
private fun RipDpiModalIconBadge(
    icon: ImageVector,
    tone: RipDpiDialogTone,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val containerColor =
        when (tone) {
            RipDpiDialogTone.Destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)

            RipDpiDialogTone.Info,
            RipDpiDialogTone.Default,
            -> colors.inputBackground
        }
    val iconTint =
        when (tone) {
            RipDpiDialogTone.Destructive -> MaterialTheme.colorScheme.error

            RipDpiDialogTone.Info,
            RipDpiDialogTone.Default,
            -> colors.foreground
        }

    Box(
        modifier =
            modifier
                .size(components.dialogIconSize)
                .background(color = containerColor, shape = RipDpiThemeTokens.shapes.full),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
    }
}

@Composable
private fun DialogActionRow(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: (() -> Unit)?,
    confirmTestTag: String?,
    dismissTestTag: String?,
    tone: RipDpiDialogTone,
    actionLayout: RipDpiActionLayout,
    hasConfirmAction: Boolean,
) {
    val spacing = RipDpiThemeTokens.spacing
    val resolvedActionLayout = actionLayout.resolvedActionLayout()
    val primaryVariant =
        when (tone) {
            RipDpiDialogTone.Destructive -> RipDpiButtonVariant.Destructive

            RipDpiDialogTone.Info,
            RipDpiDialogTone.Default,
            -> RipDpiButtonVariant.Primary
        }

    if (resolvedActionLayout == RipDpiActionLayout.Stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (hasConfirmAction) {
                RipDpiButton(
                    text = confirmLabel.orEmpty(),
                    onClick = { onConfirm?.invoke() },
                    modifier = Modifier.fillMaxWidth().ripDpiTestTag(confirmTestTag),
                    variant = primaryVariant,
                    hapticFeedback = confirmActionHapticFeedback(tone),
                )
                RipDpiButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().ripDpiTestTag(dismissTestTag),
                    variant = RipDpiButtonVariant.Outline,
                    hapticFeedback = dismissActionHapticFeedback(tone, hasConfirmAction),
                )
            } else {
                RipDpiButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().ripDpiTestTag(dismissTestTag),
                    variant = primaryVariant,
                    hapticFeedback = dismissActionHapticFeedback(tone, hasConfirmAction),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasConfirmAction) {
                RipDpiButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    modifier = Modifier.ripDpiTestTag(dismissTestTag),
                    variant = RipDpiButtonVariant.Outline,
                    density = com.poyka.ripdpi.ui.components.RipDpiControlDensity.Compact,
                    hapticFeedback = dismissActionHapticFeedback(tone, hasConfirmAction),
                )
                RipDpiButton(
                    text = confirmLabel.orEmpty(),
                    onClick = { onConfirm?.invoke() },
                    modifier = Modifier.ripDpiTestTag(confirmTestTag),
                    variant = primaryVariant,
                    density = com.poyka.ripdpi.ui.components.RipDpiControlDensity.Compact,
                    hapticFeedback = confirmActionHapticFeedback(tone),
                )
            } else {
                RipDpiButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    modifier = Modifier.ripDpiTestTag(dismissTestTag),
                    variant = primaryVariant,
                    density = com.poyka.ripdpi.ui.components.RipDpiControlDensity.Compact,
                    hapticFeedback = dismissActionHapticFeedback(tone, hasConfirmAction),
                )
            }
        }
    }
}

private fun dismissActionHapticFeedback(
    tone: RipDpiDialogTone,
    hasConfirmAction: Boolean,
): RipDpiHapticFeedback =
    when {
        tone == RipDpiDialogTone.Info -> RipDpiHapticFeedback.Acknowledge
        tone == RipDpiDialogTone.Destructive && !hasConfirmAction -> RipDpiHapticFeedback.Acknowledge
        else -> RipDpiHapticFeedback.Action
    }

private fun confirmActionHapticFeedback(tone: RipDpiDialogTone): RipDpiHapticFeedback =
    when (tone) {
        RipDpiDialogTone.Destructive -> RipDpiHapticFeedback.Confirm

        RipDpiDialogTone.Info,
        RipDpiDialogTone.Default,
        -> RipDpiHapticFeedback.Action
    }

private fun defaultDialogIcon(tone: RipDpiDialogTone): ImageVector? =
    when (tone) {
        RipDpiDialogTone.Default -> null
        RipDpiDialogTone.Destructive -> RipDpiIcons.Warning
        RipDpiDialogTone.Info -> RipDpiIcons.Info
    }

@Preview(showBackground = true)
@Composable
private fun RipDpiConfirmationDialogPreview() {
    RipDpiComponentPreview {
        RipDpiDialogCard(
            title = "Stop connection?",
            message = "Active traffic may be interrupted until you reconnect the service.",
            confirmLabel = "Stop",
            dismissLabel = "Cancel",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDestructiveDialogPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiDialogCard(
            title = "Reset advanced flags?",
            message = "Custom command line arguments and bypass rules will be removed.",
            confirmLabel = "Reset",
            dismissLabel = "Keep",
            onConfirm = {},
            onDismiss = {},
            tone = RipDpiDialogTone.Destructive,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiInfoDialogPreview() {
    RipDpiComponentPreview {
        RipDpiDialogCard(
            title = "Adaptive icons depend on the launcher",
            message = "Android launchers control the mask shape, so the app can only preview the current icon family.",
            dismissLabel = "Understood",
            onDismiss = {},
            tone = RipDpiDialogTone.Info,
        )
    }
}
