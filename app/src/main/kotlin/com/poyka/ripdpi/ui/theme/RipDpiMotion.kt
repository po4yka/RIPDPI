package com.poyka.ripdpi.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode

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
                (baseDurationMillis / 2).coerceAtLeast(80)
            }

            else -> {
                baseDurationMillis
            }
        }

    val allowsInfiniteMotion: Boolean
        get() = animationsEnabled && !reducedMotion

    companion object {
        /** M3 emphasized decelerate -- use for entering elements. */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

        /** M3 emphasized accelerate -- use for exiting elements. */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

        /** M3 standard -- use for on-screen property changes (color, opacity). */
        val StandardEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
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
