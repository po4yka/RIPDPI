@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.StyleResolver
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
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
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleContent
import com.poyka.ripdpi.ui.theme.ripDpiStylePadding
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

private const val textFieldContentSpacingDp = 8

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

@Composable
@NonRestartableComposable
fun RipDpiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    trailingContent: (@Composable () -> Unit)? = null,
) {
    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        decoration = decoration,
        behavior = behavior,
        visualStyle = CommonStyle,
        trailingContent = trailingContent,
    )
}

@Composable
fun RipDpiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    visualStyle: CommonStyle = CommonStyle,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components
    val resolvedTextStyle = behavior.textStyle ?: RipDpiThemeTokens.type.monoValue
    val resolvedInteractionSource = behavior.interactionSource ?: remember { MutableInteractionSource() }
    val controlledState = rememberControlledTextFieldState(value, onValueChange)
    // Read the shown text unconditionally: edits must recompose so rememberControlledTextFieldState syncs.
    val shownTextIsEmpty = controlledState.textFieldState.text.isEmpty()
    val isEmpty = shownTextIsEmpty && value.isEmpty()
    val styleState = rememberTextFieldStyleState(resolvedInteractionSource, isEmpty, decoration, behavior)
    val styleResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.textField, visualStyle, styleState)
    val state =
        RipDpiThemeTokens.state.textField.resolve(
            enabled = behavior.enabled,
            hasError = decoration.errorText != null,
            isFocused = styleState.isFocused,
            isEmpty = isEmpty,
        )
    val decorator =
        TextFieldDecorator { innerTextField ->
            RipDpiTextFieldShell(
                shape = RipDpiThemeTokens.shapes.xl,
                styleResolver = styleResolver,
                minHeight = behavior.minHeight ?: components.inputs.controlHeight,
                trailingContent = trailingContent,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (isEmpty && decoration.placeholder != null) {
                        Text(
                            text = decoration.placeholder,
                            style = resolvedTextStyle,
                            color = state.placeholder,
                        )
                    }
                    innerTextField()
                }
            }
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(components.inputs.textFieldLabelGap),
    ) {
        decoration.label?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                style = RipDpiThemeTokens.type.smallLabel,
                color = state.label,
            )
        }
        RipDpiTextFieldInput(
            controlledState = controlledState,
            behavior = behavior,
            textStyle = resolvedTextStyle.copy(color = state.content),
            interactionSource = resolvedInteractionSource,
            decorator = decorator,
            modifier =
                Modifier
                    .fillMaxWidth()
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
                color = state.helper,
                modifier = Modifier.alpha(state.alpha),
            )
        }
    }
}

@Composable
private fun RipDpiTextFieldInput(
    controlledState: ControlledTextFieldState,
    behavior: RipDpiTextFieldBehavior,
    textStyle: TextStyle,
    interactionSource: MutableInteractionSource,
    decorator: TextFieldDecorator,
    modifier: Modifier = Modifier,
) {
    val textFieldState = controlledState.textFieldState
    val forwardEdits = controlledState.forwardEdits
    val obfuscationMode = behavior.textObfuscationMode
    // A revealed secret stays a plain field so it can be copied, as with VisualTransformation.None.
    if (obfuscationMode == null || obfuscationMode == TextObfuscationMode.Visible) {
        BasicTextField(
            state = textFieldState,
            modifier = modifier,
            enabled = behavior.enabled,
            readOnly = behavior.readOnly,
            inputTransformation = forwardEdits,
            textStyle = textStyle,
            keyboardOptions = behavior.keyboardOptions,
            onKeyboardAction = behavior.onKeyboardAction,
            lineLimits =
                if (behavior.singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
            interactionSource = interactionSource,
            decorator = decorator,
        )
    } else {
        BasicSecureTextField(
            state = textFieldState,
            modifier = modifier,
            enabled = behavior.enabled,
            readOnly = behavior.readOnly,
            inputTransformation = forwardEdits,
            textStyle = textStyle,
            keyboardOptions = secureKeyboardDefaults.merge(behavior.keyboardOptions),
            onKeyboardAction = behavior.onKeyboardAction,
            interactionSource = interactionSource,
            decorator = decorator,
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
private fun rememberTextFieldStyleState(
    interactionSource: MutableInteractionSource,
    isEmpty: Boolean,
    decoration: RipDpiTextFieldDecoration,
    behavior: RipDpiTextFieldBehavior,
): StyleState =
    rememberUpdatedStyleState(interactionSource) {
        it.isEnabled = behavior.enabled
        it[RipDpiStyleStateKeys.HasError] = decoration.errorText != null
        it[RipDpiStyleStateKeys.IsEmpty] = isEmpty
        it[RipDpiStyleStateKeys.Compact] = behavior.density == RipDpiControlDensity.Compact
    }

@Composable
@NonRestartableComposable
fun RipDpiConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    multiline: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    RipDpiConfigTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        decoration = decoration,
        behavior = behavior,
        multiline = multiline,
        visualStyle = CommonStyle,
        trailingContent = trailingContent,
    )
}

@Composable
fun RipDpiConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    multiline: Boolean = false,
    visualStyle: CommonStyle = CommonStyle,
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
        visualStyle = visualStyle,
        trailingContent = trailingContent,
    )
}

@Composable
private fun RipDpiTextFieldShell(
    shape: Shape,
    styleResolver: StyleResolver,
    minHeight: androidx.compose.ui.unit.Dp,
    trailingContent: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .ripDpiStyleSurface(styleResolver, shape = shape)
                .ripDpiStylePadding(styleResolver)
                .ripDpiStyleContent(styleResolver),
        horizontalArrangement = Arrangement.spacedBy(textFieldContentSpacingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            content()
            trailingContent?.invoke()
        },
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
