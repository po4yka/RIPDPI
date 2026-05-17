package com.poyka.ripdpi.widget.theme

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders
import com.poyka.ripdpi.ui.theme.ripDpiDarkColorScheme
import com.poyka.ripdpi.ui.theme.ripDpiLightColorScheme

private val ripDpiGlanceColorProviders =
    ColorProviders(
        light = ripDpiLightColorScheme(),
        dark = ripDpiDarkColorScheme(),
    )

@Composable
internal fun RipDpiGlanceTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = ripDpiGlanceColorProviders) {
        content()
    }
}
