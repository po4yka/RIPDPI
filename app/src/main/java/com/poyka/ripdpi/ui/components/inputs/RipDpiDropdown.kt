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
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val type = RipDpiThemeTokens.type
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val isInteractive = enabled && !readOnly
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    val borderWidth =
        when {
            errorText != null -> 2.dp
            (expanded || isFocused) && isInteractive -> 2.dp
            else -> 1.dp
        }
    val borderColor =
        when {
            errorText != null -> colors.destructive
            (expanded || isFocused) && isInteractive -> colors.foreground
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    val supportingText = errorText ?: helperText
    val supportingColor = if (errorText != null) colors.destructive else colors.mutedForeground
    val labelColor = if (errorText != null) colors.destructive else colors.mutedForeground
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
    val horizontalPadding =
        when (density) {
            RipDpiControlDensity.Default -> {
                if (animatedBorderWidth > 1.dp) {
                    components.fieldFocusedHorizontalPadding
                } else {
                    components.fieldHorizontalPadding
                }
            }

            RipDpiControlDensity.Compact -> {
                if (animatedBorderWidth > 1.dp) {
                    components.fieldFocusedHorizontalPadding - 4.dp
                } else {
                    components.fieldHorizontalPadding - 4.dp
                }
            }
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        label?.let {
            Text(text = it, style = type.smallLabel, color = labelColor)
        }
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

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { setExpanded(false) },
                modifier =
                    Modifier
                        .fillMaxWidth(0.9f)
                        .background(MaterialTheme.colorScheme.surface, RipDpiThemeTokens.shapes.xl),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
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
                                setExpanded(false)
                            }
                        },
                    )
                }
            }
        }
        supportingText?.let {
            Text(
                text = it,
                style = type.caption,
                color = supportingColor,
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
            )
        }
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
