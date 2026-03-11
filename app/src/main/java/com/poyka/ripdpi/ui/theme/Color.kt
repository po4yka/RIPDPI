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
private val LightMutedForeground = Color(0xFF666666)
private val LightAccent = Color(0xFFE8E8E8)
private val LightAccentForeground = Color(0xFF1A1A1A)
private val LightOutline = Color(0xFF757575)
private val LightOutlineVariant = Color(0xFFD0D0D0)
private val LightBorder = Color(0xFFE0E0E0)
private val LightInputBackground = Color(0xFFF5F5F5)
private val LightSuccess = Color(0xFF047857)
private val LightWarning = Color(0xFFB45309)
private val LightWarningForeground = Color(0xFFFFFFFF)
private val LightWarningContainer = Color(0xFFFFF7ED)
private val LightWarningContainerForeground = Color(0xFF1A1A1A)
private val LightDestructive = Color(0xFFB91C1C)
private val LightDestructiveForeground = Color(0xFFFFFFFF)
private val LightDestructiveContainer = Color(0xFFFEF2F2)
private val LightDestructiveContainerForeground = Color(0xFF7F1D1D)
private val LightInfo = Color(0xFF1D4ED8)
private val LightInfoForeground = Color(0xFFFFFFFF)
private val LightInfoContainer = Color(0xFFEFF6FF)
private val LightInfoContainerForeground = Color(0xFF1E3A8A)
private val LightRestricted = Color(0xFF6B7280)
private val LightRestrictedForeground = Color(0xFFFFFFFF)
private val LightRestrictedContainer = Color(0xFFF3F4F6)
private val LightRestrictedContainerForeground = Color(0xFF374151)
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
private val DarkSuccess = Color(0xFF34D399)
private val DarkWarning = Color(0xFFFBBF24)
private val DarkWarningForeground = Color(0xFF1C1917)
private val DarkWarningContainer = Color(0xFF451A03)
private val DarkWarningContainerForeground = Color(0xFFFDE68A)
private val DarkDestructive = Color(0xFFF87171)
private val DarkDestructiveForeground = Color(0xFF210A0A)
private val DarkDestructiveContainer = Color(0xFF450A0A)
private val DarkDestructiveContainerForeground = Color(0xFFFECACA)
private val DarkInfo = Color(0xFF60A5FA)
private val DarkInfoForeground = Color(0xFF0A2342)
private val DarkInfoContainer = Color(0xFF0C4A6E)
private val DarkInfoContainerForeground = Color(0xFFDBEAFE)
private val DarkRestricted = Color(0xFF9CA3AF)
private val DarkRestrictedForeground = Color(0xFF111827)
private val DarkRestrictedContainer = Color(0xFF1F2937)
private val DarkRestrictedContainerForeground = Color(0xFFE5E7EB)
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
    val warningForeground: Color,
    val warningContainer: Color,
    val warningContainerForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val destructiveContainer: Color,
    val destructiveContainerForeground: Color,
    val info: Color,
    val infoForeground: Color,
    val infoContainer: Color,
    val infoContainerForeground: Color,
    val restricted: Color,
    val restrictedForeground: Color,
    val restrictedContainer: Color,
    val restrictedContainerForeground: Color,
    val divider: Color,
    val hairline: Color,
)

val LightRipDpiExtendedColors =
    RipDpiExtendedColors(
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
        warningForeground = LightWarningForeground,
        warningContainer = LightWarningContainer,
        warningContainerForeground = LightWarningContainerForeground,
        destructive = LightDestructive,
        destructiveForeground = LightDestructiveForeground,
        destructiveContainer = LightDestructiveContainer,
        destructiveContainerForeground = LightDestructiveContainerForeground,
        info = LightInfo,
        infoForeground = LightInfoForeground,
        infoContainer = LightInfoContainer,
        infoContainerForeground = LightInfoContainerForeground,
        restricted = LightRestricted,
        restrictedForeground = LightRestrictedForeground,
        restrictedContainer = LightRestrictedContainer,
        restrictedContainerForeground = LightRestrictedContainerForeground,
        divider = LightDivider,
        hairline = RipDpiHairlineColor,
    )

val DarkRipDpiExtendedColors =
    RipDpiExtendedColors(
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
        warningForeground = DarkWarningForeground,
        warningContainer = DarkWarningContainer,
        warningContainerForeground = DarkWarningContainerForeground,
        destructive = DarkDestructive,
        destructiveForeground = DarkDestructiveForeground,
        destructiveContainer = DarkDestructiveContainer,
        destructiveContainerForeground = DarkDestructiveContainerForeground,
        info = DarkInfo,
        infoForeground = DarkInfoForeground,
        infoContainer = DarkInfoContainer,
        infoContainerForeground = DarkInfoContainerForeground,
        restricted = DarkRestricted,
        restrictedForeground = DarkRestrictedForeground,
        restrictedContainer = DarkRestrictedContainer,
        restrictedContainerForeground = DarkRestrictedContainerForeground,
        divider = DarkDivider,
        hairline = RipDpiHairlineColor,
    )

fun ripDpiLightColorScheme(): ColorScheme =
    lightColorScheme(
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
        errorContainer = LightDestructiveContainer,
        onErrorContainer = LightDestructive,
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

fun ripDpiDarkColorScheme(): ColorScheme =
    darkColorScheme(
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
        errorContainer = DarkDestructiveContainer,
        onErrorContainer = DarkDestructive,
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
