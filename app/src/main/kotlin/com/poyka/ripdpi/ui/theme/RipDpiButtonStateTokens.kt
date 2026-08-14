package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val PrimaryPressedLerp = 0.35f
private const val SecondaryPressedLerp = 0.5f
private const val DestructivePressedLerp = 0.3f
private const val IconButtonPressedLerp = 0.25f
private const val LoadingContentAlpha = 0.92f

@Immutable
data class RipDpiButtonStateTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
    private val shapes: RipDpiShapeMetrics,
    private val motion: RipDpiMotion,
) {
    fun resolve(
        role: RipDpiButtonStateRole,
        enabled: Boolean,
        loading: Boolean,
        isPressed: Boolean,
        isFocused: Boolean,
    ): RipDpiButtonStateStyle {
        val interactive = enabled && !loading
        val containerAndContent = resolveButtonContainerAndContent(role, interactive, isPressed)
        val borderWidth = resolveButtonBorderWidth(role, interactive, isFocused)
        val borderColor = resolveButtonBorderColor(role, interactive, isFocused)
        return RipDpiButtonStateStyle(
            container = containerAndContent.first,
            content = containerAndContent.second,
            border = borderColor,
            borderWidth = borderWidth,
            cornerRadius =
                if (isPressed && interactive) {
                    shapes.controlIncreasedCornerRadius
                } else {
                    shapes.controlCornerRadius
                },
            scale = if (isPressed && interactive) motion.pressScale else 1f,
            contentAlpha = if (loading) LoadingContentAlpha else 1f,
        )
    }

    private fun resolveButtonContainerAndContent(
        role: RipDpiButtonStateRole,
        interactive: Boolean,
        isPressed: Boolean,
    ): Pair<Color, Color> =
        if (!interactive) {
            when (role) {
                RipDpiButtonStateRole.Primary,
                RipDpiButtonStateRole.Secondary,
                RipDpiButtonStateRole.Destructive,
                -> colors.border to colors.mutedForeground

                RipDpiButtonStateRole.Outline,
                RipDpiButtonStateRole.Ghost,
                -> Color.Transparent to colors.mutedForeground
            }
        } else {
            resolveButtonInteractiveContainerAndContent(role, isPressed)
        }

    private fun resolveButtonInteractiveContainerAndContent(
        role: RipDpiButtonStateRole,
        isPressed: Boolean,
    ): Pair<Color, Color> {
        val pressedOverlay = colorScheme.onSurfaceVariant
        return when (role) {
            RipDpiButtonStateRole.Primary -> {
                val base = colors.foreground
                (if (isPressed) lerp(base, pressedOverlay, PrimaryPressedLerp) else base) to colors.background
            }

            RipDpiButtonStateRole.Secondary -> {
                val base = colorScheme.secondary
                (if (isPressed) lerp(base, colorScheme.surfaceVariant, SecondaryPressedLerp) else base) to
                    colorScheme.onSecondary
            }

            RipDpiButtonStateRole.Outline,
            RipDpiButtonStateRole.Ghost,
            -> {
                (if (isPressed) colorScheme.surfaceVariant else Color.Transparent) to colors.foreground
            }

            RipDpiButtonStateRole.Destructive -> {
                val container =
                    if (isPressed) {
                        lerp(colors.destructive, pressedOverlay, DestructivePressedLerp)
                    } else {
                        colors.destructive
                    }
                container to colors.destructiveForeground
            }
        }
    }

    private fun resolveButtonBorderWidth(
        role: RipDpiButtonStateRole,
        interactive: Boolean,
        isFocused: Boolean,
    ): Dp =
        when {
            !interactive && role == RipDpiButtonStateRole.Ghost -> 0.dp
            isFocused -> 2.dp
            role == RipDpiButtonStateRole.Outline -> 1.dp
            else -> 0.dp
        }

    private fun resolveButtonBorderColor(
        role: RipDpiButtonStateRole,
        interactive: Boolean,
        isFocused: Boolean,
    ): Color =
        when {
            !interactive && role == RipDpiButtonStateRole.Outline -> colors.border
            isFocused -> colors.outline
            role == RipDpiButtonStateRole.Outline -> colors.border
            else -> Color.Transparent
        }
}

@Immutable
data class RipDpiIconButtonStateTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
    private val motion: RipDpiMotion,
) {
    fun resolve(
        role: RipDpiIconButtonStateRole,
        enabled: Boolean,
        loading: Boolean,
        selected: Boolean,
        isPressed: Boolean,
        isFocused: Boolean,
    ): RipDpiIconButtonStateStyle {
        val interactive = enabled && !loading
        return RipDpiIconButtonStateStyle(
            container = resolveIconButtonContainer(role, interactive, selected, isPressed),
            content = resolveIconButtonContent(role, interactive),
            border =
                if (isFocused) {
                    colors.outline
                } else if (role == RipDpiIconButtonStateRole.Outline) {
                    colors.border
                } else {
                    Color.Transparent
                },
            borderWidth =
                if (isFocused) {
                    2.dp
                } else if (role == RipDpiIconButtonStateRole.Outline) {
                    1.dp
                } else {
                    0.dp
                },
            scale = if (isPressed && interactive) motion.pressScale else 1f,
        )
    }

    private fun resolveIconButtonContainer(
        role: RipDpiIconButtonStateRole,
        interactive: Boolean,
        selected: Boolean,
        isPressed: Boolean,
    ): Color {
        val baseContainer =
            when (role) {
                RipDpiIconButtonStateRole.Ghost -> Color.Transparent
                RipDpiIconButtonStateRole.Tonal -> if (selected) colorScheme.surfaceVariant else colors.accent
                RipDpiIconButtonStateRole.Filled -> colors.foreground
                RipDpiIconButtonStateRole.Outline -> Color.Transparent
                RipDpiIconButtonStateRole.Destructive -> Color.Transparent
                RipDpiIconButtonStateRole.Warning -> Color.Transparent
            }
        return when {
            !interactive && role == RipDpiIconButtonStateRole.Ghost -> Color.Transparent
            !interactive -> colors.border
            isPressed -> lerp(baseContainer, colorScheme.onSurfaceVariant, IconButtonPressedLerp)
            else -> baseContainer
        }
    }

    private fun resolveIconButtonContent(
        role: RipDpiIconButtonStateRole,
        interactive: Boolean,
    ): Color =
        when {
            !interactive -> colors.mutedForeground
            role == RipDpiIconButtonStateRole.Filled -> colors.background
            role == RipDpiIconButtonStateRole.Destructive -> colors.destructive
            role == RipDpiIconButtonStateRole.Warning -> colors.warning
            else -> colors.foreground
        }
}
