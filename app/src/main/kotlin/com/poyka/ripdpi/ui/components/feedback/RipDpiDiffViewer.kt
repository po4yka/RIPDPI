package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiDiffKind { Added, Removed, Unchanged, Header }

enum class RipDpiDiffLayout { Unified, SideBySide }

data class RipDpiDiffLine(
    val kind: RipDpiDiffKind,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

private data class DiffLineStyle(
    val bg: Color,
    val fg: Color,
    val sign: String,
)

@Composable
private fun lineStyle(kind: RipDpiDiffKind): DiffLineStyle {
    val colors = RipDpiThemeTokens.colors
    return when (kind) {
        RipDpiDiffKind.Added -> DiffLineStyle(colors.success.copy(alpha = 0.15f), colors.success, "+")
        RipDpiDiffKind.Removed -> DiffLineStyle(colors.destructive.copy(alpha = 0.15f), colors.destructive, "-")
        RipDpiDiffKind.Unchanged -> DiffLineStyle(Color.Transparent, colors.foreground, " ")
        RipDpiDiffKind.Header -> DiffLineStyle(colors.muted, colors.mutedForeground, "@")
    }
}

/**
 * Unified or side-by-side diff renderer. In [RipDpiDiffLayout.Unified] each
 * [RipDpiDiffLine] occupies one row with old/new gutter, sign, and content.
 * In [RipDpiDiffLayout.SideBySide] a [Row] splits into two equal columns:
 * left shows Removed/Unchanged/Header lines; right shows Added/Unchanged/Header.
 *
 * Matches `components-diff-viewer.html`.
 */
@Composable
fun RipDpiDiffViewer(
    lines: List<RipDpiDiffLine>,
    modifier: Modifier = Modifier,
    layout: RipDpiDiffLayout = RipDpiDiffLayout.Unified,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.sm)
    when (layout) {
        RipDpiDiffLayout.Unified -> UnifiedDiffContent(lines, modifier, colors, shape)
        RipDpiDiffLayout.SideBySide -> SideBySideDiffContent(lines, modifier, colors, shape)
    }
}

@Composable
private fun UnifiedDiffContent(
    lines: List<RipDpiDiffLine>,
    modifier: Modifier,
    colors: com.poyka.ripdpi.ui.theme.RipDpiExtendedColors,
    shape: RoundedCornerShape,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, shape)
                .padding(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        lines.forEach { line ->
            val s = lineStyle(line.kind)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(s.bg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text =
                        line.oldLineNumber
                            ?.toString()
                            .orEmpty()
                            .padStart(4),
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
                )
                Text(
                    text =
                        line.newLineNumber
                            ?.toString()
                            .orEmpty()
                            .padStart(4),
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
                )
                Text(text = s.sign, style = RipDpiThemeTokens.type.monoSmall.copy(color = s.fg))
                Text(
                    text = line.text,
                    modifier = Modifier.weight(1f),
                    style = RipDpiThemeTokens.type.monoLog.copy(color = s.fg),
                )
            }
        }
    }
}

@Composable
private fun SideBySideDiffContent(
    lines: List<RipDpiDiffLine>,
    modifier: Modifier,
    colors: com.poyka.ripdpi.ui.theme.RipDpiExtendedColors,
    shape: RoundedCornerShape,
) {
    val leftLines = lines.filter { it.kind != RipDpiDiffKind.Added }
    val rightLines = lines.filter { it.kind != RipDpiDiffKind.Removed }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, shape)
                .padding(RipDpiThemeTokens.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SideBySideColumn(
            lines = leftLines,
            useOldLineNumber = true,
            colors = colors,
            modifier = Modifier.weight(1f),
        )
        SideBySideColumn(
            lines = rightLines,
            useOldLineNumber = false,
            colors = colors,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SideBySideColumn(
    lines: List<RipDpiDiffLine>,
    useOldLineNumber: Boolean,
    colors: com.poyka.ripdpi.ui.theme.RipDpiExtendedColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        lines.forEach { line ->
            val s = lineStyle(line.kind)
            val lineNum = if (useOldLineNumber) line.oldLineNumber else line.newLineNumber
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(s.bg)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = lineNum?.toString().orEmpty().padStart(3),
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
                )
                Text(text = s.sign, style = RipDpiThemeTokens.type.monoSmall.copy(color = s.fg))
                Text(
                    text = line.text,
                    modifier = Modifier.weight(1f),
                    style = RipDpiThemeTokens.type.monoLog.copy(color = s.fg),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiDiffViewer (light)")
@Composable
private fun RipDpiDiffViewerPreviewLight() {
    RipDpiComponentPreview {
        RipDpiDiffViewer(
            lines =
                listOf(
                    RipDpiDiffLine(RipDpiDiffKind.Header, "@@ -1,5 +1,6 @@"),
                    RipDpiDiffLine(RipDpiDiffKind.Unchanged, "strategy = \"tlsrec\"", 1, 1),
                    RipDpiDiffLine(RipDpiDiffKind.Removed, "fake_ttl = 2", 2, null),
                    RipDpiDiffLine(RipDpiDiffKind.Added, "fake_ttl = 4", null, 2),
                    RipDpiDiffLine(RipDpiDiffKind.Added, "oob = 0x00", null, 3),
                    RipDpiDiffLine(RipDpiDiffKind.Unchanged, "dns = \"1.1.1.1\"", 3, 4),
                ),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiDiffViewer (dark)")
@Composable
private fun RipDpiDiffViewerPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiDiffViewer(
            lines =
                listOf(
                    RipDpiDiffLine(RipDpiDiffKind.Removed, "old.example.com", 1, null),
                    RipDpiDiffLine(RipDpiDiffKind.Added, "new.example.com", null, 1),
                ),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiDiffViewer SideBySide")
@Composable
private fun RipDpiDiffViewerSideBySidePreview() {
    RipDpiComponentPreview {
        RipDpiDiffViewer(
            lines =
                listOf(
                    RipDpiDiffLine(RipDpiDiffKind.Header, "@@ -1,5 +1,6 @@"),
                    RipDpiDiffLine(RipDpiDiffKind.Unchanged, "strategy = \"tlsrec\"", 1, 1),
                    RipDpiDiffLine(RipDpiDiffKind.Removed, "fake_ttl = 2", 2, null),
                    RipDpiDiffLine(RipDpiDiffKind.Added, "fake_ttl = 4", null, 2),
                    RipDpiDiffLine(RipDpiDiffKind.Added, "oob = 0x00", null, 3),
                    RipDpiDiffLine(RipDpiDiffKind.Unchanged, "dns = \"1.1.1.1\"", 3, 4),
                ),
            layout = RipDpiDiffLayout.SideBySide,
        )
    }
}
