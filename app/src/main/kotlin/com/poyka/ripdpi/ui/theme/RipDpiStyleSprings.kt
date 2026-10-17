@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp

/** Keep typed spring velocity when a press changes direction in Foundation 1.13 beta. */
@Composable
internal fun rememberRipDpiSpringStyle(
    defaults: CommonStyle,
    scale: Float,
    cornerRadius: Dp? = null,
    expressiveScale: Boolean = false,
): CommonStyle {
    val motion = RipDpiThemeTokens.motion
    // Dropping the running animator also handles disabling motion without a target change.
    val animatedScale =
        if (motion.animationsEnabled) {
            animateFloatAsState(scale, motion.motionAwareSpring(expressiveScale), label = "rdsContentScale")
        } else {
            rememberUpdatedState(scale)
        }
    val animatedRadius =
        cornerRadius?.let {
            if (motion.animationsEnabled) {
                animateDpAsState(it, motion.motionAwareSpring(), label = "rdsCornerRadius")
            } else {
                rememberUpdatedState(it)
            }
        }
    return remember(defaults, animatedScale, animatedRadius) {
        CommonStyle {
            with(defaults) { applyStyle() }
            // Same-layer ties are last-wins. Caller overrides remain in resolver layer one.
            // State values are read by the attached resolver, never by Composition.
            rdsContentScale(animatedScale.value)
            animatedRadius?.let { rdsCornerRadius(it.value) }
        }
    }
}
