package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val DisabledAlpha = 0.38f

@Immutable
data class RipDpiRouteStateTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
) {
    fun resolve(role: RipDpiRouteAvailabilityStateRole): RipDpiRouteAvailabilityStateStyle =
        when (role) {
            RipDpiRouteAvailabilityStateRole.Available,
            RipDpiRouteAvailabilityStateRole.Selected,
            RipDpiRouteAvailabilityStateRole.Configured,
            RipDpiRouteAvailabilityStateRole.Active,
            -> resolveNeutralOrActiveRouteStyle(role)

            RipDpiRouteAvailabilityStateRole.NeedsSetup,
            RipDpiRouteAvailabilityStateRole.Restricted,
            RipDpiRouteAvailabilityStateRole.Degraded,
            RipDpiRouteAvailabilityStateRole.Failed,
            -> resolveAlertRouteStyle(role)
        }

    private fun resolveNeutralOrActiveRouteStyle(
        role: RipDpiRouteAvailabilityStateRole,
    ): RipDpiRouteAvailabilityStateStyle =
        when (role) {
            RipDpiRouteAvailabilityStateRole.Available -> {
                routeStyle(
                    container = colorScheme.surface,
                    border = colors.cardBorder,
                    badgeContainer = colors.inputBackground,
                    badgeBorder = colors.border,
                )
            }

            RipDpiRouteAvailabilityStateRole.Selected -> {
                routeStyle(
                    container = colors.accent,
                    border = colors.foreground,
                    mutedContent = colors.foreground,
                    marker = colors.foreground,
                    badgeContainer = colors.foreground,
                    badgeBorder = colors.foreground,
                    badgeContent = colors.background,
                    borderWidth = 2.dp,
                )
            }

            RipDpiRouteAvailabilityStateRole.Configured -> {
                routeStyle(
                    container = colorScheme.surface,
                    border = colors.outlineVariant,
                    badgeContainer = colors.accent,
                    badgeBorder = colors.outlineVariant,
                )
            }

            RipDpiRouteAvailabilityStateRole.Active -> {
                routeStyle(
                    container = colors.foreground,
                    border = colors.foreground,
                    content = colors.background,
                    mutedContent = colors.background,
                    marker = colors.success,
                    badgeContainer = colors.background,
                    badgeBorder = colors.background,
                    badgeContent = colors.foreground,
                    borderWidth = 2.dp,
                )
            }

            else -> {
                error("Unexpected role in resolveNeutralOrActiveRouteStyle: $role")
            }
        }

    private fun resolveAlertRouteStyle(role: RipDpiRouteAvailabilityStateRole): RipDpiRouteAvailabilityStateStyle =
        when (role) {
            RipDpiRouteAvailabilityStateRole.NeedsSetup -> {
                routeStyle(
                    container = colors.infoContainer,
                    border = colors.info.copy(alpha = 0.48f),
                    content = colors.infoContainerForeground,
                    mutedContent = colors.infoContainerForeground,
                    marker = colors.info,
                    badgeContainer = colors.info.copy(alpha = 0.12f),
                    badgeBorder = colors.info.copy(alpha = 0.48f),
                    badgeContent = colors.infoContainerForeground,
                )
            }

            RipDpiRouteAvailabilityStateRole.Restricted -> {
                routeStyle(
                    container = colors.restrictedContainer,
                    border = colors.restricted.copy(alpha = 0.52f),
                    content = colors.restrictedContainerForeground,
                    mutedContent = colors.restrictedContainerForeground,
                    marker = colors.restricted,
                    badgeContainer = colors.restricted.copy(alpha = 0.12f),
                    badgeBorder = colors.restricted.copy(alpha = 0.52f),
                    badgeContent = colors.restrictedContainerForeground,
                    alpha = DisabledAlpha,
                )
            }

            RipDpiRouteAvailabilityStateRole.Degraded -> {
                routeStyle(
                    container = colors.warningContainer,
                    border = colors.warning,
                    content = colors.warningContainerForeground,
                    mutedContent = colors.warningContainerForeground,
                    marker = colors.warning,
                    badgeContainer = colors.warning.copy(alpha = 0.12f),
                    badgeBorder = colors.warning,
                    badgeContent = colors.warningContainerForeground,
                )
            }

            RipDpiRouteAvailabilityStateRole.Failed -> {
                routeStyle(
                    container = colors.destructiveContainer,
                    border = colors.destructive,
                    content = colors.destructiveContainerForeground,
                    mutedContent = colors.destructiveContainerForeground,
                    marker = colors.destructive,
                    badgeContainer = colors.destructive.copy(alpha = 0.12f),
                    badgeBorder = colors.destructive,
                    badgeContent = colors.destructiveContainerForeground,
                )
            }

            else -> {
                error("Unexpected role in resolveAlertRouteStyle: $role")
            }
        }

    private fun routeStyle(
        container: Color,
        border: Color,
        content: Color = colors.foreground,
        mutedContent: Color = colors.mutedForeground,
        marker: Color = colors.foreground,
        badgeContainer: Color,
        badgeBorder: Color,
        badgeContent: Color = colors.foreground,
        borderWidth: Dp = 1.dp,
        alpha: Float = 1f,
    ): RipDpiRouteAvailabilityStateStyle =
        RipDpiRouteAvailabilityStateStyle(
            container = container,
            border = border,
            content = content,
            mutedContent = mutedContent,
            marker = marker,
            badgeContainer = badgeContainer,
            badgeBorder = badgeBorder,
            badgeContent = badgeContent,
            borderWidth = borderWidth,
            alpha = alpha,
        )
}
