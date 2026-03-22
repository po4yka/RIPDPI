package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiDropdownOption<T>(
    val value: T,
    val label: String,
)

@Composable
fun <T> RipDpiDropdown(
    options: List<RipDpiDropdownOption<T>>,
    selectedValue: T?,
    onValueSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    interactionSource: MutableInteractionSource? = null,
    testTag: String? = null,
    optionTagForValue: ((T) -> String)? = null,
) {
    val type = RipDpiThemeTokens.type
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val isInteractive = enabled && !readOnly
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val visualState =
        rememberDropdownVisualState(
            selectedValue = selectedValue,
            options = options,
            errorText = errorText,
            helperText = helperText,
            density = density,
            expanded = expanded,
            isFocused = isFocused,
            isInteractive = isInteractive,
        )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        label?.let {
            Text(text = it, style = type.smallLabel, color = visualState.labelColor)
        }
        DropdownField(
            resolvedInteractionSource = resolvedInteractionSource,
            enabled = enabled,
            isInteractive = isInteractive,
            expanded = expanded,
            setExpanded = setExpanded,
            selectedLabel = visualState.selectedLabel,
            placeholder = placeholder,
            label = label,
            errorText = errorText,
            testTag = testTag,
            borderWidth = visualState.borderWidth,
            borderColor = visualState.borderColor,
            horizontalPadding = visualState.horizontalPadding,
        ) {
            DropdownOptionsMenu(
                options = options,
                expanded = expanded,
                isInteractive = isInteractive,
                onValueSelected = onValueSelected,
                onDismiss = { setExpanded(false) },
                optionTagForValue = optionTagForValue,
            )
        }
        visualState.supportingText?.let {
            Text(
                text = it,
                style = type.caption,
                color = visualState.supportingColor,
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
            )
        }
    }
}

@Composable
private fun DropdownField(
    resolvedInteractionSource: MutableInteractionSource,
    enabled: Boolean,
    isInteractive: Boolean,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    selectedLabel: String,
    placeholder: String?,
    label: String?,
    errorText: String?,
    testTag: String?,
    borderWidth: androidx.compose.ui.unit.Dp,
    borderColor: androidx.compose.ui.graphics.Color,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    dropdownContent: @Composable () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val animatedBorderWidth by animateDpAsState(
        targetValue = borderWidth,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "dropdownBorderWidth",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "dropdownBorderColor",
    )
    val animatedChevronRotation by animateFloatAsState(
        targetValue = if (expanded) 270f else 90f,
        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
        label = "dropdownChevronRotation",
    )
    Box {
        Row(
            modifier =
                Modifier
                    .ripDpiTestTag(testTag)
                    .fillMaxWidth()
                    .height(components.controlHeight)
                    .background(colors.inputBackground, RipDpiThemeTokens.shapes.xl)
                    .border(animatedBorderWidth, animatedBorderColor, RipDpiThemeTokens.shapes.xl)
                    .focusable(enabled = isInteractive, interactionSource = resolvedInteractionSource)
                    .semantics {
                        label?.let { contentDescription = it }
                        errorText?.let { error(it) }
                    }.ripDpiClickable(
                        enabled = isInteractive,
                        role = androidx.compose.ui.semantics.Role.Button,
                        interactionSource = resolvedInteractionSource,
                    ) { setExpanded(true) }
                    .padding(horizontal = horizontalPadding)
                    .alpha(if (enabled) 1f else 0.38f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel.ifEmpty { placeholder.orEmpty() },
                modifier = Modifier.weight(1f),
                style = RipDpiThemeTokens.type.monoValue,
                color = if (selectedLabel.isEmpty()) colors.mutedForeground else colors.foreground,
            )
            Icon(
                imageVector = RipDpiIcons.ChevronRight,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.graphicsLayer { rotationZ = animatedChevronRotation },
            )
        }
        dropdownContent()
    }
}

@Composable
private fun <T> DropdownOptionsMenu(
    options: List<RipDpiDropdownOption<T>>,
    expanded: Boolean,
    isInteractive: Boolean,
    onValueSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    optionTagForValue: ((T) -> String)?,
) {
    val colors = RipDpiThemeTokens.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .fillMaxWidth(0.9f)
                .background(MaterialTheme.colorScheme.surface, RipDpiThemeTokens.shapes.xl),
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                modifier = Modifier.ripDpiTestTag(optionTagForValue?.invoke(option.value)),
                text = {
                    Text(
                        text = option.label,
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                },
                onClick = {
                    if (isInteractive) {
                        onValueSelected(option.value)
                        onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun <T> rememberDropdownVisualState(
    selectedValue: T?,
    options: List<RipDpiDropdownOption<T>>,
    errorText: String?,
    helperText: String?,
    density: RipDpiControlDensity,
    expanded: Boolean,
    isFocused: Boolean,
    isInteractive: Boolean,
): DropdownVisualState {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val borderWidth =
        when {
            errorText != null -> 2.dp
            (expanded || isFocused) && isInteractive -> 2.dp
            else -> 1.dp
        }
    return DropdownVisualState(
        selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty(),
        borderWidth = borderWidth,
        borderColor =
            when {
                errorText != null -> colors.destructive
                (expanded || isFocused) && isInteractive -> colors.foreground
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        supportingText = errorText ?: helperText,
        supportingColor = if (errorText != null) colors.destructive else colors.mutedForeground,
        labelColor = if (errorText != null) colors.destructive else colors.mutedForeground,
        horizontalPadding =
            dropdownHorizontalPadding(
                focusedHorizontalPadding = components.fieldFocusedHorizontalPadding,
                defaultHorizontalPadding = components.fieldHorizontalPadding,
                density = density,
                borderWidth = borderWidth,
            ),
    )
}

private data class DropdownVisualState(
    val selectedLabel: String,
    val borderWidth: androidx.compose.ui.unit.Dp,
    val borderColor: androidx.compose.ui.graphics.Color,
    val supportingText: String?,
    val supportingColor: androidx.compose.ui.graphics.Color,
    val labelColor: androidx.compose.ui.graphics.Color,
    val horizontalPadding: androidx.compose.ui.unit.Dp,
)

private fun dropdownHorizontalPadding(
    focusedHorizontalPadding: androidx.compose.ui.unit.Dp,
    defaultHorizontalPadding: androidx.compose.ui.unit.Dp,
    density: RipDpiControlDensity,
    borderWidth: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp {
    val basePadding =
        if (borderWidth > 1.dp) {
            focusedHorizontalPadding
        } else {
            defaultHorizontalPadding
        }
    return when (density) {
        RipDpiControlDensity.Default -> basePadding
        RipDpiControlDensity.Compact -> basePadding - 4.dp
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDropdownPreview() {
    val options =
        listOf(
            RipDpiDropdownOption("auto", "Auto"),
            RipDpiDropdownOption("fake", "desync (fake)"),
            RipDpiDropdownOption("proxy", "SOCKS5 proxy"),
        )
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiDropdown(
                options = options,
                selectedValue = "auto",
                onValueSelected = {},
                label = "Mode",
                helperText = "Select how traffic should be handled",
            )
            RipDpiDropdown(
                options = options,
                selectedValue = "fake",
                onValueSelected = {},
                label = "Selected",
            )
            RipDpiDropdown(
                options = options,
                selectedValue = "proxy",
                onValueSelected = {},
                label = "Disabled",
                enabled = false,
            )
        }
    }
}
