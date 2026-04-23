package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
import com.poyka.ripdpi.ui.theme.RipDpiTextFieldStateStyle
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val dropdownItemSpacingDp = 8
private const val dropdownWidthFraction = 0.9f

data class RipDpiDropdownOption<T>(
    val value: T,
    val label: String,
)

@Composable
fun <T> RipDpiDropdown(
    options: ImmutableList<RipDpiDropdownOption<T>>,
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
    val components = RipDpiThemeTokens.components
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val isInteractive = enabled && !readOnly
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    val fieldState =
        RipDpiThemeTokens.state.textField.resolve(
            enabled = enabled,
            hasError = errorText != null,
            isFocused = isInteractive && (expanded || isFocused),
            isEmpty = selectedLabel.isEmpty(),
        )
    val horizontalPadding =
        dropdownHorizontalPadding(
            focusedHorizontalPadding = components.inputs.fieldFocusedHorizontalPadding,
            defaultHorizontalPadding = components.inputs.fieldHorizontalPadding,
            density = density,
            borderWidth = fieldState.borderWidth,
        )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(components.inputs.textFieldLabelGap),
    ) {
        label?.let {
            Text(text = it, style = type.smallLabel, color = fieldState.label)
        }
        DropdownField(
            resolvedInteractionSource = resolvedInteractionSource,
            isInteractive = isInteractive,
            expanded = expanded,
            setExpanded = setExpanded,
            selectedLabel = selectedLabel,
            placeholder = placeholder,
            label = label,
            errorText = errorText,
            testTag = testTag,
            fieldState = fieldState,
            horizontalPadding = horizontalPadding,
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
        (errorText ?: helperText)?.let {
            Text(
                text = it,
                style = type.caption,
                color = fieldState.helper,
                modifier = Modifier.alpha(fieldState.alpha),
            )
        }
    }
}

@Composable
private fun DropdownField(
    resolvedInteractionSource: MutableInteractionSource,
    isInteractive: Boolean,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    selectedLabel: String,
    placeholder: String?,
    label: String?,
    errorText: String?,
    testTag: String?,
    fieldState: RipDpiTextFieldStateStyle,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    dropdownContent: @Composable () -> Unit,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val animatedBorderWidth by animateDpAsState(
        targetValue = fieldState.borderWidth,
        animationSpec = motion.quickTween(),
        label = "dropdownBorderWidth",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = fieldState.border,
        animationSpec = motion.stateTween(),
        label = "dropdownBorderColor",
    )
    val animatedChevronRotation by animateFloatAsState(
        targetValue = if (expanded) 270f else 90f,
        animationSpec = motion.quickTween(),
        label = "dropdownChevronRotation",
    )
    Box {
        Row(
            modifier =
                Modifier
                    .ripDpiTestTag(testTag)
                    .fillMaxWidth()
                    .height(components.inputs.controlHeight)
                    .background(fieldState.container, RipDpiThemeTokens.shapes.xl)
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
                    .alpha(fieldState.alpha),
            horizontalArrangement = Arrangement.spacedBy(dropdownItemSpacingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel.ifEmpty { placeholder.orEmpty() },
                modifier = Modifier.weight(1f),
                style = RipDpiThemeTokens.type.monoValue,
                color = if (selectedLabel.isEmpty()) fieldState.placeholder else fieldState.content,
            )
            Icon(
                imageVector = RipDpiIcons.ChevronRight,
                contentDescription = null,
                tint = fieldState.helper,
                modifier = Modifier.graphicsLayer { rotationZ = animatedChevronRotation },
            )
        }
        dropdownContent()
    }
}

@Composable
private fun <T> DropdownOptionsMenu(
    options: ImmutableList<RipDpiDropdownOption<T>>,
    expanded: Boolean,
    isInteractive: Boolean,
    onValueSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    optionTagForValue: ((T) -> String)?,
) {
    val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiSurfaceRole.DropdownMenu)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .fillMaxWidth(dropdownWidthFraction)
                .background(surfaceStyle.container, RipDpiThemeTokens.shapes.xl)
                .border(
                    width = if (surfaceStyle.border == androidx.compose.ui.graphics.Color.Transparent) 0.dp else 1.dp,
                    color = surfaceStyle.border,
                    shape = RipDpiThemeTokens.shapes.xl,
                ),
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                modifier = Modifier.ripDpiTestTag(optionTagForValue?.invoke(option.value)),
                text = {
                    Text(
                        text = option.label,
                        style = RipDpiThemeTokens.type.body,
                        color = surfaceStyle.content,
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

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun RipDpiDropdownPreview() {
    val options =
        persistentListOf(
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
