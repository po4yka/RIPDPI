package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass

/**
 * A primary action fills its column on a phone, so its edges match the cards. In a wider pane the layout cap keeps
 * it a button that starts at the column edge.
 */
@Composable
fun Modifier.primaryActionWidth(): Modifier {
    val layout = RipDpiThemeTokens.layout
    return if (layout.widthClass == RipDpiWidthClass.Compact) {
        fillMaxWidth()
    } else {
        widthIn(max = layout.primaryActionMaxWidth).fillMaxWidth()
    }
}
