package com.poyka.ripdpi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

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
