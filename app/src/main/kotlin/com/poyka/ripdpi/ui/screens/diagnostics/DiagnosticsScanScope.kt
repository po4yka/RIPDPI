package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
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
    Sites(R.string.diagnostics_radar_sector_sites),
    Quic(R.string.diagnostics_phase_quic),
    Services(R.string.diagnostics_radar_sector_services),
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
        "domain_reachability", "strategy_https", "strategy_http" -> ScopeSector.Sites
        "quic_reachability", "strategy_quic", "http3" -> ScopeSector.Quic
        "service_reachability", "circumvention_reachability" -> ScopeSector.Services
        "telegram_availability", "telegram" -> ScopeSector.Services
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

/** The scope in its card, with a title and one line that says what the numbers in the hub mean. */
@Composable
internal fun DiagnosticsScanScopeCard(
    layout: ScopeLayout,
    progress: Float?,
    steps: Pair<Int, Int>?,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val marks = layout.marks.size
    val problems = layout.marks.count { it.kind != ScopeMarkKind.Answered }
    val status =
        when {
            steps != null -> stringResource(R.string.diagnostics_radar_checked, steps.first, steps.second)
            problems > 0 -> stringResource(R.string.diagnostics_radar_problems, problems, marks)
            else -> stringResource(R.string.diagnostics_radar_answered, marks, marks)
        }
    RipDpiCard(modifier = modifier) {
        RipDpiPanelHeader(title = stringResource(R.string.diagnostics_radar_title), supporting = status)
        DiagnosticsScanScope(layout, progress, steps, onSelectProbe)
    }
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
    val labels = rememberScopeLabels(layout.sectorStarts)
    val marks = layout.marks
    val scanning = progress != null
    val hubText = rememberHubText(marks, steps)
    val reveal = rememberScopeReveal(marks, scanning)
    val sweep = rememberScopeSweep(scanning && motion.allowsInfiniteMotion)
    val live = animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), motion.stateTween(), label = "Scope progress")
    val currentOnSelectProbe by rememberUpdatedState(onSelectProbe)
    // One palette for a theme, so a progress update does not make the draw caches again.
    val palette =
        remember(colors) {
            ScopePalette(colors.outline, colors.signal, colors.success, colors.destructive, colors.warning, colors.card)
        }
    val scope = rememberCoroutineScope()
    // The ring on a tapped mark: it shows at once and fades, so no mark stays selected.
    val press = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf<ScopeMark?>(null) }

    Box(
        modifier
            .fillMaxWidth()
            .wrapContentWidth()
            .widthIn(max = MaxScopeWidth)
            .aspectRatio(1f)
            .semantics { hideFromAccessibility() }
            .pointerInput(marks, labels.band) {
                detectTapGestures { tap ->
                    val frame = scopeFrame(Size(size.width.toFloat(), size.height.toFloat()), labels.band)
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
        // The face reads no animated value and has its own offscreen layer, so a frame of the arm or of the reveal
        // draws it again as one picture and does not run its drawing code.
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val face = ScopeFace(scopeFrame(size, labels.band), palette, this)
                    onDrawBehind { drawScopeFace(face, layout.sectorStarts, labels.sector, labels.scale) }
                },
        )
        // The moving parts have their own layer: only this layer is recorded again for a frame.
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawWithCache {
                    val face = ScopeFace(scopeFrame(size, labels.band), palette, this)
                    val places = ScopePlaces(face.frame, marks)
                    onDrawBehind {
                        if (scanning) {
                            drawSweep(face, if (motion.allowsInfiniteMotion) sweep.floatValue else live.value)
                        } else {
                            drawResult(face, marks, places, reveal.time.value)
                            pressed?.let { drawPress(face, it, press.value, colors.foreground) }
                        }
                        drawHub(face, hubText, live.value.takeIf { scanning })
                    }
                },
        )
    }
}

/** The measured labels of the dial and the room that the sector labels need outside the rim. */
private class ScopeLabels(
    val sector: List<TextLayoutResult>,
    val scale: List<TextLayoutResult>,
    val band: Float,
)

@Composable
private fun rememberScopeLabels(sectorStarts: List<Float>): ScopeLabels {
    val colors = RipDpiThemeTokens.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // A sector name is a title of a part of the dial; a scale value is a note on a ring.
    val sectorStyle = RipDpiThemeTokens.type.smallLabel.copy(color = colors.foreground)
    val scaleStyle = RipDpiThemeTokens.type.monoSmall.copy(color = colors.mutedForeground)
    val sectorTexts = ScopeSector.entries.map { stringResource(it.label) }
    val scaleTexts =
        listOf(
            stringResource(R.string.unit_milliseconds_format, ScaleRingMillis.first()),
            stringResource(R.string.unit_seconds_format, 1),
        )
    val sector = remember(sectorTexts, sectorStyle, density) { sectorTexts.map { measurer.measure(it, sectorStyle) } }
    val scale = remember(scaleTexts, scaleStyle, density) { scaleTexts.map { measurer.measure(it, scaleStyle) } }
    return remember(sector, scale, sectorStarts) { ScopeLabels(sector, scale, labelBand(sectorStarts, sector)) }
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

/** The ring on a tapped mark. It grows and fades as [ring] goes from 1 to 0. */
private fun DrawScope.drawPress(
    face: ScopeFace,
    mark: ScopeMark,
    ring: Float,
    color: Color,
) {
    if (ring <= 0f) return
    val radius = PressRadius.toPx() + RippleGrowth.toPx() * (1f - ring)
    drawCircle(color.copy(alpha = ring), radius, face.frame.at(mark), style = face.mark)
}

/** The reveal at [time]: the arm on its one turn, the marks it has passed, and the closing pulse. */
private fun DrawScope.drawResult(
    face: ScopeFace,
    marks: List<ScopeMark>,
    places: ScopePlaces,
    time: Float,
) {
    val frame = face.frame
    if (time > 0f && time < 1f) drawSweep(face, time)
    // A ray joins each mark to the hub, so the angle of a mark reads as its own probe.
    for (index in marks.indices) {
        val mark = marks[index]
        if (time < mark.turn) continue
        val ray = face.palette.of(mark.kind).copy(alpha = RayAlpha)
        drawLine(ray, places.rayStart(index), places.mark(index), face.hair.width)
    }
    for (index in marks.indices) {
        val mark = marks[index]
        if (time >= mark.turn) drawMark(places.mark(index), mark, time - mark.turn, face)
    }
    val pulse = (time - 1f) / (RevealEnd - 1f)
    if (pulse > 0f && pulse < 1f) {
        drawCircle(
            face.palette.arm.copy(alpha = PulseAlpha * (1f - pulse)),
            frame.hub + (frame.rim - frame.hub) * pulse,
            frame.center,
            style = face.armStroke,
        )
    }
}

/** The place of each mark and of the start of its ray, found once for a size and not for each frame. */
private class ScopePlaces(
    frame: ScopeFrame,
    marks: List<ScopeMark>,
) {
    private val xs = FloatArray(marks.size * 2)
    private val ys = FloatArray(marks.size * 2)

    init {
        marks.forEachIndexed { index, mark ->
            val at = frame.at(mark)
            val start = frame.point(mark.turn, frame.hub)
            xs[index * 2] = at.x
            ys[index * 2] = at.y
            xs[index * 2 + 1] = start.x
            ys[index * 2 + 1] = start.y
        }
    }

    fun mark(index: Int): Offset = Offset(xs[index * 2], ys[index * 2])

    fun rayStart(index: Int): Offset = Offset(xs[index * 2 + 1], ys[index * 2 + 1])
}

private class ScopePalette(
    val line: Color,
    val arm: Color,
    val answered: Color,
    val blocked: Color,
    val silent: Color,
    val hub: Color,
) {
    fun of(kind: ScopeMarkKind): Color =
        when (kind) {
            ScopeMarkKind.Answered -> answered
            ScopeMarkKind.Blocked -> blocked
            ScopeMarkKind.Silent -> silent
        }
}

/** The frame with the brushes of the face. The arm brushes point to the right; the arm is drawn in a turned canvas. */
private class ScopeFace(
    val frame: ScopeFrame,
    val palette: ScopePalette,
    density: Density,
) {
    private val center = frame.center
    val armTip = center + Offset(frame.rim, 0f)

    // The strokes are made once, so a frame makes no stroke object.
    val hair = Stroke(with(density) { Hairline.toPx() })
    val mark = Stroke(with(density) { MarkStroke.toPx() }, cap = StrokeCap.Round)
    val armStroke = Stroke(with(density) { ArmWidth.toPx() })
    val rimStroke = Stroke(with(density) { RimWidth.toPx() })
    val progress = Stroke(with(density) { ProgressWidth.toPx() }, cap = StrokeCap.Round)
    val glowBand = Stroke(frame.rim - frame.hub)

    /** A light under the dial that is strongest at the hub. */
    val disc =
        Brush.radialGradient(
            listOf(palette.arm.copy(alpha = DiscAlpha), palette.arm.copy(alpha = 0f)),
            center,
            frame.rim,
        )

    /** The afterglow: it ends at the arm and fades over [GlowTurn] behind it. */
    val glow =
        Brush.sweepGradient(
            0f to palette.arm.copy(alpha = 0f),
            1f - GlowTurn to palette.arm.copy(alpha = 0f),
            1f to palette.arm.copy(alpha = GlowAlpha),
            center = center,
        )

    /** A shade that grows toward the rim, so the face reads as a shallow bowl. */
    val shade =
        Brush.radialGradient(
            0f to palette.line.copy(alpha = 0f),
            ShadeStart to palette.line.copy(alpha = 0f),
            1f to palette.line.copy(alpha = ShadeAlpha),
            center = center,
            radius = frame.rim,
        )
    val arm =
        Brush.horizontalGradient(
            listOf(palette.arm.copy(alpha = ArmRootAlpha), palette.arm),
            center.x + frame.hub,
            center.x + frame.rim,
        )
    val tip =
        Brush.radialGradient(
            listOf(palette.arm.copy(alpha = TipAlpha), palette.arm.copy(alpha = 0f)),
            armTip,
            with(density) { TipGlow.toPx() },
        )
}

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
    val outside = labelBand + LabelGap.toPx() + RimGap.toPx()
    val rim = (size.minDimension / 2 - outside).coerceAtLeast(MinRim.toPx())
    return ScopeFrame(Offset(size.width / 2, size.height / 2), rim * HubShare, rim)
}

/**
 * The face: a lit disc, bands between the scale rings, a ruler of the log scale at both sides of the label gap, and
 * a double rim with a notch at each sector edge.
 */
private fun DrawScope.drawScopeFace(
    face: ScopeFace,
    sectorStarts: List<Float>,
    sectorLabels: List<TextLayoutResult>,
    scaleLabels: List<TextLayoutResult>,
) {
    val frame = face.frame
    val line = face.palette.line
    val hair = Hairline.toPx()
    drawCircle(face.disc, frame.rim, frame.center)
    // Every second band is tinted, so the rings read as steps of the scale and not as lone lines.
    val rings = ScaleRingMillis.map { frame.radius(scopeReach(it.toLong())) }
    val edges = listOf(frame.hub) + rings + frame.radius(LaneReach)
    edges.zipWithNext().forEachIndexed { index, (inner, outer) ->
        if (index % 2 == 0) drawBand(line.copy(alpha = BandAlpha), frame, inner, outer)
    }
    // The lane with no measured time is one tone darker than the bands.
    drawBand(line.copy(alpha = LaneAlpha), frame, frame.radius(LaneReach), frame.rim)
    ScaleRingMillis.forEachIndexed { index, millis ->
        val radius = frame.radius(scopeReach(millis.toLong()))
        drawCircle(line.copy(alpha = RingAlpha), radius, frame.center, style = Stroke(hair))
        val label = scaleLabels[index]
        drawText(label, topLeft = frame.center - Offset(label.size.width / 2f, radius + label.size.height + hair))
    }
    drawCircle(line.copy(alpha = RingAlpha), frame.radius(LaneReach), frame.center, style = Stroke(hair))
    drawCircle(face.shade, frame.rim, frame.center)
    drawScaleRuler(frame, line, sectorStarts.first(), inward = -1f)
    drawScaleRuler(frame, line, sectorStarts.last(), inward = 1f)
    drawCircle(line, frame.rim, frame.center, style = Stroke(RimWidth.toPx()))
    drawCircle(line.copy(alpha = RingAlpha), frame.rim + RimGap.toPx(), frame.center, style = Stroke(hair))
    sectorStarts.forEach {
        drawLine(line.copy(alpha = FaintAlpha), frame.point(it, frame.hub), frame.point(it, frame.rim), hair)
        drawLine(line, frame.point(it, frame.rim), frame.point(it, frame.rim + RimGap.toPx()), RimWidth.toPx())
    }
    sectorLabels.forEachIndexed { index, label ->
        val extent = labelExtent(sectorStarts, sectorLabels, index)
        val turn = (sectorStarts[index] + sectorStarts[index + 1]) / 2
        val middle = frame.point(turn, frame.rim + RimGap.toPx() + LabelGap.toPx() + extent / 2)
        drawText(label, topLeft = middle - Offset(label.size.width / 2f, label.size.height / 2f))
    }
}

private fun DrawScope.drawBand(
    color: Color,
    frame: ScopeFrame,
    inner: Float,
    outer: Float,
) {
    drawCircle(color, (inner + outer) / 2, frame.center, style = Stroke(outer - inner))
}

/** Ticks of the log scale along the spoke at [turn]: 20 to 90, 200 to 900, and 2000 to 5000 milliseconds. */
private fun DrawScope.drawScaleRuler(
    frame: ScopeFrame,
    line: Color,
    turn: Float,
    inward: Float,
) {
    for (decade in RulerDecades) {
        for (step in RulerSteps) {
            val millis = decade * step
            if (millis > MaxScaleMillis) break
            val length = (if (step == RulerMiddleStep) RulerLongTick else RulerTick).toPx()
            val radius = frame.radius(scopeReach(millis))
            // The tick lies across the spoke, on the side of the label gap.
            val along = frame.point(turn, radius)
            val across = (frame.point(turn + inward * QuarterTurn, 1f) - frame.center) * length
            drawLine(line.copy(alpha = RingAlpha), along, along + across, Hairline.toPx())
        }
    }
}

/** The arm at [turn] with its afterglow and a light at the tip. The canvas turns, so the brushes stay fixed. */
private fun DrawScope.drawSweep(
    face: ScopeFace,
    turn: Float,
) {
    val frame = face.frame
    val middle = (frame.rim + frame.hub) / 2
    rotate(turn * FullTurn + TopDegrees, frame.center) {
        val glow = (GlowTurn.coerceAtMost(turn)) * FullTurn
        drawArc(
            face.glow,
            -glow,
            glow,
            useCenter = false,
            topLeft = frame.center - Offset(middle, middle),
            size = Size(2 * middle, 2 * middle),
            style = face.glowBand,
        )
        drawLine(face.arm, frame.center + Offset(frame.hub, 0f), face.armTip, ArmWidth.toPx(), StrokeCap.Round)
        drawCircle(face.tip, TipGlow.toPx(), face.armTip)
        drawCircle(face.palette.arm, TipDot.toPx(), face.armTip)
    }
}

/** One mark. [age] is the part of the turn since the arm passed it; a new mark pops in and sends one ripple. */
private fun DrawScope.drawMark(
    at: Offset,
    mark: ScopeMark,
    age: Float,
    face: ScopeFace,
) {
    val color = face.palette.of(mark.kind)
    val ripple = age / RippleSpan
    if (ripple < 1f) {
        val reach = MarkRadius.toPx() + RippleGrowth.toPx() * ripple
        drawCircle(color.copy(alpha = PulseAlpha * (1f - ripple)), reach, at, style = face.hair)
    }
    val radius = MarkRadius.toPx() * overshoot(age / PopSpan)
    // A soft disc under the mark lifts it off the bands.
    drawCircle(color.copy(alpha = HaloAlpha), radius * HaloShare, at)
    when (mark.kind) {
        ScopeMarkKind.Answered -> {
            // The edge in the color of the hub keeps two dots apart when they touch.
            drawCircle(face.palette.hub, radius * AnsweredShare + MarkEdge.toPx(), at)
            drawCircle(color, radius * AnsweredShare, at)
        }

        ScopeMarkKind.Blocked -> {
            val width = face.mark.width
            drawLine(color, at - Offset(radius, radius), at + Offset(radius, radius), width, StrokeCap.Round)
            drawLine(color, at + Offset(radius, -radius), at + Offset(-radius, radius), width, StrokeCap.Round)
        }

        ScopeMarkKind.Silent -> {
            drawCircle(color, radius, at, style = face.mark)
        }
    }
}

/**
 * The hub: a cap over the middle of the face with its number and, during a scan, the [progress] arc on a track.
 * A number that is wider than the hub is drawn smaller, so it stays inside.
 */
private fun DrawScope.drawHub(
    face: ScopeFace,
    text: TextLayoutResult?,
    progress: Float?,
) {
    val frame = face.frame
    val line = face.palette.line
    drawCircle(face.palette.hub, frame.hub, frame.center)
    drawCircle(line, frame.hub, frame.center, style = face.rimStroke)
    drawCircle(line.copy(alpha = FaintAlpha), frame.hub - HubGap.toPx(), frame.center, style = face.hair)
    if (progress != null) {
        val radius = frame.hub + HubGap.toPx()
        val arc = face.progress
        drawCircle(line.copy(alpha = FaintAlpha), radius, frame.center, style = arc)
        drawArc(
            face.palette.arm,
            TopDegrees,
            FullTurn * progress,
            useCenter = false,
            topLeft = frame.center - Offset(radius, radius),
            size = Size(2 * radius, 2 * radius),
            style = arc,
        )
    }
    if (text == null) {
        drawCircle(line, CenterDot.toPx(), frame.center)
    } else {
        // The number stays inside the inner ring of the hub.
        val fit = (2 * (frame.hub - 2 * HubGap.toPx()) / text.size.width).coerceAtMost(1f)
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
private const val MaxMeasuredReach = 0.72f
private const val LaneReach = 0.78f

/** The lane between the last measured time and the rim. */
internal const val UnmeasuredReach = 0.89f
private const val SectorBaseWeight = 2

/** The part of the turn at the top that holds the scale labels and no marks. */
internal const val ScaleGap = 0.12f

private val MaxScopeWidth = 360.dp
private val MinRim = 48.dp
private val LabelGap = 6.dp
private val TouchRadius = 24.dp
private val Hairline = 1.dp
private val ArmWidth = 2.dp
private val HubGap = 5.dp
private val ProgressWidth = 3.dp
private val RimWidth = 2.dp
private val RimGap = 4.dp
private val RulerTick = 3.dp
private val RulerLongTick = 5.dp
private val TipGlow = 9.dp
private val TipDot = 2.5.dp
private val MarkRadius = 5.dp
private val MarkStroke = 2.dp
private val MarkEdge = 1.5.dp
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
private const val PulseAlpha = 0.6f
private const val AnsweredShare = 0.8f
private const val GlowTurn = 0.16f
private const val GlowAlpha = 0.32f
private const val DiscAlpha = 0.1f
private const val BandAlpha = 0.07f
private const val LaneAlpha = 0.12f
private const val ArmRootAlpha = 0.25f
private const val TipAlpha = 0.5f
private const val HaloAlpha = 0.16f
private const val RayAlpha = 0.22f
private const val ShadeStart = 0.7f
private const val ShadeAlpha = 0.14f
private const val HaloShare = 1.8f
private val RulerDecades = listOf(10L, 100L, 1000L)
private val RulerSteps = 2L..9L
private const val RulerMiddleStep = 5L

// The reveal runs from 0 to RevealEnd: one arm turn, then the closing pulse.
private const val RevealEnd = 1.3f
private const val RevealMillis = 3200
private const val PressMillis = 600
private const val SweepNanos = 4_000_000_000L
private const val PopSpan = 0.08f
private const val RippleSpan = 0.2f
private const val BackTension = 1.70158f
