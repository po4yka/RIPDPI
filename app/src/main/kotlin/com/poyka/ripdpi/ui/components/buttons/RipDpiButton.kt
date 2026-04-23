package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiButtonStateRole
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiButtonVariant {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
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
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by resolvedInteractionSource.collectIsPressedAsState()
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val state =
        RipDpiThemeTokens.state.button.resolve(
            role = variant.toStateRole(),
            enabled = enabled,
            loading = loading,
            isPressed = isPressed,
            isFocused = isFocused,
        )
    val isInteractive = enabled && !loading
    val cornerRadius by animateDpAsState(
        targetValue = state.cornerRadius,
        animationSpec = motion.motionAwareSpring(),
        label = "buttonCorner",
    )
    val shape = RoundedCornerShape(cornerRadius)
    val horizontalPadding =
        when (density) {
            RipDpiControlDensity.Default -> {
                components.buttonHorizontalPadding
            }

            RipDpiControlDensity.Compact -> {
                components.buttonHorizontalPadding -
                    components.buttonFocusedHorizontalPaddingOffset
            }
        }
    val animatedContainerColor by animateColorAsState(
        targetValue = state.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "buttonContainer",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = state.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "buttonContent",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = state.border,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "buttonBorder",
    )
    val pressedScale by animateFloatAsState(
        targetValue = state.scale,
        animationSpec = motion.motionAwareSpring(),
        label = "buttonScale",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = state.contentAlpha,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "buttonContentAlpha",
    )

    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = components.buttonMinHeight)
                .clip(shape)
                .background(animatedContainerColor, shape)
                .border(width = state.borderWidth, color = animatedBorderColor, shape = shape)
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
            horizontalArrangement = Arrangement.spacedBy(components.buttonIconGap),
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

private fun RipDpiButtonVariant.toStateRole(): RipDpiButtonStateRole =
    when (this) {
        RipDpiButtonVariant.Primary -> RipDpiButtonStateRole.Primary
        RipDpiButtonVariant.Secondary -> RipDpiButtonStateRole.Secondary
        RipDpiButtonVariant.Outline -> RipDpiButtonStateRole.Outline
        RipDpiButtonVariant.Ghost -> RipDpiButtonStateRole.Ghost
        RipDpiButtonVariant.Destructive -> RipDpiButtonStateRole.Destructive
    }

@Suppress("UnusedPrivateMember")
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

@Suppress("UnusedPrivateMember")
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
