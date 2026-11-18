package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ConnectionStageState
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** The part of the scope that holds one family of probes, clockwise from the top. */
internal enum class ScopeSector(
    @param:StringRes val label: Int,
) {
    Dns(R.string.diagnostics_phase_dns),
    Tcp(R.string.diagnostics_phase_tcp),
    Reach(R.string.diagnostics_phase_reach),
    Quic(R.string.diagnostics_phase_quic),
    Service(R.string.diagnostics_phase_svc),
}

internal enum class ScopeMarkKind { Answered, Blocked, Silent }

@Immutable
internal data class ScopeMark(
    val probe: DiagnosticsProbeResultUiModel,
    val kind: ScopeMarkKind,
    /** Clockwise from the top, as a fraction of the turn. */
    val turn: Float,
    /** From the hub (0) to the rim (1). Distance is the measured time; [UnmeasuredReach] is the lane with no time. */
    val reach: Float,
)

/** The marks and the turn at which each sector starts, with the end of the last sector as the final value. */
@Immutable
internal data class ScopeLayout(
    val marks: List<ScopeMark>,
    val sectorStarts: List<Float>,
)

private fun scopeSector(probeType: String): ScopeSector? =
    when (probeType) {
        "dns_integrity", "dns", "doh_json_survey" -> ScopeSector.Dns
        "tcp_fat_header", "tcp" -> ScopeSector.Tcp
        "domain_reachability", "strategy_https", "strategy_http" -> ScopeSector.Reach
        "quic_reachability", "strategy_quic", "http3" -> ScopeSector.Quic
        "service_reachability", "circumvention_reachability", "telegram_availability", "telegram" -> ScopeSector.Service
        else -> null
    }

/** The place of [millis] on the log time scale, from the hub to the last measured place. */
internal fun scopeReach(millis: Long): Float =
    MaxMeasuredReach *
        log10(millis.coerceIn(MinScaleMillis, MaxScaleMillis) / MinScaleMillis.toFloat()) /
        log10(MaxScaleMillis / MinScaleMillis.toFloat())

/** Every probe gets a mark. A probe with no measured time goes to the outer lane, whether it answered or not. */
private fun DiagnosticsProbeResultUiModel.toScopeMark(): ScopeMark {
    val stages = connectionStages?.lanes.orEmpty().flatMap { it.stages }
    val millis = stages.mapNotNull { it.elapsedMs ?: it.durationMs }.maxOrNull()
    val failed = tone == DiagnosticsTone.Negative || tone == DiagnosticsTone.Warning
    val kind =
        when {
            !failed -> ScopeMarkKind.Answered
            millis == null || stages.any { it.state == ConnectionStageState.TIMED_OUT } -> ScopeMarkKind.Silent
            else -> ScopeMarkKind.Blocked
        }
    val measured = millis?.takeIf { kind != ScopeMarkKind.Silent }
    return ScopeMark(this, kind, 0f, measured?.let(::scopeReach) ?: UnmeasuredReach)
}

/**
 * Places the probes that have a sector. A sector with more probes gets a wider part of the turn. The sectors leave
 * [ScaleGap] free at the top, where the scale labels stand, so that no mark covers a label.
 */
internal fun scopeLayout(results: List<DiagnosticsProbeResultUiModel>): ScopeLayout {
    val sectors =
        ScopeSector.entries.map { sector ->
            results.filter { scopeSector(it.probeType) == sector }.map { it.toScopeMark() }
        }
    val weights = sectors.map { it.size + SectorBaseWeight }
    val starts =
        weights.runningFold(ScaleGap / 2) { start, weight -> start + (1f - ScaleGap) * weight / weights.sum() }
    val marks =
        sectors.flatMapIndexed { index, sector ->
            val span = starts[index + 1] - starts[index]
            sector.mapIndexed { slot, mark -> mark.copy(turn = starts[index] + span * (slot + 1f) / (sector.size + 1)) }
        }
    return ScopeLayout(marks, starts)
}

/**
 * The scan scope: each probe is a mark, and its distance from the hub is the measured answer time on a log scale.
 * The outer lane holds the probes with no measured time. While a scan runs, the arm sweeps, the arc at the hub shows
 * [progress], and the hub shows [steps]. When results arrive, the arm turns once and the marks land as it passes;
 * after that the scope is still, and the hub shows the probes with a problem out of all marks, or the number of
 * marks when none has a problem. The results list is the accessible form of the same data, so the drawing has no
 * semantics.
 */
@Composable
internal fun DiagnosticsScanScope(
    layout: ScopeLayout,
    progress: Float?,
    steps: Pair<Int, Int>?,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val performHaptic = rememberRipDpiHapticPerformer()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = RipDpiThemeTokens.type.smallLabel.copy(color = colors.mutedForeground)
    val sectorTexts = ScopeSector.entries.map { stringResource(it.label) }
    val scaleTexts = ScaleRingMillis.map { stringResource(R.string.unit_milliseconds_format, it) }
    val sectorLabels =
        remember(sectorTexts, labelStyle, density) { sectorTexts.map { measurer.measure(it, labelStyle) } }
    val scaleLabels = remember(scaleTexts, labelStyle, density) { scaleTexts.map { measurer.measure(it, labelStyle) } }
    val labelBand = remember(layout.sectorStarts, sectorLabels) { labelBand(layout.sectorStarts, sectorLabels) }
    val marks = layout.marks
    val scanning = progress != null
    val hubText = rememberHubText(marks, steps)
    val reveal = rememberScopeReveal(marks, scanning)
    val sweep = rememberScopeSweep(scanning && motion.allowsInfiniteMotion)
    val live = animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), motion.stateTween(), label = "Scope progress")
    val currentOnSelectProbe by rememberUpdatedState(onSelectProbe)
    val palette = ScopePalette(colors.outline, colors.signal, colors.success, colors.destructive, colors.warning)
    val scope = rememberCoroutineScope()
    // The ring on a tapped mark: it shows at once and fades, so no mark stays selected.
    val press = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf<ScopeMark?>(null) }

    Canvas(
        modifier
            .fillMaxWidth()
            .wrapContentWidth()
            .widthIn(max = MaxScopeWidth)
            .aspectRatio(1f)
            .semantics { hideFromAccessibility() }
            .pointerInput(marks, labelBand) {
                detectTapGestures { tap ->
                    val frame = scopeFrame(Size(size.width.toFloat(), size.height.toFloat()), labelBand)
                    val mark = frame.nearest(marks, tap, TouchRadius.toPx())
                    when {
                        // A tap during the reveal shows the whole result at once.
                        marks.isNotEmpty() && reveal.time.value < RevealEnd -> {
                            reveal.finish()
                        }

                        mark != null -> {
                            performHaptic(RipDpiHapticFeedback.Selection)
                            pressed = mark
                            scope.launch {
                                press.snapTo(1f)
                                press.animateTo(0f, motion.durationTween(PressMillis))
                            }
                            currentOnSelectProbe(mark.probe)
                        }
                    }
                }
            },
    ) {
        val frame = scopeFrame(size, labelBand)
        drawScopeFace(frame, palette.line, layout.sectorStarts, sectorLabels, scaleLabels)
        if (scanning) {
            drawSweep(frame, if (motion.allowsInfiniteMotion) sweep.floatValue else live.value, palette.arm)
            drawProgressArc(frame, live.value, palette.arm)
        } else {
            drawResult(frame, marks, reveal.time.value, palette)
            pressed?.takeIf { press.value > 0f }?.let {
                drawCircle(
                    colors.foreground.copy(alpha = press.value),
                    PressRadius.toPx() + RippleGrowth.toPx() * (1f - press.value),
                    frame.at(it),
                    style = Stroke(MarkStroke.toPx()),
                )
            }
        }
        drawHub(frame, palette.line, hubText)
    }
}

/**
 * The number in the hub: the steps during a scan, then the probes with a problem out of all marks, or the number of
 * marks when none has a problem. One format gives the digits, so they do not change script between the states.
 */
@Composable
private fun rememberHubText(
    marks: List<ScopeMark>,
    steps: Pair<Int, Int>?,
): TextLayoutResult? {
    val colors = RipDpiThemeTokens.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val problems = remember(marks) { marks.count { it.kind != ScopeMarkKind.Answered } }
    val label =
        when {
            steps != null -> "%d/%d".format(steps.first, steps.second)
            marks.isEmpty() -> null
            problems > 0 -> "%d/%d".format(problems, marks.size)
            else -> "%d".format(marks.size)
        }
    val color =
        when {
            steps != null -> colors.foreground
            problems > 0 -> colors.destructive
            else -> colors.success
        }
    val style = RipDpiThemeTokens.type.monoValue.copy(color = color)
    return remember(label, style, density) { label?.let { measurer.measure(it, style) } }
}

/** The one-off reveal of new results. [finish] shows the whole result at once. */
private class ScopeReveal(
    val time: Animatable<Float, AnimationVector1D>,
    val finish: () -> Unit,
)

@Composable
private fun rememberScopeReveal(
    marks: List<ScopeMark>,
    scanning: Boolean,
): ScopeReveal {
    val motion = RipDpiThemeTokens.motion
    val scope = rememberCoroutineScope()
    val resultKey = remember(marks) { marks.map { it.probe.id }.hashCode() }
    // The saved value names the results that were revealed, so new results get their own reveal.
    var revealedKey by rememberSaveable { mutableIntStateOf(0) }
    val time =
        remember(resultKey) {
            Animatable(if (revealedKey == resultKey || !motion.allowsInfiniteMotion) RevealEnd else 0f)
        }
    LaunchedEffect(resultKey, scanning) {
        if (!scanning && marks.isNotEmpty()) {
            time.animateTo(RevealEnd, motion.durationTween(RevealMillis, LinearEasing))
            revealedKey = resultKey
        }
    }
    return remember(time) {
        ScopeReveal(time) {
            scope.launch { time.snapTo(RevealEnd) }
            revealedKey = resultKey
        }
    }
}

/** The room that the upright sector labels need outside the rim, from the widest label at its own angle. */
private fun labelBand(
    sectorStarts: List<Float>,
    labels: List<TextLayoutResult>,
): Float = labels.indices.maxOf { labelExtent(sectorStarts, labels, it) }

/** How far label [index] reaches along the ray through the middle of its sector. */
private fun labelExtent(
    sectorStarts: List<Float>,
    labels: List<TextLayoutResult>,
    index: Int,
): Float {
    val angle = ((sectorStarts[index] + sectorStarts[index + 1]) / 2 - QuarterTurn) * 2 * PI
    return (abs(cos(angle)) * labels[index].size.width + abs(sin(angle)) * labels[index].size.height).toFloat()
}

/** The turn of the arm while a scan runs. It stays at 0 when [moves] is false. */
@Composable
private fun rememberScopeSweep(moves: Boolean): FloatState {
    val sweep = remember { mutableFloatStateOf(0f) }
    if (moves) {
        LaunchedEffect(Unit) {
            val start = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos { sweep.floatValue = (it - start) % SweepNanos / SweepNanos.toFloat() }
            }
        }
    }
    return sweep
}

private fun DrawScope.drawProgressArc(
    frame: ScopeFrame,
    progress: Float,
    color: Color,
) {
    val radius = frame.hub + HubGap.toPx()
    drawArc(
        color,
        TopDegrees,
        FullTurn * progress,
        useCenter = false,
        topLeft = frame.center - Offset(radius, radius),
        size = Size(2 * radius, 2 * radius),
        style = Stroke(ArmWidth.toPx(), cap = StrokeCap.Round),
    )
}

/** The reveal at [time]: the arm on its one turn, the marks it has passed, and the closing pulse. */
private fun DrawScope.drawResult(
    frame: ScopeFrame,
    marks: List<ScopeMark>,
    time: Float,
    palette: ScopePalette,
) {
    if (time > 0f && time < 1f) drawSweep(frame, time, palette.arm)
    marks.forEach { if (time >= it.turn) drawMark(frame.at(it), it, time - it.turn, palette) }
    val pulse = (time - 1f) / (RevealEnd - 1f)
    if (pulse > 0f && pulse < 1f) {
        drawCircle(
            palette.arm.copy(alpha = PulseAlpha * (1f - pulse)),
            frame.hub + (frame.rim - frame.hub) * pulse,
            frame.center,
            style = Stroke(ArmWidth.toPx()),
        )
    }
}

private class ScopePalette(
    val line: Color,
    val arm: Color,
    val answered: Color,
    val blocked: Color,
    val silent: Color,
)

private class ScopeFrame(
    val center: Offset,
    val hub: Float,
    val rim: Float,
) {
    fun point(
        turn: Float,
        radius: Float,
    ): Offset {
        val angle = (turn - QuarterTurn) * 2 * PI
        return center + Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
    }

    fun radius(reach: Float): Float = hub + (rim - hub) * (HubClearance + (1f - HubClearance) * reach)

    fun at(mark: ScopeMark): Offset = point(mark.turn, radius(mark.reach))

    /** The mark that is closest to [tap], when it is within [touch]. */
    fun nearest(
        marks: List<ScopeMark>,
        tap: Offset,
        touch: Float,
    ): ScopeMark? = marks.minByOrNull { (at(it) - tap).getDistance() }?.takeIf { (at(it) - tap).getDistance() <= touch }
}

/** The labels stand outside the rim, so a long or large label takes room from the dial and is not cut. */
private fun Density.scopeFrame(
    size: Size,
    labelBand: Float,
): ScopeFrame {
    val rim = (size.minDimension / 2 - labelBand - LabelGap.toPx()).coerceAtLeast(MinRim.toPx())
    return ScopeFrame(Offset(size.width / 2, size.height / 2), rim * HubShare, rim)
}

private fun DrawScope.drawScopeFace(
    frame: ScopeFrame,
    line: Color,
    sectorStarts: List<Float>,
    sectorLabels: List<TextLayoutResult>,
    scaleLabels: List<TextLayoutResult>,
) {
    val hair = Stroke(Hairline.toPx())
    ScaleRingMillis.forEachIndexed { index, millis ->
        val radius = frame.radius(scopeReach(millis.toLong()))
        drawCircle(line.copy(alpha = RingAlpha), radius, frame.center, style = hair)
        val label = scaleLabels[index]
        drawText(label, topLeft = frame.center - Offset(label.size.width / 2f, radius + label.size.height))
    }
    // Outside this line a mark has no measured time.
    drawCircle(line.copy(alpha = FaintAlpha), frame.radius(LaneReach), frame.center, style = hair)
    drawCircle(line.copy(alpha = RimAlpha), frame.rim, frame.center, style = hair)
    sectorStarts.forEach {
        drawLine(line.copy(alpha = FaintAlpha), frame.point(it, frame.hub), frame.point(it, frame.rim), hair.width)
    }
    sectorLabels.forEachIndexed { index, label ->
        val start = sectorStarts[index]
        val extent = labelExtent(sectorStarts, sectorLabels, index)
        val middle = frame.point((start + sectorStarts[index + 1]) / 2, frame.rim + LabelGap.toPx() + extent / 2)
        drawText(label, topLeft = middle - Offset(label.size.width / 2f, label.size.height / 2f))
    }
}

/** The arm at [turn] with the afterglow behind it. */
private fun DrawScope.drawSweep(
    frame: ScopeFrame,
    turn: Float,
    color: Color,
) {
    val middle = (frame.rim + frame.hub) / 2
    val arm = turn * FullTurn
    for (slice in 0 until GlowSlices) {
        val end = arm - slice * GlowSliceDegrees
        if (end <= 0f) break
        val start = (end - GlowSliceDegrees).coerceAtLeast(0f)
        drawArc(
            color.copy(alpha = GlowAlpha * (1f - slice / GlowSlices.toFloat())),
            TopDegrees + start,
            end - start,
            useCenter = false,
            topLeft = frame.center - Offset(middle, middle),
            size = Size(2 * middle, 2 * middle),
            style = Stroke(frame.rim - frame.hub),
        )
    }
    drawLine(color, frame.point(turn, frame.hub), frame.point(turn, frame.rim), ArmWidth.toPx(), StrokeCap.Round)
}

/** One mark. [age] is the part of the turn since the arm passed it; a new mark pops in and sends one ripple. */
private fun DrawScope.drawMark(
    at: Offset,
    mark: ScopeMark,
    age: Float,
    palette: ScopePalette,
) {
    val color =
        when (mark.kind) {
            ScopeMarkKind.Answered -> palette.answered
            ScopeMarkKind.Blocked -> palette.blocked
            ScopeMarkKind.Silent -> palette.silent
        }
    val ripple = age / RippleSpan
    if (ripple < 1f) {
        drawCircle(
            color.copy(alpha = PulseAlpha * (1f - ripple)),
            MarkRadius.toPx() + RippleGrowth.toPx() * ripple,
            at,
            style = Stroke(Hairline.toPx()),
        )
    }
    val radius = MarkRadius.toPx() * overshoot(age / PopSpan)
    val stroke = Stroke(MarkStroke.toPx(), cap = StrokeCap.Round)
    when (mark.kind) {
        ScopeMarkKind.Answered -> {
            drawCircle(color, radius * AnsweredShare, at)
        }

        ScopeMarkKind.Blocked -> {
            drawLine(color, at - Offset(radius, radius), at + Offset(radius, radius), stroke.width, stroke.cap)
            drawLine(color, at + Offset(radius, -radius), at + Offset(-radius, radius), stroke.width, stroke.cap)
        }

        ScopeMarkKind.Silent -> {
            drawCircle(color, radius, at, style = stroke)
        }
    }
}

/** The hub with its number. A number that is wider than the hub is drawn smaller, so it stays inside. */
private fun DrawScope.drawHub(
    frame: ScopeFrame,
    line: Color,
    text: TextLayoutResult?,
) {
    drawCircle(line.copy(alpha = RimAlpha), frame.hub, frame.center, style = Stroke(Hairline.toPx()))
    if (text == null) {
        drawCircle(line, CenterDot.toPx(), frame.center)
    } else {
        val fit = ((2 * frame.hub - 2 * HubGap.toPx()) / text.size.width).coerceAtMost(1f)
        scale(fit, frame.center) {
            drawText(text, topLeft = frame.center - Offset(text.size.width / 2f, text.size.height / 2f))
        }
    }
}

/** Back-out easing: the mark grows a little past its size and comes back. */
private fun overshoot(fraction: Float): Float {
    val rest = fraction.coerceIn(0f, 1f) - 1f
    return 1f + (BackTension + 1f) * rest * rest * rest + BackTension * rest * rest
}

private val ScaleRingMillis = listOf(100, 1000)
private const val MinScaleMillis = 8L
private const val MaxScaleMillis = 5000L
private const val MaxMeasuredReach = 0.78f
private const val LaneReach = 0.84f

/** The lane between the last measured time and the rim. */
internal const val UnmeasuredReach = 0.92f
private const val SectorBaseWeight = 2

/** The part of the turn at the top that holds the scale labels and no marks. */
internal const val ScaleGap = 0.12f

private val MaxScopeWidth = 360.dp
private val MinRim = 48.dp
private val LabelGap = 6.dp
private val TouchRadius = 24.dp
private val Hairline = 1.dp
private val ArmWidth = 2.dp
private val HubGap = 4.dp
private val MarkRadius = 5.dp
private val MarkStroke = 2.dp
private val RippleGrowth = 16.dp
private val PressRadius = 10.dp
private val CenterDot = 2.dp

private const val HubShare = 0.22f
private const val HubClearance = 0.06f
private const val QuarterTurn = 0.25f
private const val FullTurn = 360f
private const val TopDegrees = -90f

private const val FaintAlpha = 0.3f
private const val RingAlpha = 0.5f
private const val RimAlpha = 0.75f
private const val PulseAlpha = 0.6f
private const val AnsweredShare = 0.8f
private const val GlowSlices = 12
private const val GlowSliceDegrees = 4f
private const val GlowAlpha = 0.18f

// The reveal runs from 0 to RevealEnd: one arm turn, then the closing pulse.
private const val RevealEnd = 1.3f
private const val RevealMillis = 3200
private const val PressMillis = 600
private const val SweepNanos = 4_000_000_000L
private const val PopSpan = 0.08f
private const val RippleSpan = 0.2f
private const val BackTension = 1.70158f
