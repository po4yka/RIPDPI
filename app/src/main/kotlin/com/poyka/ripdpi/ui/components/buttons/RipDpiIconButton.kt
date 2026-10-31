package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
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
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val DisabledIconContentAlpha = 0.38f

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

/**
 * Icon action on Material 3: Ghost, Destructive, and Warning render [IconButton], Tonal renders
 * [FilledTonalIconButton], Filled renders [FilledIconButton], and Outline renders
 * [OutlinedIconButton]. [selected] changes only the tonal container; the action stays a button.
 */
@Suppress("LongMethod")
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
    // Both densities keep the 48dp target and default icon size; the parameter stays for callers.
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    interactionSource: MutableInteractionSource? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Action,
) {
    val motion = RipDpiThemeTokens.motion
    val shape = RipDpiThemeTokens.shapes.xl
    val inspectionMode = LocalInspectionMode.current
    val performHaptic = rememberRipDpiHapticPerformer()
    val loadingLabel = stringResource(R.string.cd_loading)
    val buttonModifier =
        modifier
            .size(RipDpiThemeTokens.components.buttons.iconButtonSize)
            .semantics {
                this.contentDescription = contentDescription
                if (loading) stateDescription = loadingLabel
            }
    val isInteractive = enabled && !loading
    val colors = ripDpiIconButtonColors(style, enabled = enabled, selected = selected)
    val handleClick = {
        performHaptic(hapticFeedback)
        onClick()
    }
    val content: @Composable () -> Unit = {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { motion.quickContentTransform() },
            label = "iconButtonContent",
        ) { isLoading ->
            val tint = LocalContentColor.current
            if (isLoading) {
                if (shouldUseStaticButtonLoadingIndicator(inspectionMode, motion.allowsInfiniteMotion)) {
                    RipDpiStaticCircularProgressIndicator(
                        modifier = Modifier.size(RipDpiIconSizes.Default),
                        color = tint,
                        trackColor = RipDpiThemeTokens.colors.muted,
                        strokeWidth = RipDpiStroke.Thick,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(RipDpiIconSizes.Default),
                        color = tint,
                        strokeWidth = RipDpiStroke.Thick,
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(RipDpiIconSizes.Default),
                )
            }
        }
    }

    when (style) {
        RipDpiIconButtonStyle.Ghost,
        RipDpiIconButtonStyle.Destructive,
        RipDpiIconButtonStyle.Warning,
        -> {
            IconButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                colors = colors,
                interactionSource = interactionSource,
                shape = shape,
                content = content,
            )
        }

        RipDpiIconButtonStyle.Tonal -> {
            FilledTonalIconButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = colors,
                interactionSource = interactionSource,
                content = content,
            )
        }

        RipDpiIconButtonStyle.Filled -> {
            FilledIconButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = colors,
                interactionSource = interactionSource,
                content = content,
            )
        }

        RipDpiIconButtonStyle.Outline -> {
            OutlinedIconButton(
                onClick = handleClick,
                modifier = buttonModifier,
                enabled = isInteractive,
                shape = shape,
                colors = colors,
                border = BorderStroke(RipDpiStroke.Thin, RipDpiThemeTokens.colors.border),
                interactionSource = interactionSource,
                content = content,
            )
        }
    }
}

/**
 * Maps RDS roles into Material icon button colors. A disabled icon uses the standard disabled
 * alpha; a loading icon (enabled but busy) uses the muted foreground.
 */
@Composable
internal fun ripDpiIconButtonColors(
    style: RipDpiIconButtonStyle,
    enabled: Boolean,
    selected: Boolean,
): IconButtonColors {
    val colors = RipDpiThemeTokens.colors
    val disabledContent =
        if (enabled) colors.mutedForeground else colors.foreground.copy(alpha = DisabledIconContentAlpha)
    val (container, content) =
        when (style) {
            RipDpiIconButtonStyle.Ghost, RipDpiIconButtonStyle.Outline -> {
                Color.Transparent to colors.foreground
            }

            RipDpiIconButtonStyle.Tonal -> {
                if (selected) colors.muted to colors.foreground else colors.accent to colors.accentForeground
            }

            RipDpiIconButtonStyle.Filled -> {
                colors.foreground to colors.background
            }

            RipDpiIconButtonStyle.Destructive -> {
                Color.Transparent to colors.destructive
            }

            RipDpiIconButtonStyle.Warning -> {
                Color.Transparent to colors.warning
            }
        }
    val disabledContainer =
        when (style) {
            RipDpiIconButtonStyle.Tonal, RipDpiIconButtonStyle.Filled -> colors.border
            else -> Color.Transparent
        }
    return IconButtonDefaults.iconButtonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = disabledContainer,
        disabledContentColor = disabledContent,
    )
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
