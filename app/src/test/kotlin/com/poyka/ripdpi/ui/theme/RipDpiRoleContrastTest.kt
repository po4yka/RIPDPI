package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.compositeOver
import com.poyka.ripdpi.ui.theme.ContrastAssertions.Enhanced
import com.poyka.ripdpi.ui.theme.ContrastAssertions.NonText
import com.poyka.ripdpi.ui.theme.ContrastAssertions.Text
import com.poyka.ripdpi.ui.theme.ContrastAssertions.assertAtLeast
import com.poyka.ripdpi.ui.theme.ContrastAssertions.ratio
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiRoleContrastTest {
    private data class Palette(
        val label: String,
        val level: RipDpiContrastLevel,
        val colors: RipDpiExtendedColors,
        val surfaces: RipDpiSurfaceTokens,
        val states: RipDpiStateTokens,
    )

    private val palettes =
        RipDpiContrastLevel.entries.flatMap { level ->
            listOf(false, true).map { isDark ->
                val scheme = if (isDark) ripDpiDarkColorScheme(level) else ripDpiLightColorScheme(level)
                val colors = ripDpiExtendedColors(scheme, isDark, level)
                Palette(
                    label = "${if (isDark) "dark" else "light"} $level",
                    level = level,
                    colors = colors,
                    surfaces = ripDpiSurfaceTokens(colors, scheme),
                    states = ripDpiStateTokens(colors, scheme),
                )
            }
        }

    @Test
    fun `every surface role keeps content readable on its container`() {
        palettes.forEach { p ->
            RipDpiSurfaceRole.entries.forEach { role ->
                val style = p.surfaces.resolve(role)
                assertAtLeast("${p.label} $role", Text, style.content, style.container)
            }
        }
    }

    @Test
    fun `every banner state role keeps text and icon readable`() {
        palettes.forEach { p ->
            RipDpiBannerStateRole.entries.forEach { role ->
                val s = p.states.banner.resolve(role)
                val id = "${p.label} banner $role"
                assertAtLeast("$id title", Text, s.title, s.container)
                assertAtLeast("$id message", Text, s.message, s.container)
                assertAtLeast("$id icon", NonText, s.icon, s.iconContainer.compositeOver(s.container))
            }
        }
    }

    @Test
    fun `every actuator state and stage role keeps content readable`() {
        palettes.forEach { p ->
            RipDpiActuatorStateRole.entries.forEach { role ->
                val s = p.states.actuator.resolve(role)
                val id = "${p.label} actuator $role"
                assertAtLeast("$id carriage", Text, s.carriageContent, s.carriage)
                assertAtLeast("$id slot", Text, s.slotContent, s.terminal)
                assertAtLeast("$id label", Text, s.label, p.colors.background)
                assertAtLeast("$id route label", Text, s.routeLabel, p.colors.background)
            }
            RipDpiActuatorStageRole.entries.forEach { role ->
                val s = p.states.actuator.resolveStage(role)
                assertAtLeast("${p.label} stage $role", Text, s.content, s.container)
            }
        }
    }

    @Test
    fun `every route state role keeps content and badge readable`() {
        palettes.forEach { p ->
            RipDpiRouteAvailabilityStateRole.entries.forEach { role ->
                val s = p.states.route.resolve(role)
                val id = "${p.label} route $role"
                assertAtLeast("$id content", Text, s.content, s.container)
                assertAtLeast("$id muted content", Text, s.mutedContent, s.container)
                assertAtLeast("$id badge", Text, s.badgeContent, s.badgeContainer.compositeOver(s.container))
            }
        }
    }

    @Test
    fun `RDS tone colors meet the contrast target of their level`() {
        palettes.forEach { p ->
            val c = p.colors
            val textMinimum = if (p.level == RipDpiContrastLevel.High) Enhanced else Text
            listOf(
                Triple("success", c.success, null),
                Triple("warning", c.warning, c.warningForeground),
                Triple("info", c.info, c.infoForeground),
                Triple("restricted", c.restricted, c.restrictedForeground),
            ).forEach { (name, tone, onTone) ->
                assertAtLeast("${p.label} $name on page", textMinimum, tone, c.background)
                if (onTone != null) assertAtLeast("${p.label} on-$name", textMinimum, onTone, tone)
            }
            listOf(
                Triple("warning", c.warningContainerForeground, c.warningContainer),
                Triple("info", c.infoContainerForeground, c.infoContainer),
                Triple("restricted", c.restrictedContainerForeground, c.restrictedContainer),
            ).forEach { (name, fg, container) ->
                assertAtLeast("${p.label} $name container", textMinimum, fg, container)
            }
        }
    }

    @Test
    fun `higher contrast levels never lower a tone color ratio`() {
        listOf(false, true).forEach { isDark ->
            fun tones(level: RipDpiContrastLevel): List<Float> {
                val scheme = if (isDark) ripDpiDarkColorScheme(level) else ripDpiLightColorScheme(level)
                val c = ripDpiExtendedColors(scheme, isDark, level)
                return listOf(c.success, c.warning, c.info, c.restricted).map { ratio(it, c.background) }
            }
            val standard = tones(RipDpiContrastLevel.Standard)
            val medium = tones(RipDpiContrastLevel.Medium)
            val high = tones(RipDpiContrastLevel.High)
            standard.indices.forEach { i ->
                assertTrue("dark=$isDark tone $i medium", medium[i] >= standard[i])
                assertTrue("dark=$isDark tone $i high", high[i] >= medium[i])
            }
        }
    }
}
