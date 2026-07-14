package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class RipDpiKbdSize {
    /** Inline within prose; no shadow, 14dp min width. */
    Small,

    /** Tooltip / list-row default; 22dp min width. */
    Standard,

    /** First-run / empty-state emphasis; 28dp min width. */
    Large,
}

private data class KbdMetrics(
    val padding: PaddingValues,
    val minWidth: Dp,
    val corner: Dp,
    val showShadowLine: Boolean,
)

@Composable
private fun metricsFor(size: RipDpiKbdSize): KbdMetrics =
    when (size) {
        RipDpiKbdSize.Small -> {
            KbdMetrics(
                padding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                minWidth = 14.dp,
                corner = 3.dp,
                showShadowLine = false,
            )
        }

        RipDpiKbdSize.Standard -> {
            KbdMetrics(
                padding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                minWidth = 22.dp,
                corner = 4.dp,
                showShadowLine = true,
            )
        }

        RipDpiKbdSize.Large -> {
            KbdMetrics(
                padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                minWidth = 28.dp,
                corner = 4.dp,
                showShadowLine = true,
            )
        }
    }

@Composable
private fun textStyleFor(size: RipDpiKbdSize): TextStyle =
    when (size) {
        RipDpiKbdSize.Small -> RipDpiThemeTokens.type.monoSmall
        RipDpiKbdSize.Standard -> RipDpiThemeTokens.type.monoSmall
        RipDpiKbdSize.Large -> RipDpiThemeTokens.type.monoInline
    }

/** A single keycap atom (e.g. ⌘, K, esc). */
@Composable
fun RipDpiKbd(
    label: String,
    modifier: Modifier = Modifier,
    size: RipDpiKbdSize = RipDpiKbdSize.Standard,
) {
    val metrics = metricsFor(size)
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(metrics.corner)
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = metrics.minWidth)
                .background(colors.card, shape)
                .border(width = 1.dp, color = colors.border, shape = shape)
                .padding(metrics.padding)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = textStyleFor(size).copy(color = colors.foreground),
        )
    }
}

/** Composed shortcut: keycap atoms joined by a muted "+" separator. */
@Composable
fun RipDpiKbdShortcut(
    keys: ImmutableList<String>,
    modifier: Modifier = Modifier,
    size: RipDpiKbdSize = RipDpiKbdSize.Standard,
) {
    Row(
        modifier = modifier.semantics { contentDescription = keys.joinToString(" + ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        keys.forEachIndexed { index, key ->
            if (index > 0) {
                Text(
                    text = "+",
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = RipDpiThemeTokens.colors.mutedForeground),
                )
            }
            RipDpiKbd(label = key, size = size)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiKbdShortcut — sizes (light)")
@Composable
private fun RipDpiKbdShortcutLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiKbdShortcut(keys = persistentListOf("⌘", "K"), size = RipDpiKbdSize.Small)
            RipDpiKbdShortcut(keys = persistentListOf("⌘", "K"))
            RipDpiKbdShortcut(keys = persistentListOf("⌘", "⇧", "D"), size = RipDpiKbdSize.Large)
            RipDpiKbd(label = "esc")
        }
    }
}

@Preview(showBackground = true, name = "RipDpiKbdShortcut — sizes (dark)")
@Composable
private fun RipDpiKbdShortcutDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiKbdShortcut(keys = persistentListOf("⌘", "K"))
            RipDpiKbdShortcut(keys = persistentListOf("⌘", "⌥", "→"), size = RipDpiKbdSize.Large)
        }
    }
}
