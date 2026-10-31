package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val DisabledAlpha = 0.38f

// Mirrors the internal KeyboardOptions.SecureTextField that BasicSecureTextField uses by default.
private val secureKeyboardDefaults = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

data class RipDpiTextFieldDecoration(
    val label: String? = null,
    val placeholder: String? = null,
    val helperText: String? = null,
    val errorText: String? = null,
    val testTag: String? = null,
)

data class RipDpiTextFieldBehavior(
    val enabled: Boolean = true,
    val readOnly: Boolean = false,
    val density: RipDpiControlDensity = RipDpiControlDensity.Default,
    val singleLine: Boolean = true,
    val textStyle: TextStyle? = null,
    val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    val onKeyboardAction: KeyboardActionHandler? = null,
    // Hidden or RevealLastTyped renders a secure field (masked, no copy/cut); Visible renders a plain field.
    // Keep the field non-null while a reveal toggle switches between Visible and Hidden.
    val textObfuscationMode: TextObfuscationMode? = null,
    val minHeight: Dp? = null,
    val interactionSource: MutableInteractionSource? = null,
)

/** Material 3 [OutlinedTextField] (or [OutlinedSecureTextField]) with the label and helper text above and below it. */
@Composable
fun RipDpiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components
    val colors = RipDpiThemeTokens.colors
    val resolvedTextStyle = behavior.textStyle ?: RipDpiThemeTokens.type.monoValue
    // A placeholder is a hint, not a technical value: sans text with the same metrics as monoValue.
    // A caller-supplied style (config and code fields) still applies to the placeholder.
    val placeholderTextStyle = behavior.textStyle ?: RipDpiThemeTokens.type.secondaryBody
    val controlledState = rememberControlledTextFieldState(value, onValueChange)
    // Read the shown text in composition: edits must recompose so rememberControlledTextFieldState syncs.
    controlledState.textFieldState.text
    val hasError = decoration.errorText != null
    val supportingColor = if (hasError) colors.destructive else colors.mutedForeground
    val horizontalPadding = if (behavior.density == RipDpiControlDensity.Compact) 12.dp else 16.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(components.inputs.textFieldLabelGap),
    ) {
        decoration.label?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                style = RipDpiThemeTokens.type.smallLabel,
                color = supportingColor,
            )
        }
        RipDpiTextFieldInput(
            controlledState = controlledState,
            behavior = behavior,
            textStyle = resolvedTextStyle,
            isError = hasError,
            placeholder =
                decoration.placeholder?.let { { Text(text = it, style = placeholderTextStyle) } },
            trailingContent = trailingContent,
            horizontalPadding = horizontalPadding,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = behavior.minHeight ?: components.inputs.controlHeight)
                    .ripDpiTestTag(decoration.testTag)
                    .semantics {
                        decoration.label?.let { contentDescription = it }
                        decoration.errorText?.let { error(it) }
                    },
        )
        (decoration.errorText ?: decoration.helperText)?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.caption,
                color = supportingColor,
                modifier = Modifier.alpha(if (behavior.enabled) 1f else DisabledAlpha),
            )
        }
    }
}

/** RDS roles mapped into the Material outlined field colors: ink focus ring, destructive error, muted idle. */
@Composable
internal fun ripDpiOutlinedTextFieldColors(
    disabledText: Color? = null,
    disabledBorder: Color? = null,
    disabledTrailingIcon: Color? = null,
): TextFieldColors {
    val colors = RipDpiThemeTokens.colors
    val disabledContent = colors.mutedForeground.copy(alpha = DisabledAlpha)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.foreground,
        unfocusedTextColor = colors.mutedForeground,
        disabledTextColor = disabledText ?: disabledContent,
        errorTextColor = colors.foreground,
        focusedContainerColor = colors.inputBackground,
        unfocusedContainerColor = colors.inputBackground,
        disabledContainerColor = colors.inputBackground,
        errorContainerColor = colors.inputBackground,
        cursorColor = colors.foreground,
        errorCursorColor = colors.destructive,
        focusedBorderColor = colors.foreground,
        unfocusedBorderColor = colors.outlineVariant,
        disabledBorderColor = disabledBorder ?: colors.border,
        errorBorderColor = colors.destructive,
        focusedTrailingIconColor = colors.mutedForeground,
        unfocusedTrailingIconColor = colors.mutedForeground,
        disabledTrailingIconColor = disabledTrailingIcon ?: disabledContent,
        errorTrailingIconColor = colors.destructive,
        focusedPlaceholderColor = colors.mutedForeground,
        unfocusedPlaceholderColor = colors.mutedForeground,
        disabledPlaceholderColor = disabledContent,
        errorPlaceholderColor = colors.mutedForeground,
    )
}

@Composable
private fun RipDpiTextFieldInput(
    controlledState: ControlledTextFieldState,
    behavior: RipDpiTextFieldBehavior,
    textStyle: TextStyle,
    isError: Boolean,
    placeholder: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val textFieldState = controlledState.textFieldState
    val forwardEdits = controlledState.forwardEdits
    val obfuscationMode = behavior.textObfuscationMode
    val colors = ripDpiOutlinedTextFieldColors()
    val shape = RipDpiThemeTokens.shapes.xl
    val verticalPadding =
        if (behavior.singleLine) 0.dp else RipDpiThemeTokens.components.inputs.multilineFieldVerticalPadding
    val contentPadding =
        OutlinedTextFieldDefaults.contentPaddingWithoutLabel(
            start = horizontalPadding,
            end = horizontalPadding,
            top = verticalPadding,
            bottom = verticalPadding,
        )
    // A revealed secret stays a plain field so it can be copied, as with VisualTransformation.None.
    if (obfuscationMode == null || obfuscationMode == TextObfuscationMode.Visible) {
        OutlinedTextField(
            state = textFieldState,
            modifier = modifier,
            enabled = behavior.enabled,
            readOnly = behavior.readOnly,
            inputTransformation = forwardEdits,
            textStyle = textStyle,
            placeholder = placeholder,
            trailingIcon = trailingContent,
            isError = isError,
            keyboardOptions = behavior.keyboardOptions,
            onKeyboardAction = behavior.onKeyboardAction,
            lineLimits =
                if (behavior.singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = behavior.interactionSource,
        )
    } else {
        OutlinedSecureTextField(
            state = textFieldState,
            modifier = modifier,
            enabled = behavior.enabled,
            inputTransformation = forwardEdits,
            textStyle = textStyle,
            placeholder = placeholder,
            trailingIcon = trailingContent,
            isError = isError,
            keyboardOptions = secureKeyboardDefaults.merge(behavior.keyboardOptions),
            onKeyboardAction = behavior.onKeyboardAction,
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            interactionSource = behavior.interactionSource,
            textObfuscationMode = obfuscationMode,
        )
    }
}

/**
 * Keeps a [TextFieldState] controlled by the caller's value, as the value-based BasicTextField was: the
 * caller's value wins, so programmatic updates appear and edits the caller rejected are dropped.
 */
private class ControlledTextFieldState(
    initialValue: String,
) {
    // Not rememberSaveable: fields carry credentials that must stay out of saved state.
    val textFieldState = TextFieldState(initialValue)
    var onValueChange: (String) -> Unit = {}

    // The text the caller knows about: the last value it passed in or was told about.
    private var lastSynced = initialValue

    val forwardEdits =
        InputTransformation {
            if (!asCharSequence().contentEquals(originalText)) report(asCharSequence().toString())
        }

    fun sync(value: String) {
        val text = textFieldState.text
        when {
            text.contentEquals(value) -> {
                lastSynced = value
            }

            // Changed without an InputTransformation pass, for example a hardware-keyboard undo.
            !text.contentEquals(lastSynced) -> {
                report(text.toString())
            }

            else -> {
                lastSynced = value
                textFieldState.edit {
                    val selection = selection
                    replace(0, length, value)
                    this.selection =
                        TextRange(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length))
                }
            }
        }
    }

    private fun report(text: String) {
        lastSynced = text
        onValueChange(text)
    }
}

@Composable
private fun rememberControlledTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
): ControlledTextFieldState {
    val controlledState = remember { ControlledTextFieldState(value) }
    SideEffect {
        controlledState.onValueChange = onValueChange
        controlledState.sync(value)
    }
    return controlledState
}

@Composable
fun RipDpiConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    multiline: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components

    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        decoration = decoration,
        behavior =
            behavior.copy(
                singleLine = !multiline,
                textStyle = behavior.textStyle ?: RipDpiThemeTokens.type.monoConfig,
                minHeight =
                    if (multiline) {
                        behavior.minHeight ?: components.inputs.multilineFieldMinHeight
                    } else {
                        behavior.minHeight
                    },
            ),
        trailingContent = trailingContent,
    )
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTextFieldLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                decoration = RipDpiTextFieldDecoration(placeholder = "128"),
            )
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = "128",
                        helperText = "Maximum connections",
                    ),
            )
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                decoration = RipDpiTextFieldDecoration(errorText = "Value must stay below 128"),
            )
            RipDpiTextField(
                value = "",
                onValueChange = {},
                decoration = RipDpiTextFieldDecoration(label = "Port", placeholder = "1080"),
                behavior = RipDpiTextFieldBehavior(enabled = false),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiTextFieldDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                decoration = RipDpiTextFieldDecoration(placeholder = "128"),
            )
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                decoration = RipDpiTextFieldDecoration(errorText = "Value must stay below 128"),
            )
        }
    }
}
