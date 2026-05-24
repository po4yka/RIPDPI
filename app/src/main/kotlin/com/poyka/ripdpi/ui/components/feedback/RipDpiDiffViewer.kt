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

data class RipDpiDiffLine(
    val kind: RipDpiDiffKind,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

/**
 * Unified-style diff renderer. Each [RipDpiDiffLine] occupies one
 * row: gutter columns for old/new line numbers, sign column (+ / - /
 *  ), then the line content in monoLog type. Tinted backgrounds per
 * kind via the design system success/destructive containers.
 *
 * Matches `components-diff-viewer.html` (unified variant).
 */
@Composable
fun RipDpiDiffViewer(
    lines: List<RipDpiDiffLine>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.sm)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, shape)
                .padding(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        lines.forEach { line ->
            val (bg, fg, sign) =
                when (line.kind) {
                    RipDpiDiffKind.Added -> Triple(colors.success.copy(alpha = 0.15f), colors.success, "+")
                    RipDpiDiffKind.Removed -> Triple(colors.destructive.copy(alpha = 0.15f), colors.destructive, "-")
                    RipDpiDiffKind.Unchanged -> Triple(Color.Transparent, colors.foreground, " ")
                    RipDpiDiffKind.Header -> Triple(colors.muted, colors.mutedForeground, "@")
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
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
                Text(text = sign, style = RipDpiThemeTokens.type.monoSmall.copy(color = fg))
                Text(
                    text = line.text,
                    modifier = Modifier.weight(1f),
                    style = RipDpiThemeTokens.type.monoLog.copy(color = fg),
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
