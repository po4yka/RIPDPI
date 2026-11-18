package com.poyka.ripdpi.ui.screens.connection

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.components.buttons.RipDpiTextAction
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyProbeValueLabels
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
    onApplyLane: (String) -> Unit = {},
) {
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    val running = state.progress != null
    // The lane the user tapped shows its detail. A new result closes it.
    var inspected by rememberSaveable(state.finishedAt, running) { mutableIntStateOf(-1) }
    // The sweep plays once for each result. Coming back to the screen must not replay it.
    var sweptResult by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    val sweep =
        remember(state.finishedAt) {
            // A running test adds lanes one by one, so nothing is left to sweep.
            Animatable(if (motion.animationsEnabled && !running && sweptResult != state.finishedAt) 0f else 1f)
        }
    LaunchedEffect(sweep) {
        sweep.animateTo(1f, motion.signatureTween())
        sweptResult = state.finishedAt
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // No side padding: the lanes share the left and right edges of the rows around them.
                .padding(vertical = spacing.sm)
                .ripDpiTestTag(RipDpiTestTags.ConnectionStrategyLanes),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        val count = state.lanes.size
        if (count == 0) PlaceholderLaneRows()
        if (state.lanes.any { it.stage != null }) StrategyGatesHeader(state.lanes)
        state.lanes.forEachIndexed { index, lane ->
            StrategyLaneRow(
                lane = lane,
                stale = state.otherNetwork,
                progress = { ((sweep.value * (count + StaggerSpan - 1) - index) / StaggerSpan).coerceIn(0f, 1f) },
                // A skipped lane has nothing more to show than its row.
                onClick =
                    { inspected = if (inspected == index) -1 else index }
                        .takeIf { lane.outcome != StrategyLaneOutcome.Skipped && !running },
            )
            if (inspected == index) {
                StrategyLaneDetail(
                    lane = lane,
                    // A result from another network, and the strategy already in use, offer nothing to apply.
                    onApply =
                        {
                            onApplyLane(
                                lane.id,
                            )
                        }.takeIf { lane.applicable && !lane.current && !state.otherNetwork },
                )
            }
        }
        StrategyLanesFooter(state)
    }
}

private const val PlaceholderLanes = 3
private const val PlaceholderSegments = 6

/** Empty lanes for a device with no strategy test yet: the shape of what the test gives. */
@Composable
internal fun ConnectionStrategyLanesPlaceholder(modifier: Modifier = Modifier) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        // Decoration only: the action below it names the test for a screen reader.
        modifier = modifier.fillMaxWidth().padding(vertical = spacing.sm).clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        PlaceholderLaneRows()
    }
}

@Composable
private fun PlaceholderLaneRows() {
    val spacing = RipDpiThemeTokens.spacing
    val track = RipDpiThemeTokens.colors.outlineVariant
    val empty =
        StrategyLaneUiState(
            label = "",
            outcome = StrategyLaneOutcome.Blocked,
            succeededTargets = 0,
            totalTargets = PlaceholderSegments,
            latencyMs = null,
            recommended = false,
        )
    repeat(PlaceholderLanes) {
        Row(modifier = Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Canvas(Modifier.weight(NameWeight).height(LaneHeight)) {
                scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
                    drawLine(
                        color = track,
                        start = Offset(LaneStroke.toPx() / 2, size.height / 2),
                        end = Offset(size.width / 2, size.height / 2),
                        strokeWidth = LaneStroke.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            Canvas(Modifier.weight(LaneWeight).height(LaneHeight)) { drawLane(empty, 0f, track, track, track) }
        }
    }
}

// Where the TCP, TLS, and HTTP gates stand on a lane, as shares of its length.
private val GateShares = floatArrayOf(0.25f, 0.53f, 0.81f)

/**
 * The names of the gates, each ending at its gate. The gate most blocked lanes stop at is the dark one:
 * that is where the blocking acts on this network.
 */
@Composable
private fun StrategyGatesHeader(lanes: List<StrategyLaneUiState>) {
    val colors = RipDpiThemeTokens.colors
    val stacked = LocalDensity.current.fontScale >= StackedLaneFontScale
    val dominant =
        lanes
            .mapNotNull { it.stage }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<StrategyLaneStage, Int>> { it.value }.thenByDescending { it.key })
            ?.key
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        if (!stacked) Spacer(Modifier.weight(NameWeight))
        Row(Modifier.weight(if (stacked) 1f else LaneWeight)) {
            StrategyLaneStage.entries.forEachIndexed { index, stage ->
                Text(
                    text = stage.name.uppercase(),
                    style = RipDpiThemeTokens.type.monoSmall,
                    fontWeight = if (stage == dominant) FontWeight.SemiBold else null,
                    color = if (stage == dominant) colors.foreground else colors.mutedForeground,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(GateShares[index] - (GateShares.getOrNull(index - 1) ?: 0f)),
                )
            }
            Spacer(Modifier.weight(1f - GateShares.last()))
        }
        // shortcut: the header copies the two value columns of a lane row to end at the same edge; a value
        // wider than its minimum moves that row's gates. Lay the lanes out as one grid if that shows.
        val sample = lanes.firstOrNull { it.outcome != StrategyLaneOutcome.Skipped } ?: lanes.first()
        Text(
            text = "${sample.succeededTargets}/${sample.totalTargets}",
            style = RipDpiThemeTokens.type.monoSmall,
            modifier = Modifier.alpha(0f),
        )
        Spacer(Modifier.width(ValueMinWidth))
    }
}

/** What a tapped lane adds to its row: the sites in words, the gate it stopped at, and the way to use it. */
@Composable
private fun StrategyLaneDetail(
    lane: StrategyLaneUiState,
    onApply: (() -> Unit)?,
) {
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.ConnectionStrategyLaneDetail),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                listOfNotNull(
                    stringResource(R.string.config_strategy_lanes_sites, lane.succeededTargets, lane.totalTargets),
                    lane.stage?.name?.uppercase(),
                ).joinToString(" · "),
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
            modifier = Modifier.weight(1f),
        )
        if (onApply != null) {
            RipDpiTextAction(
                text = stringResource(R.string.strategy_tuner_apply),
                onClick = onApply,
                color = colors.foreground,
            )
        }
    }
}

/** The age of the test, whether it is from another network, and how many candidates the lanes leave out. */
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
        val notes =
            listOfNotNull(
                stringResource(R.string.config_strategy_lanes_other_network).takeIf { state.otherNetwork },
                stringResource(R.string.config_strategy_lanes_more, state.hiddenCount).takeIf { state.hiddenCount > 0 },
            )
        Text(text = notes.joinToString(" · "), style = style, color = color)
        // A running test has no age yet; it shows how far it is.
        val steps = state.progress?.let { "${it.completedSteps}/${it.totalSteps}" }
        Text(text = steps ?: age, style = style, color = color)
    }
}

private fun relativeAge(finishedAt: Long): String =
    DateUtils
        .getRelativeTimeSpanString(finishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

@Composable
private fun StrategyLaneRow(
    lane: StrategyLaneUiState,
    stale: Boolean,
    progress: () -> Float,
    onClick: (() -> Unit)?,
) {
    val colors = RipDpiThemeTokens.colors
    val passes = lane.outcome == StrategyLaneOutcome.Passed || lane.outcome == StrategyLaneOutcome.Partial
    val winner = stringResource(R.string.diagnostics_strategy_selection_winner)
    val stacked = LocalDensity.current.fontScale >= StackedLaneFontScale
    val context = LocalContext.current
    // A translated family name where the app has one; the engine label is English only.
    val laneName =
        remember(lane, context) {
            lane.family?.let(StrategyProbeValueLabels(AndroidStringResolver(context))::knownFamily) ?: lane.label
        }
    val name: @Composable (Modifier) -> Unit = { nameModifier ->
        Text(
            text = laneName,
            style = RipDpiThemeTokens.type.caption,
            fontWeight = if (lane.recommended) FontWeight.SemiBold else null,
            color = if (passes && !stale) colors.foreground else colors.mutedForeground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = nameModifier,
        )
    }
    val tap by rememberUpdatedState(onClick)
    val rowModifier =
        Modifier
            .fillMaxWidth()
            // The tap has no semantics of its own: a second source on this node would split it in two for
            // TalkBack. The action is declared in the one block below.
            .pointerInput(onClick != null) { if (onClick != null) detectTapGestures { tap?.invoke() } }
            .semantics(mergeDescendants = true) {
                if (lane.recommended) stateDescription = winner
                if (onClick != null) {
                    onClick {
                        onClick()
                        true
                    }
                }
            }
    val gap = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)
    if (stacked) {
        Column(modifier = rowModifier) {
            name(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
                StrategyLaneTrack(lane, passes && !stale, stale, progress, Modifier.weight(1f))
            }
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = gap) {
            name(Modifier.weight(NameWeight))
            StrategyLaneTrack(lane, passes && !stale, stale, progress, Modifier.weight(LaneWeight))
        }
    }
}

/** The lane, the sites it opened, and its time or state. */
@Composable
private fun RowScope.StrategyLaneTrack(
    lane: StrategyLaneUiState,
    passes: Boolean,
    stale: Boolean,
    progress: () -> Float,
    laneModifier: Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    // The length of the fill is the result. Colour only names two lanes: the winner and the strategy in use.
    // A result from another network keeps its shape but loses both.
    val fill =
        when {
            stale -> colors.mutedForeground
            lane.recommended -> colors.signal
            lane.current -> colors.foreground
            else -> colors.mutedForeground
        }
    val track = colors.outlineVariant
    val stop = if (stale) colors.mutedForeground else colors.foreground
    Canvas(laneModifier.height(LaneHeight)) { drawLane(lane, progress(), fill, track, stop) }
    val skipped = lane.outcome == StrategyLaneOutcome.Skipped || lane.totalTargets <= 0
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
                stale -> colors.mutedForeground
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
        StrategyLaneOutcome.Running -> ""
        else -> lane.latencyMs?.let { stringResource(R.string.unit_milliseconds_format, it) }.orEmpty()
    }

private fun DrawScope.drawLane(
    lane: StrategyLaneUiState,
    progress: Float,
    fill: Color,
    track: Color,
    stop: Color,
) {
    val y = size.height / 2
    val mirror = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    lane.stage?.let { stage ->
        // A blocked lane runs as far as the gate that stopped it; the rest of the way stays open.
        val gate = size.width * GateShares[stage.ordinal]
        val stroke = LaneStroke.toPx()
        scale(scaleX = mirror, scaleY = 1f) {
            drawLine(
                color = track,
                start = Offset(gate, y),
                end = Offset(size.width, y),
                strokeWidth = stroke / 2,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke, stroke * 2)),
            )
            val reach = (gate - stroke * 2) * progress
            if (reach > stroke) {
                drawLine(fill, Offset(stroke / 2, y), Offset(reach, y), stroke, StrokeCap.Round)
            }
            if (progress >= 1f) {
                drawRoundRect(stop, Offset(gate - stroke / 2, 0f), Size(stroke, size.height), CornerRadius(stroke / 2))
            }
        }
        return
    }
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
    scale(scaleX = mirror, scaleY = 1f) {
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
                            lane("Current strategy", passed, succeeded = 6, latencyMs = 110).copy(current = true),
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
