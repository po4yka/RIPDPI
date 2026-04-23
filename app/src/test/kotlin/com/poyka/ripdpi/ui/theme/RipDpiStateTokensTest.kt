package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import org.junit.Assert.assertEquals
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
    fun `primary button disabled resolves muted palette`() {
        val style =
            tokens.button.resolve(
                role = RipDpiButtonStateRole.Primary,
                enabled = false,
                loading = false,
                isPressed = false,
                isFocused = false,
            )

        assertEquals(LightRipDpiExtendedColors.border, style.container)
        assertEquals(LightRipDpiExtendedColors.mutedForeground, style.content)
        assertEquals(0.dp, style.borderWidth)
    }

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
    fun `warning banner resolves semantic warning surface`() {
        val style = tokens.banner.resolve(RipDpiBannerStateRole.Warning)

        assertEquals(LightRipDpiExtendedColors.warningContainer, style.container)
        assertEquals(LightRipDpiExtendedColors.warning, style.icon)
        assertEquals(LightRipDpiExtendedColors.warningContainerForeground, style.title)
    }

    @Test
    fun `state role mappings centralize component variant translation`() {
        assertEquals(
            RipDpiButtonStateRole.Destructive,
            DefaultRipDpiStateRoleMappings.button.fromVariant(RipDpiButtonVariant.Destructive),
        )
        assertEquals(
            RipDpiIconButtonStateRole.Outline,
            DefaultRipDpiStateRoleMappings.iconButton.fromStyle(RipDpiIconButtonStyle.Outline),
        )
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
