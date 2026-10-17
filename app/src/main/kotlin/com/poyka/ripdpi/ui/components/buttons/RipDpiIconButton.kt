@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaticCircularProgressIndicator
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiSpringStyle
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleContent
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

enum class RipDpiIconButtonStyle {
    Ghost,
    Tonal,
    Filled,
    Outline,

    /** Transparent container with the destructive content color; for delete and remove actions. */
    Destructive,

    /** Transparent container with the warning content color; for dismissals inside warning surfaces. */
    Warning,
}

@Composable
@NonRestartableComposable
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
    RipDpiIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        style = style,
        enabled = enabled,
        loading = loading,
        selected = selected,
        density = density,
        interactionSource = interactionSource,
        hapticFeedback = hapticFeedback,
        visualStyle = CommonStyle,
    )
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
    visualStyle: CommonStyle = CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val shape = RipDpiThemeTokens.shapes.xl
    val inspectionMode = LocalInspectionMode.current
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val styleState =
        rememberUpdatedStyleState(resolvedInteractionSource) {
            it.isEnabled = enabled
            it.isSelected = selected
            it[RipDpiStyleStateKeys.Loading] = loading
            it[RipDpiStyleStateKeys.Compact] = density == RipDpiControlDensity.Compact
        }
    val stateRole = RipDpiThemeTokens.stateRoles.iconButton.fromStyle(style)
    val state =
        RipDpiThemeTokens.state.iconButton.resolve(
            role = stateRole,
            enabled = enabled,
            loading = loading,
            selected = selected,
            isPressed = styleState.isPressed,
            isFocused = styleState.isFocused,
        )
    val defaults =
        rememberRipDpiSpringStyle(
            defaults = RipDpiThemeTokens.styles.iconButton(stateRole),
            scale = state.scale,
        )
    val styleResolver = rememberRipDpiStyleResolver(defaults, visualStyle, styleState)
    val isInteractive = enabled && !loading
    val iconSize =
        when (density) {
            RipDpiControlDensity.Default -> RipDpiIconSizes.Default
            RipDpiControlDensity.Compact -> RipDpiIconSizes.Default
        }
    val animatedIconTint by animateColorAsState(
        targetValue = state.content,
        animationSpec = motion.stateTween(),
        label = "iconButtonTint",
    )
    val loadingLabel = stringResource(R.string.cd_loading)
    val accessibilityName = contentDescription
    Row(
        modifier =
            modifier
                .size(components.buttons.iconButtonSize)
                .ripDpiStyleSurface(styleResolver, shape = shape, clip = true)
                .semantics {
                    this.contentDescription = accessibilityName
                    if (loading) stateDescription = loadingLabel
                }.ripDpiClickable(
                    enabled = isInteractive,
                    role = Role.Button,
                    interactionSource = resolvedInteractionSource,
                    hapticFeedback = hapticFeedback,
                    onClick = onClick,
                ).ripDpiStyleContent(styleResolver),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { motion.quickContentTransform() },
            label = "iconButtonContent",
        ) { isLoading ->
            if (isLoading) {
                if (shouldUseStaticButtonLoadingIndicator(inspectionMode, motion.allowsInfiniteMotion)) {
                    RipDpiStaticCircularProgressIndicator(
                        modifier = Modifier.size(iconSize),
                        color = animatedIconTint,
                        trackColor = RipDpiThemeTokens.colors.muted,
                        strokeWidth = RipDpiStroke.Thick,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize),
                        color = animatedIconTint,
                        strokeWidth = RipDpiStroke.Thick,
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
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
