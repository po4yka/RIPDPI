package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * The text action a Material 3 top app bar carries for an editor's primary action, such as Save. The bar insets for
 * the glyph padding inside a 48dp icon button, so the text takes the same inset to line up with the content column.
 */
@Composable
fun RipDpiTopBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val targetSize = RipDpiThemeTokens.components.buttons.iconButtonSize
    val glyphInset = (targetSize - RipDpiIconSizes.Default) / 2
    RipDpiTextAction(
        text = text,
        onClick = onClick,
        // Padding sits outside the touch target. The text's own inset is part of the target, so the outer padding
        // is the glyph inset less that inset, and the label's trailing edge still lands on the glyph inset. The target
        // is at least 48dp wide with the text at its end; a short label is not centered away from the edge.
        modifier = modifier.padding(horizontal = glyphInset - RipDpiTextActionRingInset).widthIn(min = targetSize),
        contentAlignment = Alignment.CenterEnd,
        textStyle = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
        enabled = enabled,
    )
}
