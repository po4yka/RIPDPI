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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    val keyboardActions: KeyboardActions = KeyboardActions.Default,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
    val minHeight: Dp? = null,
    val interactionSource: MutableInteractionSource? = null,
)

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
    val resolvedTextStyle = behavior.textStyle ?: RipDpiThemeTokens.type.monoValue
    val resolvedInteractionSource = behavior.interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val colors = resolveTextFieldColors(
        enabled = behavior.enabled,
        hasError = decoration.errorText != null,
        isFocused = isFocused,
        isEmpty = value.isEmpty(),
    )
    val borderWidth = resolveBorderWidth(enabled = behavior.enabled, hasError = decoration.errorText != null, isFocused)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        decoration.label?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.smallLabel,
                color = colors.labelColor,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = behavior.enabled,
            readOnly = behavior.readOnly,
            singleLine = behavior.singleLine,
            textStyle = resolvedTextStyle.copy(color = colors.contentColor),
            keyboardOptions = behavior.keyboardOptions,
            keyboardActions = behavior.keyboardActions,
            visualTransformation = behavior.visualTransformation,
            interactionSource = resolvedInteractionSource,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(decoration.testTag)
                    .semantics {
                        decoration.label?.let { contentDescription = it }
                        decoration.errorText?.let { error(it) }
                    },
            decorationBox = { innerTextField ->
                RipDpiTextFieldShell(
                    enabled = behavior.enabled,
                    shape = RipDpiThemeTokens.shapes.xl,
                    borderColor = colors.borderColor,
                    borderWidth = borderWidth,
                    minHeight = behavior.minHeight ?: components.controlHeight,
                    density = behavior.density,
                    trailingContent = trailingContent,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && decoration.placeholder != null) {
                            Text(
                                text = decoration.placeholder,
                                style = resolvedTextStyle,
                                color = RipDpiThemeTokens.colors.mutedForeground,
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
                color = colors.helperColor,
                modifier = Modifier.alpha(if (behavior.enabled) 1f else 0.38f),
            )
        }
    }
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
                        behavior.minHeight ?: components.multilineFieldMinHeight
                    } else {
                        behavior.minHeight
                    },
            ),
        trailingContent = trailingContent,
    )
}

@Composable
private fun RipDpiTextFieldShell(
    enabled: Boolean,
    shape: Shape,
    borderColor: Color,
    borderWidth: androidx.compose.ui.unit.Dp,
    minHeight: androidx.compose.ui.unit.Dp,
    density: RipDpiControlDensity,
    trailingContent: (@Composable () -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val basePadding =
        if (borderWidth > 1.dp) {
            components.fieldFocusedHorizontalPadding
        } else {
            components.fieldHorizontalPadding
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
                .background(colors.inputBackground, shape)
                .border(borderWidth, borderColor, shape)
                .padding(start = horizontalPadding, end = horizontalPadding)
                .alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            content()
            trailingContent?.invoke()
        },
    )
}

private data class RipDpiTextFieldColors(
    val borderColor: Color,
    val contentColor: Color,
    val helperColor: Color,
    val labelColor: Color,
)

@Composable
private fun resolveTextFieldColors(
    enabled: Boolean,
    hasError: Boolean,
    isFocused: Boolean,
    isEmpty: Boolean,
): RipDpiTextFieldColors {
    val colors = RipDpiThemeTokens.colors
    return RipDpiTextFieldColors(
        borderColor =
            when {
                !enabled -> colors.border
                hasError -> colors.destructive
                isFocused -> colors.foreground
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        contentColor =
            when {
                !enabled || isEmpty -> colors.mutedForeground
                isFocused || hasError -> colors.foreground
                else -> colors.mutedForeground
            },
        helperColor = if (hasError) colors.destructive else colors.mutedForeground,
        labelColor = if (hasError) colors.destructive else colors.mutedForeground,
    )
}

private fun resolveBorderWidth(
    enabled: Boolean,
    hasError: Boolean,
    isFocused: Boolean,
): Dp =
    when {
        !enabled -> 1.dp
        hasError || isFocused -> 2.dp
        else -> 1.dp
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
