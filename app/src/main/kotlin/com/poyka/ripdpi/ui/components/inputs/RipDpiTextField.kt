package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val textFieldContentSpacingDp = 8

// Internal threshold for detecting a focused/thickened border — not a design token.
private val FocusedPaddingMinimumDp = 1.dp

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
    val textStyle: TextStyle? = null,
    val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    val minHeight: Dp? = null,
    val interactionSource: MutableInteractionSource? = null,
)

@Composable
fun RipDpiTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    onKeyboardAction: KeyboardActionHandler? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    RipDpiTextFieldLayout(
        state = state,
        modifier = modifier,
        decoration = decoration,
        behavior = behavior,
        trailingContent = trailingContent,
    ) { fieldModifier, textStyle, interactionSource, decorator ->
        BasicTextField(
            state = state,
            modifier = fieldModifier,
            enabled = behavior.enabled,
            readOnly = behavior.readOnly,
            inputTransformation = inputTransformation,
            textStyle = textStyle,
            keyboardOptions = behavior.keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = lineLimits,
            interactionSource = interactionSource,
            outputTransformation = outputTransformation,
            decorator = decorator,
        )
    }
}

@Composable
fun RipDpiSecureTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    inputTransformation: InputTransformation? = null,
    onKeyboardAction: KeyboardActionHandler? = null,
    textObfuscationMode: TextObfuscationMode = TextObfuscationMode.Hidden,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    RipDpiTextFieldLayout(
        state = state,
        modifier = modifier,
        decoration = decoration,
        behavior = behavior,
        trailingContent = trailingContent,
    ) { fieldModifier, textStyle, interactionSource, decorator ->
        BasicSecureTextField(
            state = state,
            modifier = fieldModifier,
            enabled = behavior.enabled,
            readOnly = behavior.readOnly,
            inputTransformation = inputTransformation,
            textStyle = textStyle,
            keyboardOptions = behavior.keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            interactionSource = interactionSource,
            decorator = decorator,
            textObfuscationMode = textObfuscationMode,
        )
    }
}

@Composable
private fun RipDpiTextFieldLayout(
    state: TextFieldState,
    modifier: Modifier,
    decoration: RipDpiTextFieldDecoration,
    behavior: RipDpiTextFieldBehavior,
    trailingContent: (@Composable () -> Unit)?,
    textField: @Composable (
        Modifier,
        TextStyle,
        MutableInteractionSource,
        TextFieldDecorator,
    ) -> Unit,
) {
    val components = RipDpiThemeTokens.components
    val resolvedTextStyle = behavior.textStyle ?: RipDpiThemeTokens.type.monoValue
    val resolvedInteractionSource = behavior.interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val fieldStyle =
        RipDpiThemeTokens.state.textField.resolve(
            enabled = behavior.enabled,
            hasError = decoration.errorText != null,
            isFocused = isFocused,
            isEmpty = state.text.isEmpty(),
        )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(components.inputs.textFieldLabelGap),
    ) {
        decoration.label?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.smallLabel,
                color = fieldStyle.label,
            )
        }
        textField(
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(decoration.testTag)
                .semantics {
                    decoration.label?.let { contentDescription = it }
                    decoration.errorText?.let { error(it) }
                },
            resolvedTextStyle.copy(color = fieldStyle.content),
            resolvedInteractionSource,
            TextFieldDecorator { innerTextField ->
                RipDpiTextFieldShell(
                    shape = RipDpiThemeTokens.shapes.xl,
                    containerColor = fieldStyle.container,
                    borderColor = fieldStyle.border,
                    borderWidth = fieldStyle.borderWidth,
                    alpha = fieldStyle.alpha,
                    minHeight = behavior.minHeight ?: components.inputs.controlHeight,
                    density = behavior.density,
                    trailingContent = trailingContent,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (state.text.isEmpty() && decoration.placeholder != null) {
                            Text(
                                text = decoration.placeholder,
                                style = resolvedTextStyle,
                                color = fieldStyle.placeholder,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
        (decoration.errorText ?: decoration.helperText)?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.caption,
                color = fieldStyle.helper,
                modifier = Modifier.alpha(fieldStyle.alpha),
            )
        }
    }
}

@Composable
fun RipDpiConfigTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    decoration: RipDpiTextFieldDecoration = RipDpiTextFieldDecoration(),
    behavior: RipDpiTextFieldBehavior = RipDpiTextFieldBehavior(),
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    inputTransformation: InputTransformation? = null,
    outputTransformation: OutputTransformation? = null,
    onKeyboardAction: KeyboardActionHandler? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val components = RipDpiThemeTokens.components

    RipDpiTextField(
        state = state,
        modifier = modifier,
        decoration = decoration,
        behavior =
            behavior.copy(
                textStyle = behavior.textStyle ?: RipDpiThemeTokens.type.monoConfig,
                minHeight =
                    if (lineLimits is TextFieldLineLimits.MultiLine) {
                        behavior.minHeight ?: components.inputs.multilineFieldMinHeight
                    } else {
                        behavior.minHeight
                    },
            ),
        lineLimits = lineLimits,
        inputTransformation = inputTransformation,
        outputTransformation = outputTransformation,
        onKeyboardAction = onKeyboardAction,
        trailingContent = trailingContent,
    )
}

@Composable
private fun RipDpiTextFieldShell(
    shape: Shape,
    containerColor: Color,
    borderColor: Color,
    borderWidth: androidx.compose.ui.unit.Dp,
    alpha: Float,
    minHeight: androidx.compose.ui.unit.Dp,
    density: RipDpiControlDensity,
    trailingContent: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val components = RipDpiThemeTokens.components
    val basePadding =
        if (borderWidth > FocusedPaddingMinimumDp) {
            components.inputs.fieldFocusedHorizontalPadding
        } else {
            components.inputs.fieldHorizontalPadding
        }
    val horizontalPadding =
        when (density) {
            RipDpiControlDensity.Default -> basePadding
            RipDpiControlDensity.Compact -> basePadding - 4.dp
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(containerColor, shape)
                .border(borderWidth, borderColor, shape)
                .padding(start = horizontalPadding, end = horizontalPadding)
                .alpha(alpha),
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
                state =
                    rememberRipDpiTextFieldState(
                        value = "128",
                        onValueChange = {},
                    ),
                decoration = RipDpiTextFieldDecoration(placeholder = "128"),
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = "128",
                        onValueChange = {},
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = "128",
                        helperText = "Maximum connections",
                    ),
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = "128",
                        onValueChange = {},
                    ),
                decoration = RipDpiTextFieldDecoration(errorText = "Value must stay below 128"),
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = "",
                        onValueChange = {},
                    ),
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
                state =
                    rememberRipDpiTextFieldState(
                        value = "128",
                        onValueChange = {},
                    ),
                decoration = RipDpiTextFieldDecoration(placeholder = "128"),
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = "128",
                        onValueChange = {},
                    ),
                decoration = RipDpiTextFieldDecoration(errorText = "Value must stay below 128"),
            )
        }
    }
}
