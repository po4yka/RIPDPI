package com.poyka.ripdpi.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiHapticFeedback {
    None,
    Action,
    Selection,
    Toggle,
}

private fun View.performRipDpiHapticFeedback(feedback: RipDpiHapticFeedback) {
    val platformFeedback =
        when (feedback) {
            RipDpiHapticFeedback.None -> return
            RipDpiHapticFeedback.Action -> HapticFeedbackConstants.VIRTUAL_KEY
            RipDpiHapticFeedback.Selection -> HapticFeedbackConstants.CLOCK_TICK
            RipDpiHapticFeedback.Toggle -> HapticFeedbackConstants.KEYBOARD_TAP
        }
    performHapticFeedback(platformFeedback)
}

fun Modifier.ripDpiClickable(
    enabled: Boolean = true,
    role: Role? = null,
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

fun Modifier.ripDpiSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Selection,
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
            indication = LocalIndication.current,
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
