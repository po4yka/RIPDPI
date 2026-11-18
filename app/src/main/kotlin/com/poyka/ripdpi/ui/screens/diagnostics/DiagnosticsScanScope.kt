package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.PI
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
    /** From the hub (0) to the rim (1). Distance is the measured time; the rim means no measured answer. */
    val reach: Float,
    /** One mark that stands for all silent probes of its sector. */
    val stacked: Boolean = false,
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

/** The place of [millis] on the log time scale, from the hub to just inside the rim. */
internal fun scopeReach(millis: Long): Float =
    MaxMeasuredReach *
        log10(millis.coerceIn(MinScaleMillis, MaxScaleMillis) / MinScaleMillis.toFloat()) /
        log10(MaxScaleMillis / MinScaleMillis.toFloat())

private fun DiagnosticsProbeResultUiModel.toScopeMark(): ScopeMark? {
    val stages = connectionStages?.lanes.orEmpty().flatMap { it.stages }
    val millis = stages.mapNotNull { it.elapsedMs ?: it.durationMs }.maxOrNull()
    val failed = tone == DiagnosticsTone.Negative || tone == DiagnosticsTone.Warning
    return when {
        failed && (millis == null || stages.any { it.state == ConnectionStageState.TIMED_OUT }) -> {
            ScopeMark(this, ScopeMarkKind.Silent, 0f, 1f)
        }

        // An answer with no measured time has no honest place on the scale; the results list still shows it.
        millis == null -> {
            null
        }

        else -> {
            ScopeMark(this, if (failed) ScopeMarkKind.Blocked else ScopeMarkKind.Answered, 0f, scopeReach(millis))
        }
    }
}

/** Places the probes that have a sector. A sector with many silent probes shows them as one stacked mark. */
internal fun scopeMarks(results: List<DiagnosticsProbeResultUiModel>): List<ScopeMark> {
    val sectors = ScopeSector.entries
    return sectors.flatMapIndexed { index, sector ->
        val all = results.filter { scopeSector(it.probeType) == sector }.mapNotNull { it.toScopeMark() }
        val silent = all.filter { it.kind == ScopeMarkKind.Silent }
        val stack = silent.size > MaxSoloSilentMarks
        val shown = if (stack) all - silent.toSet() + silent.first().copy(stacked = true) else all
        shown.mapIndexed { slot, mark ->
            mark.copy(turn = (index + (slot + 1f) / (shown.size + 1)) / sectors.size)
        }
    }
}

/**
 * The scan scope: each probe is a mark, its distance from the hub is the measured answer time on a log scale, and
 * the rim holds the probes with no answer. While a scan runs, the arm shows [progress]. When results arrive, the arm
 * turns once and the marks land as it passes; after that the scope is still. The results list under the scope is
 * the accessible form of the same data, so the drawing has no semantics.
 */
@Composable
internal fun DiagnosticsScanScope(
    marks: List<ScopeMark>,
    progress: Float?,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val performHaptic = rememberRipDpiHapticPerformer()
    val measurer = rememberTextMeasurer()
    val labelStyle = RipDpiThemeTokens.type.smallLabel.copy(color = colors.mutedForeground)
    val sectorTexts = ScopeSector.entries.map { stringResource(it.label) }
    val scaleTexts = ScaleRingMillis.map { stringResource(R.string.unit_milliseconds_format, it) }
    val sectorLabels = remember(sectorTexts, labelStyle) { sectorTexts.map { measurer.measure(it, labelStyle) } }
    val scaleLabels = remember(scaleTexts, labelStyle) { scaleTexts.map { measurer.measure(it, labelStyle) } }

    val resultKey = remember(marks) { marks.map { it.probe.id } }
    // The saved value names the results that were revealed, so new results get their own reveal.
    var revealedKey by rememberSaveable { mutableIntStateOf(0) }
    val reveal =
        remember(resultKey) {
            Animatable(if (revealedKey == resultKey.hashCode() || !motion.allowsInfiniteMotion) RevealEnd else 0f)
        }
    val scanning = progress != null
    LaunchedEffect(resultKey, scanning) {
        if (!scanning && marks.isNotEmpty()) {
            reveal.animateTo(RevealEnd, motion.durationTween(RevealMillis, LinearEasing))
            revealedKey = resultKey.hashCode()
        }
    }
    val live = animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), motion.stateTween(), label = "Scope progress")
    val currentOnSelectProbe by rememberUpdatedState(onSelectProbe)
    val palette = ScopePalette(colors.outline, colors.signal, colors.foreground, colors.destructive, colors.warning)

    Canvas(
        modifier
            .fillMaxWidth()
            .wrapContentWidth()
            .widthIn(max = MaxScopeWidth)
            .aspectRatio(1f)
            .semantics { hideFromAccessibility() }
            .pointerInput(marks) {
                detectTapGestures { tap ->
                    val frame = scopeFrame(Size(size.width.toFloat(), size.height.toFloat()))
                    // A mark that the arm has not reached yet is not on the screen, so it takes no tap.
                    marks
                        .filter { reveal.value >= it.turn }
                        .minByOrNull { (frame.at(it) - tap).getDistance() }
                        ?.takeIf { (frame.at(it) - tap).getDistance() <= TouchRadius.toPx() }
                        ?.let {
                            performHaptic(RipDpiHapticFeedback.Selection)
                            currentOnSelectProbe(it.probe)
                        }
                }
            },
    ) {
        val frame = scopeFrame(size)
        drawScopeFace(frame, palette.line, sectorLabels, scaleLabels)
        if (scanning) {
            drawSweep(frame, live.value, palette.arm)
            drawArc(
                palette.arm,
                TopDegrees,
                FullTurn * live.value,
                useCenter = false,
                topLeft = frame.center - Offset(frame.hub + HubGap.toPx(), frame.hub + HubGap.toPx()),
                size = Size(2 * (frame.hub + HubGap.toPx()), 2 * (frame.hub + HubGap.toPx())),
                style = Stroke(ArmWidth.toPx(), cap = StrokeCap.Round),
            )
        } else {
            val time = reveal.value
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
        drawHub(frame, palette.line)
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
}

private fun Density.scopeFrame(size: Size): ScopeFrame {
    val rim = size.minDimension / 2 - LabelBand.toPx()
    return ScopeFrame(Offset(size.width / 2, size.height / 2), rim * HubShare, rim)
}

private fun DrawScope.drawScopeFace(
    frame: ScopeFrame,
    line: Color,
    sectorLabels: List<TextLayoutResult>,
    scaleLabels: List<TextLayoutResult>,
) {
    val hair = Hairline.toPx()
    val dots = PathEffect.dashPathEffect(floatArrayOf(hair, DotGap.toPx()))
    MinorRingMillis.forEach {
        val radius = frame.radius(scopeReach(it))
        drawCircle(line.copy(alpha = FaintAlpha), radius, frame.center, style = Stroke(hair, pathEffect = dots))
    }
    ScaleRingMillis.forEachIndexed { index, millis ->
        val radius = frame.radius(scopeReach(millis.toLong()))
        drawCircle(line.copy(alpha = RingAlpha), radius, frame.center, style = Stroke(hair))
        val label = scaleLabels[index]
        drawText(label, topLeft = frame.center + Offset(LabelGap.toPx(), -radius - label.size.height))
    }
    val dashes = PathEffect.dashPathEffect(floatArrayOf(RimDash.toPx(), RimDash.toPx()))
    drawCircle(line.copy(alpha = RimAlpha), frame.rim, frame.center, style = Stroke(hair, pathEffect = dashes))
    for (tick in 0 until TickCount) {
        val major = tick % MajorTickEvery == 0
        val turn = tick / TickCount.toFloat()
        drawLine(
            line.copy(alpha = if (major) RimAlpha else RingAlpha),
            frame.point(turn, frame.rim + TickStart.toPx()),
            frame.point(turn, frame.rim + (if (major) MajorTick else MinorTick).toPx()),
            hair,
        )
    }
    val sectors = sectorLabels.size
    sectorLabels.forEachIndexed { index, label ->
        drawLine(
            line.copy(alpha = FaintAlpha),
            frame.point(index / sectors.toFloat(), frame.hub),
            frame.point(index / sectors.toFloat(), frame.rim),
            hair,
        )
        val middle = frame.point((index + HalfStep) / sectors, frame.rim + LabelOffset.toPx())
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
    drawLine(
        color,
        frame.point(turn, frame.hub),
        frame.point(turn, frame.rim + MajorTick.toPx()),
        ArmWidth.toPx(),
        StrokeCap.Round,
    )
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
    val scale = overshoot(age / PopSpan)
    val radius = MarkRadius.toPx() * scale
    val stroke = Stroke(MarkStroke.toPx(), cap = StrokeCap.Round)
    when (mark.kind) {
        ScopeMarkKind.Answered -> {
            // A fresh mark is bright, then settles so that the blocked marks lead.
            val settle = (age / RippleSpan).coerceIn(0f, 1f)
            drawCircle(color.copy(alpha = 1f - (1f - AnsweredAlpha) * settle), radius * AnsweredShare, at)
        }

        ScopeMarkKind.Blocked -> {
            drawLine(color, at - Offset(radius, radius), at + Offset(radius, radius), stroke.width, stroke.cap)
            drawLine(color, at + Offset(radius, -radius), at + Offset(-radius, radius), stroke.width, stroke.cap)
        }

        ScopeMarkKind.Silent -> {
            drawCircle(color, radius, at, style = stroke)
            if (mark.stacked) drawCircle(color.copy(alpha = RimAlpha), radius * StackShare, at, style = stroke)
        }
    }
}

private fun DrawScope.drawHub(
    frame: ScopeFrame,
    line: Color,
) {
    val hair = Hairline.toPx()
    drawCircle(line.copy(alpha = RimAlpha), frame.hub, frame.center, style = Stroke(hair))
    drawCircle(line.copy(alpha = RingAlpha), frame.hub * HubInnerShare, frame.center, style = Stroke(hair))
    repeat(CrosshairArms) {
        val turn = it / CrosshairArms.toFloat()
        drawLine(
            line.copy(alpha = RimAlpha),
            frame.point(turn, frame.hub * HubInnerShare),
            frame.point(turn, frame.hub * CrosshairShare),
            hair,
        )
    }
    drawCircle(line, CenterDot.toPx(), frame.center)
}

/** Back-out easing: the mark grows a little past its size and comes back. */
private fun overshoot(fraction: Float): Float {
    val rest = fraction.coerceIn(0f, 1f) - 1f
    return 1f + (BackTension + 1f) * rest * rest * rest + BackTension * rest * rest
}

private val ScaleRingMillis = listOf(100, 1000)
private val MinorRingMillis = listOf(30L, 300L, 3000L)
private const val MinScaleMillis = 8L
private const val MaxScaleMillis = 5000L
private const val MaxMeasuredReach = 0.92f
private const val MaxSoloSilentMarks = 2

private val MaxScopeWidth = 360.dp
private val LabelBand = 44.dp
private val LabelOffset = 28.dp
private val LabelGap = 4.dp
private val TouchRadius = 24.dp
private val Hairline = 1.dp
private val DotGap = 5.dp
private val RimDash = 4.dp
private val TickStart = 4.dp
private val MinorTick = 7.dp
private val MajorTick = 10.dp
private val ArmWidth = 2.dp
private val HubGap = 4.dp
private val MarkRadius = 5.dp
private val MarkStroke = 2.dp
private val RippleGrowth = 16.dp
private val CenterDot = 2.dp

private const val HubShare = 0.2f
private const val HubClearance = 0.06f
private const val HubInnerShare = 0.7f
private const val CrosshairShare = 0.45f
private const val CrosshairArms = 4
private const val TickCount = 60
private const val MajorTickEvery = 6
private const val HalfStep = 0.5f
private const val QuarterTurn = 0.25f
private const val FullTurn = 360f
private const val TopDegrees = -90f

private const val FaintAlpha = 0.25f
private const val RingAlpha = 0.45f
private const val RimAlpha = 0.7f
private const val PulseAlpha = 0.6f
private const val AnsweredAlpha = 0.6f
private const val AnsweredShare = 0.8f
private const val StackShare = 1.8f
private const val GlowSlices = 12
private const val GlowSliceDegrees = 4f
private const val GlowAlpha = 0.18f

// The reveal runs from 0 to RevealEnd: one arm turn, then the closing pulse.
private const val RevealEnd = 1.3f
private const val RevealMillis = 3200
private const val PopSpan = 0.08f
private const val RippleSpan = 0.2f
private const val BackTension = 1.70158f
