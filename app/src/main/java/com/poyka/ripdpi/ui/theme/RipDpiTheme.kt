package com.poyka.ripdpi.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
fun RipDpiTheme(
    themePreference: String = "system",
    content: @Composable () -> Unit,
) {
    val isDark =
        when (themePreference) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }

    LaunchedEffect(themePreference) {
        val mode =
            when (themePreference) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    val colorScheme = if (isDark) ripDpiDarkColorScheme() else ripDpiLightColorScheme()
    val extendedColors = if (isDark) DarkRipDpiExtendedColors else LightRipDpiExtendedColors
    val density = LocalDensity.current
    val screenWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
    val layout = ripDpiLayoutForWidth(screenWidthDp = screenWidthDp)
    val motion = rememberRipDpiMotion()

    CompositionLocalProvider(
        LocalRipDpiExtendedColors provides extendedColors,
        LocalRipDpiTextStyles provides RipDpiTypeScale,
        LocalRipDpiSpacing provides DefaultRipDpiSpacing,
        LocalRipDpiLayout provides layout,
        LocalRipDpiComponentMetrics provides DefaultRipDpiComponentMetrics,
        LocalRipDpiShapes provides DefaultRipDpiShapes,
        LocalRipDpiMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RipDpiTypography,
            shapes = RipDpiShapes,
            content = content,
        )
    }
}

object RipDpiThemeTokens {
    val colors: RipDpiExtendedColors
        @Composable get() = LocalRipDpiExtendedColors.current

    val type: RipDpiTextStyles
        @Composable get() = LocalRipDpiTextStyles.current

    val spacing: RipDpiSpacing
        @Composable get() = LocalRipDpiSpacing.current

    val layout: RipDpiLayout
        @Composable get() = LocalRipDpiLayout.current

    val components: RipDpiComponentMetrics
        @Composable get() = LocalRipDpiComponentMetrics.current

    val shapes: RipDpiShapeTokens
        @Composable get() = LocalRipDpiShapes.current

    val motion: RipDpiMotion
        @Composable get() = LocalRipDpiMotion.current
}
