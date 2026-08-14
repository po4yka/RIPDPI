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

    // === Page-transition motion (motion-page-transitions.html) ===

    /**
     * Child page enters from the right (push). Drives translationX from
     * 1.0f (off-screen right, screen-width units) to 0f. 320 ms
     * EmphasizedDecelerate, matches OS-level forward-nav feel.
     */
    fun pageEnterSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = EmphasizedDecelerate)

    /**
     * Parent page parallax-exits on push. Drives translationX from 0f to
     * -0.25f (-25% of screen width) AND alpha from 1.0f to 0.5f in lock-step.
     * Same 320 ms emphasized curve so it stays synchronized with the
     * incoming child.
     */
    fun pageExitSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = EmphasizedDecelerate)

    /**
     * Modal sheet slides up from the bottom. Drives translationY from 1.0f
     * (off-screen bottom, sheet-height units) to 0f. Same 320 ms emphasized
     * curve as horizontal page transitions for cross-axis consistency.
     */
    fun modalEnterSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = EmphasizedDecelerate)

    /**
     * Scrim fade behind modal sheets. Drives alpha from 0f to 0.4f. Same
     * 320 ms emphasized curve so scrim and sheet rise together.
     */
    fun scrimFadeSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = EmphasizedDecelerate)

    // === Toast choreography (motion-toast-choreography.html) ===

    /**
     * Toast rise from below into the front slot. Drives translationY from
     * +80f (px below) to 0f and scale from 0.95f to 1.0f in lock-step.
     * 320 ms EmphasizedDecelerate for a settled landing.
     */
    fun toastEnterSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(emphasizedDurationMillis), easing = EmphasizedDecelerate)

    /**
     * Toast pushed back to the next stack slot when a newer toast arrives.
     * Drives translationY (0 -> -12 -> -26), scale (1.0 -> 0.97 -> 0.93),
     * alpha (1.0 -> 0.75 -> 0.4). 220 ms state curve so the push-back is
     * snappier than the enter; consumers chain two pushBack invocations
     * for front -> slot-2 -> slot-3.
     */
    fun toastPushBackSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(stateDurationMillis), easing = StandardEasing)

    /**
     * Toast exit (auto-timeout or swipe-throw). Drives translationY from
     * -26f (slot-3 settled) to -58f and alpha from 0.4f to 0f. 220 ms
     * state curve. For swipe-dismiss, the consumer drives translationX
     * 1:1 with the finger and triggers exitSpec at > 40 % screen width.
     */
    fun toastExitSpec(): TweenSpec<Float> =
        tween(durationMillis = duration(stateDurationMillis), easing = StandardEasing)

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
