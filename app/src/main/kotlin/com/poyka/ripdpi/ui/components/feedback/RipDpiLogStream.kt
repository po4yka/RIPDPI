package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiLogLevel { Trace, Debug, Info, Warn, Error }

data class RipDpiLogEntry(
    val level: RipDpiLogLevel,
    val timestamp: String,
    val tag: String,
    val message: String,
)

/**
 * Virtualized streaming log viewer. Uses LazyColumn for cheap scroll
 * of long lists. When [autoScroll] is true and a new entry arrives,
 * jumps to the bottom. Per-row colors are driven by [RipDpiLogLevel]
 * mapped to design system warning/destructive/info/muted tokens.
 *
 * Matches `components-log-stream.html`.
 */
@Composable
fun RipDpiLogStream(
    entries: List<RipDpiLogEntry>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    autoScroll: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.sm)
    val state = rememberLazyListState()
    LaunchedEffect(entries.size, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            state.animateScrollToItem(entries.lastIndex)
        }
    }
    LazyColumn(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(colors.card, shape)
                .padding(RipDpiThemeTokens.spacing.sm),
        state = state,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries) { entry ->
            val levelColor =
                when (entry.level) {
                    RipDpiLogLevel.Trace -> colors.mutedForeground
                    RipDpiLogLevel.Debug -> colors.mutedForeground
                    RipDpiLogLevel.Info -> colors.info
                    RipDpiLogLevel.Warn -> colors.warning
                    RipDpiLogLevel.Error -> colors.destructive
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.timestamp,
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
                )
                Text(
                    text =
                        entry.level.name
                            .first()
                            .toString(),
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = levelColor),
                )
                Text(
                    text = entry.tag,
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.accent),
                )
                Text(
                    text = entry.message,
                    modifier = Modifier.weight(1f),
                    style = RipDpiThemeTokens.type.monoLog.copy(color = colors.foreground),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiLogStream (light)")
@Composable
private fun RipDpiLogStreamPreviewLight() {
    RipDpiComponentPreview {
        RipDpiLogStream(
            entries =
                listOf(
                    RipDpiLogEntry(RipDpiLogLevel.Info, "12:00:01", "core", "tunnel up"),
                    RipDpiLogEntry(RipDpiLogLevel.Debug, "12:00:02", "dns", "resolved 1.1.1.1"),
                    RipDpiLogEntry(RipDpiLogLevel.Warn, "12:00:03", "net", "rtt 380ms"),
                    RipDpiLogEntry(RipDpiLogLevel.Error, "12:00:04", "net", "TLS handshake failed"),
                    RipDpiLogEntry(RipDpiLogLevel.Info, "12:00:05", "core", "reconnect scheduled"),
                ),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiLogStream (dark)")
@Composable
private fun RipDpiLogStreamPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiLogStream(
            entries =
                listOf(
                    RipDpiLogEntry(RipDpiLogLevel.Trace, "12:00:00", "vpn", "init"),
                    RipDpiLogEntry(RipDpiLogLevel.Info, "12:00:01", "vpn", "ready"),
                ),
        )
    }
}
