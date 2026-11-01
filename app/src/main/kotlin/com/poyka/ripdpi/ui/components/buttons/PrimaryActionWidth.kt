package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * A primary action fills its column up to the layout cap, so it stays a button in a wide pane and starts at the
 * column edge.
 */
@Composable
fun Modifier.primaryActionWidth(): Modifier =
    widthIn(max = RipDpiThemeTokens.layout.primaryActionMaxWidth).fillMaxWidth()
