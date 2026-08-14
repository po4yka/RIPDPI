package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private const val ActuatorEngagingRailBlend = 0.08f

@Immutable
data class RipDpiActuatorStateTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
) {
    fun resolve(role: RipDpiActuatorStateRole): RipDpiActuatorStateStyle =
        when (role) {
            RipDpiActuatorStateRole.Open -> {
                baseStateStyle(
                    carriage = colorScheme.surface,
                    carriageContent = colors.foreground,
                    terminal = colors.inputBackground,
                    terminalBorder = colors.mutedForeground,
                )
            }

            RipDpiActuatorStateRole.Engaging -> {
                baseStateStyle(
                    rail = lerp(colors.inputBackground, colors.foreground, ActuatorEngagingRailBlend),
                    railBorder = colors.outline,
                    carriage = colors.foreground,
                    carriageContent = colors.background,
                    terminal = colors.accent,
                    terminalBorder = colors.mutedForeground,
                )
            }

            RipDpiActuatorStateRole.Locked -> {
                baseStateStyle(
                    rail = colors.accent,
                    railBorder = colors.foreground,
                    carriage = colors.foreground,
                    carriageContent = colors.background,
                    terminal = colors.foreground,
                    terminalBorder = colors.foreground,
                    slotContent = colors.background,
                )
            }

            RipDpiActuatorStateRole.Degraded -> {
                baseStateStyle(
                    rail = colors.warningContainer,
                    railBorder = colors.warning,
                    carriage = colors.foreground,
                    carriageContent = colors.background,
                    terminal = colors.foreground,
                    terminalBorder = colors.warning,
                    slotContent = colors.background,
                    routeLabel = colors.warning,
                )
            }

            RipDpiActuatorStateRole.Fault -> {
                baseStateStyle(
                    rail = colors.destructiveContainer,
                    railBorder = colors.destructive,
                    carriage = colorScheme.surface,
                    carriageContent = colors.destructive,
                    terminal = colors.inputBackground,
                    terminalBorder = colors.destructive,
                    routeLabel = colors.destructive,
                )
            }
        }

    fun resolveStage(role: RipDpiActuatorStageRole): RipDpiActuatorStageStyle =
        when (role) {
            RipDpiActuatorStageRole.Pending -> {
                RipDpiActuatorStageStyle(
                    container = colors.inputBackground,
                    border = colors.border,
                    content = colors.mutedForeground,
                    striped = false,
                    pulsing = false,
                )
            }

            RipDpiActuatorStageRole.Active -> {
                RipDpiActuatorStageStyle(
                    container = colors.foreground,
                    border = colors.foreground,
                    content = colors.background,
                    striped = false,
                    pulsing = true,
                )
            }

            RipDpiActuatorStageRole.Complete -> {
                RipDpiActuatorStageStyle(
                    container = colors.accent,
                    border = colors.foreground,
                    content = colors.foreground,
                    striped = false,
                    pulsing = false,
                )
            }

            RipDpiActuatorStageRole.Warning -> {
                RipDpiActuatorStageStyle(
                    container = colors.warningContainer,
                    border = colors.warning,
                    content = colors.warningContainerForeground,
                    striped = true,
                    pulsing = true,
                )
            }

            RipDpiActuatorStageRole.Failed -> {
                RipDpiActuatorStageStyle(
                    container = colors.destructiveContainer,
                    border = colors.destructive,
                    content = colors.destructiveContainerForeground,
                    striped = true,
                    pulsing = false,
                )
            }
        }

    /**
     * Boundaries carry WCAG 1.4.11's 3:1 non-text contrast requirement, so they
     * cannot use the hairline `border` token, which sits near 1.3:1 against the
     * page in both themes and only suits decorative separators.
     *
     * The rail's own outline is measured against the page and uses `outline`
     * (4.4:1 light, 3.0:1 dark). The terminal slot sits on the raised track
     * rather than the page, so it needs one step more weight: `outline` would
     * reach only 2.7:1 there in dark, while `mutedForeground` holds 6.6:1 in
     * both themes.
     */
    private fun baseStateStyle(
        rail: Color = colors.inputBackground,
        railBorder: Color = colors.outline,
        carriage: Color,
        carriageContent: Color,
        terminal: Color,
        terminalBorder: Color,
        label: Color = colors.mutedForeground,
        routeLabel: Color = colors.foreground,
        slotContent: Color = colors.foreground,
    ): RipDpiActuatorStateStyle =
        RipDpiActuatorStateStyle(
            rail = rail,
            railBorder = railBorder,
            carriage = carriage,
            carriageContent = carriageContent,
            terminal = terminal,
            terminalBorder = terminalBorder,
            label = label,
            routeLabel = routeLabel,
            slotContent = slotContent,
        )
}
