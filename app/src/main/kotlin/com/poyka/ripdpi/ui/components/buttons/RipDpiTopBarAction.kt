package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** The text action a Material 3 top app bar carries for an editor's primary action, such as Save. */
@Composable
fun RipDpiTopBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RipDpiTextAction(
        text = text,
        onClick = onClick,
        modifier = modifier,
        textStyle = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
        enabled = enabled,
    )
}
