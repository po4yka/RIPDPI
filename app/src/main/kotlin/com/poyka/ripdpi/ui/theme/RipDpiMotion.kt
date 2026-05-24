package com.poyka.ripdpi.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode

private const val minReducedDurationMillis = 80

@Immutable
data class RipDpiMotion(
    val animationsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val quickDurationMillis: Int = 120,
    val stateDurationMillis: Int = 220,
    val emphasizedDurationMillis: Int = 320,
    val routeDurationMillis: Int = 260,
    val pressScale: Float = 0.98f,
    val selectionScale: Float = 1.02f,
    val emphasisScale: Float = 1.04f,
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

    fun routePopExitTransition(targetScale: Float = 0.992f): ExitTransition =
        if (!animationsEnabled) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = quickTween(easing = EmphasizedAccelerate)) +
                scaleOut(targetScale = targetScale, animationSpec = quickTween(easing = EmphasizedAccelerate))
        }

    fun nestedEnterTransition(): EnterTransition =
        if (!animationsEnabled) {
            EnterTransition.None
        } else {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> (fullWidth * 0.15f).toInt() },
                animationSpec = routeTween(),
            ) + fadeIn(animationSpec = routeTween())
        }

    fun nestedPopExitTransition(): ExitTransition =
        if (!animationsEnabled) {
            ExitTransition.None
        } else {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> (fullWidth * 0.15f).toInt() },
                animationSpec = quickTween(easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = quickTween(easing = EmphasizedAccelerate))
        }

    /** Linear sweep for skeleton shimmer: 1200ms, restart. */
    fun shimmerSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        )

    /** Cardiac pulse for heartbeat / fresh-data badges: 900ms, restart. */
    fun pulseSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        )

    // === Connection-state actuator motion fingerprints (motion-connection-states.html) ===

    /**
     * Concentric ring expansion for the Connecting state.
     * Inset → scale(1.4), opacity 0.7 → 0, 2 s `StandardEasing`, infinite, restart.
     * Animate a Float 0f → 1f and use it to drive both `scale` (1.0 + 0.4*t) and
     * `alpha` (0.7 * (1 - t)).
     */
    fun connectRingSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 2_000, easing = StandardEasing),
            repeatMode = RepeatMode.Restart,
        )

    /**
     * Slow inner-core breathe for the Tunneling state.
     * Scale 1.0 ↔ 0.92, opacity 0.12 ↔ 0.22, 1.6 s ease-in-out, infinite reverse.
     * Animate a Float 0f → 1f with `RepeatMode.Reverse` and use it to interpolate
     * scale and alpha.
     */
    fun tunnelBreatheSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = EaseInOutEasing),
            repeatMode = RepeatMode.Reverse,
        )

    /**
     * Asymmetric two-step wobble flash for the Degraded state.
     * 1.2 s total, two opacity flashes inside the cycle, scaled border ring.
     * The infinite repeatable just paces; the consumer reads progress (Float)
     * and applies the keyframe shape: opacity 0 → 0.5 (20-50%) → 0 (60-100%).
     */
    fun degradedWobbleSpec(): InfiniteRepeatableSpec<Float> =
        infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        )

    // === Data-ticker motion (motion-data-ticker.html) ===

    /**
     * Sliding-digit ticker for live metered values (KB/s, RTT, etc.).
     * 320 ms `EmphasizedDecelerate`, one-shot per digit change. Each digit
     * column animates its translationY between the value rows. The 320 ms
     * matches the spec card's `cubic-bezier(0.05, 0.7, 0.1, 1)` decelerate.
     */
    fun digitSlideSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = EmphasizedDecelerate)

    /**
     * Countdown bar for known-duration affordances (reconnect, snooze, probe).
     * Linear easing — the user reads remaining time, so the visual rate must
     * match clock time. The caller supplies the total countdown duration.
     */
    fun countdownSpec(totalMillis: Int): TweenSpec<Float> =
        tween(durationMillis = duration(totalMillis), easing = LinearEasing)

    /** Spring spec that respects reducedMotion -- falls back to critically-damped (no bounce). */
    fun <T> motionAwareSpring(expressive: Boolean = false): SpringSpec<T> =
        if (reducedMotion || !animationsEnabled) {
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

        /** CSS ease-in-out (cubic-bezier(0.42, 0, 0.58, 1)) -- for symmetric breathe / pulse. */
        val EaseInOutEasing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)

        /** M3 Expressive standard spring -- critically damped, no overshoot. */
        const val StandardSpringDamping = 1f
        const val StandardSpringStiffness = 500f

        /** M3 Expressive expressive spring -- under-damped, slight bounce. */
        const val ExpressiveSpringDamping = 0.7f
        const val ExpressiveSpringStiffness = 400f
    }
}

val DefaultRipDpiMotion = RipDpiMotion()

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
