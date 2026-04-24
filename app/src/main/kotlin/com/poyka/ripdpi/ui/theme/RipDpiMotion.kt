package com.poyka.ripdpi.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
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

    /** Spring spec for interactive press/release animations (M3 Expressive standard scheme). */
    fun <T> standardSpring(): SpringSpec<T> =
        spring(
            dampingRatio = StandardSpringDamping,
            stiffness = StandardSpringStiffness,
        )

    /** Spring spec for selection pops and emphasis animations (M3 Expressive expressive scheme). */
    fun <T> expressiveSpring(): SpringSpec<T> =
        spring(
            dampingRatio = ExpressiveSpringDamping,
            stiffness = ExpressiveSpringStiffness,
        )

    /** Spring spec that respects reducedMotion -- falls back to critically-damped (no bounce). */
    fun <T> motionAwareSpring(expressive: Boolean = false): SpringSpec<T> =
        if (reducedMotion || !animationsEnabled) {
            spring(dampingRatio = 1f, stiffness = StandardSpringStiffness)
        } else if (expressive) {
            expressiveSpring()
        } else {
            standardSpring()
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
