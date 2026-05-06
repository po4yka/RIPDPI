package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

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

internal val LocalRipDpiStateTokens =
    staticCompositionLocalOf {
        ripDpiStateTokens(
            colors = LightRipDpiExtendedColors,
            colorScheme = ripDpiLightColorScheme(),
            components = DefaultRipDpiComponents,
            motion = DefaultRipDpiMotion,
        )
    }
