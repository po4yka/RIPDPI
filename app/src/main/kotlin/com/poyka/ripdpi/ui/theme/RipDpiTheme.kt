package com.poyka.ripdpi.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.intl.LocaleList

@Composable
fun RipDpiTheme(
    themePreference: String = "system",
    contrastLevel: RipDpiContrastLevel = RipDpiContrastLevel.Standard,
    hapticFeedbackDisabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark =
        when (themePreference) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }

    if (!LocalInspectionMode.current) {
        LaunchedEffect(themePreference) {
            val mode =
                when (themePreference) {
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    val languageTags = LocalConfiguration.current.locales.toLanguageTags()
    val type = remember(languageTags) { RipDpiTypeScale.withLocaleList(LocaleList(languageTags)) }
    val typography = remember(type) { ripDpiTypography(type) }
    val colorScheme = if (isDark) ripDpiDarkColorScheme() else ripDpiLightColorScheme()
    val baseColors = if (isDark) DarkRipDpiExtendedColors else LightRipDpiExtendedColors
    val extendedColors = baseColors.adjustForContrast(contrastLevel, isDark)
    val density = LocalDensity.current
    val screenWidthDp =
        with(density) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
                .value
                .toInt()
        }
    val layout = ripDpiLayoutForWidth(screenWidthDp = screenWidthDp)
    val baseMotion = rememberRipDpiMotion()
    val inheritedHapticFeedback = LocalHapticFeedback.current
    val motion =
        remember(baseMotion, hapticFeedbackDisabled) {
            baseMotion.copy(hapticsEnabled = baseMotion.hapticsEnabled && !hapticFeedbackDisabled)
        }
    val surfaces =
        ripDpiSurfaceTokens(
            colors = extendedColors,
            colorScheme = colorScheme,
        )
    val state =
        ripDpiStateTokens(
            colors = extendedColors,
            colorScheme = colorScheme,
            components = DefaultRipDpiComponents,
            motion = motion,
        )

    CompositionLocalProvider(
        LocalHapticFeedback provides
            if (motion.hapticsEnabled) inheritedHapticFeedback else DisabledHapticFeedback,
        LocalRipDpiExtendedColors provides extendedColors,
        LocalRipDpiContrastLevel provides contrastLevel,
        LocalRipDpiTextStyles provides type,
        LocalRipDpiSpacing provides DefaultRipDpiSpacing,
        LocalRipDpiLayout provides layout,
        LocalRipDpiComponents provides DefaultRipDpiComponents,
        LocalRipDpiShapes provides DefaultRipDpiShapes,
        LocalRipDpiMotion provides motion,
        LocalReducedMotion provides motion.reducedMotion,
        LocalRipDpiSurfaceTokens provides surfaces,
        LocalRipDpiStateTokens provides state,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = RipDpiShapes,
            content = content,
        )
    }
}

private object DisabledHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

object RipDpiThemeTokens {
    val styles: RipDpiComponentStyles
        get() = RipDpiComponentStyles

    val colors: RipDpiExtendedColors
        @Composable get() = LocalRipDpiExtendedColors.current

    val type: RipDpiTextStyles
        @Composable get() = LocalRipDpiTextStyles.current

    val spacing: RipDpiSpacing
        @Composable get() = LocalRipDpiSpacing.current

    val layout: RipDpiLayout
        @Composable get() = LocalRipDpiLayout.current

    val components: RipDpiComponents
        @Composable get() = LocalRipDpiComponents.current

    val shapes: RipDpiShapeTokens
        @Composable get() = LocalRipDpiShapes.current

    val motion: RipDpiMotion
        @Composable get() = LocalRipDpiMotion.current

    val surfaces: RipDpiSurfaceTokens
        @Composable get() = LocalRipDpiSurfaceTokens.current

    val surfaceRoles: RipDpiSurfaceRoleMappings
        get() = DefaultRipDpiSurfaceRoleMappings

    val state: RipDpiStateTokens
        @Composable get() = LocalRipDpiStateTokens.current

    val stateRoles: RipDpiStateRoleMappings
        get() = DefaultRipDpiStateRoleMappings

    val contrastLevel: RipDpiContrastLevel
        @Composable get() = LocalRipDpiContrastLevel.current
}
