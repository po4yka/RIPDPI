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
    ActuatorRail,
    ActuatorCarriage,
    ActuatorTerminalSlot,
    ActuatorPipelineSegment,
    RouteProfile,
    RouteCapability,
    RouteStack,
    RouteProvider,
    RouteOpportunity,
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
            RipDpiSurfaceRole.Card,
            RipDpiSurfaceRole.TonalCard,
            RipDpiSurfaceRole.ElevatedCard,
            RipDpiSurfaceRole.StatusCard,
            RipDpiSurfaceRole.SelectedCard,
            RipDpiSurfaceRole.Dialog,
            RipDpiSurfaceRole.BottomSheet,
            RipDpiSurfaceRole.Banner,
            RipDpiSurfaceRole.Snackbar,
            RipDpiSurfaceRole.SwitchThumb,
            RipDpiSurfaceRole.BottomSheetIconBadge,
            RipDpiSurfaceRole.DialogIconBadge,
            RipDpiSurfaceRole.DialogDestructiveIconBadge,
            RipDpiSurfaceRole.DropdownMenu,
            RipDpiSurfaceRole.BottomBar,
            RipDpiSurfaceRole.BottomBarIndicator,
            -> resolveGeneralSurface(role)

            RipDpiSurfaceRole.ActuatorRail,
            RipDpiSurfaceRole.ActuatorCarriage,
            RipDpiSurfaceRole.ActuatorTerminalSlot,
            RipDpiSurfaceRole.ActuatorPipelineSegment,
            -> resolveActuatorSurface(role)

            RipDpiSurfaceRole.RouteProfile,
            RipDpiSurfaceRole.RouteCapability,
            RipDpiSurfaceRole.RouteStack,
            RipDpiSurfaceRole.RouteProvider,
            RipDpiSurfaceRole.RouteOpportunity,
            -> resolveRouteSurface(role)
        }

    private fun resolveGeneralSurface(role: RipDpiSurfaceRole): RipDpiSurfaceStyle =
        resolveCardOrOverlaySurface(role) ?: resolveChromeSurface(role)
            ?: error("Unsupported general surface role: $role")

    private fun resolveCardOrOverlaySurface(role: RipDpiSurfaceRole): RipDpiSurfaceStyle? =
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

            else -> {
                null
            }
        }

    private fun resolveChromeSurface(role: RipDpiSurfaceRole): RipDpiSurfaceStyle? =
        when (role) {
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

            RipDpiSurfaceRole.BottomSheetIconBadge,
            RipDpiSurfaceRole.DialogIconBadge,
            -> {
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

            else -> {
                null
            }
        }

    private fun resolveActuatorSurface(role: RipDpiSurfaceRole): RipDpiSurfaceStyle =
        when (role) {
            RipDpiSurfaceRole.ActuatorRail -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.ActuatorCarriage -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.outlineVariant,
                    content = colors.foreground,
                    shadowElevation = 8.dp,
                )
            }

            RipDpiSurfaceRole.ActuatorTerminalSlot -> {
                RipDpiSurfaceStyle(
                    container = colors.accent,
                    border = colors.outlineVariant,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.ActuatorPipelineSegment -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    content = colors.mutedForeground,
                )
            }

            else -> {
                error("Unsupported actuator surface role: $role")
            }
        }

    private fun resolveRouteSurface(role: RipDpiSurfaceRole): RipDpiSurfaceStyle =
        when (role) {
            RipDpiSurfaceRole.RouteProfile -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.RouteCapability -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.RouteStack -> {
                RipDpiSurfaceStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.RouteProvider -> {
                RipDpiSurfaceStyle(
                    container = colors.accent,
                    border = colors.outlineVariant,
                    content = colors.foreground,
                )
            }

            RipDpiSurfaceRole.RouteOpportunity -> {
                RipDpiSurfaceStyle(
                    container = colorScheme.surface,
                    border = colors.border,
                    content = colors.foreground,
                )
            }

            else -> {
                error("Unsupported route surface role: $role")
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
