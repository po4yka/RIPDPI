package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.feedback.LocalRipDpiHandOff
import com.poyka.ripdpi.ui.screens.onboarding.scenes.BarrierScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.BenchScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.CheckpointScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.Frame
import com.poyka.ripdpi.ui.screens.onboarding.scenes.NightScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.Parts
import com.poyka.ripdpi.ui.screens.onboarding.scenes.Pen
import com.poyka.ripdpi.ui.screens.onboarding.scenes.Scene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.SceneColors
import com.poyka.ripdpi.ui.screens.onboarding.scenes.SceneHeight
import com.poyka.ripdpi.ui.screens.onboarding.scenes.SceneTextBase
import com.poyka.ripdpi.ui.screens.onboarding.scenes.SceneWidth
import com.poyka.ripdpi.ui.screens.onboarding.scenes.UnseenScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.WallScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.WelcomeScene
import com.poyka.ripdpi.ui.screens.onboarding.scenes.ease
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.floor
import kotlin.random.Random

private val StageMaxWidth = 420.dp
private const val FadeSide = 0.07f
private const val FadeBottom = 0.9f
private val SideFade =
    Brush.horizontalGradient(
        0f to Color.Transparent,
        FadeSide to Color.Black,
        1f - FadeSide to Color.Black,
        1f to Color.Transparent,
    )
private val BottomFade = Brush.verticalGradient(FadeBottom to Color.Black, 1f to Color.Transparent)
private const val NanosPerSecond = 1_000_000_000f
private const val LongestStep = 0.05f
private const val SettleStep = 1f / 30f
private const val SceneSeed = 7
private const val SceneSlide = 30f

/** What the scenes show of the setup: the choices, and the state of the connection test. */
@Immutable
internal data class OnboardingSceneState(
    val advanced: Boolean = false,
    val proxy: Boolean = false,
    val dns: String = "",
    /** How many stages of the test are complete, from 0 to 3. */
    val stage: Int = 0,
    val running: Boolean = false,
    /** The stage at which the test failed, or -1. */
    val failedAt: Int = -1,
    val passed: Boolean = false,
)

/**
 * The scene state for [uiState]. With [statesTest] false the step does not state the connection test, so the test
 * shows as not started: a failed test must not mark a step before the test.
 */
internal fun onboardingSceneState(
    uiState: OnboardingUiState,
    dns: String,
    statesTest: Boolean,
): OnboardingSceneState {
    val choices =
        OnboardingSceneState(
            advanced = OnboardingPersona.fromId(uiState.selectedPersona) == OnboardingPersona.Advanced,
            proxy = uiState.selectedMode == Mode.Proxy,
            dns = dns,
        )
    val stages = OnboardingValidationStep.entries.size
    return when (val test = uiState.validationState.takeIf { statesTest } ?: OnboardingValidationState.Idle) {
        OnboardingValidationState.Idle -> {
            choices
        }

        OnboardingValidationState.RequestingNotifications,
        OnboardingValidationState.RequestingVpnConsent,
        is OnboardingValidationState.StartingMode,
        -> {
            choices.copy(running = true, stage = OnboardingValidationStep.Tunnel.ordinal)
        }

        is OnboardingValidationState.CheckingDns -> {
            choices.copy(running = true, stage = OnboardingValidationStep.Dns.ordinal)
        }

        is OnboardingValidationState.RunningTrafficCheck -> {
            choices.copy(running = true, stage = OnboardingValidationStep.Connectivity.ordinal)
        }

        is OnboardingValidationState.Success -> {
            choices.copy(passed = true, stage = stages)
        }

        is OnboardingValidationState.Failed -> {
            val at = (test.failedStep ?: OnboardingValidationStep.Dns).ordinal
            choices.copy(failedAt = at, stage = at)
        }
    }
}

/** The scenes of the stage, one for each step in [OnboardingPages], and the time that they share. */
private class SceneStage {
    private val parts = Parts(Random(SceneSeed))
    private val scenes: List<Scene> =
        OnboardingPages.map { page ->
            when ((page as? OnboardingPage.Setup)?.kind) {
                null -> UnseenScene(parts)
                SetupPageKind.Welcome -> WelcomeScene(parts)
                SetupPageKind.PersonaSelection -> BenchScene(parts)
                SetupPageKind.ModeSelection -> WallScene(parts)
                SetupPageKind.DnsSelection -> CheckpointScene(parts)
                SetupPageKind.BackgroundGuidance -> NightScene(parts)
                SetupPageKind.ConnectionTest -> BarrierScene(parts, arrival = false)
                SetupPageKind.Completion -> BarrierScene(parts, arrival = true)
            }
        }
    private val shown = BooleanArray(scenes.size)
    private val frame = Frame()
    private var time = 0f
    private var front = -1
    private var started = false
    private var still: OnboardingSceneState? = null

    /** Seconds that the frame loop has added since the last draw. */
    var pending = 0f

    fun draw(
        pen: Pen,
        state: OnboardingSceneState,
        position: Float,
        moves: Boolean,
    ) {
        val at = position.coerceIn(0f, scenes.lastIndex.toFloat())
        val base = floor(at).toInt().coerceAtMost(scenes.lastIndex)
        val next = ease(at - base)
        val dt = if (moves) pending.coerceAtMost(LongestStep * 2f) else 0f
        pending = 0f
        time += dt
        frame.take(state, dt)
        scenes.forEachIndexed { index, scene ->
            val visible = index == base || (index == base + 1 && next > 0f)
            if (visible && !shown[index]) arrive(pen, scene, moves)
            shown[index] = visible
        }
        // With no motion every change of the setup gives a new still frame, and so does the end of motion.
        if (moves) still = null
        if (!moves && still != state) {
            still = state
            scenes.forEachIndexed { index, scene -> if (shown[index]) settle(pen, scene) }
        }
        val leading = if (next < HalfWay) base else base + 1
        if (leading != front) {
            front = leading
            if (started) parts.clear()
        }
        started = true
        pen.time = time
        drawScene(pen, base, 1f - next, -SceneSlide * next, dt)
        if (next > 0f) drawScene(pen, base + 1, next, SceneSlide * (1f - next), dt)
    }

    private fun Frame.take(
        state: OnboardingSceneState,
        step: Float,
    ) {
        dt = step
        advanced = state.advanced
        proxy = state.proxy
        dns = state.dns
        stage = state.stage
        running = state.running
        failedAt = state.failedAt
        passed = state.passed
    }

    /**
     * A scene that comes into view plays from its start. The scene of the first frame is already at its still
     * frame, so a restored step does not play again; only the welcome scene plays, because it opens as the app icon.
     */
    private fun arrive(
        pen: Pen,
        scene: Scene,
        moves: Boolean,
    ) {
        if (moves && (started || scene is WelcomeScene)) scene.enter(frame) else settle(pen, scene)
    }

    /** Moves [scene] to its still frame without drawing it. */
    private fun settle(
        pen: Pen,
        scene: Scene,
    ) {
        val step = frame.dt
        scene.enter(frame)
        frame.dt = SettleStep
        pen.mute = true
        pen.time = 0f
        while (scene.since < scene.stillAt) {
            scene.since += SettleStep
            pen.time += SettleStep
            with(scene) { pen.draw(frame) }
        }
        pen.mute = false
        frame.dt = step
        time = maxOf(time, pen.time)
        parts.clear()
    }

    private fun drawScene(
        pen: Pen,
        index: Int,
        alpha: Float,
        shift: Float,
        dt: Float,
    ) {
        val scene = scenes[index]
        scene.since += dt
        pen.alpha = alpha
        pen.at(shift, 0f) {
            with(scene) { pen.draw(frame) }
            if (index == front) parts.draw(pen, dt)
        }
        pen.alpha = 1f
    }

    private companion object {
        const val HalfWay = 0.5f
    }
}

/**
 * The pinned stage above the onboarding pages: one drawn scene for each step, with the ghost and the tombstone of
 * the app icon. The scene follows [pagePosition], which is read in the draw phase, so a drag between two steps
 * moves from one scene to the next. The stage is decoration with no semantics: the page under it names each state.
 *
 * The scenes move only while the app allows infinite motion; otherwise each shows one still frame.
 */
@Composable
internal fun OnboardingSceneStage(
    state: OnboardingSceneState,
    pagePosition: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    // A preview shows the still frame.
    val moves = RipDpiThemeTokens.motion.allowsInfiniteMotion && !LocalInspectionMode.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val pen =
        remember(colors, density, type) {
            val size = with(density) { SceneTextBase.toSp() }
            Pen(
                colors = SceneColors(colors),
                measurer = measurer,
                styles =
                    listOf(
                        type.monoInline.copy(fontSize = size, fontWeight = FontWeight.SemiBold),
                        type.screenTitle.copy(fontSize = size, fontWeight = FontWeight.Bold),
                    ),
            )
        }
    val stage = remember { SceneStage() }
    val frames = remember { mutableIntStateOf(0) }
    val currentState by rememberUpdatedState(state)
    if (moves) {
        LaunchedEffect(stage) {
            var last = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    stage.pending += ((now - last) / NanosPerSecond).coerceAtMost(LongestStep)
                    last = now
                    frames.intValue++
                }
            }
        }
    }
    // The main screen opens from the middle of the stage, and the splash icon moves to it.
    val handOff = LocalRipDpiHandOff.current
    val splash = LocalOnboardingBadgeAnchor.current
    val radius = with(density) { OnboardingStepBadgeSize.toPx() } / 2f
    Box(modifier = modifier.fillMaxWidth().padding(bottom = spacing.md), contentAlignment = Alignment.Center) {
        Spacer(
            modifier =
                Modifier
                    .widthIn(max = StageMaxWidth)
                    .fillMaxWidth()
                    .aspectRatio(SceneWidth / SceneHeight)
                    .onGloballyPositioned { coordinates ->
                        if (handOff != null) {
                            handOff.anchor = coordinates.boundsInRoot().center
                            handOff.anchorRadius = radius
                            handOff.sourceLine = null
                        }
                        splash?.bounds = Rect(coordinates.boundsInWindow().center, radius)
                    }.clearAndSetSemantics {}
                    .clipToBounds()
                    // The stage fades into the page at its sides and at its bottom, so it has no edge.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawBehind {
                        // Each frame of the loop draws the stage again.
                        frames.intValue
                        val unit = size.width / SceneWidth
                        scale(unit, unit, pivot = Offset.Zero) {
                            pen.scope = this
                            stage.draw(pen, currentState, pagePosition(), moves)
                        }
                        drawRect(SideFade, blendMode = BlendMode.DstIn)
                        drawRect(BottomFade, blendMode = BlendMode.DstIn)
                    },
        )
    }
}
