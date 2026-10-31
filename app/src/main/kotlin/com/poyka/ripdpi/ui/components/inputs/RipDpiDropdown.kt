package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val DisabledAlpha = 0.38f
private const val dropdownWidthFraction = 0.9f

data class RipDpiDropdownOption<T>(
    val value: T,
    val label: String,
)

@Immutable
data class RipDpiDropdownLabels(
    val label: String? = null,
    val placeholder: String? = null,
    val helperText: String? = null,
    val errorText: String? = null,
    val accessibilityLabel: String? = label,
)

/** Keep the positional legacy API; the labels overload carries the label and supporting text. */
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
    RipDpiDropdown(
        options = options,
        selectedValue = selectedValue,
        onValueSelected = onValueSelected,
        labels = RipDpiDropdownLabels(label, placeholder, helperText, errorText),
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        density = density,
        interactionSource = interactionSource,
        testTag = testTag,
        optionTagForValue = optionTagForValue,
    )
}

@Composable
fun <T> RipDpiDropdown(
    options: ImmutableList<RipDpiDropdownOption<T>>,
    selectedValue: T?,
    onValueSelected: (T) -> Unit,
    labels: RipDpiDropdownLabels,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    interactionSource: MutableInteractionSource? = null,
    testTag: String? = null,
    optionTagForValue: ((T) -> String)? = null,
) {
    val type = RipDpiThemeTokens.type
    val components = RipDpiThemeTokens.components
    val colors = RipDpiThemeTokens.colors
    val isInteractive = enabled && !readOnly
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    val supportingColor = if (labels.errorText != null) colors.destructive else colors.mutedForeground
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(components.inputs.textFieldLabelGap),
    ) {
        labels.label?.let {
            Text(text = it, style = type.smallLabel, color = supportingColor)
        }
        DropdownField(
            enabled = enabled,
            density = density,
            interactionSource = interactionSource,
            isInteractive = isInteractive,
            expanded = expanded,
            setExpanded = setExpanded,
            selectedLabel = selectedLabel,
            placeholder = labels.placeholder,
            label = labels.accessibilityLabel,
            errorText = labels.errorText,
            testTag = testTag,
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
        (labels.errorText ?: labels.helperText)?.let {
            Text(
                text = it,
                style = type.caption,
                color = supportingColor,
                modifier = Modifier.alpha(if (enabled) 1f else DisabledAlpha),
            )
        }
    }
}

/**
 * A Material outlined field shows the selection. It is inert (`enabled = false`) and drawn with the
 * interactive colors, so an overlay owns the click, focus, Enter key and button semantics.
 */
@Composable
private fun DropdownField(
    enabled: Boolean,
    density: RipDpiControlDensity,
    interactionSource: MutableInteractionSource?,
    isInteractive: Boolean,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    selectedLabel: String,
    placeholder: String?,
    label: String?,
    errorText: String?,
    testTag: String?,
    dropdownContent: @Composable () -> Unit,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val colors = RipDpiThemeTokens.colors
    val animatedChevronRotation by animateFloatAsState(
        targetValue = if (expanded) 270f else 90f,
        animationSpec = motion.quickTween(),
        label = "dropdownChevronRotation",
    )
    val shownText = selectedLabel.ifEmpty { placeholder.orEmpty() }
    val textState = remember(shownText) { TextFieldState(shownText) }
    val borderColor =
        when {
            !enabled -> colors.border
            errorText != null -> colors.destructive
            isInteractive && expanded -> colors.foreground
            else -> colors.outlineVariant
        }
    val textColor = if (selectedLabel.isEmpty()) colors.mutedForeground else colors.foreground
    val fieldColors =
        ripDpiOutlinedTextFieldColors(
            disabledText = if (enabled) textColor else textColor.copy(alpha = DisabledAlpha),
            disabledBorder = borderColor,
            disabledTrailingIcon = colors.mutedForeground.copy(alpha = if (enabled) 1f else DisabledAlpha),
        )
    val horizontalPadding = if (density == RipDpiControlDensity.Compact) 12.dp else 16.dp
    Box {
        OutlinedTextField(
            state = textState,
            // Options are labels such as "Light" or "TCP", not technical values.
            textStyle = RipDpiThemeTokens.type.secondaryBody,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = components.inputs.controlHeight),
            enabled = false,
            readOnly = true,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 2),
            trailingIcon = {
                Icon(
                    imageVector = RipDpiIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer { rotationZ = animatedChevronRotation },
                )
            },
            shape = RipDpiThemeTokens.shapes.xl,
            colors = fieldColors,
            contentPadding =
                OutlinedTextFieldDefaults.contentPaddingWithoutLabel(
                    start = horizontalPadding,
                    end = horizontalPadding,
                ),
        )
        // A sibling on top of the inert field: the field would otherwise swallow the touch.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .ripDpiTestTag(testTag)
                    .semantics {
                        label?.let { contentDescription = it }
                        errorText?.let { error(it) }
                    }.ripDpiClickable(
                        enabled = isInteractive,
                        role = Role.Button,
                        interactionSource = interactionSource,
                    ) { setExpanded(true) },
        )
        dropdownContent()
    }
}

/** Material owns the popup surface and item behavior; the RDS dropdown roles map into its colors and shape. */
@Composable
private fun <T> DropdownOptionsMenu(
    options: ImmutableList<RipDpiDropdownOption<T>>,
    expanded: Boolean,
    isInteractive: Boolean,
    onValueSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    optionTagForValue: ((T) -> String)?,
) {
    val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.inputs.dropdownMenu)
    val menuBorder =
        surfaceStyle.border.takeUnless { it == Color.Transparent }?.let { BorderStroke(RipDpiStroke.Thin, it) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(dropdownWidthFraction),
        shape = RipDpiThemeTokens.shapes.xl,
        containerColor = surfaceStyle.container,
        border = menuBorder,
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                modifier = Modifier.ripDpiTestTag(optionTagForValue?.invoke(option.value)),
                text = { Text(text = option.label, style = RipDpiThemeTokens.type.body) },
                colors = MenuDefaults.itemColors(textColor = surfaceStyle.content),
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
