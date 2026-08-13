package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

private val LocalRipDpiTextFieldReplacementKey = compositionLocalOf<Any?> { null }

@Composable
internal fun RipDpiTextFieldReplacementScope(
    replacementKey: Any,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRipDpiTextFieldReplacementKey provides replacementKey, content = content)
}

/**
 * Owns state-based editing while projecting accepted text into an existing screen-state contract.
 * External replacements, such as imports or resets, must change [replacementKey]. Ordinary value
 * emissions never rewrite the active editing buffer, preserving selection and IME composition when
 * owner state echoes edits asynchronously or out of order.
 */
@Composable
fun rememberRipDpiTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
    replacementKey: Any? = null,
): TextFieldState {
    val effectiveReplacementKey = replacementKey ?: LocalRipDpiTextFieldReplacementKey.current
    val state = remember { TextFieldState(initialText = value) }
    BindRipDpiTextFieldState(
        state = state,
        value = value,
        onValueChange = onValueChange,
        replacementKey = effectiveReplacementKey,
    )
    return state
}

/** Creates a non-saveable editing buffer for credentials and other sensitive drafts. */
@Composable
fun rememberRipDpiSecureTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
    replacementKey: Any? = null,
): TextFieldState {
    val effectiveReplacementKey = replacementKey ?: LocalRipDpiTextFieldReplacementKey.current
    val state = remember { TextFieldState(initialText = value) }
    BindRipDpiTextFieldState(
        state = state,
        value = value,
        onValueChange = onValueChange,
        replacementKey = effectiveReplacementKey,
    )
    return state
}

@Composable
private fun BindRipDpiTextFieldState(
    state: TextFieldState,
    value: String,
    onValueChange: (String) -> Unit,
    replacementKey: Any?,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val lastReplacementKey = remember(state) { arrayOf(replacementKey) }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != currentValue) currentOnValueChange(text)
        }
    }
    LaunchedEffect(state, replacementKey) {
        if (lastReplacementKey[0] != replacementKey) {
            lastReplacementKey[0] = replacementKey
            if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
        }
    }
}
