package com.poyka.ripdpi.widget.theme

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider

// Light palette values sourced from ui/theme/Color.kt
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightSuccess = Color(0xFF047857)
private val LightError = Color(0xFFB91C1C)
private val LightWarning = Color(0xFFB45309)
private val LightMutedForeground = Color(0xFF575757)

// Dark palette values sourced from ui/theme/Color.kt
private val DarkOnPrimary = Color(0xFF121212)
private val DarkSuccess = Color(0xFF34D399)
private val DarkError = Color(0xFFF87171)
private val DarkWarning = Color(0xFFFBBF24)
private val DarkMutedForeground = Color(0xFFA3A3A3)

internal val GlanceOnPrimary = ColorProvider(day = LightOnPrimary, night = DarkOnPrimary)
internal val GlanceSuccess = ColorProvider(day = LightSuccess, night = DarkSuccess)
internal val GlanceError = ColorProvider(day = LightError, night = DarkError)
internal val GlanceWarning = ColorProvider(day = LightWarning, night = DarkWarning)
internal val GlanceMutedForeground = ColorProvider(day = LightMutedForeground, night = DarkMutedForeground)
