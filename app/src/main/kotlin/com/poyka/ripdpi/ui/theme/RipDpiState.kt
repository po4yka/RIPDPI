package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class RipDpiStateTokens(
    val settingsRow: RipDpiSettingsRowStateTokens,
    val banner: RipDpiBannerStateTokens,
    val actuator: RipDpiActuatorStateTokens,
    val route: RipDpiRouteStateTokens,
)

fun ripDpiStateTokens(
    colors: RipDpiExtendedColors,
    colorScheme: ColorScheme,
): RipDpiStateTokens =
    RipDpiStateTokens(
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
        )
    }
