@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.StyleResolver
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.NonRestartableComposable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiTextFieldStateStyle
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleContent
import com.poyka.ripdpi.ui.theme.ripDpiStylePadding
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val dropdownItemSpacingDp = 8
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

/** Keep the positional legacy API; use the labels overload to supply a visual style. */
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
@NonRestartableComposable
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
    RipDpiDropdown(
        options = options,
        selectedValue = selectedValue,
        onValueSelected = onValueSelected,
        labels = labels,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        density = density,
        interactionSource = interactionSource,
        testTag = testTag,
        visualStyle = CommonStyle,
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
    visualStyle: CommonStyle = CommonStyle,
    optionTagForValue: ((T) -> String)? = null,
) {
    val type = RipDpiThemeTokens.type
    val components = RipDpiThemeTokens.components
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isInteractive = enabled && !readOnly
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    val styleState =
        rememberUpdatedStyleState(resolvedInteractionSource) {
            it.isEnabled = enabled
            it[RipDpiStyleStateKeys.HasError] = labels.errorText != null
            it[RipDpiStyleStateKeys.IsEmpty] = selectedLabel.isEmpty()
            it[RipDpiStyleStateKeys.Compact] = density == RipDpiControlDensity.Compact
            it[RipDpiStyleStateKeys.Expanded] = expanded
            it[RipDpiStyleStateKeys.ReadOnly] = readOnly
        }
    val styleResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.dropdown, visualStyle, styleState)
    val fieldState =
        RipDpiThemeTokens.state.textField.resolve(
            enabled = enabled,
            hasError = labels.errorText != null,
            isFocused = isInteractive && (expanded || styleState.isFocused),
            isEmpty = selectedLabel.isEmpty(),
        )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(components.inputs.textFieldLabelGap),
    ) {
        labels.label?.let {
            Text(text = it, style = type.smallLabel, color = fieldState.label)
        }
        DropdownField(
            resolvedInteractionSource = resolvedInteractionSource,
            isInteractive = isInteractive,
            expanded = expanded,
            setExpanded = setExpanded,
            selectedLabel = selectedLabel,
            placeholder = labels.placeholder,
            label = labels.accessibilityLabel,
            errorText = labels.errorText,
            testTag = testTag,
            fieldState = fieldState,
            styleResolver = styleResolver,
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
    styleResolver: StyleResolver,
    dropdownContent: @Composable () -> Unit,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
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
                    .heightIn(min = components.inputs.controlHeight)
                    .ripDpiStyleSurface(styleResolver, shape = RipDpiThemeTokens.shapes.xl)
                    .semantics {
                        label?.let { contentDescription = it }
                        errorText?.let { error(it) }
                    }.ripDpiClickable(
                        enabled = isInteractive,
                        role = androidx.compose.ui.semantics.Role.Button,
                        interactionSource = resolvedInteractionSource,
                    ) { setExpanded(true) }
                    .ripDpiStylePadding(styleResolver)
                    .ripDpiStyleContent(styleResolver),
            horizontalArrangement = Arrangement.spacedBy(dropdownItemSpacingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedLabel.ifEmpty { placeholder.orEmpty() },
                modifier = Modifier.weight(1f),
                // Options are labels such as "Light" or "TCP", not technical values.
                style = RipDpiThemeTokens.type.secondaryBody,
                color = if (selectedLabel.isEmpty()) fieldState.placeholder else fieldState.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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

/** Material owns the popup surface and item behavior; field visualStyle does not style this host. */
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
