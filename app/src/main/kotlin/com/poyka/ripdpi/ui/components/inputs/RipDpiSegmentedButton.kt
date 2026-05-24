package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Pill-shaped segmented control: a Row of equal-width segments where
 * the selected segment carries the foreground container and the rest
 * read as muted on the card. Matches `components-segmented.html`.
 */
@Composable
fun RipDpiSegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(colors.muted, shape)
                .border(width = 1.dp, color = colors.border, shape = shape)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val container = if (isSelected) colors.foreground else colors.muted
            val content = if (isSelected) colors.background else colors.mutedForeground
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(container, shape)
                        .ripDpiClickable(enabled = true) { onSelect(index) }
                        .padding(horizontal = RipDpiThemeTokens.spacing.md, vertical = RipDpiThemeTokens.spacing.sm),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style =
                        if (isSelected) {
                            RipDpiThemeTokens.type.bodyEmphasis.copy(color = content)
                        } else {
                            RipDpiThemeTokens.type.secondaryBody.copy(color = content)
                        },
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSegmentedButton (light)")
@Composable
private fun RipDpiSegmentedPreviewLight() {
    RipDpiComponentPreview {
        var sel by remember { mutableIntStateOf(0) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiSegmentedButton(
                options = listOf("Auto", "Manual"),
                selectedIndex = sel,
                onSelect = { sel = it },
                modifier = Modifier.fillMaxWidth(),
            )
            RipDpiSegmentedButton(
                options = listOf("All", "Errors", "Warnings"),
                selectedIndex = 1,
                onSelect = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSegmentedButton (dark)")
@Composable
private fun RipDpiSegmentedPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiSegmentedButton(
            options = listOf("On", "Off"),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
