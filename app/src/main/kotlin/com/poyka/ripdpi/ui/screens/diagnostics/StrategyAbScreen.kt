package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * UI state for a single per-target row in the strategy comparison.
 *
 * @param name      Domain / host under test.
 * @param latencyMs Round-trip latency in milliseconds, or null when the
 *                  target could not be reached.
 * @param passed    Whether the strategy passed this target.
 */
data class StrategyTargetState(
    val name: String,
    val latencyMs: Int?,
    val passed: Boolean,
)

/**
 * UI state for one side of the A/B comparison panel.
 *
 * @param name      Strategy identifier (e.g. "desync_fake").
 * @param label     Badge label ("CURRENT" or "CANDIDATE").
 * @param isCurrent Whether this is the active production strategy.
 * @param isWinner  Whether this side won the comparison.
 * @param score     Number of targets passed.
 * @param total     Total number of targets tested.
 * @param targets   Per-target detail rows.
 */
data class StrategySideState(
    val name: String,
    val label: String,
    val isCurrent: Boolean,
    val isWinner: Boolean,
    val score: Int,
    val total: Int,
    val targets: List<StrategyTargetState>,
)

/**
 * UI state for the full A/B strategy comparison screen.
 *
 * @param strategyA     Left-side (current) strategy panel.
 * @param strategyB     Right-side (candidate) strategy panel.
 * @param verdictLabel  Human-readable verdict summary shown in the action strip.
 * @param winnerName    Name of the winning strategy (unused in display, kept for callers).
 */
data class StrategyAbState(
    val strategyA: StrategySideState,
    val strategyB: StrategySideState,
    val verdictLabel: String,
    val winnerName: String,
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Presentation-only A/B strategy comparison screen matching
 * `docs/design/rds/preview/vpn-strategy-ab.html`.
 *
 * Shows a two-column side-by-side layout: strategy A (current) on the left
 * and strategy B (candidate) on the right. Each column shows a score,
 * a per-target detail list with pass/fail icons and latency values, and a
 * header badge. A verdict strip below the columns summarises the winner and
 * exposes a Switch CTA. All tokens come from [RipDpiThemeTokens]; no literal
 * colours, dp values outside `ui/theme/`, or animation-spec constants appear
 * in this file.
 */
@Composable
fun StrategyAbScreen(
    state: StrategyAbState,
    onSwitch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.strategy_ab_title),
        modifier = modifier,
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(RipDpiThemeTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            CompareRow(
                strategyA = state.strategyA,
                strategyB = state.strategyB,
            )
            VerdictStrip(
                verdictLabel = state.verdictLabel,
                onSwitch = onSwitch,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Two-column compare layout
// ---------------------------------------------------------------------------

@Composable
private fun CompareRow(
    strategyA: StrategySideState,
    strategyB: StrategySideState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        StrategySidePanel(
            side = strategyA,
            modifier = Modifier.weight(1f),
        )
        StrategySidePanel(
            side = strategyB,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Single strategy panel
// ---------------------------------------------------------------------------

@Composable
private fun StrategySidePanel(
    side: StrategySideState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    val borderColor = if (side.isWinner) colors.success else colors.cardBorder
    val badgeBackground = if (side.isWinner) colors.success else colors.muted
    val badgeForeground = if (side.isWinner) colors.foreground else colors.mutedForeground

    Surface(
        modifier = modifier,
        shape = shapes.lg,
        color = colors.card,
        contentColor = colors.foreground,
        border = BorderStroke(RipDpiStroke.Thin, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
        ) {
            // Header: strategy name + badge
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = side.name,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Surface(
                    shape = shapes.xxl,
                    color = badgeBackground,
                    contentColor = badgeForeground,
                ) {
                    Text(
                        text = side.label,
                        style = type.monoSmall,
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                    )
                }
            }

            // Score
            Text(
                text = "${side.score}/${side.total}",
                style = type.brandStatus,
                color = colors.foreground,
                modifier = Modifier.padding(bottom = spacing.sm),
            )

            // Per-target rows
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                side.targets.forEach { target ->
                    TargetRow(target = target)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Target row
// ---------------------------------------------------------------------------

@Composable
private fun TargetRow(
    target: StrategyTargetState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    val iconTint = if (target.passed) colors.success else colors.destructive
    val icon = if (target.passed) RipDpiIcons.Check else RipDpiIcons.Warning

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(RipDpiIconSizes.Small),
        )
        Text(
            text = target.name,
            style = type.monoSmall,
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = target.latencyMs?.let { "$it ms" } ?: "—",
            style = type.monoSmall,
            color = colors.mutedForeground,
        )
    }
}

// ---------------------------------------------------------------------------
// Verdict strip
// ---------------------------------------------------------------------------

@Composable
private fun VerdictStrip(
    verdictLabel: String,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.md,
        color = colors.infoContainer,
        contentColor = colors.infoContainerForeground,
        border = BorderStroke(RipDpiStroke.Thin, colors.success),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = verdictLabel,
                style = type.body,
                color = colors.infoContainerForeground,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            RipDpiButton(
                text = stringResource(R.string.strategy_ab_action_switch),
                onClick = onSwitch,
                variant = RipDpiButtonVariant.Primary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
private fun StrategyAbScreenLightPreview() {
    RipDpiComponentPreview {
        StrategyAbScreen(
            state = sampleStrategyAbState(),
            onSwitch = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StrategyAbScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        StrategyAbScreen(
            state = sampleStrategyAbState(),
            onSwitch = {},
            onBack = {},
        )
    }
}
