package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.formatNumericRange
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal enum class SummaryCapsuleTone {
    Neutral,
    Active,
    Info,
    Warning,
}

@Composable
internal fun rememberSettingsOptions(
    labelArrayRes: Int,
    valueArrayRes: Int,
): List<RipDpiDropdownOption<String>> {
    val labels = stringArrayResource(labelArrayRes)
    val values = stringArrayResource(valueArrayRes)

    return remember(labels, values) {
        labels.zip(values) { label, value ->
            RipDpiDropdownOption(value = value, label = label)
        }
    }
}

@Composable
internal fun AdvancedDropdownSetting(
    title: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    setting: AdvancedOptionSetting,
    onSelected: (AdvancedOptionSetting, String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = title,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.advancedTitle(setting.name)),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        description?.let {
            Text(
                text = it,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.advancedDescription(setting.name)),
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = { selectedValue -> onSelected(setting, selectedValue) },
            enabled = enabled,
            testTag = RipDpiTestTags.advancedOption(setting),
            optionTagForValue = { selectedValue ->
                RipDpiTestTags.dropdownOption(
                    RipDpiTestTags.advancedOption(setting),
                    selectedValue,
                )
            },
        )
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
internal fun AdvancedTextSetting(
    title: String,
    value: String,
    setting: AdvancedTextSetting,
    onConfirm: (AdvancedTextSetting, String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    multiline: Boolean = false,
    validator: (String) -> Boolean = { true },
    invalidMessage: String? = null,
    disabledMessage: String? = null,
    keyboardOptions: KeyboardOptions =
        KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
    showDivider: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var input by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedInput = input.trim()
    val isValid = remember(normalizedInput) { validator(normalizedInput) }
    val isDirty = normalizedInput != value
    val errorText = if (normalizedInput.isNotEmpty() && !isValid) invalidMessage else null
    val helperText =
        if (errorText == null && !enabled) {
            disabledMessage
        } else {
            null
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        description?.let {
            Text(
                text = it,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        RipDpiConfigTextField(
            value = input,
            onValueChange = { input = it },
            decoration =
                RipDpiTextFieldDecoration(
                    placeholder = placeholder,
                    helperText = helperText,
                    errorText = errorText,
                    testTag = RipDpiTestTags.advancedInput(setting),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    enabled = enabled,
                    keyboardOptions = keyboardOptions,
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (enabled && isDirty && isValid) {
                                    onConfirm(setting, normalizedInput)
                                }
                            },
                        ),
                ),
            multiline = multiline,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = { onConfirm(setting, normalizedInput) },
                enabled = enabled && isDirty && isValid,
                variant = RipDpiButtonVariant.Outline,
                trailingIcon = RipDpiIcons.Check,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.advancedSave(setting)),
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
internal fun ActivationRangeEditorCard(
    title: String,
    description: String,
    currentRange: NumericRangeModel,
    emptySummary: String,
    effectSummary: String,
    enabled: Boolean,
    minValue: Long,
    onSave: (Long?, Long?) -> Unit,
    dimension: ActivationWindowDimension,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var startInput by rememberSaveable(currentRange.start, currentRange.end) {
        mutableStateOf(currentRange.start?.toString().orEmpty())
    }
    var endInput by rememberSaveable(currentRange.start, currentRange.end) {
        mutableStateOf(currentRange.end?.toString().orEmpty())
    }
    val currentStart = currentRange.start?.toString().orEmpty()
    val currentEnd = currentRange.end?.toString().orEmpty()
    val isDirty = startInput != currentStart || endInput != currentEnd
    val startValid = isActivationBoundaryValid(startInput, minValue)
    val endValid = isActivationBoundaryValid(endInput, minValue)
    val isValid = startValid && endValid
    val currentSummary =
        formatNumericRange(currentRange)
            ?: emptySummary
    val statusLabel = activationRangeStatusLabel(currentRange.isEmpty)

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = if (currentRange.isEmpty) StatusIndicatorTone.Idle else StatusIndicatorTone.Active,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_range_summary_label),
                value = currentSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.activation_window_range_effect_label),
                value = effectSummary,
            )
        }
        ActivationRangeInputs(
            dimension = dimension,
            enabled = enabled,
            minValue = minValue,
            startInput = startInput,
            endInput = endInput,
            onStartChange = { startInput = it },
            onEndChange = { endInput = it },
        )
        ActivationRangeSaveAction(
            dimension = dimension,
            enabled = enabled && isDirty && isValid,
            startInput = startInput,
            endInput = endInput,
            onSave = onSave,
        )
    }
}

@Composable
private fun activationRangeStatusLabel(isEmpty: Boolean): String =
    if (isEmpty) {
        stringResource(R.string.activation_window_range_status_open)
    } else {
        stringResource(R.string.activation_window_range_status_scoped)
    }

@Composable
private fun ActivationRangeInputs(
    dimension: ActivationWindowDimension,
    enabled: Boolean,
    minValue: Long,
    startInput: String,
    endInput: String,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ActivationBoundaryField(
            title = stringResource(R.string.activation_window_field_from),
            value = startInput,
            enabled = enabled,
            minValue = minValue,
            onValueChange = onStartChange,
            modifier = Modifier.weight(1f),
            testTag = RipDpiTestTags.activationStart(dimension),
        )
        ActivationBoundaryField(
            title = stringResource(R.string.activation_window_field_to),
            value = endInput,
            enabled = enabled,
            minValue = minValue,
            onValueChange = onEndChange,
            modifier = Modifier.weight(1f),
            testTag = RipDpiTestTags.activationEnd(dimension),
        )
    }
}

@Composable
private fun ActivationRangeSaveAction(
    dimension: ActivationWindowDimension,
    enabled: Boolean,
    startInput: String,
    endInput: String,
    onSave: (Long?, Long?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        RipDpiButton(
            text = stringResource(R.string.config_save),
            onClick = { onSave(parseOptionalRangeValue(startInput), parseOptionalRangeValue(endInput)) },
            enabled = enabled,
            variant = RipDpiButtonVariant.Outline,
            trailingIcon = RipDpiIcons.Check,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.activationSave(dimension)),
        )
    }
}

@Composable
internal fun ActivationBoundaryField(
    title: String,
    value: String,
    enabled: Boolean,
    minValue: Long,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val isValid = isActivationBoundaryValid(value, minValue)
    val helperText =
        if (!enabled && isValid) {
            stringResource(R.string.advanced_settings_visual_controls_disabled)
        } else {
            null
        }
    val errorText =
        if (!isValid) {
            stringResource(R.string.config_error_out_of_range)
        } else {
            null
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiConfigTextField(
            value = value,
            onValueChange = onValueChange,
            decoration =
                RipDpiTextFieldDecoration(
                    helperText = helperText,
                    errorText = errorText,
                    testTag = testTag,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    enabled = enabled,
                    density = RipDpiControlDensity.Compact,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                ),
        )
    }
}

@Composable
internal fun ProfileSummaryLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    summaryKey: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = label,
            modifier =
                Modifier.ripDpiTestTag(summaryKey?.let { RipDpiTestTags.advancedSummaryLabel(it) }),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Text(
            text = value,
            modifier =
                Modifier.ripDpiTestTag(summaryKey?.let { RipDpiTestTags.advancedSummaryValue(it) }),
            style = type.secondaryBody,
            color = colors.foreground,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SummaryCapsuleFlow(
    items: List<Pair<String, SummaryCapsuleTone>>,
    modifier: Modifier = Modifier,
    testTagPrefix: String? = null,
) {
    val spacing = RipDpiThemeTokens.spacing

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items.forEach { (text, tone) ->
            SummaryCapsule(
                text = text,
                tone = tone,
                modifier =
                    Modifier.ripDpiTestTag(
                        testTagPrefix?.let { prefix ->
                            RipDpiTestTags.advancedCapsule("$prefix-$text")
                        },
                    ),
            )
        }
    }
}

@Composable
internal fun SummaryCapsule(
    text: String,
    tone: SummaryCapsuleTone,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val (container, content, border) =
        when (tone) {
            SummaryCapsuleTone.Neutral -> {
                Triple(colors.muted, colors.foreground, colors.border)
            }

            SummaryCapsuleTone.Active -> {
                Triple(colors.infoContainer, colors.infoContainerForeground, colors.info)
            }

            SummaryCapsuleTone.Info -> {
                Triple(colors.card, colors.mutedForeground, colors.border)
            }

            SummaryCapsuleTone.Warning -> {
                Triple(colors.warningContainer, colors.warningContainerForeground, colors.warning)
            }
        }

    Text(
        text = text,
        modifier =
            modifier
                .background(container, RipDpiThemeTokens.shapes.lg)
                .border(1.dp, border, RipDpiThemeTokens.shapes.lg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        style = type.caption,
        color = content,
    )
}
