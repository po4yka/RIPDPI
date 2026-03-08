package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightBackground = Color(0xFFFAFAFA)
private val LightForeground = Color(0xFF1A1A1A)
private val LightSurfaceVariant = Color(0xFFF0F0F0)
private val LightOnSurfaceVariant = Color(0xFF444444)
private val LightCard = Color(0xFFFFFFFF)
private val LightCardForeground = Color(0xFF1A1A1A)
private val LightPrimary = Color(0xFF1A1A1A)
private val LightPrimaryForeground = Color(0xFFFFFFFF)
private val LightSecondary = Color(0xFFF0F0F0)
private val LightSecondaryForeground = Color(0xFF1A1A1A)
private val LightMuted = Color(0xFFF5F5F5)
private val LightMutedForeground = Color(0xFF757575)
private val LightAccent = Color(0xFFE8E8E8)
private val LightAccentForeground = Color(0xFF1A1A1A)
private val LightOutline = Color(0xFF757575)
private val LightOutlineVariant = Color(0xFFD0D0D0)
private val LightBorder = Color(0xFFE0E0E0)
private val LightInputBackground = Color(0xFFF5F5F5)
private val LightSuccess = Color(0xFF1A1A1A)
private val LightWarning = Color(0xFF757575)
private val LightDestructive = Color(0xFF444444)
private val LightDestructiveForeground = Color(0xFFFFFFFF)
private val LightDivider = Color(0xFFF0F0F0)
private val LightCardBorder = Color(0xFFE8E8E8)

private val DarkBackground = Color(0xFF121212)
private val DarkForeground = Color(0xFFE8E8E8)
private val DarkSurfaceVariant = Color(0xFF222222)
private val DarkOnSurfaceVariant = Color(0xFFCCCCCC)
private val DarkCard = Color(0xFF1A1A1A)
private val DarkCardForeground = Color(0xFFE8E8E8)
private val DarkPrimary = Color(0xFFE8E8E8)
private val DarkPrimaryForeground = Color(0xFF121212)
private val DarkSecondary = Color(0xFF222222)
private val DarkSecondaryForeground = Color(0xFFE8E8E8)
private val DarkMuted = Color(0xFF1E1E1E)
private val DarkMutedForeground = Color(0xFF888888)
private val DarkAccent = Color(0xFF2A2A2A)
private val DarkAccentForeground = Color(0xFFE8E8E8)
private val DarkOutline = Color(0xFF616161)
private val DarkOutlineVariant = Color(0xFF333333)
private val DarkBorder = Color(0xFF2A2A2A)
private val DarkInputBackground = Color(0xFF1E1E1E)
private val DarkSuccess = Color(0xFFE8E8E8)
private val DarkWarning = Color(0xFF616161)
private val DarkDestructive = Color(0xFF888888)
private val DarkDestructiveForeground = Color(0xFF121212)
private val DarkDivider = Color(0xFF222222)
private val DarkCardBorder = Color(0xFF2A2A2A)

val RipDpiHairlineColor = Color(0xFF666666)

@Immutable
data class RipDpiExtendedColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val border: Color,
    val cardBorder: Color,
    val inputBackground: Color,
    val success: Color,
    val warning: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val divider: Color,
    val hairline: Color,
)

val LightRipDpiExtendedColors = RipDpiExtendedColors(
    background = LightBackground,
    foreground = LightForeground,
    card = LightCard,
    cardForeground = LightCardForeground,
    muted = LightMuted,
    mutedForeground = LightMutedForeground,
    accent = LightAccent,
    accentForeground = LightAccentForeground,
    border = LightBorder,
    cardBorder = LightCardBorder,
    inputBackground = LightInputBackground,
    success = LightSuccess,
    warning = LightWarning,
    destructive = LightDestructive,
    destructiveForeground = LightDestructiveForeground,
    divider = LightDivider,
    hairline = RipDpiHairlineColor,
)

val DarkRipDpiExtendedColors = RipDpiExtendedColors(
    background = DarkBackground,
    foreground = DarkForeground,
    card = DarkCard,
    cardForeground = DarkCardForeground,
    muted = DarkMuted,
    mutedForeground = DarkMutedForeground,
    accent = DarkAccent,
    accentForeground = DarkAccentForeground,
    border = DarkBorder,
    cardBorder = DarkCardBorder,
    inputBackground = DarkInputBackground,
    success = DarkSuccess,
    warning = DarkWarning,
    destructive = DarkDestructive,
    destructiveForeground = DarkDestructiveForeground,
    divider = DarkDivider,
    hairline = RipDpiHairlineColor,
)

fun ripDpiLightColorScheme(): ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightPrimaryForeground,
    primaryContainer = LightAccent,
    onPrimaryContainer = LightAccentForeground,
    secondary = LightSecondary,
    onSecondary = LightSecondaryForeground,
    secondaryContainer = LightMuted,
    onSecondaryContainer = LightForeground,
    tertiary = LightAccent,
    onTertiary = LightAccentForeground,
    tertiaryContainer = LightSurfaceVariant,
    onTertiaryContainer = LightForeground,
    error = LightDestructive,
    onError = LightDestructiveForeground,
    errorContainer = LightDestructive,
    onErrorContainer = LightDestructiveForeground,
    background = LightBackground,
    onBackground = LightForeground,
    surface = LightCard,
    onSurface = LightCardForeground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color(0x99000000),
    inverseSurface = DarkCard,
    inverseOnSurface = DarkForeground,
    inversePrimary = DarkPrimary,
    surfaceTint = LightPrimary,
)

fun ripDpiDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkPrimaryForeground,
    primaryContainer = DarkAccent,
    onPrimaryContainer = DarkAccentForeground,
    secondary = DarkSecondary,
    onSecondary = DarkSecondaryForeground,
    secondaryContainer = DarkMuted,
    onSecondaryContainer = DarkForeground,
    tertiary = DarkAccent,
    onTertiary = DarkAccentForeground,
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = DarkForeground,
    error = DarkDestructive,
    onError = DarkDestructiveForeground,
    errorContainer = DarkDestructive,
    onErrorContainer = DarkDestructiveForeground,
    background = DarkBackground,
    onBackground = DarkForeground,
    surface = DarkCard,
    onSurface = DarkCardForeground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color(0xCC000000),
    inverseSurface = LightCard,
    inverseOnSurface = LightForeground,
    inversePrimary = LightPrimary,
    surfaceTint = DarkPrimary,
)

internal val LocalRipDpiExtendedColors = staticCompositionLocalOf { LightRipDpiExtendedColors }
