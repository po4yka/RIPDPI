package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role

fun Modifier.ripDpiClickable(
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
        minimumInteractiveComponentSize().clickable(
            enabled = enabled,
            role = role,
            interactionSource = resolvedInteractionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
    }

fun Modifier.ripDpiSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier =
    composed {
        val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
        minimumInteractiveComponentSize().selectable(
            selected = selected,
            enabled = enabled,
            role = role,
            interactionSource = resolvedInteractionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
    }

fun Modifier.ripDpiToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier =
    composed {
        val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
        minimumInteractiveComponentSize().toggleable(
            value = value,
            enabled = enabled,
            role = role,
            interactionSource = resolvedInteractionSource,
            indication = LocalIndication.current,
            onValueChange = onValueChange,
        )
    }
