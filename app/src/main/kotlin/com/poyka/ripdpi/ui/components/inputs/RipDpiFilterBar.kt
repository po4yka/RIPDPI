package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiFilter(
    val key: String,
    val label: String,
)

/**
 * Horizontal scrolling filter toolbar of chips. Selection set is
 * managed by the caller via [selectedKeys] and [onToggle]. Layout:
 * scroll container, left-aligned chips with spacing.lg, optional
 * trailing "Clear" affordance when any selection is active.
 *
 * Matches `components-filter-bar.html`.
 */
@Composable
fun RipDpiFilterBar(
    filters: List<RipDpiFilter>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onClearAll: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, RoundedCornerShape(RipDpiThemeTokens.spacing.sm))
                .padding(RipDpiThemeTokens.spacing.sm)
                .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        filters.forEach { filter ->
            RipDpiChip(
                text = filter.label,
                onClick = { onToggle(filter.key) },
                selected = filter.key in selectedKeys,
            )
        }
        if (selectedKeys.isNotEmpty()) {
            RipDpiChip(text = stringResource(R.string.action_clear), onClick = onClearAll, selected = false)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiFilterBar (light)")
@Composable
private fun RipDpiFilterBarLightPreview() {
    RipDpiComponentPreview {
        var sel by remember { mutableStateOf(setOf("errors")) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiFilterBar(
                filters =
                    listOf(
                        RipDpiFilter("all", "All"),
                        RipDpiFilter("errors", "Errors"),
                        RipDpiFilter("warnings", "Warnings"),
                        RipDpiFilter("info", "Info"),
                        RipDpiFilter("debug", "Debug"),
                    ),
                selectedKeys = sel,
                onToggle = { k -> sel = if (k in sel) sel - k else sel + k },
                onClearAll = { sel = emptySet() },
            )
            Text(
                "selected: ${sel.joinToString(", ")}",
                style = RipDpiThemeTokens.type.monoSmall.copy(color = RipDpiThemeTokens.colors.mutedForeground),
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiFilterBar (dark)")
@Composable
private fun RipDpiFilterBarDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiFilterBar(
            filters = listOf(RipDpiFilter("a", "Recent"), RipDpiFilter("b", "Connected")),
            selectedKeys = setOf("a"),
            onToggle = {},
        )
    }
}
