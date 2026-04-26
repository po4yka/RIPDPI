package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val PrimaryPressedLerp = 0.35f
private const val SecondaryPressedLerp = 0.5f
private const val DestructivePressedLerp = 0.3f
private const val IconButtonPressedLerp = 0.25f
private const val DisabledAlpha = 0.38f
private const val LoadingContentAlpha = 0.92f
private const val SwitchLuminanceMidpoint = 0.5f
private const val SwitchTrackDarkBlend = 0.25f
private const val SwitchTrackLightBlend = 0.16f
private const val SwitchThumbDarkBlend = 0.5f
private const val SwitchPressedCheckedTrackBlend = 0.18f
private const val SwitchPressedDarkTrackBlend = 0.32f
private const val SwitchPressedLightTrackBlend = 0.22f
private const val ActuatorEngagingRailBlend = 0.08f

enum class RipDpiButtonStateRole {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

enum class RipDpiIconButtonStateRole {
    Ghost,
    Tonal,
    Filled,
    Outline,
}

enum class RipDpiSettingsRowStateRole {
    Default,
    Tonal,
    Selected,
}

enum class RipDpiBannerStateRole {
    Warning,
    Error,
    Info,
    Restricted,
}

enum class RipDpiActuatorStateRole {
    Open,
    Engaging,
    Locked,
    Degraded,
    Fault,
}

enum class RipDpiActuatorStageRole {
    Pending,
    Active,
    Complete,
    Warning,
    Failed,
}

enum class RipDpiRouteAvailabilityStateRole {
    Available,
    Selected,
    Configured,
    NeedsSetup,
    Restricted,
    Active,
    Degraded,
    Failed,
}

@Immutable
data class RipDpiButtonStateStyle(
    val container: Color,
    val content: Color,
    val border: Color,
    val borderWidth: Dp,
    val cornerRadius: Dp,
    val scale: Float,
    val contentAlpha: Float,
)

@Immutable
data class RipDpiIconButtonStateStyle(
    val container: Color,
    val content: Color,
    val border: Color,
    val borderWidth: Dp,
    val scale: Float,
)

@Immutable
data class RipDpiTextFieldStateStyle(
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
    val content: Color,
    val label: Color,
    val helper: Color,
    val placeholder: Color,
    val alpha: Float,
)

@Immutable
data class RipDpiChipStateStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val cornerRadius: Dp,
    val scale: Float,
    val alpha: Float,
)

@Immutable
data class RipDpiSwitchStateStyle(
    val track: Color,
    val thumb: Color,
    val alpha: Float,
)

@Immutable
data class RipDpiSettingsRowStateStyle(
    val container: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val value: Color,
    val leadingBadgeContainer: Color,
    val leadingBadgeIcon: Color,
)

@Immutable
data class RipDpiBannerStateStyle(
    val container: Color,
    val border: Color,
    val iconContainer: Color,
    val icon: Color,
    val title: Color,
    val message: Color,
)

@Immutable
data class RipDpiActuatorStateStyle(
    val rail: Color,
    val railBorder: Color,
    val carriage: Color,
    val carriageContent: Color,
    val terminal: Color,
    val terminalBorder: Color,
    val label: Color,
    val routeLabel: Color,
    val slotContent: Color,
)

@Immutable
data class RipDpiActuatorStageStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val striped: Boolean,
    val pulsing: Boolean,
)

@Immutable
data class RipDpiRouteAvailabilityStateStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val mutedContent: Color,
    val marker: Color,
    val badgeContainer: Color,
    val badgeBorder: Color,
    val badgeContent: Color,
    val borderWidth: Dp,
    val alpha: Float,
)

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
                } else if (role ==
                    RipDpiIconButtonStateRole.Outline
                ) {
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
            else -> colors.foreground
        }
}

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
data class RipDpiSwitchStateTokens(
    private val colors: RipDpiExtendedColors,
    private val colorScheme: ColorScheme,
) {
    fun resolve(
        checked: Boolean,
        enabled: Boolean,
        isPressed: Boolean,
    ): RipDpiSwitchStateStyle {
        val isDark = colorScheme.background.luminance() < SwitchLuminanceMidpoint
        return RipDpiSwitchStateStyle(
            track =
                switchTrackColor(
                    backgroundColor = colors.background,
                    foregroundColor = colors.foreground,
                    onSurfaceVariant = colorScheme.onSurfaceVariant,
                    checked = checked,
                    isPressed = isPressed,
                    isDark = isDark,
                ),
            thumb =
                switchThumbColor(
                    backgroundColor = colors.background,
                    foregroundColor = colors.foreground,
                    checked = checked,
                    isDark = isDark,
                ),
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

@Immutable
data class RipDpiBannerStateTokens(
    private val colors: RipDpiExtendedColors,
) {
    fun resolve(role: RipDpiBannerStateRole): RipDpiBannerStateStyle =
        when (role) {
            RipDpiBannerStateRole.Warning -> {
                RipDpiBannerStateStyle(
                    container = colors.warningContainer,
                    border = colors.warning.copy(alpha = 0.52f),
                    iconContainer = colors.warning.copy(alpha = 0.12f),
                    icon = colors.warning,
                    title = colors.warningContainerForeground,
                    message = colors.warningContainerForeground,
                )
            }

            RipDpiBannerStateRole.Error -> {
                RipDpiBannerStateStyle(
                    container = colors.destructiveContainer,
                    border = colors.destructive.copy(alpha = 0.52f),
                    iconContainer = colors.destructive.copy(alpha = 0.12f),
                    icon = colors.destructive,
                    title = colors.destructiveContainerForeground,
                    message = colors.destructiveContainerForeground,
                )
            }

            RipDpiBannerStateRole.Info -> {
                RipDpiBannerStateStyle(
                    container = colors.infoContainer,
                    border = colors.info.copy(alpha = 0.48f),
                    iconContainer = colors.info.copy(alpha = 0.12f),
                    icon = colors.info,
                    title = colors.infoContainerForeground,
                    message = colors.infoContainerForeground,
                )
            }

            RipDpiBannerStateRole.Restricted -> {
                RipDpiBannerStateStyle(
                    container = colors.restrictedContainer,
                    border = colors.restricted.copy(alpha = 0.52f),
                    iconContainer = colors.restricted.copy(alpha = 0.12f),
                    icon = colors.restricted,
                    title = colors.restrictedContainerForeground,
                    message = colors.restrictedContainerForeground,
                )
            }
        }
}

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
                    terminalBorder = colors.border,
                )
            }

            RipDpiActuatorStateRole.Engaging -> {
                baseStateStyle(
                    rail = lerp(colors.inputBackground, colors.foreground, ActuatorEngagingRailBlend),
                    railBorder = colors.outlineVariant,
                    carriage = colors.foreground,
                    carriageContent = colors.background,
                    terminal = colors.accent,
                    terminalBorder = colors.outlineVariant,
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

    private fun baseStateStyle(
        rail: Color = colors.inputBackground,
        railBorder: Color = colors.border,
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

@Immutable
data class RipDpiStateTokens(
    val button: RipDpiButtonStateTokens,
    val iconButton: RipDpiIconButtonStateTokens,
    val textField: RipDpiTextFieldStateTokens,
    val chip: RipDpiChipStateTokens,
    val switch: RipDpiSwitchStateTokens,
    val settingsRow: RipDpiSettingsRowStateTokens,
    val banner: RipDpiBannerStateTokens,
    val actuator: RipDpiActuatorStateTokens,
    val route: RipDpiRouteStateTokens,
)

fun ripDpiStateTokens(
    colors: RipDpiExtendedColors,
    colorScheme: ColorScheme,
    components: RipDpiComponents,
    motion: RipDpiMotion,
): RipDpiStateTokens =
    RipDpiStateTokens(
        button = RipDpiButtonStateTokens(colors, colorScheme, components.shapes, motion),
        iconButton = RipDpiIconButtonStateTokens(colors, colorScheme, motion),
        textField = RipDpiTextFieldStateTokens(colors),
        chip = RipDpiChipStateTokens(colors, colorScheme, components.shapes, motion),
        switch = RipDpiSwitchStateTokens(colors, colorScheme),
        settingsRow = RipDpiSettingsRowStateTokens(colors),
        banner = RipDpiBannerStateTokens(colors),
        actuator = RipDpiActuatorStateTokens(colors, colorScheme),
        route = RipDpiRouteStateTokens(colors, colorScheme),
    )

private fun switchTrackColor(
    backgroundColor: Color,
    foregroundColor: Color,
    onSurfaceVariant: Color,
    checked: Boolean,
    isPressed: Boolean,
    isDark: Boolean,
): Color {
    val uncheckedTrack =
        if (isDark) {
            lerp(backgroundColor, foregroundColor, SwitchTrackDarkBlend)
        } else {
            lerp(backgroundColor, foregroundColor, SwitchTrackLightBlend)
        }

    val base =
        if (checked) {
            foregroundColor
        } else {
            uncheckedTrack
        }

    if (!isPressed) {
        return base
    }

    return if (checked) {
        lerp(base, onSurfaceVariant, SwitchPressedCheckedTrackBlend)
    } else if (isDark) {
        lerp(base, foregroundColor, SwitchPressedDarkTrackBlend)
    } else {
        lerp(base, foregroundColor, SwitchPressedLightTrackBlend)
    }
}

private fun switchThumbColor(
    backgroundColor: Color,
    foregroundColor: Color,
    checked: Boolean,
    isDark: Boolean,
): Color =
    if (checked) {
        backgroundColor
    } else if (isDark) {
        lerp(backgroundColor, foregroundColor, SwitchThumbDarkBlend)
    } else {
        foregroundColor
    }

internal val LocalRipDpiStateTokens =
    staticCompositionLocalOf {
        ripDpiStateTokens(
            colors = LightRipDpiExtendedColors,
            colorScheme = ripDpiLightColorScheme(),
            components = DefaultRipDpiComponents,
            motion = DefaultRipDpiMotion,
        )
    }
