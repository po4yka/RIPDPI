package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    textStyle: TextStyle = RipDpiThemeTokens.type.monoValue,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    minHeight: androidx.compose.ui.unit.Dp? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val type = RipDpiThemeTokens.type
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val borderWidth =
        when {
            !enabled -> 1.dp
            errorText != null -> 2.dp
            isFocused -> 2.dp
            else -> 1.dp
        }
    val borderColor =
        when {
            !enabled -> colors.border
            errorText != null -> colors.destructive
            isFocused -> colors.foreground
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val contentColor =
        when {
            !enabled -> colors.mutedForeground
            value.isEmpty() -> colors.mutedForeground
            isFocused || errorText != null -> colors.foreground
            else -> colors.mutedForeground
        }
    val helperColor =
        when {
            errorText != null -> colors.destructive
            else -> colors.mutedForeground
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        label?.let {
            Text(
                text = it,
                style = type.smallLabel,
                color = colors.mutedForeground,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            textStyle = textStyle.copy(color = contentColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = resolvedInteractionSource,
            modifier =
                Modifier
                    .fillMaxWidth(),
            decorationBox = { innerTextField ->
                RipDpiTextFieldShell(
                    enabled = enabled,
                    shape = RipDpiThemeTokens.shapes.xl,
                    borderColor = borderColor,
                    borderWidth = borderWidth,
                    minHeight = minHeight ?: components.controlHeight,
                    trailingContent = trailingContent,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = colors.mutedForeground,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
        (errorText ?: helperText)?.let {
            Text(
                text = it,
                style = type.caption,
                color = helperColor,
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
            )
        }
    }
}

@Composable
fun RipDpiConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    multiline: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val components = RipDpiThemeTokens.components

    RipDpiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helperText = helperText,
        errorText = errorText,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = !multiline,
        textStyle = RipDpiThemeTokens.type.monoConfig,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        minHeight = if (multiline) components.multilineFieldMinHeight else null,
        trailingContent = trailingContent,
        interactionSource = interactionSource,
    )
}

@Composable
private fun RipDpiTextFieldShell(
    enabled: Boolean,
    shape: Shape,
    borderColor: Color,
    borderWidth: androidx.compose.ui.unit.Dp,
    minHeight: androidx.compose.ui.unit.Dp,
    trailingContent: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val startPadding = if (borderWidth > 1.dp) components.fieldFocusedHorizontalPadding else components.fieldHorizontalPadding
    val endPadding = if (borderWidth > 1.dp) components.fieldFocusedHorizontalPadding else components.fieldHorizontalPadding

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(colors.inputBackground, shape)
                .border(borderWidth, borderColor, shape)
                .padding(start = startPadding, end = endPadding)
                .alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                placeholder = "128",
            )
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                placeholder = "128",
                helperText = "Maximum connections",
            )
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                errorText = "Value must stay below 128",
            )
            RipDpiTextField(
                value = "",
                onValueChange = {},
                label = "Port",
                placeholder = "1080",
                enabled = false,
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
                placeholder = "128",
            )
            RipDpiTextField(
                value = "128",
                onValueChange = {},
                errorText = "Value must stay below 128",
            )
        }
    }
}
