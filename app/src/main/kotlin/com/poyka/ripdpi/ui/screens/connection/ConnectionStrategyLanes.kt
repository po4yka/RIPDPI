package com.poyka.ripdpi.ui.screens.connection

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

private const val NameWeight = 0.42f
private const val LaneWeight = 0.58f
private const val MaxLaneSegments = 12

// Each lane starts one step after the lane above it and takes this many steps to fill.
private const val StaggerSpan = 3f

// From this font scale a name no longer fits beside its lane, so it moves above it.
private const val StackedLaneFontScale = 1.3f
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
    // The sweep plays once for each result. Coming back to the screen must not replay it.
    var sweptResult by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    val sweep =
        remember(state.finishedAt) {
            Animatable(if (motion.animationsEnabled && sweptResult != state.finishedAt) 0f else 1f)
        }
    LaunchedEffect(sweep) {
        sweep.animateTo(1f, motion.signatureTween())
        sweptResult = state.finishedAt
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
        StrategyLanesFooter(state)
    }
}

/** The age of the test, and how many candidates the lanes leave out. */
@Composable
private fun StrategyLanesFooter(state: StrategyLanesUiState) {
    val age by produceState(relativeAge(state.finishedAt), state.finishedAt) {
        while (true) {
            // First, so a new result does not keep the age of the result before it.
            value = relativeAge(state.finishedAt)
            delay(DateUtils.MINUTE_IN_MILLIS)
        }
    }
    val style = RipDpiThemeTokens.type.caption
    val color = RipDpiThemeTokens.colors.mutedForeground
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (state.hiddenCount > 0) {
            Text(
                text = stringResource(R.string.config_strategy_lanes_more, state.hiddenCount),
                style = style,
                color = color,
            )
        } else {
            Spacer(Modifier)
        }
        Text(text = age, style = style, color = color)
    }
}

private fun relativeAge(finishedAt: Long): String =
    DateUtils
        .getRelativeTimeSpanString(finishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

@Composable
private fun StrategyLaneRow(
    lane: StrategyLaneUiState,
    progress: () -> Float,
) {
    val colors = RipDpiThemeTokens.colors
    val passes = lane.outcome == StrategyLaneOutcome.Passed || lane.outcome == StrategyLaneOutcome.Partial
    val winner = stringResource(R.string.diagnostics_strategy_selection_winner)
    val stacked = LocalDensity.current.fontScale >= StackedLaneFontScale
    val name: @Composable (Modifier) -> Unit = { nameModifier ->
        Text(
            text = lane.label,
            style = RipDpiThemeTokens.type.caption,
            fontWeight = if (lane.recommended) FontWeight.SemiBold else null,
            color = if (passes) colors.foreground else colors.mutedForeground,
            maxLines = if (stacked) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = nameModifier,
        )
    }
    val rowModifier =
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            if (lane.recommended) stateDescription = winner
        }
    val gap = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)
    if (stacked) {
        Column(modifier = rowModifier) {
            name(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                StrategyLaneTrack(lane, passes, progress, Modifier.weight(1f))
            }
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
            name(Modifier.weight(NameWeight))
            StrategyLaneTrack(lane, passes, progress, Modifier.weight(LaneWeight))
        }
    }
}

/** The lane, the sites it opened, and its time or state. */
@Composable
private fun RowScope.StrategyLaneTrack(
    lane: StrategyLaneUiState,
    passes: Boolean,
    progress: () -> Float,
    laneModifier: Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    // A blocked candidate can still open a site or two; those are muted so they do not look like a pass.
    val fill =
        when {
            lane.recommended -> colors.signal
            lane.outcome == StrategyLaneOutcome.Passed -> colors.success
            lane.outcome == StrategyLaneOutcome.Partial -> colors.warning
            else -> colors.mutedForeground
        }
    val track = colors.outlineVariant
    Canvas(laneModifier.height(LaneHeight)) { drawLane(lane, progress(), fill, track) }
    val skipped = lane.outcome == StrategyLaneOutcome.Skipped
    val sites = "${lane.succeededTargets}/${lane.totalTargets}"
    val sitesLabel = stringResource(R.string.config_strategy_lanes_sites, lane.succeededTargets, lane.totalTargets)
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
        fontWeight = if (lane.recommended) FontWeight.SemiBold else null,
        color =
            when {
                lane.recommended -> colors.signal
                passes -> colors.foreground
                else -> colors.mutedForeground
            },
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.widthIn(min = ValueMinWidth),
    )
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

@Preview(showBackground = true)
@Composable
private fun ConnectionStrategyLanesPreview() {
    fun lane(
        label: String,
        outcome: StrategyLaneOutcome,
        succeeded: Int = 0,
        latencyMs: Long? = null,
        recommended: Boolean = false,
    ) = StrategyLaneUiState(
        label = label,
        outcome = outcome,
        succeededTargets = succeeded,
        totalTargets = if (outcome == StrategyLaneOutcome.Skipped) 0 else PreviewTargets,
        latencyMs = latencyMs,
        recommended = recommended,
    )
    val passed = StrategyLaneOutcome.Passed
    RipDpiTheme {
        ConnectionStrategyLanes(
            state =
                StrategyLanesUiState(
                    lanes =
                        persistentListOf(
                            lane("Fake + split", passed, succeeded = 6, latencyMs = 84, recommended = true),
                            lane("OOB byte", passed, succeeded = 6, latencyMs = 110),
                            lane("Disorder", StrategyLaneOutcome.Partial, succeeded = 5, latencyMs = 132),
                            lane("Split at SNI", StrategyLaneOutcome.Blocked, succeeded = 1),
                            lane("QUIC drop", StrategyLaneOutcome.Skipped),
                        ),
                    finishedAt = System.currentTimeMillis(),
                    hiddenCount = 4,
                ),
        )
    }
}

private const val PreviewTargets = 6
