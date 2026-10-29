package com.poyka.ripdpi.widget.theme

import androidx.glance.color.ColorProvider
import com.poyka.ripdpi.ui.theme.DarkRipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.ripDpiDarkColorScheme
import com.poyka.ripdpi.ui.theme.ripDpiLightColorScheme

// Widgets read the same standard-contrast schemes and derived RDS roles as the app theme; no hex lives here.
private val Light = ripDpiLightColorScheme()
private val Dark = ripDpiDarkColorScheme()
private val LightRds = LightRipDpiExtendedColors
private val DarkRds = DarkRipDpiExtendedColors

internal val GlanceOnPrimary = ColorProvider(day = Light.onPrimary, night = Dark.onPrimary)
internal val GlanceSuccess = ColorProvider(day = LightRds.success, night = DarkRds.success)
internal val GlanceError = ColorProvider(day = LightRds.destructive, night = DarkRds.destructive)
internal val GlanceWarning = ColorProvider(day = LightRds.warning, night = DarkRds.warning)
internal val GlanceMutedForeground = ColorProvider(day = LightRds.mutedForeground, night = DarkRds.mutedForeground)
