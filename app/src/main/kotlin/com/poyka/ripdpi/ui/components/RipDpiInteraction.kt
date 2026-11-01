package com.poyka.ripdpi.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiHapticFeedback {
    None,
    Action,
    Selection,
    Toggle,
    Confirm,
    Success,
    Error,
    Acknowledge,
}

private fun View.performRipDpiHapticFeedback(feedback: RipDpiHapticFeedback) {
    val platformFeedback =
        when (feedback) {
            RipDpiHapticFeedback.None -> {
                return
            }

            RipDpiHapticFeedback.Action -> {
                HapticFeedbackConstants.VIRTUAL_KEY
            }

            RipDpiHapticFeedback.Selection -> {
                HapticFeedbackConstants.CLOCK_TICK
            }

            RipDpiHapticFeedback.Toggle -> {
                HapticFeedbackConstants.KEYBOARD_TAP
            }

            RipDpiHapticFeedback.Confirm,
            RipDpiHapticFeedback.Success,
            -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }

            RipDpiHapticFeedback.Error -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }

            RipDpiHapticFeedback.Acknowledge -> {
                HapticFeedbackConstants.CONTEXT_CLICK
            }
        }
    performHapticFeedback(platformFeedback)
}

@Composable
fun rememberRipDpiHapticPerformer(): (RipDpiHapticFeedback) -> Unit {
    val motion = RipDpiThemeTokens.motion
    val view = LocalView.current
    return remember(view, motion.hapticsEnabled) {
        { feedback: RipDpiHapticFeedback ->
            if (motion.hapticsEnabled) {
                view.performRipDpiHapticFeedback(feedback)
            }
        }
    }
}

fun Modifier.ripDpiClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Action,
    onClick: () -> Unit,
): Modifier =
    composed {
        val motion = RipDpiThemeTokens.motion
        val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
        val view = LocalView.current
        minimumInteractiveComponentSize().clickable(
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            interactionSource = resolvedInteractionSource,
            indication = LocalIndication.current,
            onClick = {
                if (motion.hapticsEnabled) {
                    view.performRipDpiHapticFeedback(hapticFeedback)
                }
                onClick()
            },
        )
    }

/**
 * Draws a 2dp keyboard focus ring just inside [shape]. The ring takes whichever of ink or paper holds at least 3:1
 * against [container] ([ink] defaults to the foreground); a transparent container sits on the page, so it is measured as the page background.
 */
fun Modifier.ripDpiFocusRing(
    interactionSource: InteractionSource,
    shape: Shape,
    container: Color,
    ink: Color = Color.Unspecified,
): Modifier =
    composed {
        val colors = RipDpiThemeTokens.colors
        val focused by interactionSource.collectIsFocusedAsState()
        if (!focused) {
            Modifier
        } else {
            val surface = if (container == Color.Transparent) colors.background else container
            Modifier.border(
                RipDpiStroke.Thick,
                ripDpiFocusRingColor(surface, ink.takeOrElse { colors.foreground }, colors.background),
                shape,
            )
        }
    }

internal fun ripDpiFocusRingColor(
    container: Color,
    ink: Color,
    paper: Color,
): Color = if (contrastRatio(ink, container) >= FocusRingMinimumContrast) ink else paper

internal fun contrastRatio(
    first: Color,
    second: Color,
): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

private const val FocusRingMinimumContrast = 3f

fun Modifier.ripDpiSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Selection,
    showIndication: Boolean = true,
    onClick: () -> Unit,
): Modifier =
    composed {
        val motion = RipDpiThemeTokens.motion
        val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
        val view = LocalView.current
        minimumInteractiveComponentSize().selectable(
            selected = selected,
            enabled = enabled,
            role = role,
            interactionSource = resolvedInteractionSource,
            indication = if (showIndication) LocalIndication.current else null,
            onClick = {
                if (motion.hapticsEnabled) {
                    view.performRipDpiHapticFeedback(hapticFeedback)
                }
                onClick()
            },
        )
    }

fun Modifier.ripDpiToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Toggle,
    onValueChange: (Boolean) -> Unit,
): Modifier =
    composed {
        val motion = RipDpiThemeTokens.motion
        val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
        val view = LocalView.current
        minimumInteractiveComponentSize().toggleable(
            value = value,
            enabled = enabled,
            role = role,
            interactionSource = resolvedInteractionSource,
            indication = LocalIndication.current,
            onValueChange = { newValue ->
                if (motion.hapticsEnabled) {
                    view.performRipDpiHapticFeedback(hapticFeedback)
                }
                onValueChange(newValue)
            },
        )
    }
