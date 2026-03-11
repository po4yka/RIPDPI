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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiIconButtonStyle {
    Ghost,
    Tonal,
    Filled,
    Outline,
}

@Composable
fun RipDpiIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RipDpiIconButtonStyle = RipDpiIconButtonStyle.Ghost,
    enabled: Boolean = true,
    loading: Boolean = false,
    selected: Boolean = false,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val scheme = MaterialTheme.colorScheme
    val shape = RipDpiThemeTokens.shapes.xl
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by resolvedInteractionSource.collectIsPressedAsState()
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val isInteractive = enabled && !loading
    val iconSize =
        when (density) {
            RipDpiControlDensity.Default -> RipDpiIconSizes.Default
            RipDpiControlDensity.Compact -> RipDpiIconSizes.Small
        }
    val baseContainer =
        when (style) {
            RipDpiIconButtonStyle.Ghost -> Color.Transparent
            RipDpiIconButtonStyle.Tonal -> if (selected) scheme.surfaceVariant else colors.accent
            RipDpiIconButtonStyle.Filled -> colors.foreground
            RipDpiIconButtonStyle.Outline -> Color.Transparent
        }
    val background =
        when {
            !isInteractive && style == RipDpiIconButtonStyle.Ghost -> Color.Transparent
            !isInteractive -> colors.border
            isPressed -> lerp(baseContainer, scheme.onSurfaceVariant, 0.25f)
            else -> baseContainer
        }
    val iconTint =
        when {
            !isInteractive -> colors.mutedForeground
            style == RipDpiIconButtonStyle.Filled -> colors.background
            else -> colors.foreground
        }
    val borderColor =
        when {
            isFocused -> MaterialTheme.colorScheme.outline
            style == RipDpiIconButtonStyle.Outline && enabled -> colors.border
            style == RipDpiIconButtonStyle.Outline -> colors.border
            else -> Color.Transparent
        }
    val animatedBackground by animateColorAsState(
        targetValue = background,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "iconButtonBackground",
    )
    val animatedIconTint by animateColorAsState(
        targetValue = iconTint,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "iconButtonTint",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "iconButtonBorder",
    )
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed && isInteractive) motion.pressScale else 1f,
        animationSpec = tween(
            durationMillis = motion.duration(motion.quickDurationMillis),
            easing = FastOutSlowInEasing,
        ),
        label = "iconButtonScale",
    )

    Row(
        modifier =
            modifier
                .size(components.iconButtonSize)
                .clip(shape)
                .background(animatedBackground, shape)
                .border(
                    if (isFocused) {
                        2.dp
                    } else if (style == RipDpiIconButtonStyle.Outline) {
                        1.dp
                    } else {
                        0.dp
                    },
                    animatedBorderColor,
                    shape,
                )
                .focusable(enabled = isInteractive, interactionSource = resolvedInteractionSource)
                .ripDpiClickable(
                    enabled = isInteractive,
                    role = Role.Button,
                    interactionSource = resolvedInteractionSource,
                    onClick = onClick,
                )
                .graphicsLayer {
                    scaleX = pressedScale
                    scaleY = pressedScale
                },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
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
            label = "iconButtonContent",
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = animatedIconTint,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = animatedIconTint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiIconButtonPreview() {
    RipDpiComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiIconButton(
                icon = RipDpiIcons.Search,
                contentDescription = "Search",
                onClick = {},
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Overflow,
                contentDescription = "More",
                onClick = {},
                style = RipDpiIconButtonStyle.Tonal,
                selected = true,
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Settings,
                contentDescription = "Settings",
                onClick = {},
                style = RipDpiIconButtonStyle.Outline,
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Close,
                contentDescription = "Close",
                onClick = {},
                style = RipDpiIconButtonStyle.Filled,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiIconButtonDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiIconButton(
                icon = RipDpiIcons.Back,
                contentDescription = "Back",
                onClick = {},
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Warning,
                contentDescription = "Warning",
                onClick = {},
                style = RipDpiIconButtonStyle.Tonal,
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Settings,
                contentDescription = "Settings",
                onClick = {},
                style = RipDpiIconButtonStyle.Outline,
                enabled = false,
            )
        }
    }
}
