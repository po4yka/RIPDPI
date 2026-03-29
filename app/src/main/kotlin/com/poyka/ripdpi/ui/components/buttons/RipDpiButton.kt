package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiButtonVariant {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

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
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by resolvedInteractionSource.collectIsPressedAsState()
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val isInteractive = enabled && !loading
    val base = buttonPalette(variant = variant, enabled = isInteractive, isPressed = isPressed)
    val horizontalPadding =
        when (density) {
            RipDpiControlDensity.Default -> components.buttonHorizontalPadding
            RipDpiControlDensity.Compact -> components.buttonHorizontalPadding - 4.dp
        }
    val borderWidth =
        when {
            !isInteractive && variant == RipDpiButtonVariant.Ghost -> 0.dp
            isFocused -> 2.dp
            variant == RipDpiButtonVariant.Outline -> 1.dp
            else -> 0.dp
        }
    val borderColor =
        when {
            !isInteractive && variant == RipDpiButtonVariant.Outline -> colors.border
            isFocused -> MaterialTheme.colorScheme.outline
            variant == RipDpiButtonVariant.Outline -> colors.border
            else -> Color.Transparent
        }
    val animatedContainerColor by animateColorAsState(
        targetValue = base.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "buttonContainer",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = base.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "buttonContent",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "buttonBorder",
    )
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed && isInteractive) motion.pressScale else 1f,
        animationSpec =
            tween(
                durationMillis = motion.duration(motion.quickDurationMillis),
                easing = FastOutSlowInEasing,
            ),
        label = "buttonScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (loading) 0.92f else 1f,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "buttonContentAlpha",
    )

    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = components.buttonMinHeight)
                .clip(shape)
                .background(animatedContainerColor, shape)
                .border(width = borderWidth, color = animatedBorderColor, shape = shape)
                .focusable(enabled = isInteractive, interactionSource = resolvedInteractionSource)
                .ripDpiClickable(
                    enabled = isInteractive,
                    role = Role.Button,
                    interactionSource = resolvedInteractionSource,
                    hapticFeedback = hapticFeedback,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .graphicsLayer {
                        scaleX = pressedScale
                        scaleY = pressedScale
                    }.padding(
                        horizontal = horizontalPadding,
                        vertical = components.buttonVerticalPadding,
                    ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading || leadingIcon != null) {
                Box(
                    modifier = Modifier.size(RipDpiIconSizes.Default),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = loading,
                        transitionSpec = {
                            (
                                fadeIn(
                                    animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                                ) + scaleIn(initialScale = 0.92f)
                            ) togetherWith (
                                fadeOut(
                                    animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                                ) + scaleOut(targetScale = 0.92f)
                            )
                        },
                        label = "buttonLeadingContent",
                    ) { isLoading ->
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(RipDpiIconSizes.Small),
                                color = animatedContentColor,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            leadingIcon?.let {
                                RipDpiButtonIcon(icon = it, tint = animatedContentColor)
                            }
                        }
                    }
                }
            }

            Text(
                text = text,
                color = animatedContentColor,
                style = type.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(contentAlpha),
            )

            if (trailingIcon != null) {
                Box(
                    modifier = Modifier.size(RipDpiIconSizes.Default),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = loading,
                        transitionSpec = {
                            fadeIn(
                                animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                            ) togetherWith
                                fadeOut(
                                    animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                                )
                        },
                        label = "buttonTrailingContent",
                    ) { isLoading ->
                        if (!isLoading) {
                            RipDpiButtonIcon(icon = trailingIcon, tint = animatedContentColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RipDpiButtonIcon(
    icon: ImageVector,
    tint: Color,
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(RipDpiIconSizes.Default),
    )
}

private data class RipDpiButtonPalette(
    val container: Color,
    val content: Color,
)

@Composable
private fun buttonPalette(
    variant: RipDpiButtonVariant,
    enabled: Boolean,
    isPressed: Boolean,
): RipDpiButtonPalette {
    val colors = RipDpiThemeTokens.colors
    val scheme = MaterialTheme.colorScheme

    if (!enabled) {
        return when (variant) {
            RipDpiButtonVariant.Primary,
            RipDpiButtonVariant.Secondary,
            RipDpiButtonVariant.Destructive,
            -> {
                RipDpiButtonPalette(
                    container = colors.border,
                    content = colors.mutedForeground,
                )
            }

            RipDpiButtonVariant.Outline,
            RipDpiButtonVariant.Ghost,
            -> {
                RipDpiButtonPalette(
                    container = Color.Transparent,
                    content = colors.mutedForeground,
                )
            }
        }
    }

    val pressedOverlay = scheme.onSurfaceVariant
    return when (variant) {
        RipDpiButtonVariant.Primary -> {
            val base = colors.foreground
            RipDpiButtonPalette(
                container = if (isPressed) lerp(base, pressedOverlay, 0.35f) else base,
                content = colors.background,
            )
        }

        RipDpiButtonVariant.Secondary -> {
            val base = scheme.secondary
            RipDpiButtonPalette(
                container = if (isPressed) lerp(base, scheme.surfaceVariant, 0.5f) else base,
                content = scheme.onSecondary,
            )
        }

        RipDpiButtonVariant.Outline -> {
            RipDpiButtonPalette(
                container = if (isPressed) scheme.surfaceVariant else Color.Transparent,
                content = colors.foreground,
            )
        }

        RipDpiButtonVariant.Ghost -> {
            RipDpiButtonPalette(
                container = if (isPressed) scheme.surfaceVariant else Color.Transparent,
                content = colors.foreground,
            )
        }

        RipDpiButtonVariant.Destructive -> {
            RipDpiButtonPalette(
                container = if (isPressed) lerp(colors.destructive, pressedOverlay, 0.3f) else colors.destructive,
                content = colors.destructiveForeground,
            )
        }
    }
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
