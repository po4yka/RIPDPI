package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
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

@Suppress("LongMethod", "CyclomaticComplexMethod")
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
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Action,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val shape = RipDpiThemeTokens.shapes.xl
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by resolvedInteractionSource.collectIsPressedAsState()
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val state =
        RipDpiThemeTokens.state.iconButton.resolve(
            role = RipDpiThemeTokens.stateRoles.iconButton.fromStyle(style),
            enabled = enabled,
            loading = loading,
            selected = selected,
            isPressed = isPressed,
            isFocused = isFocused,
        )
    val isInteractive = enabled && !loading
    val iconSize =
        when (density) {
            RipDpiControlDensity.Default -> RipDpiIconSizes.Default
            RipDpiControlDensity.Compact -> RipDpiIconSizes.Small
        }
    val animatedBackground by animateColorAsState(
        targetValue = state.container,
        animationSpec = motion.stateTween(),
        label = "iconButtonBackground",
    )
    val animatedIconTint by animateColorAsState(
        targetValue = state.content,
        animationSpec = motion.stateTween(),
        label = "iconButtonTint",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = state.border,
        animationSpec = motion.quickTween(),
        label = "iconButtonBorder",
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = state.borderWidth,
        animationSpec = motion.quickTween(),
        label = "iconButtonBorderWidth",
    )
    val pressedScale by animateFloatAsState(
        targetValue = state.scale,
        animationSpec = motion.motionAwareSpring(),
        label = "iconButtonScale",
    )

    Row(
        modifier =
            modifier
                .size(components.buttons.iconButtonSize)
                .clip(shape)
                .background(animatedBackground, shape)
                .border(animatedBorderWidth, animatedBorderColor, shape)
                .focusable(enabled = isInteractive, interactionSource = resolvedInteractionSource)
                .ripDpiClickable(
                    enabled = isInteractive,
                    role = Role.Button,
                    interactionSource = resolvedInteractionSource,
                    hapticFeedback = hapticFeedback,
                    onClick = onClick,
                ).graphicsLayer {
                    scaleX = pressedScale
                    scaleY = pressedScale
                },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { motion.quickContentTransform() },
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

@Suppress("UnusedPrivateMember")
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

@Suppress("UnusedPrivateMember")
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
