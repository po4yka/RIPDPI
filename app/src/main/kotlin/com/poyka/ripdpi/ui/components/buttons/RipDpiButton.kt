@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiSpringStyle
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleContent
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

enum class RipDpiButtonVariant {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

@Composable
@NonRestartableComposable
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
    RipDpiButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        loading = loading,
        density = density,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        interactionSource = interactionSource,
        hapticFeedback = hapticFeedback,
        wrapLabel = wrapLabel,
        visualStyle = CommonStyle,
    )
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
    wrapLabel: Boolean = true,
    visualStyle: CommonStyle = CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val inspectionMode = LocalInspectionMode.current
    val loadingStateDescription = stringResource(R.string.cd_loading)
    val type = RipDpiThemeTokens.type
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val styleState =
        rememberUpdatedStyleState(resolvedInteractionSource) {
            it.isEnabled = enabled
            it[RipDpiStyleStateKeys.Loading] = loading
            it[RipDpiStyleStateKeys.Compact] = density == RipDpiControlDensity.Compact
        }
    val stateRole = RipDpiThemeTokens.stateRoles.button.fromVariant(variant)
    val state =
        RipDpiThemeTokens.state.button.resolve(
            role = stateRole,
            enabled = enabled,
            loading = loading,
            isPressed = styleState.isPressed,
            isFocused = styleState.isFocused,
        )
    val defaults =
        rememberRipDpiSpringStyle(
            defaults = RipDpiThemeTokens.styles.button(stateRole),
            scale = state.scale,
            cornerRadius = state.cornerRadius,
        )
    val styleResolver = rememberRipDpiStyleResolver(defaults, visualStyle, styleState)
    val isInteractive = enabled && !loading
    val contentIconSize =
        when (density) {
            RipDpiControlDensity.Default -> RipDpiIconSizes.Default
            RipDpiControlDensity.Compact -> RipDpiIconSizes.Default
        }
    val animatedContentColor by animateColorAsState(
        targetValue = state.content,
        animationSpec = motion.stateTween(),
        label = "buttonContent",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = state.contentAlpha,
        animationSpec = motion.quickTween(),
        label = "buttonContentAlpha",
    )

    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = components.buttons.minHeight)
                .ripDpiStyleSurface(styleResolver, clip = true)
                .ripDpiClickable(
                    enabled = isInteractive,
                    role = Role.Button,
                    interactionSource = resolvedInteractionSource,
                    hapticFeedback = hapticFeedback,
                    onClick = onClick,
                ).semantics {
                    // Without this a screen reader hears the button go from enabled to disabled and
                    // cannot tell "busy" from "unavailable".
                    if (loading) {
                        stateDescription = loadingStateDescription
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .ripDpiStyleContent(styleResolver, applyPadding = true),
            horizontalArrangement = Arrangement.spacedBy(components.buttons.iconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading || leadingIcon != null) {
                Box(
                    modifier = Modifier.size(contentIconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = loading,
                        transitionSpec = { motion.quickContentTransform() },
                        label = "buttonLeadingContent",
                    ) { isLoading ->
                        if (isLoading) {
                            if (
                                shouldUseStaticButtonLoadingIndicator(
                                    inspectionMode,
                                    motion.allowsInfiniteMotion,
                                )
                            ) {
                                RipDpiStaticCircularProgressIndicator(
                                    modifier = Modifier.size(RipDpiIconSizes.Small),
                                    color = animatedContentColor,
                                    trackColor = RipDpiThemeTokens.colors.muted,
                                    strokeWidth = RipDpiStroke.Thick,
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(RipDpiIconSizes.Small),
                                    color = animatedContentColor,
                                    strokeWidth = RipDpiStroke.Thick,
                                )
                            }
                        } else {
                            leadingIcon?.let {
                                RipDpiButtonIcon(icon = it, tint = animatedContentColor, size = contentIconSize)
                            }
                        }
                    }
                }
            }

            Text(
                text = text,
                color = animatedContentColor,
                style = type.button,
                maxLines = if (wrapLabel) Int.MAX_VALUE else 1,
                overflow = if (wrapLabel) TextOverflow.Clip else TextOverflow.Ellipsis,
                textAlign = if (wrapLabel) TextAlign.Center else null,
                modifier =
                    (
                        if (wrapLabel) {
                            Modifier.weight(
                                1f,
                                fill = false,
                            )
                        } else {
                            Modifier
                        }
                    ).width(IntrinsicSize.Max).alpha(contentAlpha),
            )

            if (trailingIcon != null) {
                Box(
                    modifier = Modifier.size(contentIconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = loading,
                        transitionSpec = { motion.quickFadeContentTransform() },
                        label = "buttonTrailingContent",
                    ) { isLoading ->
                        if (!isLoading) {
                            RipDpiButtonIcon(icon = trailingIcon, tint = animatedContentColor, size = contentIconSize)
                        }
                    }
                }
            }
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
