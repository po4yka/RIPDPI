package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Renders the same binary choice in N alternative styles for visual
 * comparison and spec documentation: switch, segmented button row, and
 * chip group. Matches `components-toggle-alternatives.html` — used in
 * the design-system docs as an A/B/C comparison.
 *
 * Not intended for production use directly; consumers should pick ONE
 * variant (RipDpiSwitch, RipDpiSegmentedButton, or RipDpiChip) per
 * surface. This composable exists for the spec gallery.
 */
@Composable
fun RipDpiToggleAlternatives(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    options: List<String> = listOf("Off", "On"),
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Switch", style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground))
            if (options.size == 2) {
                Switch(
                    checked = selectedIndex == 1,
                    onCheckedChange = { onSelect(if (it) 1 else 0) },
                )
            } else {
                Text(
                    "(switch fits binary only)",
                    style = RipDpiThemeTokens.type.caption.copy(color = colors.mutedForeground),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            Text("Segmented", style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground))
            RipDpiSegmentedButton(
                options = options,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            Text("Chips", style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground))
            Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                options.forEachIndexed { index, label ->
                    RipDpiChip(
                        text = label,
                        onClick = { onSelect(index) },
                        selected = index == selectedIndex,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiToggleAlternatives (light)")
@Composable
private fun RipDpiToggleAlternativesLightPreview() {
    RipDpiComponentPreview {
        var sel by remember { mutableIntStateOf(0) }
        RipDpiToggleAlternatives(
            selectedIndex = sel,
            onSelect = { sel = it },
            options = listOf("Off", "On"),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiToggleAlternatives (dark)")
@Composable
private fun RipDpiToggleAlternativesDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiToggleAlternatives(
            selectedIndex = 1,
            onSelect = {},
            options = listOf("Auto", "Manual", "Off"),
        )
    }
}
