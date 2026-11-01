package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.distinctFrom
import com.poyka.ripdpi.ui.testing.ripDpiAutomationTreeRoot
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiDialogTone {
    Default,
    Destructive,
    Info,
}

data class RipDpiDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val testTag: String? = null,
    // Styles the button red. Set it on actions that delete, reset, or drop state.
    val destructive: Boolean = false,
)

data class RipDpiDialogVisuals(
    val message: String? = null,
    val tone: RipDpiDialogTone = RipDpiDialogTone.Default,
    val actionLayout: RipDpiActionLayout = RipDpiActionLayout.Adaptive,
    val icon: ImageVector? = defaultDialogIcon(tone),
)

@Composable
fun RipDpiDialog(
    onDismissRequest: () -> Unit,
    title: String,
    dismissAction: RipDpiDialogAction,
    modifier: Modifier = Modifier,
    dialogTestTag: String? = null,
    confirmAction: RipDpiDialogAction? = null,
    visuals: RipDpiDialogVisuals = RipDpiDialogVisuals(),
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
                dismissAction = dismissAction,
                modifier =
                    modifier
                        .ripDpiTestTag(dialogTestTag)
                        .widthIn(max = layout.dialogMaxWidth)
                        .fillMaxWidth(),
                confirmAction = confirmAction,
                visuals = visuals,
                content = content,
            )
        }
    }
}

@Composable
fun RipDpiDialogCard(
    title: String,
    dismissAction: RipDpiDialogAction,
    modifier: Modifier = Modifier,
    confirmAction: RipDpiDialogAction? = null,
    visuals: RipDpiDialogVisuals = RipDpiDialogVisuals(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val hasConfirmAction = confirmAction != null
    val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.feedback.dialog)

    Surface(
        modifier = modifier,
        shape = RipDpiThemeTokens.shapes.xxl,
        color = surfaceStyle.container,
        contentColor = surfaceStyle.content,
        shadowElevation = surfaceStyle.shadowElevation,
        border = BorderStroke(RipDpiStroke.Thin, surfaceStyle.border),
    ) {
        BoxWithConstraints {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (constraints.hasBoundedHeight) {
                                Modifier.verticalScroll(
                                    rememberScrollState(),
                                )
                            } else {
                                Modifier
                            },
                        ).padding(horizontal = spacing.xxl, vertical = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                visuals.icon?.let {
                    RipDpiModalIconBadge(
                        icon = it,
                        tone = visuals.tone,
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
                    visuals.message.distinctFrom(title)?.let {
                        Text(
                            text = it,
                            style = type.body,
                            color = colors.mutedForeground,
                        )
                    }
                }

                content()

                DialogActionRow(
                    dismissLabel = dismissAction.label,
                    onDismiss = dismissAction.onClick,
                    confirmLabel = confirmAction?.label,
                    onConfirm = confirmAction?.onClick,
                    confirmTestTag = confirmAction?.testTag,
                    dismissTestTag = dismissAction.testTag,
                    primaryDestructive = confirmAction?.destructive ?: dismissAction.destructive,
                    tone = visuals.tone,
                    actionLayout = visuals.actionLayout,
                    hasConfirmAction = hasConfirmAction,
                )
            }
        }
    }
}

@Composable
private fun RipDpiModalIconBadge(
    icon: ImageVector,
    tone: RipDpiDialogTone,
    modifier: Modifier = Modifier,
) {
    val components = RipDpiThemeTokens.components
    val surfaceStyle =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.feedback.dialogIconBadge(tone),
        )

    val badgeBorder =
        surfaceStyle.border.takeUnless { it == Color.Transparent }?.let { BorderStroke(RipDpiStroke.Thin, it) }
    Surface(
        modifier = modifier.size(components.feedback.dialogIconSize),
        shape = CircleShape,
        color = surfaceStyle.container,
        contentColor = surfaceStyle.content,
        border = badgeBorder,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = surfaceStyle.content,
                modifier = Modifier.size(RipDpiIconSizes.Default),
            )
        }
    }
}

/**
 * Whether the dialog actions share one row. Each button is its label plus padding on both edges, but never narrower
 * than the Material minimum button width.
 */
internal fun dialogActionsFitInRow(
    labelWidthsPx: List<Int>,
    buttonPaddingPx: Float,
    minButtonPx: Float,
    gapPx: Float,
    availablePx: Int,
): Boolean {
    val buttons = labelWidthsPx.sumOf { maxOf(it + 2 * buttonPaddingPx, minButtonPx).toDouble() }
    return buttons + gapPx * (labelWidthsPx.size - 1) <= availablePx
}

@Composable
private fun DialogActionRow(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: (() -> Unit)?,
    confirmTestTag: String?,
    dismissTestTag: String?,
    primaryDestructive: Boolean,
    tone: RipDpiDialogTone,
    actionLayout: RipDpiActionLayout,
    hasConfirmAction: Boolean,
) {
    val spacing = RipDpiThemeTokens.spacing
    val buttons = RipDpiThemeTokens.components.buttons
    val measurer = rememberTextMeasurer()
    // The button draws its label over the text style Material gives a text button, so measure the same merge.
    val labelStyle =
        MaterialTheme.typography.labelLarge
            .merge(
                RipDpiThemeTokens.type.button,
            ).copy(hyphens = Hyphens.None)
    val density = LocalDensity.current
    val resolved = actionLayout.resolvedDialogActionLayout()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // An adaptive dialog stacks only when both labels cannot share one row; an explicit layout is kept.
        val stacked =
            when {
                resolved == RipDpiActionLayout.Stacked -> {
                    true
                }

                actionLayout != RipDpiActionLayout.Adaptive || !hasConfirmAction -> {
                    false
                }

                else -> {
                    val labelWidths =
                        listOf(dismissLabel, confirmLabel.orEmpty()).map {
                            measurer.measure(it, labelStyle, maxLines = 1).size.width
                        }
                    !with(density) {
                        dialogActionsFitInRow(
                            labelWidthsPx = labelWidths,
                            buttonPaddingPx =
                                (buttons.horizontalPadding - buttons.focusedHorizontalPaddingOffset)
                                    .toPx(),
                            minButtonPx = ButtonDefaults.MinWidth.toPx(),
                            gapPx = spacing.sm.toPx(),
                            availablePx = constraints.maxWidth,
                        )
                    }
                }
            }
        if (stacked) {
            DialogStackedActions(
                dismissLabel = dismissLabel,
                onDismiss = onDismiss,
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                confirmTestTag = confirmTestTag,
                dismissTestTag = dismissTestTag,
                primaryDestructive = primaryDestructive,
                tone = tone,
                hasConfirmAction = hasConfirmAction,
            )
        } else {
            DialogInlineActions(
                dismissLabel = dismissLabel,
                onDismiss = onDismiss,
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                confirmTestTag = confirmTestTag,
                dismissTestTag = dismissTestTag,
                primaryDestructive = primaryDestructive,
                tone = tone,
                hasConfirmAction = hasConfirmAction,
            )
        }
    }
}

/** Fallback for labels that do not fit side by side: full-width buttons with the confirm action on top. */
@Composable
private fun DialogStackedActions(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: (() -> Unit)?,
    confirmTestTag: String?,
    dismissTestTag: String?,
    primaryDestructive: Boolean,
    tone: RipDpiDialogTone,
    hasConfirmAction: Boolean,
) {
    val primaryVariant = if (primaryDestructive) RipDpiButtonVariant.Destructive else RipDpiButtonVariant.Primary
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        if (hasConfirmAction) {
            RipDpiButton(
                wrapLabel = true,
                text = confirmLabel.orEmpty(),
                onClick = { onConfirm?.invoke() },
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(confirmTestTag),
                variant = primaryVariant,
                hapticFeedback = confirmActionHapticFeedback(primaryDestructive),
            )
        }
        RipDpiButton(
            wrapLabel = true,
            text = dismissLabel,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().ripDpiTestTag(dismissTestTag),
            variant = if (hasConfirmAction) RipDpiButtonVariant.Outline else primaryVariant,
            hapticFeedback = dismissActionHapticFeedback(tone, hasConfirmAction),
        )
    }
}

/** Material 3 dialog actions: text buttons, end-aligned, the dismiss action before the confirm action. */
@Composable
private fun DialogInlineActions(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: (() -> Unit)?,
    confirmTestTag: String?,
    dismissTestTag: String?,
    primaryDestructive: Boolean,
    tone: RipDpiDialogTone,
    hasConfirmAction: Boolean,
) {
    val primaryVariant = if (primaryDestructive) RipDpiButtonVariant.DestructiveGhost else RipDpiButtonVariant.Ghost
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RipDpiButton(
            wrapLabel = true,
            text = dismissLabel,
            onClick = onDismiss,
            modifier = Modifier.ripDpiTestTag(dismissTestTag),
            variant = if (hasConfirmAction) RipDpiButtonVariant.Ghost else primaryVariant,
            density = RipDpiControlDensity.Compact,
            hapticFeedback = dismissActionHapticFeedback(tone, hasConfirmAction),
        )
        if (hasConfirmAction) {
            RipDpiButton(
                wrapLabel = true,
                text = confirmLabel.orEmpty(),
                onClick = { onConfirm?.invoke() },
                modifier = Modifier.ripDpiTestTag(confirmTestTag),
                variant = primaryVariant,
                density = RipDpiControlDensity.Compact,
                hapticFeedback = confirmActionHapticFeedback(primaryDestructive),
            )
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

private fun confirmActionHapticFeedback(destructive: Boolean): RipDpiHapticFeedback =
    if (destructive) RipDpiHapticFeedback.Confirm else RipDpiHapticFeedback.Action

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
            dismissAction = RipDpiDialogAction(label = "Cancel", onClick = {}),
            confirmAction = RipDpiDialogAction(label = "Stop", onClick = {}),
            visuals =
                RipDpiDialogVisuals(
                    message = "Active traffic may be interrupted until you reconnect the service.",
                ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDestructiveDialogPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiDialogCard(
            title = "Reset advanced flags?",
            dismissAction = RipDpiDialogAction(label = "Keep", onClick = {}),
            confirmAction = RipDpiDialogAction(label = "Reset", onClick = {}, destructive = true),
            visuals =
                RipDpiDialogVisuals(
                    message = "Custom command line arguments and routing rules will be removed.",
                    tone = RipDpiDialogTone.Destructive,
                ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiInfoDialogPreview() {
    RipDpiComponentPreview {
        RipDpiDialogCard(
            title = "Adaptive icons depend on the launcher",
            dismissAction = RipDpiDialogAction(label = "Understood", onClick = {}),
            visuals =
                RipDpiDialogVisuals(
                    message =
                        "Android launchers control the mask shape, so the app can only preview " +
                            "the current icon family.",
                    tone = RipDpiDialogTone.Info,
                ),
        )
    }
}
