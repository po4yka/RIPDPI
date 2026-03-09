package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
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
    forceFocused: Boolean = false,
    textStyle: TextStyle = RipDpiThemeTokens.type.monoValue,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    var isFocused by remember { mutableStateOf(false) }
    val fieldFocused = forceFocused || isFocused
    val borderWidth =
        when {
            !enabled -> 1.dp
            errorText != null -> 2.dp
            fieldFocused -> 2.dp
            else -> 1.dp
        }
    val borderColor =
        when {
            !enabled -> colors.border
            errorText != null -> colors.destructive
            fieldFocused -> colors.foreground
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val contentColor =
        when {
            !enabled -> colors.mutedForeground
            value.isEmpty() -> colors.mutedForeground
            fieldFocused || errorText != null -> colors.foreground
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
            decorationBox = { innerTextField ->
                RipDpiTextFieldShell(
                    enabled = enabled,
                    shape = RoundedCornerShape(16.dp),
                    borderColor = borderColor,
                    borderWidth = borderWidth,
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
private fun RipDpiTextFieldShell(
    enabled: Boolean,
    shape: Shape,
    borderColor: Color,
    borderWidth: androidx.compose.ui.unit.Dp,
    trailingContent: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val startPadding = if (borderWidth > 1.dp) 18.dp else 17.dp
    val endPadding = if (borderWidth > 1.dp) 18.dp else 17.dp

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
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
                forceFocused = true,
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
