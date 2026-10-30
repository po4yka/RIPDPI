package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val DisabledAlpha = 0.38f

@Immutable
data class RipDpiTextFieldStateTokens(
    private val colors: RipDpiExtendedColors,
) {
    fun resolve(
        enabled: Boolean,
        hasError: Boolean,
        isFocused: Boolean,
        isEmpty: Boolean,
    ): RipDpiTextFieldStateStyle =
        RipDpiTextFieldStateStyle(
            container = colors.inputBackground,
            border =
                when {
                    !enabled -> colors.border
                    hasError -> colors.destructive
                    isFocused -> colors.foreground
                    else -> colors.outlineVariant
                },
            borderWidth =
                when {
                    !enabled -> 1.dp
                    hasError || isFocused -> 2.dp
                    else -> 1.dp
                },
            content =
                when {
                    !enabled || isEmpty -> colors.mutedForeground
                    hasError || isFocused -> colors.foreground
                    else -> colors.mutedForeground
                },
            label = if (hasError) colors.destructive else colors.mutedForeground,
            helper = if (hasError) colors.destructive else colors.mutedForeground,
            placeholder = colors.mutedForeground,
            alpha = if (enabled) 1f else DisabledAlpha,
        )
}

@Immutable
data class RipDpiChipStateTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
    private val shapes: RipDpiShapeMetrics,
    private val motion: RipDpiMotion,
) {
    fun resolve(
        selected: Boolean,
        enabled: Boolean,
        isPressed: Boolean,
    ): RipDpiChipStateStyle {
        val interactive = enabled
        return RipDpiChipStateStyle(
            container =
                when {
                    selected -> colors.foreground
                    isPressed -> colorScheme.surfaceVariant
                    else -> Color.Transparent
                },
            border =
                when {
                    selected -> colors.foreground
                    enabled -> colors.outlineVariant
                    else -> colors.border
                },
            content =
                when {
                    selected -> colors.background
                    enabled -> colors.foreground
                    else -> colors.mutedForeground
                },
            cornerRadius =
                if (isPressed && interactive) {
                    shapes.controlCornerRadius
                } else {
                    shapes.largeCornerRadius
                },
            scale =
                when {
                    isPressed && interactive -> motion.pressScale
                    selected -> motion.selectionScale
                    else -> 1f
                },
            alpha = if (enabled) 1f else DisabledAlpha,
        )
    }
}

@Immutable
data class RipDpiSettingsRowStateTokens(
    private val colors: RipDpiExtendedColors,
) {
    fun resolve(role: RipDpiSettingsRowStateRole): RipDpiSettingsRowStateStyle =
        when (role) {
            RipDpiSettingsRowStateRole.Default -> {
                RipDpiSettingsRowStateStyle(
                    container = Color.Transparent,
                    border = Color.Transparent,
                    title = colors.foreground,
                    subtitle = colors.mutedForeground,
                    value = colors.mutedForeground,
                    leadingBadgeContainer = colors.accent,
                    leadingBadgeIcon = colors.foreground,
                )
            }

            RipDpiSettingsRowStateRole.Tonal -> {
                RipDpiSettingsRowStateStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    title = colors.foreground,
                    subtitle = colors.mutedForeground,
                    value = colors.mutedForeground,
                    leadingBadgeContainer = colors.accent,
                    leadingBadgeIcon = colors.foreground,
                )
            }

            RipDpiSettingsRowStateRole.Selected -> {
                RipDpiSettingsRowStateStyle(
                    container = colors.accent,
                    border = colors.foreground,
                    title = colors.foreground,
                    subtitle = colors.foreground,
                    value = colors.foreground,
                    leadingBadgeContainer = colors.foreground,
                    leadingBadgeIcon = colors.background,
                )
            }
        }
}
