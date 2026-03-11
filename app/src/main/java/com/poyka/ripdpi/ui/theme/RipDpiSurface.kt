package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class RipDpiSurfaceRole {
    Card,
    TonalCard,
    ElevatedCard,
    StatusCard,
    SelectedCard,
    Sheet,
    Banner,
    Snackbar,
}

@Immutable
data class RipDpiSurfaceStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val shadowElevation: Dp = 0.dp,
)

@Composable
fun ripDpiSurfaceStyle(role: RipDpiSurfaceRole): RipDpiSurfaceStyle {
    val colors = RipDpiThemeTokens.colors

    return when (role) {
        RipDpiSurfaceRole.Card -> {
            RipDpiSurfaceStyle(
                container = MaterialTheme.colorScheme.surface,
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
                container = MaterialTheme.colorScheme.surface,
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

        RipDpiSurfaceRole.Sheet -> {
            RipDpiSurfaceStyle(
                container = MaterialTheme.colorScheme.surface,
                border = colors.cardBorder,
                content = colors.foreground,
                shadowElevation = 24.dp,
            )
        }

        RipDpiSurfaceRole.Banner -> {
            RipDpiSurfaceStyle(
                container = MaterialTheme.colorScheme.surface,
                border = colors.border,
                content = colors.foreground,
            )
        }

        RipDpiSurfaceRole.Snackbar -> {
            RipDpiSurfaceStyle(
                container = MaterialTheme.colorScheme.inverseSurface,
                border = Color.Transparent,
                content = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 18.dp,
            )
        }
    }
}
