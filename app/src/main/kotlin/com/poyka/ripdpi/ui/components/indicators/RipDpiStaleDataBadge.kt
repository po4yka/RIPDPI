package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.time.Duration

private const val FreshCutoffSeconds = 5
private const val RecentCutoffMinutes = 1
private const val AgingCutoffMinutes = 5
private const val StaleCutoffMinutes = 30

enum class RipDpiStaleTier {
    /** < 5s — data is updating now. Spec shows a pulse here. */
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
        age.inWholeSeconds < FreshCutoffSeconds -> RipDpiStaleTier.Fresh
        age.inWholeMinutes < RecentCutoffMinutes -> RipDpiStaleTier.Recent
        age.inWholeMinutes < AgingCutoffMinutes -> RipDpiStaleTier.Aging
        age.inWholeMinutes < StaleCutoffMinutes -> RipDpiStaleTier.Stale
        else -> RipDpiStaleTier.Expired
    }

/**
 * Tier to render for a *live* telemetry panel whose snapshot is [age] old, given
 * the [activePollInterval] currently driving updates — or `null` when the data is
 * still fresh (younger than `2 ×` the poll interval) and the badge should be
 * omitted entirely.
 *
 * A healthy poll cycle keeps the snapshot age below one interval, well under the
 * `2 ×` gate, so no badge ever flashes on a live, updating panel; the badge only
 * appears once updates visibly stall. The function is pure and monotonic in
 * [age]: for a fixed age it always returns the same result, so it cannot flicker
 * at the threshold between recompositions — only a real change in age can flip it.
 *
 * The `2 ×` gate (e.g. 2 s at a 1 s poll) is tighter than the [RipDpiStaleTier.Fresh]
 * window (< 5 s), so [staleTierFor] would otherwise label a just-stalled panel
 * "Fresh" (a green *updating* pulse) — the opposite of the intended signal. The
 * Fresh tier is therefore collapsed into [RipDpiStaleTier.Recent]: when this
 * returns non-null the data is, by construction, no longer fresh.
 */
fun liveStaleBadgeTier(
    age: Duration,
    activePollInterval: Duration,
): RipDpiStaleTier? {
    if (age < activePollInterval * 2) return null
    val tier = staleTierFor(age)
    return if (tier == RipDpiStaleTier.Fresh) RipDpiStaleTier.Recent else tier
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
    val motion = RipDpiThemeTokens.motion
    val freshPulseAlpha =
        if (tier == RipDpiStaleTier.Fresh && motion.allowsInfiniteMotion) {
            val transition = rememberInfiniteTransition(label = "freshPulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.4f,
                animationSpec = motion.pulseSpec(),
                label = "freshPulse",
            )
            alpha
        } else {
            1f
        }
    val tierLabel =
        stringResource(
            when (tier) {
                RipDpiStaleTier.Fresh -> R.string.stale_tier_fresh
                RipDpiStaleTier.Recent -> R.string.stale_tier_recent
                RipDpiStaleTier.Aging -> R.string.stale_tier_aging
                RipDpiStaleTier.Stale -> R.string.stale_tier_stale
                RipDpiStaleTier.Expired -> R.string.stale_tier_expired
            },
        )
    val badgeDescription = stringResource(R.string.cd_stale_data_badge, label, tierLabel)
    val indicators = RipDpiThemeTokens.components.indicators
    Row(
        modifier =
            modifier
                .background(tones.container, RoundedCornerShape(percent = 50))
                .border(width = RipDpiStroke.Thin, color = tones.border, shape = RoundedCornerShape(percent = 50))
                .padding(
                    horizontal = RipDpiThemeTokens.spacing.sm,
                    vertical = indicators.staleBadgeVerticalPadding,
                ).semantics { contentDescription = badgeDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Box(
            modifier =
                Modifier
                    .size(indicators.staleBadgeDotSize)
                    .background(tones.dot.copy(alpha = freshPulseAlpha), CircleShape),
        )
        Text(text = label, style = RipDpiThemeTokens.type.monoSmall.copy(color = tones.content))
    }
}

@Preview(showBackground = true, name = "RipDpiStaleDataBadge — all tiers (light)")
@Composable
private fun RipDpiStaleDataBadgeLightPreview() {
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
private fun RipDpiStaleDataBadgeDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiStaleDataBadge(label = "just now", tier = RipDpiStaleTier.Fresh)
            RipDpiStaleDataBadge(label = "18 m ago", tier = RipDpiStaleTier.Stale)
        }
    }
}
