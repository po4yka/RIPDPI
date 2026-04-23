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
        val containerAndContent =
            if (!interactive) {
                when (role) {
                    RipDpiButtonStateRole.Primary,
                    RipDpiButtonStateRole.Secondary,
                    RipDpiButtonStateRole.Destructive,
                    -> {
                        colors.border to colors.mutedForeground
                    }

                    RipDpiButtonStateRole.Outline,
                    RipDpiButtonStateRole.Ghost,
                    -> {
                        Color.Transparent to colors.mutedForeground
                    }
                }
            } else {
                val pressedOverlay = colorScheme.onSurfaceVariant
                when (role) {
                    RipDpiButtonStateRole.Primary -> {
                        val base = colors.foreground
                        (if (isPressed) lerp(base, pressedOverlay, PrimaryPressedLerp) else base) to colors.background
                    }

                    RipDpiButtonStateRole.Secondary -> {
                        val base = colorScheme.secondary
                        (if (isPressed) lerp(base, colorScheme.surfaceVariant, SecondaryPressedLerp) else base) to
                            colorScheme.onSecondary
                    }

                    RipDpiButtonStateRole.Outline -> {
                        (if (isPressed) colorScheme.surfaceVariant else Color.Transparent) to colors.foreground
                    }

                    RipDpiButtonStateRole.Ghost -> {
                        (if (isPressed) colorScheme.surfaceVariant else Color.Transparent) to colors.foreground
                    }

                    RipDpiButtonStateRole.Destructive -> {
                        (
                            if (isPressed) {
                                lerp(colors.destructive, pressedOverlay, DestructivePressedLerp)
                            } else {
                                colors.destructive
                            }
                        ) to colors.destructiveForeground
                    }
                }
            }

        val borderWidth =
            when {
                !interactive && role == RipDpiButtonStateRole.Ghost -> 0.dp
                isFocused -> 2.dp
                role == RipDpiButtonStateRole.Outline -> 1.dp
                else -> 0.dp
            }
        val borderColor =
            when {
                !interactive && role == RipDpiButtonStateRole.Outline -> colors.border
                isFocused -> colors.outline
                role == RipDpiButtonStateRole.Outline -> colors.border
                else -> Color.Transparent
            }

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
        val baseContainer =
            when (role) {
                RipDpiIconButtonStateRole.Ghost -> Color.Transparent
                RipDpiIconButtonStateRole.Tonal -> if (selected) colorScheme.surfaceVariant else colors.accent
                RipDpiIconButtonStateRole.Filled -> colors.foreground
                RipDpiIconButtonStateRole.Outline -> Color.Transparent
            }
        val container =
            when {
                !interactive && role == RipDpiIconButtonStateRole.Ghost -> Color.Transparent
                !interactive -> colors.border
                isPressed -> lerp(baseContainer, colorScheme.onSurfaceVariant, IconButtonPressedLerp)
                else -> baseContainer
            }
        val content =
            when {
                !interactive -> colors.mutedForeground
                role == RipDpiIconButtonStateRole.Filled -> colors.background
                else -> colors.foreground
            }
        val border =
            when {
                isFocused -> colors.outline
                role == RipDpiIconButtonStateRole.Outline -> colors.border
                else -> Color.Transparent
            }
        val borderWidth =
            when {
                isFocused -> 2.dp
                role == RipDpiIconButtonStateRole.Outline -> 1.dp
                else -> 0.dp
            }
        return RipDpiIconButtonStateStyle(
            container = container,
            content = content,
            border = border,
            borderWidth = borderWidth,
            scale = if (isPressed && interactive) motion.pressScale else 1f,
        )
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
data class RipDpiStateTokens(
    val button: RipDpiButtonStateTokens,
    val iconButton: RipDpiIconButtonStateTokens,
    val textField: RipDpiTextFieldStateTokens,
    val chip: RipDpiChipStateTokens,
    val switch: RipDpiSwitchStateTokens,
    val settingsRow: RipDpiSettingsRowStateTokens,
    val banner: RipDpiBannerStateTokens,
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
