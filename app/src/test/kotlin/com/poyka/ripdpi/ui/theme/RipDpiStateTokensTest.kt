package com.poyka.ripdpi.ui.theme

import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiStateTokensTest {
    private val tokens =
        ripDpiStateTokens(
            colors = LightRipDpiExtendedColors,
            colorScheme = ripDpiLightColorScheme(),
        )

    @Test
    fun `selected settings row promotes badge and value tokens`() {
        val style = tokens.settingsRow.resolve(RipDpiSettingsRowStateRole.Selected)

        assertEquals(LightRipDpiExtendedColors.accent, style.container)
        assertEquals(LightRipDpiExtendedColors.foreground, style.value)
        assertEquals(LightRipDpiExtendedColors.background, style.leadingBadgeIcon)
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
