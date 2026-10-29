package com.poyka.ripdpi.widget.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import com.poyka.ripdpi.ui.theme.DarkRipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.LightRipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.ripDpiDarkColorScheme
import com.poyka.ripdpi.ui.theme.ripDpiLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Widget colors must resolve to the app theme's standard-contrast Material scheme and derived RDS roles in both day and
 * night modes (widget-theme parity rule in `.claude/rules/rds-spec.md`).
 */
@RunWith(RobolectricTestRunner::class)
class GlanceWidgetThemeParityTest {
    @Test
    fun `glance day palette resolves to the light app theme`() {
        val scheme = ripDpiLightColorScheme()
        assertParity(modeContext(night = false), scheme.onPrimary, LightRipDpiExtendedColors)
    }

    @Test
    fun `glance night palette resolves to the dark app theme`() {
        val scheme = ripDpiDarkColorScheme()
        assertParity(modeContext(night = true), scheme.onPrimary, DarkRipDpiExtendedColors)
    }

    private fun assertParity(
        context: Context,
        onPrimary: Color,
        rds: RipDpiExtendedColors,
    ) {
        val expected =
            mapOf(
                "OnPrimary" to (GlanceOnPrimary to onPrimary),
                "Success" to (GlanceSuccess to rds.success),
                "Error" to (GlanceError to rds.destructive),
                "Warning" to (GlanceWarning to rds.warning),
                "MutedForeground" to (GlanceMutedForeground to rds.mutedForeground),
            )
        expected.forEach { (label, pair) ->
            val (provider: ColorProvider, app: Color) = pair
            assertEquals(label, app, provider.getColor(context))
        }
    }

    private fun modeContext(night: Boolean): Context {
        val config = Configuration(RuntimeEnvironment.getApplication().resources.configuration)
        config.uiMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return RuntimeEnvironment.getApplication().createConfigurationContext(config)
    }
}
