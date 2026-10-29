package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightSuccess = Color(0xFF047857)
private val LightWarning = Color(0xFFB45309)
private val LightWarningForeground = Color(0xFFFFFFFF)
private val LightWarningContainer = Color(0xFFFFF7ED)
private val LightWarningContainerForeground = Color(0xFF1A1A1A)
private val LightInfo = Color(0xFF1D4ED8)
private val LightInfoForeground = Color(0xFFFFFFFF)
private val LightInfoContainer = Color(0xFFEFF6FF)
private val LightInfoContainerForeground = Color(0xFF1E3A8A)
private val LightRestricted = Color(0xFF6B7280)
private val LightRestrictedForeground = Color(0xFFFFFFFF)
private val LightRestrictedContainer = Color(0xFFF3F4F6)
private val LightRestrictedContainerForeground = Color(0xFF374151)

// Dark tone containers stay near-neutral, not a saturated block. The text keeps a muted tint of
// the tone, so a container with no icon or border (a metric pill) still reads as its tone.
private val DarkSuccess = Color(0xFF34D399)
private val DarkWarning = Color(0xFFFBBF24)
private val DarkWarningForeground = Color(0xFF1C1917)
private val DarkWarningContainer = Color(0xFF2B261D)
private val DarkWarningContainerForeground = Color(0xFFF3DDB0)
private val DarkInfo = Color(0xFF60A5FA)
private val DarkInfoForeground = Color(0xFF0A2342)
private val DarkInfoContainer = Color(0xFF1D242D)
private val DarkInfoContainerForeground = Color(0xFFCFE0F2)
private val DarkRestricted = Color(0xFF9CA3AF)
private val DarkRestrictedForeground = Color(0xFF111827)
private val DarkRestrictedContainer = Color(0xFF1F2937)
private val DarkRestrictedContainerForeground = Color(0xFFE5E7EB)

private val LightScrim = Color(0x99000000)
private val DarkScrim = Color(0xCC000000)

enum class RipDpiContrastLevel {
    Standard,
    Medium,
    High,
}

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
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
)

/**
 * Derives the RDS neutral and destructive roles from the Material scheme so contrast and (later) dynamic color
 * reach RDS components. Success, warning, info, restricted, and scrim stay RDS-owned.
 */
fun ripDpiExtendedColors(
    scheme: ColorScheme,
    isDark: Boolean,
): RipDpiExtendedColors =
    RipDpiExtendedColors(
        background = scheme.surface,
        foreground = scheme.onSurface,
        card = scheme.surfaceContainerLow,
        cardForeground = scheme.onSurface,
        muted = scheme.surfaceContainerHigh,
        mutedForeground = scheme.onSurfaceVariant,
        accent = scheme.secondaryContainer,
        accentForeground = scheme.onSecondaryContainer,
        border = scheme.outlineVariant,
        cardBorder = scheme.outlineVariant,
        inputBackground = scheme.surfaceContainerHighest,
        success = if (isDark) DarkSuccess else LightSuccess,
        warning = if (isDark) DarkWarning else LightWarning,
        warningForeground = if (isDark) DarkWarningForeground else LightWarningForeground,
        warningContainer = if (isDark) DarkWarningContainer else LightWarningContainer,
        warningContainerForeground = if (isDark) DarkWarningContainerForeground else LightWarningContainerForeground,
        destructive = scheme.error,
        destructiveForeground = scheme.onError,
        destructiveContainer = scheme.errorContainer,
        destructiveContainerForeground = scheme.onErrorContainer,
        info = if (isDark) DarkInfo else LightInfo,
        infoForeground = if (isDark) DarkInfoForeground else LightInfoForeground,
        infoContainer = if (isDark) DarkInfoContainer else LightInfoContainer,
        infoContainerForeground = if (isDark) DarkInfoContainerForeground else LightInfoContainerForeground,
        restricted = if (isDark) DarkRestricted else LightRestricted,
        restrictedForeground = if (isDark) DarkRestrictedForeground else LightRestrictedForeground,
        restrictedContainer = if (isDark) DarkRestrictedContainer else LightRestrictedContainer,
        restrictedContainerForeground =
            if (isDark) DarkRestrictedContainerForeground else LightRestrictedContainerForeground,
        divider = scheme.outlineVariant,
        hairline = scheme.outline,
        outline = scheme.outline,
        outlineVariant = scheme.outlineVariant,
        scrim = if (isDark) DarkScrim else LightScrim,
    )

val LightRipDpiExtendedColors = ripDpiExtendedColors(ripDpiLightColorScheme(), isDark = false)

val DarkRipDpiExtendedColors = ripDpiExtendedColors(ripDpiDarkColorScheme(), isDark = true)

internal val LocalRipDpiExtendedColors = staticCompositionLocalOf { LightRipDpiExtendedColors }
internal val LocalRipDpiContrastLevel = staticCompositionLocalOf { RipDpiContrastLevel.Standard }
