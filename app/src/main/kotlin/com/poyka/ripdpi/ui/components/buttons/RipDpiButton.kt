package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaticCircularProgressIndicator
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiButtonVariant {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

/**
 * Action button on Material 3: Primary and Destructive render [Button], Secondary renders
 * [FilledTonalButton], Outline renders [OutlinedButton], and Ghost renders [TextButton]. Primary
 * keeps the RDS ink fill. A loading button is disabled and reports a busy state description.
 */
@Suppress("LongMethod")
@Composable
fun RipDpiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    interactionSource: MutableInteractionSource? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Action,
    wrapLabel: Boolean = true,
) {
    val components = RipDpiThemeTokens.components
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val inspectionMode = LocalInspectionMode.current
    val loadingStateDescription = stringResource(R.string.cd_loading)
    val type = RipDpiThemeTokens.type
    val performHaptic = rememberRipDpiHapticPerformer()
    val horizontalPadding =
        components.buttons.horizontalPadding -
            if (density == RipDpiControlDensity.Compact) components.buttons.focusedHorizontalPaddingOffset else 0.dp
    val contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = components.buttons.verticalPadding)
    val shape = RoundedCornerShape(components.shapes.controlCornerRadius)
    val buttonModifier =
        modifier
            .defaultMinSize(minHeight = components.buttons.minHeight)
            .semantics {
                // Without this a screen reader hears the button go from enabled to disabled and
                // cannot tell "busy" from "unavailable".
                if (loading) {
                    stateDescription = loadingStateDescription
                }
            }
    val isInteractive = enabled && !loading
    val handleClick = {
        performHaptic(hapticFeedback)
        onClick()
    }
    val content: @Composable RowScope.() -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(components.buttons.iconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val contentColor = LocalContentColor.current
            if (loading || leadingIcon != null) {
                Box(modifier = Modifier.size(RipDpiIconSizes.Default), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = loading,
                        transitionSpec = { motion.quickContentTransform() },
                        label = "buttonLeadingContent",
                    ) { isLoading ->
                        if (isLoading) {
                            if (shouldUseStaticButtonLoadingIndicator(inspectionMode, motion.allowsInfiniteMotion)) {
                                RipDpiStaticCircularProgressIndicator(
                                    modifier = Modifier.size(RipDpiIconSizes.Small),
                                    color = contentColor,
                                    trackColor = colors.muted,
                                    strokeWidth = RipDpiStroke.Thick,
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(RipDpiIconSizes.Small),
                                    color = contentColor,
                                    strokeWidth = RipDpiStroke.Thick,
                                )
                            }
                        } else {
                            leadingIcon?.let {
                                RipDpiButtonIcon(
                                    icon = it,
                                    tint = contentColor,
                                    size = RipDpiIconSizes.Default,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = text,
                style = type.button,
                maxLines = if (wrapLabel) Int.MAX_VALUE else 1,
                overflow = if (wrapLabel) TextOverflow.Clip else TextOverflow.Ellipsis,
                textAlign = if (wrapLabel) TextAlign.Center else null,
                modifier =
                    (if (wrapLabel) Modifier.weight(1f, fill = false) else Modifier).width(IntrinsicSize.Max),
            )

            if (trailingIcon != null) {
                Box(modifier = Modifier.size(RipDpiIconSizes.Default), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = loading,
                        transitionSpec = { motion.quickFadeContentTransform() },
                        label = "buttonTrailingContent",
                    ) { isLoading ->
                        if (!isLoading) {
                            RipDpiButtonIcon(icon = trailingIcon, tint = contentColor, size = RipDpiIconSizes.Default)
                        }
                    }
                }
            }
        }
    }

    when (variant) {
        RipDpiButtonVariant.Primary,
        RipDpiButtonVariant.Destructive,
        -> {
            Button(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = ripDpiButtonColors(variant),
                contentPadding = contentPadding,
                interactionSource = interactionSource,
                content = content,
            )
        }

        RipDpiButtonVariant.Secondary -> {
            FilledTonalButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = ripDpiButtonColors(variant),
                contentPadding = contentPadding,
                interactionSource = interactionSource,
                content = content,
            )
        }

        RipDpiButtonVariant.Outline -> {
            OutlinedButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = ripDpiButtonColors(variant),
                border = BorderStroke(RipDpiStroke.Thin, colors.border),
                contentPadding = contentPadding,
                interactionSource = interactionSource,
                content = content,
            )
        }

        RipDpiButtonVariant.Ghost -> {
            TextButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = ripDpiButtonColors(variant),
                contentPadding = contentPadding,
                interactionSource = interactionSource,
                content = content,
            )
        }
    }
}

/** Maps RDS roles into Material button colors; disabled and loading share the muted palette. */
@Composable
internal fun ripDpiButtonColors(variant: RipDpiButtonVariant): ButtonColors {
    val colors = RipDpiThemeTokens.colors
    return when (variant) {
        RipDpiButtonVariant.Primary -> {
            ButtonDefaults.buttonColors(
                containerColor = colors.foreground,
                contentColor = colors.background,
                disabledContainerColor = colors.border,
                disabledContentColor = colors.mutedForeground,
            )
        }

        RipDpiButtonVariant.Destructive -> {
            ButtonDefaults.buttonColors(
                containerColor = colors.destructive,
                contentColor = colors.destructiveForeground,
                disabledContainerColor = colors.border,
                disabledContentColor = colors.mutedForeground,
            )
        }

        RipDpiButtonVariant.Secondary -> {
            ButtonDefaults.filledTonalButtonColors(
                disabledContainerColor = colors.border,
                disabledContentColor = colors.mutedForeground,
            )
        }

        RipDpiButtonVariant.Outline,
        RipDpiButtonVariant.Ghost,
        -> {
            ButtonDefaults.textButtonColors(
                containerColor = Color.Transparent,
                contentColor = colors.foreground,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = colors.mutedForeground,
            )
        }
    }
}

internal fun shouldUseStaticButtonLoadingIndicator(
    isInspectionMode: Boolean,
    allowsInfiniteMotion: Boolean,
): Boolean = isInspectionMode || !allowsInfiniteMotion

@Composable
private fun RipDpiButtonIcon(
    icon: ImageVector,
    tint: Color,
    size: Dp,
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

@Preview(showBackground = true)
@Composable
private fun RipDpiButtonLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiButton(text = "Connect", onClick = {})
            RipDpiButton(text = "Connect", onClick = {}, enabled = false)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RipDpiButton(text = "Secondary", onClick = {}, variant = RipDpiButtonVariant.Secondary)
                RipDpiButton(text = "Outline", onClick = {}, variant = RipDpiButtonVariant.Outline)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RipDpiButton(text = "Ghost", onClick = {}, variant = RipDpiButtonVariant.Ghost)
                RipDpiButton(text = "Reset", onClick = {}, variant = RipDpiButtonVariant.Destructive)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiButtonDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiButton(text = "Connect", onClick = {})
            RipDpiButton(text = "Connecting", onClick = {}, loading = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RipDpiButton(text = "Cancel", onClick = {}, variant = RipDpiButtonVariant.Outline)
                RipDpiButton(text = "Reset", onClick = {}, variant = RipDpiButtonVariant.Destructive)
            }
        }
    }
}
