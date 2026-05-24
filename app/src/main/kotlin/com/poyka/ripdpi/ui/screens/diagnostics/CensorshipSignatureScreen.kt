package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** Severity tier for a single detected censorship signature. */
enum class RipDpiSignatureSeverity {
    /** No evidence of interference. */
    Ok,

    /** Anomalous behavior, possible interference. */
    Warn,

    /** Strong evidence of active blocking. */
    Bad,
}

/** Single detected signature row. */
data class RipDpiCensorshipSignature(
    val code: String,
    val title: String,
    val description: String,
    val evidence: String,
    val severity: RipDpiSignatureSeverity,
)

/**
 * Detail screen rendering the diagnostic engine's detected
 * censorship signatures: severity-coded icon + code + title +
 * description + mono evidence block per row. The header pill in the
 * top-right reflects the worst tier across all rows (Bad > Warn >
 * Ok); when empty, the screen shows a "No signatures detected" Ok
 * banner.
 *
 * Matches `diagnostic-censorship-signature.html`. Consumes only
 * existing design-system tokens (success / warning / destructive
 * containers + mono-log type) — no diagnostics-pipeline contract
 * change.
 */
@Composable
fun CensorshipSignatureScreen(
    signatures: List<RipDpiCensorshipSignature>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.md)
    val worstSeverity =
        signatures.maxByOrNull { it.severity.ordinal }?.severity ?: RipDpiSignatureSeverity.Ok
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.card, shape)
                .border(width = 1.dp, color = colors.cardBorder, shape = shape)
                .padding(RipDpiThemeTokens.spacing.md),
    ) {
        SignatureHeader(severity = worstSeverity, count = signatures.size)
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        if (signatures.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = RipDpiThemeTokens.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No signatures detected",
                    style = RipDpiThemeTokens.type.secondaryBody.copy(color = colors.mutedForeground),
                )
            }
        } else {
            signatures.forEachIndexed { index, signature ->
                if (index > 0) HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                SignatureRow(signature = signature)
            }
        }
    }
}

@Composable
private fun SignatureHeader(
    severity: RipDpiSignatureSeverity,
    count: Int,
) {
    val colors = RipDpiThemeTokens.colors
    val (pillBg, pillFg, label) = severityToHeader(severity, count, colors)
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = RipDpiThemeTokens.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Censorship signatures", style = RipDpiThemeTokens.type.bodyEmphasis)
            Text(
                "$count signature${if (count == 1) "" else "s"} detected",
                style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
            )
        }
        Row(
            modifier =
                Modifier
                    .background(pillBg, RoundedCornerShape(percent = 50))
                    .padding(horizontal = RipDpiThemeTokens.spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).background(pillFg))
            Text(label, style = RipDpiThemeTokens.type.monoSmall.copy(color = pillFg))
        }
    }
}

@Composable
private fun SignatureRow(signature: RipDpiCensorshipSignature) {
    val colors = RipDpiThemeTokens.colors
    val (iconBg, iconFg) = severityToIconColors(signature.severity, colors)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = RipDpiThemeTokens.spacing.md)
                .semantics { contentDescription = "${signature.severity.name.lowercase()}: ${signature.title}" },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .background(iconBg, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    signature.severity.name
                        .first()
                        .toString(),
                style = RipDpiThemeTokens.type.monoSmall.copy(color = iconFg),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(signature.title, style = RipDpiThemeTokens.type.bodyEmphasis)
                Box(
                    modifier =
                        Modifier
                            .padding(start = 6.dp)
                            .background(colors.muted, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        signature.code,
                        style = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground),
                    )
                }
            }
            Text(
                signature.description,
                modifier = Modifier.padding(top = 4.dp),
                style = RipDpiThemeTokens.type.body.copy(color = colors.mutedForeground),
            )
            EvidenceBlock(signature = signature)
        }
    }
}

@Composable
private fun EvidenceBlock(signature: RipDpiCensorshipSignature) {
    val colors = RipDpiThemeTokens.colors
    val borderColor =
        when (signature.severity) {
            RipDpiSignatureSeverity.Ok -> colors.outlineVariant
            RipDpiSignatureSeverity.Warn -> colors.warning
            RipDpiSignatureSeverity.Bad -> colors.destructive
        }
    Row(
        modifier =
            Modifier
                .padding(top = RipDpiThemeTokens.spacing.sm)
                .background(colors.muted, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(modifier = Modifier.size(width = 2.dp, height = 16.dp).background(borderColor))
        Text(
            signature.evidence,
            modifier = Modifier.padding(start = 8.dp),
            style = RipDpiThemeTokens.type.monoLog.copy(color = colors.foreground),
        )
    }
}

private fun severityToIconColors(
    severity: RipDpiSignatureSeverity,
    colors: com.poyka.ripdpi.ui.theme.RipDpiExtendedColors,
): Pair<Color, Color> =
    when (severity) {
        // No successContainer token in the design system; muted-with-success-tint is the spec equivalent.
        RipDpiSignatureSeverity.Ok -> colors.muted to colors.success

        RipDpiSignatureSeverity.Warn -> colors.warningContainer to colors.warning

        RipDpiSignatureSeverity.Bad -> colors.destructiveContainer to colors.destructive
    }

private fun severityToHeader(
    severity: RipDpiSignatureSeverity,
    count: Int,
    colors: com.poyka.ripdpi.ui.theme.RipDpiExtendedColors,
): Triple<Color, Color, String> =
    when {
        count == 0 -> Triple(colors.muted, colors.success, "CLEAR")
        severity == RipDpiSignatureSeverity.Bad -> Triple(colors.destructiveContainer, colors.destructive, "BLOCKING")
        severity == RipDpiSignatureSeverity.Warn -> Triple(colors.warningContainer, colors.warning, "ANOMALOUS")
        else -> Triple(colors.muted, colors.foreground, "OBSERVED")
    }

@Preview(showBackground = true, name = "CensorshipSignatureScreen (light)")
@Composable
private fun CensorshipSignatureScreenPreviewLight() {
    RipDpiComponentPreview {
        CensorshipSignatureScreen(
            signatures =
                listOf(
                    RipDpiCensorshipSignature(
                        code = "SNI-RST",
                        title = "SNI-triggered RST",
                        description = "TCP RST observed exactly after the TLS ClientHello SNI extension.",
                        evidence = "tcpdump: 12:18:42 -> RST seq=4242 (after SNI 'youtube.com')",
                        severity = RipDpiSignatureSeverity.Bad,
                    ),
                    RipDpiCensorshipSignature(
                        code = "DNS-INJECT",
                        title = "DNS response injection",
                        description = "Resolver returned a non-authoritative A record for a censored host.",
                        evidence = "dig youtube.com -> 0.0.0.0 (TTL 0, no DNSSEC)",
                        severity = RipDpiSignatureSeverity.Warn,
                    ),
                ),
        )
    }
}

@Preview(showBackground = true, name = "CensorshipSignatureScreen — empty (dark)")
@Composable
private fun CensorshipSignatureScreenPreviewEmpty() {
    RipDpiComponentPreview(themePreference = "dark") {
        CensorshipSignatureScreen(signatures = emptyList())
    }
}
