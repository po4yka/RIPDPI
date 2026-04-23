package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiSurfaceTokensTest {
    private val colorScheme = ripDpiLightColorScheme()
    private val tokens =
        ripDpiSurfaceTokens(
            colors = LightRipDpiExtendedColors,
            colorScheme = colorScheme,
        )

    @Test
    fun `card role resolves standard panel surface`() {
        val style = tokens.resolve(RipDpiSurfaceRole.Card)

        assertEquals(colorScheme.surface, style.container)
        assertEquals(LightRipDpiExtendedColors.cardBorder, style.border)
        assertEquals(LightRipDpiExtendedColors.foreground, style.content)
        assertEquals(0.dp, style.shadowElevation)
    }

    @Test
    fun `selected card role resolves emphasized neutral panel`() {
        val style = tokens.resolve(RipDpiSurfaceRole.SelectedCard)

        assertEquals(LightRipDpiExtendedColors.accent, style.container)
        assertEquals(LightRipDpiExtendedColors.foreground, style.border)
        assertEquals(4.dp, style.shadowElevation)
    }

    @Test
    fun `dialog and bottom sheet roles stay aligned but explicit`() {
        val dialog = tokens.resolve(RipDpiSurfaceRole.Dialog)
        val bottomSheet = tokens.resolve(RipDpiSurfaceRole.BottomSheet)

        assertEquals(dialog, bottomSheet)
        assertEquals(24.dp, dialog.shadowElevation)
    }

    @Test
    fun `dropdown menu role resolves bordered floating surface`() {
        val style = tokens.resolve(RipDpiSurfaceRole.DropdownMenu)

        assertEquals(colorScheme.surface, style.container)
        assertEquals(LightRipDpiExtendedColors.cardBorder, style.border)
        assertEquals(12.dp, style.shadowElevation)
    }

    @Test
    fun `bottom bar roles use navigation chrome surfaces`() {
        val bar = tokens.resolve(RipDpiSurfaceRole.BottomBar)
        val indicator = tokens.resolve(RipDpiSurfaceRole.BottomBarIndicator)

        assertEquals(LightRipDpiExtendedColors.card, bar.container)
        assertEquals(LightRipDpiExtendedColors.border, bar.border)
        assertEquals(LightRipDpiExtendedColors.inputBackground, indicator.container)
        assertEquals(Color.Transparent, indicator.border)
    }

    @Test
    fun `dialog destructive icon badge resolves semantic destructive surface`() {
        val style = tokens.resolve(RipDpiSurfaceRole.DialogDestructiveIconBadge)

        assertEquals(LightRipDpiExtendedColors.destructiveContainer, style.container)
        assertEquals(LightRipDpiExtendedColors.destructive, style.content)
        assertEquals(Color.Transparent, style.border)
    }

    @Test
    fun `surface role mappings centralize card variants and dialog badge roles`() {
        assertEquals(
            RipDpiSurfaceRole.ElevatedCard,
            DefaultRipDpiSurfaceRoleMappings.cards.fromVariant(RipDpiCardVariant.Elevated),
        )
        assertEquals(
            RipDpiSurfaceRole.DialogDestructiveIconBadge,
            DefaultRipDpiSurfaceRoleMappings.feedback.dialogIconBadge(RipDpiDialogTone.Destructive),
        )
    }
}
