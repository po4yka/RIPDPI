package com.poyka.ripdpi.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode

private const val minReducedDurationMillis = 80

@Suppress("detekt.TooManyFunctions")
@Immutable
data class RipDpiMotion(
    val animationsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val quickDurationMillis: Int = 120,
    val stateDurationMillis: Int = 220,
    val emphasizedDurationMillis: Int = 320,
    val routeDurationMillis: Int = 260,
) {
    fun duration(baseDurationMillis: Int): Int =
        when {
            !animationsEnabled -> {
                0
            }

            reducedMotion -> {
                (baseDurationMillis / 2).coerceAtLeast(minReducedDurationMillis)
            }

            else -> {
                baseDurationMillis
            }
        }

    val allowsInfiniteMotion: Boolean
        get() = animationsEnabled && !reducedMotion

    fun <T> quickTween(easing: Easing = StandardEasing): TweenSpec<T> =
        tween(durationMillis = duration(quickDurationMillis), easing = easing)

    fun <T> stateTween(easing: Easing = StandardEasing): TweenSpec<T> =
        tween(durationMillis = duration(stateDurationMillis), easing = easing)

    fun <T> emphasizedTween(easing: Easing = EmphasizedDecelerate): TweenSpec<T> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = easing)

    fun <T> durationTween(
        baseDurationMillis: Int,
        easing: Easing = StandardEasing,
    ): TweenSpec<T> = tween(durationMillis = duration(baseDurationMillis), easing = easing)

    fun <T> routeTween(easing: Easing = EmphasizedDecelerate): TweenSpec<T> =
        tween(durationMillis = duration(routeDurationMillis), easing = easing)

    fun sectionEnterTransition(): EnterTransition =
        if (!animationsEnabled) {
            EnterTransition.None
        } else {
            expandVertically(animationSpec = emphasizedTween(easing = EmphasizedDecelerate)) +
                fadeIn(animationSpec = stateTween(easing = EmphasizedDecelerate))
        }

    fun sectionExitTransition(): ExitTransition =
        if (!animationsEnabled) {
            ExitTransition.None
        } else {
            shrinkVertically(animationSpec = quickTween(easing = EmphasizedAccelerate)) +
                fadeOut(animationSpec = quickTween(easing = EmphasizedAccelerate))
        }

    fun quickContentTransform(
        initialScale: Float = 0.92f,
        targetScale: Float = 0.92f,
    ): ContentTransform =
        (
            fadeIn(animationSpec = quickTween()) +
                scaleIn(initialScale = initialScale, animationSpec = quickTween())
        ) togetherWith (
            fadeOut(animationSpec = quickTween()) +
                scaleOut(targetScale = targetScale, animationSpec = quickTween())
        )

    fun quickFadeContentTransform(): ContentTransform =
        fadeIn(animationSpec = quickTween()) togetherWith
            fadeOut(animationSpec = quickTween())

    fun routeEnterTransition(initialScale: Float = 0.985f): EnterTransition =
        if (!animationsEnabled) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = routeTween()) +
                scaleIn(initialScale = initialScale, animationSpec = routeTween())
        }

    fun routeExitTransition(): ExitTransition =
        if (!animationsEnabled) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = quickTween(easing = EmphasizedAccelerate))
        }

    // The back gesture seeks pop transitions by its progress, so both sides share one linear spec:
    // the outgoing screen then tracks the finger and does not vanish before the gesture commits.
    fun <T> routePopTween(): TweenSpec<T> = routeTween(easing = LinearEasing)

    fun routePopEnterTransition(initialScale: Float = 0.992f): EnterTransition =
        if (!animationsEnabled) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = routePopTween()) +
                scaleIn(initialScale = initialScale, animationSpec = routePopTween())
        }

    // Material predictive back: the outgoing screen shrinks toward 90% and fades out.
    fun routePopExitTransition(targetScale: Float = 0.9f): ExitTransition =
        if (!animationsEnabled) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = routePopTween()) +
                scaleOut(targetScale = targetScale, animationSpec = routePopTween())
        }

    /**
     * Linear sweep for skeleton shimmer: 1200ms, restart. Callers MUST guard
     * subscription with `motion.allowsInfiniteMotion` (a 1ms collapse would
     * produce non-deterministic progress values at any capture point).
     */
    fun shimmerSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        )

    /**
     * Cardiac pulse for heartbeat / fresh-data badges: 900ms, restart.
     * Callers MUST guard subscription with `motion.allowsInfiniteMotion`.
     */
    fun pulseSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        )

    /**
     * Reversible alpha pulse for in-flight progress indicators where the
     * caller picks the duration (e.g. AnalysisProgressIndicator uses
     * different cadences for the active-segment pulse vs the pending
     * shimmer). When `allowsInfiniteMotion` is false (reduced motion or
     * animations disabled) the spec collapses to a 1 ms restart, which
     * effectively snaps to the target value without invoking the curve.
     */
    fun smoothPulseSpec(durationMillis: Int): InfiniteRepeatableSpec<Float> {
        val effective = if (allowsInfiniteMotion) duration(durationMillis).coerceAtLeast(1) else 1
        return infiniteRepeatable(
            animation = tween(durationMillis = effective, easing = LinearEasing),
            repeatMode = if (allowsInfiniteMotion) RepeatMode.Reverse else RepeatMode.Restart,
        )
    }

    // === Connection-state actuator motion fingerprints (motion-connection-states.html) ===

    // === Data-ticker motion (motion-data-ticker.html) ===

    // === Page-transition motion (motion-page-transitions.html) ===

    // === Toast choreography (motion-toast-choreography.html) ===

    /** Disabled motion snaps; reduced motion uses critical damping without bounce. */
    fun <T> motionAwareSpring(expressive: Boolean = false): FiniteAnimationSpec<T> =
        if (!animationsEnabled) {
            snap()
        } else if (reducedMotion) {
            spring(dampingRatio = 1f, stiffness = StandardSpringStiffness)
        } else if (expressive) {
            expressiveSpringSpec()
        } else {
            standardSpringSpec()
        }

    companion object {
        /** M3 emphasized decelerate -- use for entering elements. */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

        /** M3 emphasized accelerate -- use for exiting elements. */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

        /** M3 standard -- use for on-screen property changes (color, opacity). */
        val StandardEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

        /** M3 Expressive standard spring -- critically damped, no overshoot. */
        const val StandardSpringDamping = 1f
        const val StandardSpringStiffness = 500f

        /** M3 Expressive expressive spring -- under-damped, slight bounce. */
        const val ExpressiveSpringDamping = 0.7f
        const val ExpressiveSpringStiffness = 400f
    }
}

val DefaultRipDpiMotion = RipDpiMotion()

/**
 * Material motion scheme backed by the RDS duration buckets: fast = quick, default = state, slow = emphasized.
 * Spatial specs use the emphasized-decelerate curve and effects specs the standard curve. Disabled animations
 * snap; reduced motion uses the reduced RDS durations via [RipDpiMotion.duration].
 */
@Immutable
internal class RipDpiMotionScheme(
    private val motion: RipDpiMotion,
) : MotionScheme {
    private fun <T> spec(
        baseDurationMillis: Int,
        easing: Easing,
    ): FiniteAnimationSpec<T> =
        if (motion.animationsEnabled) motion.durationTween(baseDurationMillis, easing) else snap()

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spec(motion.stateDurationMillis, RipDpiMotion.EmphasizedDecelerate)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        spec(motion.quickDurationMillis, RipDpiMotion.EmphasizedDecelerate)

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        spec(motion.emphasizedDurationMillis, RipDpiMotion.EmphasizedDecelerate)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        spec(motion.stateDurationMillis, RipDpiMotion.StandardEasing)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        spec(motion.quickDurationMillis, RipDpiMotion.StandardEasing)

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        spec(motion.emphasizedDurationMillis, RipDpiMotion.StandardEasing)
}

internal fun RipDpiMotion.toMotionScheme(): MotionScheme = RipDpiMotionScheme(this)

/** Spring spec for interactive press/release animations (M3 Expressive standard scheme). */
private fun <T> standardSpringSpec(): SpringSpec<T> =
    spring(
        dampingRatio = RipDpiMotion.StandardSpringDamping,
        stiffness = RipDpiMotion.StandardSpringStiffness,
    )

/** Spring spec for selection pops and emphasis animations (M3 Expressive expressive scheme). */
private fun <T> expressiveSpringSpec(): SpringSpec<T> =
    spring(
        dampingRatio = RipDpiMotion.ExpressiveSpringDamping,
        stiffness = RipDpiMotion.ExpressiveSpringStiffness,
    )

internal val LocalRipDpiMotion = staticCompositionLocalOf { DefaultRipDpiMotion }

/**
 * Ergonomic CompositionLocal for reduced-motion: components can read
 * `LocalReducedMotion.current` directly without going through the full
 * `RipDpiThemeTokens.motion` lookup. Always provided alongside
 * `LocalRipDpiMotion` from `RipDpiTheme` and stays in lock-step with
 * `RipDpiMotion.reducedMotion`.
 *
 * The underlying detection is `ValueAnimator.areAnimatorsEnabled()` —
 * Google's canonical signal for the user's reduced-motion preference —
 * which reflects `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` AND
 * the API 33+ "remove animations" accessibility setting in a single
 * call. See `rememberRipDpiMotion()`.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberRipDpiMotion(): RipDpiMotion {
    val isInspectionMode = LocalInspectionMode.current
    val isStaticMotion = isInspectionMode || System.getProperty("ripdpi.staticMotion")?.toBoolean() == true
    val areAnimatorsEnabled = ValueAnimator.areAnimatorsEnabled()

    return when {
        isStaticMotion -> {
            DefaultRipDpiMotion.copy(
                animationsEnabled = false,
                reducedMotion = true,
                hapticsEnabled = false,
            )
        }

        !areAnimatorsEnabled -> {
            DefaultRipDpiMotion.copy(
                animationsEnabled = false,
                reducedMotion = true,
            )
        }

        else -> {
            DefaultRipDpiMotion
        }
    }
}
