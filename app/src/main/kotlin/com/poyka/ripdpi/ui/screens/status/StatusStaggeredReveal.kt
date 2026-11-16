package com.poyka.ripdpi.ui.screens.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Item [index] of a list that was just disclosed: it enters after the items before it.
 * With [animate] false, or with animations off, the item is there at once.
 */
@Composable
internal fun StatusStaggeredReveal(
    index: Int,
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    val visible = remember { MutableTransitionState(!animate).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        enter = RipDpiThemeTokens.motion.staggeredSectionEnterTransition(index),
        exit = ExitTransition.None,
    ) {
        content()
    }
}
