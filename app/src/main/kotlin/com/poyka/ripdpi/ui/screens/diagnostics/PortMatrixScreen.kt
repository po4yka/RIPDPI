package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Verdict for a single (host, port) probe in the port matrix.
 * Color tier is keyed to standard tonal containers per the spec.
 */
enum class RipDpiPortVerdict {
    /** TCP handshake completed within budget. */
    Ok,

    /** Response received but flagged (RST, abnormal latency, MTU clamp). */
    Warn,

    /** No response within budget or actively rejected. */
    Bad,

    /** Probe skipped (out of budget, prior dependency failed). */
    Skipped,
}

/** Single row in [PortMatrixScreen]. */
data class RipDpiPortMatrixRow(
    val host: String,
    val subtitle: String?,
    val verdictByPort: Map<Int, RipDpiPortVerdict>,
)

/**
 * 12-port-column connectivity matrix for the diagnostic port-scan
 * subsystem. Renders one row per probed host; each cell is a
 * verdict tile color-coded per [RipDpiPortVerdict]. The header row
 * lists port numbers; the leading column shows host + optional
 * subtitle (e.g., ASN, region, response time).
 *
 * Matches `diagnostic-port-matrix.html`. Consumes only existing
 * design-system tokens (success / warning / destructive / accent
 * containers) — no diagnostics-pipeline contract change.
 */
@Composable
fun PortMatrixScreen(
    ports: List<Int>,
    rows: List<RipDpiPortMatrixRow>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.sm)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, shape)
                .border(width = 1.dp, color = colors.cardBorder, shape = shape)
                .padding(RipDpiThemeTokens.spacing.md),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        PortMatrixHeader(legendEntries = RipDpiPortVerdict.entries)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                MatrixHeaderRow(ports = ports)
                rows.forEach { row ->
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    MatrixRow(row = row, ports = ports)
                }
            }
        }
    }
}

@Composable
private fun PortMatrixHeader(legendEntries: List<RipDpiPortVerdict>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "Port matrix", style = RipDpiThemeTokens.type.bodyEmphasis)
        Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            legendEntries.forEach { verdict ->
                LegendChip(verdict = verdict)
            }
        }
    }
}

@Composable
private fun LegendChip(verdict: RipDpiPortVerdict) {
    val colors = RipDpiThemeTokens.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(verdictColor(verdict, colors), RoundedCornerShape(2.dp)),
        )
        Text(
            text = verdict.name.uppercase(),
            style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
        )
    }
}

@Composable
private fun MatrixHeaderRow(ports: List<Int>) {
    val colors = RipDpiThemeTokens.colors
    Row(modifier = Modifier.background(colors.muted)) {
        Box(modifier = Modifier.width(110.dp).padding(8.dp)) {
            Text(
                text = "HOST / PORT",
                style = RipDpiThemeTokens.type.sectionTitle.copy(color = colors.foreground),
            )
        }
        ports.forEach { port ->
            Box(
                modifier = Modifier.defaultMinSize(minWidth = 44.dp).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = port.toString(),
                    style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
                )
            }
        }
    }
}

@Composable
private fun MatrixRow(
    row: RipDpiPortMatrixRow,
    ports: List<Int>,
) {
    val colors = RipDpiThemeTokens.colors
    Row {
        Column(
            modifier = Modifier.width(110.dp).background(colors.muted).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(row.host, style = RipDpiThemeTokens.type.monoValue.copy(color = colors.foreground))
            row.subtitle?.let {
                Text(it, style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground))
            }
        }
        ports.forEach { port ->
            val verdict = row.verdictByPort[port] ?: RipDpiPortVerdict.Skipped
            VerdictCell(verdict = verdict, host = row.host, port = port)
        }
    }
}

@Composable
private fun VerdictCell(
    verdict: RipDpiPortVerdict,
    host: String,
    port: Int,
) {
    val colors = RipDpiThemeTokens.colors
    val bg = verdictColor(verdict, colors)
    Box(
        modifier =
            Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .background(bg)
                .semantics { contentDescription = "$host port $port ${verdict.name.lowercase()}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = verdict.name.first().toString(),
            style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.background),
        )
    }
}

private fun verdictColor(
    verdict: RipDpiPortVerdict,
    colors: com.poyka.ripdpi.ui.theme.RipDpiExtendedColors,
): Color =
    when (verdict) {
        RipDpiPortVerdict.Ok -> colors.success
        RipDpiPortVerdict.Warn -> colors.warning
        RipDpiPortVerdict.Bad -> colors.destructive
        RipDpiPortVerdict.Skipped -> colors.accent
    }

@Preview(showBackground = true, name = "PortMatrixScreen (light)")
@Composable
private fun PortMatrixScreenPreviewLight() {
    val ports = listOf(80, 443, 853, 1194, 4500, 8443)
    val rows =
        listOf(
            RipDpiPortMatrixRow(
                host = "1.1.1.1",
                subtitle = "Cloudflare",
                verdictByPort =
                    mapOf(
                        80 to RipDpiPortVerdict.Ok,
                        443 to RipDpiPortVerdict.Ok,
                        853 to RipDpiPortVerdict.Ok,
                        1194 to RipDpiPortVerdict.Warn,
                        4500 to RipDpiPortVerdict.Bad,
                        8443 to RipDpiPortVerdict.Skipped,
                    ),
            ),
            RipDpiPortMatrixRow(
                host = "8.8.8.8",
                subtitle = "Google",
                verdictByPort =
                    mapOf(
                        80 to RipDpiPortVerdict.Ok,
                        443 to RipDpiPortVerdict.Ok,
                        853 to RipDpiPortVerdict.Warn,
                        1194 to RipDpiPortVerdict.Bad,
                        4500 to RipDpiPortVerdict.Bad,
                        8443 to RipDpiPortVerdict.Skipped,
                    ),
            ),
        )
    RipDpiComponentPreview {
        PortMatrixScreen(ports = ports, rows = rows)
    }
}

@Preview(showBackground = true, name = "PortMatrixScreen (dark)")
@Composable
private fun PortMatrixScreenPreviewDark() {
    val ports = listOf(80, 443, 853)
    val rows =
        listOf(
            RipDpiPortMatrixRow(
                host = "example.com",
                subtitle = null,
                verdictByPort = mapOf(80 to RipDpiPortVerdict.Ok, 443 to RipDpiPortVerdict.Bad),
            ),
        )
    RipDpiComponentPreview(themePreference = "dark") {
        PortMatrixScreen(ports = ports, rows = rows)
    }
}
