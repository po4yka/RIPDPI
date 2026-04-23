package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class RipDpiSurfaceRole {
    Card,
    TonalCard,
    ElevatedCard,
    StatusCard,
    SelectedCard,
    Dialog,
    BottomSheet,
    Banner,
    Snackbar,
    SwitchThumb,
    BottomSheetIconBadge,
    DialogIconBadge,
    DialogDestructiveIconBadge,
    DropdownMenu,
    BottomBar,
    BottomBarIndicator,
}

@Immutable
data class RipDpiSurfaceStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val shadowElevation: Dp = 0.dp,
)

@Immutable
data class RipDpiSurfaceTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
) {
    fun resolve(role: RipDpiSurfaceRole): RipDpiSurfaceStyle =
        when (role) {
            RipDpiSurfaceRole.Card -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.TonalCard -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.ElevatedCard -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder.copy(alpha = 0.72f),
                    content = colors.foreground,
                    shadowElevation = 18.dp,
                )
            }

            RipDpiSurfaceRole.StatusCard -> {
                RipDpiSurfaceStyle(
                    container = colors.accent,
                    border = colors.cardBorder,
                    content = colors.foreground,
                    shadowElevation = 8.dp,
                )
            }

            RipDpiSurfaceRole.SelectedCard -> {
                RipDpiSurfaceStyle(
                    container = colors.accent,
                    border = colors.foreground,
                    content = colors.foreground,
                    shadowElevation = 4.dp,
                )
            }

            RipDpiSurfaceRole.Dialog -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder,
                    content = colors.foreground,
                    shadowElevation = 24.dp,
                )
            }

            RipDpiSurfaceRole.BottomSheet -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder,
                    content = colors.foreground,
                    shadowElevation = 24.dp,
                )
            }

            RipDpiSurfaceRole.Banner -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.Snackbar -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.inverseSurface,
                    border = Color.Transparent,
                    content = colorScheme.inverseOnSurface,
                    shadowElevation = 18.dp,
                )
            }

            RipDpiSurfaceRole.SwitchThumb -> {
                RipDpiSurfaceStyle(
                    container = colors.foreground,
                    border = Color.Transparent,
                    content = colors.background,
                    shadowElevation = 3.dp,
                )
            }

            RipDpiSurfaceRole.BottomSheetIconBadge -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = Color.Transparent,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.DialogIconBadge -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = Color.Transparent,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.DialogDestructiveIconBadge -> {
                RipDpiSurfaceStyle(
                    container = colors.destructiveContainer,
                    border = Color.Transparent,
                    content = colors.destructive,
                )
            }

            RipDpiSurfaceRole.DropdownMenu -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder,
                    content = colors.foreground,
                    shadowElevation = 12.dp,
                )
            }

            RipDpiSurfaceRole.BottomBar -> {
                RipDpiSurfaceStyle(
                    container = colors.card,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.BottomBarIndicator -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = Color.Transparent,
                    content = colors.foreground,
                )
            }
        }
}

fun ripDpiSurfaceTokens(
    colors: RipDpiExtendedColors,
    colorScheme: ColorScheme,
): RipDpiSurfaceTokens = RipDpiSurfaceTokens(colors = colors, colorScheme = colorScheme)

@Composable
fun ripDpiSurfaceStyle(role: RipDpiSurfaceRole): RipDpiSurfaceStyle = RipDpiThemeTokens.surfaces.resolve(role)

internal val LocalRipDpiSurfaceTokens =
    staticCompositionLocalOf {
        ripDpiSurfaceTokens(
            colors = LightRipDpiExtendedColors,
            colorScheme = ripDpiLightColorScheme(),
        )
    }
