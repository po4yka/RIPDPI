package com.poyka.ripdpi.ui.screens.connection

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val NameWeight = 0.42f
private const val LaneWeight = 0.58f
private const val MaxLaneSegments = 12

// Each lane starts one step after the lane above it and takes this many steps to fill.
private const val StaggerSpan = 3f
private val LaneHeight = 12.dp
private val LaneStroke = 3.dp
private val RecommendedLaneStroke = 4.dp
private val SegmentGap = 2.dp
private val ValueMinWidth = 56.dp

/**
 * The last strategy test as lanes: one per candidate, one segment per test site.
 * A lane fills as far as the sites that candidate opened.
 */
@Composable
internal fun ConnectionStrategyLanes(
    state: StrategyLanesUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    // The sweep runs when the lanes appear and again for a new result, not on recomposition.
    val sweep = remember(state.finishedAt) { Animatable(if (motion.animationsEnabled) 0f else 1f) }
    LaunchedEffect(sweep) { sweep.animateTo(1f, motion.signatureTween()) }
    val age =
        remember(state.finishedAt) {
            DateUtils
                .getRelativeTimeSpanString(state.finishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString()
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = spacing.sm)
                .ripDpiTestTag(RipDpiTestTags.ConnectionStrategyLanes),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        val count = state.lanes.size
        state.lanes.forEachIndexed { index, lane ->
            StrategyLaneRow(
                lane = lane,
                progress = { ((sweep.value * (count + StaggerSpan - 1) - index) / StaggerSpan).coerceIn(0f, 1f) },
            )
        }
        Text(
            text = age,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun StrategyLaneRow(
    lane: StrategyLaneUiState,
    progress: () -> Float,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val passes = lane.outcome == StrategyLaneOutcome.Passed || lane.outcome == StrategyLaneOutcome.Partial
    val fill =
        when {
            lane.recommended -> colors.signal
            lane.outcome == StrategyLaneOutcome.Partial -> colors.warning
            else -> colors.success
        }
    val textColor = if (passes) colors.foreground else colors.mutedForeground
    val weight = if (lane.recommended) FontWeight.SemiBold else null
    val winner = stringResource(R.string.diagnostics_strategy_selection_winner)
    val sites = "${lane.succeededTargets}/${lane.totalTargets}"
    val sitesLabel = stringResource(R.string.diagnostics_strategy_metric_targets) + " " + sites
    Row(
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                if (lane.recommended) stateDescription = winner
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        Text(
            text = lane.label,
            style = type.caption,
            fontWeight = weight,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(NameWeight),
        )
        val track = colors.outlineVariant
        Canvas(Modifier.weight(LaneWeight).height(LaneHeight)) { drawLane(lane, progress(), fill, track) }
        val skipped = lane.outcome == StrategyLaneOutcome.Skipped
        Text(
            text = sites,
            style = type.monoSmall,
            color = colors.mutedForeground,
            // A skipped lane has no sites, but it keeps the column so every lane ends at one edge.
            modifier =
                if (skipped) {
                    Modifier.alpha(0f).clearAndSetSemantics {}
                } else {
                    Modifier.semantics { contentDescription = sitesLabel }
                },
        )
        Text(
            text = laneValue(lane),
            style = type.monoSmall,
            fontWeight = weight,
            color = if (lane.recommended) colors.signal else textColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = ValueMinWidth),
        )
    }
}

@Composable
private fun laneValue(lane: StrategyLaneUiState): String =
    when (lane.outcome) {
        StrategyLaneOutcome.Blocked -> stringResource(R.string.status_diagnostics_capability_blocked)
        StrategyLaneOutcome.Skipped -> stringResource(R.string.diagnostics_probe_status_skipped)
        else -> lane.latencyMs?.let { stringResource(R.string.unit_milliseconds_format, it) }.orEmpty()
    }

private fun DrawScope.drawLane(
    lane: StrategyLaneUiState,
    progress: Float,
    fill: Color,
    track: Color,
) {
    val y = size.height / 2
    if (lane.outcome == StrategyLaneOutcome.Skipped) {
        val dash = SegmentGap.toPx() * 2
        drawLine(
            color = track,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = LaneStroke.toPx() / 2,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        )
        return
    }
    // A long site list shares the lane: the filled share stays right, single sites merge.
    val segments = lane.totalTargets.coerceIn(1, MaxLaneSegments)
    val filled = lane.succeededTargets.toFloat() / lane.totalTargets.coerceAtLeast(1) * segments
    val gap = SegmentGap.toPx()
    val width = (size.width - gap * (segments - 1)) / segments
    val stroke = (if (lane.recommended) RecommendedLaneStroke else LaneStroke).toPx()
    val radius = CornerRadius(stroke / 2)
    // The lane fills from the start edge, so it mirrors in right-to-left layouts.
    scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
        repeat(segments) { index ->
            val topLeft = Offset(index * (width + gap), y - stroke / 2)
            drawRoundRect(track, topLeft, Size(width, stroke), radius)
            val share = (filled * progress - index).coerceIn(0f, 1f)
            if (share > 0f) drawRoundRect(fill, topLeft, Size(width * share, stroke), radius)
        }
    }
}
