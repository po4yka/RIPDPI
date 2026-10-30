package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiStateTokensTest {
    private val tokens =
        ripDpiStateTokens(
            colors = LightRipDpiExtendedColors,
            colorScheme = ripDpiLightColorScheme(),
            components = DefaultRipDpiComponents,
            motion = DefaultRipDpiMotion,
        )

    @Test
    fun `text field focused error uses emphasized border and foreground content`() {
        val style =
            tokens.textField.resolve(
                enabled = true,
                hasError = true,
                isFocused = true,
                isEmpty = false,
            )

        assertEquals(LightRipDpiExtendedColors.destructive, style.border)
        assertEquals(2.dp, style.borderWidth)
        assertEquals(LightRipDpiExtendedColors.foreground, style.content)
    }

    @Test
    fun `filled text field without focus keeps muted content until interaction`() {
        val style =
            tokens.textField.resolve(
                enabled = true,
                hasError = false,
                isFocused = false,
                isEmpty = false,
            )

        assertEquals(LightRipDpiExtendedColors.mutedForeground, style.content)
        assertEquals(LightRipDpiExtendedColors.outlineVariant, style.border)
    }

    @Test
    fun `selected chip resolves selected scale and inverted colors`() {
        val style =
            tokens.chip.resolve(
                selected = true,
                enabled = true,
                isPressed = false,
            )

        assertEquals(LightRipDpiExtendedColors.foreground, style.container)
        assertEquals(LightRipDpiExtendedColors.background, style.content)
        assertEquals(DefaultRipDpiMotion.selectionScale, style.scale)
    }

    @Test
    fun `selected settings row promotes badge and value tokens`() {
        val style = tokens.settingsRow.resolve(RipDpiSettingsRowStateRole.Selected)

        assertEquals(LightRipDpiExtendedColors.accent, style.container)
        assertEquals(LightRipDpiExtendedColors.foreground, style.value)
        assertEquals(LightRipDpiExtendedColors.background, style.leadingBadgeIcon)
    }

    @Test
    fun `dark switches keep the off state visible and draw disabled-on as on`() {
        val dark =
            ripDpiStateTokens(
                colors = DarkRipDpiExtendedColors,
                colorScheme = ripDpiDarkColorScheme(),
                components = DefaultRipDpiComponents,
                motion = DefaultRipDpiMotion,
            )
        val off = dark.switch.resolve(checked = false, enabled = true, isPressed = false)
        val disabledOn = dark.switch.resolve(checked = true, enabled = false, isPressed = false)

        // WCAG 1.4.11: 3:1 for the off-track outline against the card and for the thumb against its track.
        assertTrue(contrast(off.trackBorder, DarkRipDpiExtendedColors.card) >= 3f)
        assertTrue(contrast(off.thumb, off.track) >= 3f)
        assertEquals(1f, disabledOn.alpha)
        assertTrue(disabledOn.track.luminance() > off.track.luminance())
        assertTrue(contrast(disabledOn.thumb, disabledOn.track) >= 3f)
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `warning banner resolves semantic warning surface`() {
        val style = tokens.banner.resolve(RipDpiBannerStateRole.Warning)

        assertEquals(LightRipDpiExtendedColors.warningContainer, style.container)
        assertEquals(LightRipDpiExtendedColors.warning, style.icon)
        assertEquals(LightRipDpiExtendedColors.warningContainerForeground, style.title)
    }

    @Test
    fun `route states resolve semantic availability surfaces`() {
        val active = tokens.route.resolve(RipDpiRouteAvailabilityStateRole.Active)
        val setup = tokens.route.resolve(RipDpiRouteAvailabilityStateRole.NeedsSetup)
        val failed = tokens.route.resolve(RipDpiRouteAvailabilityStateRole.Failed)

        assertEquals(LightRipDpiExtendedColors.foreground, active.container)
        assertEquals(LightRipDpiExtendedColors.background, active.content)
        assertEquals(LightRipDpiExtendedColors.infoContainer, setup.container)
        assertEquals(LightRipDpiExtendedColors.destructiveContainer, failed.container)
        assertEquals(LightRipDpiExtendedColors.destructive, failed.border)
    }

    @Test
    fun `state role mappings centralize component variant translation`() {
        assertEquals(
            RipDpiSettingsRowStateRole.Selected,
            DefaultRipDpiStateRoleMappings.settingsRow.fromVariant(SettingsRowVariant.Selected),
        )
        assertEquals(
            RipDpiBannerStateRole.Restricted,
            DefaultRipDpiStateRoleMappings.banner.fromTone(WarningBannerTone.Restricted),
        )
    }
}
