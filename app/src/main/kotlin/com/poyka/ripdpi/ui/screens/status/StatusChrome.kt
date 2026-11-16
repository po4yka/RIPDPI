package com.poyka.ripdpi.ui.screens.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// One touch-target row: the brand names the app and is not the screen's subject, so the hero starts close to the top.
private val StatusTopBarMinHeight = 48.dp

@Composable
internal fun StatusTopBar(
    title: String,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val type = RipDpiThemeTokens.type
    // The dashboard column is contentMaxWidth wide including its own padding, so the title lines up with it.
    val containerMaxWidth = layout.contentMaxWidth - layout.horizontalPadding - layout.horizontalPadding

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = layout.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = containerMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = StatusTopBarMinHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = type.sectionTitle,
                color = colors.mutedForeground,
            )
        }
    }
}
