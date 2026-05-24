package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlin.time.Duration

enum class RipDpiStaleTier {
    /** < 5s — data is updating now. Spec shows a pulse here; deferred. */
    Fresh,

    /** 5s – 1m — still accurate. */
    Recent,

    /** 1m – 5m — likely accurate, refresh for time-sensitive decisions. */
    Aging,

    /** 5m – 30m — refresh before acting. */
    Stale,

    /** > 30m — treat as historic. */
    Expired,
}

fun staleTierFor(age: Duration): RipDpiStaleTier =
    when {
        age.inWholeSeconds < 5 -> RipDpiStaleTier.Fresh
        age.inWholeMinutes < 1 -> RipDpiStaleTier.Recent
        age.inWholeMinutes < 5 -> RipDpiStaleTier.Aging
        age.inWholeMinutes < 30 -> RipDpiStaleTier.Stale
        else -> RipDpiStaleTier.Expired
    }

@Composable
fun RipDpiStaleDataBadge(
    label: String,
    tier: RipDpiStaleTier,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors

    data class TierTones(
        val container: Color,
        val content: Color,
        val dot: Color,
        val border: Color,
    )
    val tones =
        when (tier) {
            RipDpiStaleTier.Fresh -> {
                TierTones(colors.success, colors.background, colors.success, Color.Transparent)
            }

            RipDpiStaleTier.Recent -> {
                TierTones(colors.card, colors.foreground, colors.mutedForeground, colors.border)
            }

            RipDpiStaleTier.Aging -> {
                TierTones(colors.muted, colors.foreground, colors.mutedForeground, colors.border)
            }

            RipDpiStaleTier.Stale -> {
                TierTones(colors.warningContainer, colors.warning, Color.Transparent, Color.Transparent)
            }

            RipDpiStaleTier.Expired -> {
                TierTones(colors.destructiveContainer, colors.destructive, Color.Transparent, Color.Transparent)
            }
        }
    Row(
        modifier =
            modifier
                .background(tones.container, RoundedCornerShape(percent = 50))
                .border(width = 1.dp, color = tones.border, shape = RoundedCornerShape(percent = 50))
                .padding(horizontal = RipDpiThemeTokens.spacing.sm, vertical = 3.dp)
                .semantics { contentDescription = "$label, ${tier.name.lowercase()} data" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .background(tones.dot, CircleShape),
        )
        Text(text = label, style = RipDpiThemeTokens.type.monoSmall.copy(color = tones.content))
    }
}

@Preview(showBackground = true, name = "RipDpiStaleDataBadge — all tiers (light)")
@Composable
private fun RipDpiStaleDataBadgePreviewLight() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiStaleDataBadge(label = "just now", tier = RipDpiStaleTier.Fresh)
            RipDpiStaleDataBadge(label = "14 s ago", tier = RipDpiStaleTier.Recent)
            RipDpiStaleDataBadge(label = "3 m ago", tier = RipDpiStaleTier.Aging)
            RipDpiStaleDataBadge(label = "18 m ago", tier = RipDpiStaleTier.Stale)
            RipDpiStaleDataBadge(label = "2 h ago", tier = RipDpiStaleTier.Expired)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiStaleDataBadge — all tiers (dark)")
@Composable
private fun RipDpiStaleDataBadgePreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiStaleDataBadge(label = "just now", tier = RipDpiStaleTier.Fresh)
            RipDpiStaleDataBadge(label = "18 m ago", tier = RipDpiStaleTier.Stale)
        }
    }
}
