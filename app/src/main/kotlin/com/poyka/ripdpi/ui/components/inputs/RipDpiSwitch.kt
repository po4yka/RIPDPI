package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/** Material 3 [Switch] with the RDS ink track. Read-only and disabled switches have no toggle action. */
@Composable
fun RipDpiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    label: String? = null,
    accessibilityLabel: String? = label,
    helperText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    testTag: String? = null,
) {
    if (label != null || helperText != null || errorText != null) {
        LabeledRipDpiSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            label = label,
            accessibilityLabel = accessibilityLabel,
            helperText = helperText,
            errorText = errorText,
            enabled = enabled,
            readOnly = readOnly,
            interactionSource = interactionSource,
            testTag = testTag,
        )
        return
    }

    SwitchToggleControl(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        accessibilityLabel = accessibilityLabel,
        enabled = enabled,
        readOnly = readOnly,
        interactionSource = interactionSource,
        testTag = testTag,
    )
}

@Composable
private fun LabeledRipDpiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    label: String?,
    accessibilityLabel: String?,
    helperText: String?,
    errorText: String?,
    enabled: Boolean,
    readOnly: Boolean,
    interactionSource: MutableInteractionSource?,
    testTag: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val labelColor = if (errorText != null) colors.destructive else colors.foreground
    val supportingColor = if (errorText != null) colors.destructive else colors.mutedForeground
    val onLabel = stringResource(R.string.semantic_state_on)
    val offLabel = stringResource(R.string.semantic_state_off)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                label?.let {
                    Text(
                        text = it,
                        style = type.body,
                        color = labelColor,
                    )
                }
                helperText?.takeIf { errorText == null }?.let {
                    Text(
                        text = it,
                        style = type.caption,
                        color = supportingColor,
                    )
                }
            }
            SwitchToggleControl(
                checked = checked,
                onCheckedChange = onCheckedChange,
                accessibilityLabel = accessibilityLabel,
                enabled = enabled,
                readOnly = readOnly,
                interactionSource = interactionSource,
                testTag = testTag,
                modifier =
                    Modifier.semantics {
                        label?.let { contentDescription = it }
                        accessibilityLabel?.let { contentDescription = it }
                        stateDescription = if (checked) onLabel else offLabel
                        errorText?.let { error(it) }
                    },
            )
        }
        errorText?.let {
            Text(
                text = it,
                style = type.caption,
                color = supportingColor,
            )
        }
    }
}

@Composable
private fun SwitchToggleControl(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    accessibilityLabel: String?,
    enabled: Boolean,
    readOnly: Boolean,
    interactionSource: MutableInteractionSource?,
    testTag: String?,
) {
    val performHaptic = rememberRipDpiHapticPerformer()
    val interactive = enabled && !readOnly && onCheckedChange != null
    Switch(
        checked = checked,
        onCheckedChange =
            if (interactive) {
                { value ->
                    performHaptic(RipDpiHapticFeedback.Toggle)
                    onCheckedChange(value)
                }
            } else {
                null
            },
        modifier =
            modifier
                .semantics { accessibilityLabel?.let { contentDescription = it } }
                .ripDpiTestTag(testTag),
        enabled = enabled,
        colors = ripDpiSwitchColors(),
        interactionSource = interactionSource,
    )
}

/** Maps RDS roles into Material switch colors: the checked track is ink with a background thumb. */
@Composable
internal fun ripDpiSwitchColors(): SwitchColors {
    val colors = RipDpiThemeTokens.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.background,
        checkedTrackColor = colors.foreground,
        checkedBorderColor = colors.foreground,
        checkedIconColor = colors.foreground,
    )
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSwitchPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiSwitch(
                checked = true,
                onCheckedChange = {},
                label = "Use system DNS as fallback",
                helperText = "Keeps a safe resolver available when tunnel DNS is unavailable",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Off", color = RipDpiThemeTokens.colors.foreground, style = RipDpiThemeTokens.type.body)
                RipDpiSwitch(checked = false, onCheckedChange = {})
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("On", color = RipDpiThemeTokens.colors.foreground, style = RipDpiThemeTokens.type.body)
                RipDpiSwitch(checked = true, onCheckedChange = {})
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Disabled", color = RipDpiThemeTokens.colors.foreground, style = RipDpiThemeTokens.type.body)
                RipDpiSwitch(checked = false, onCheckedChange = {}, enabled = false)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSwitchDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiSwitch(checked = false, onCheckedChange = {})
            RipDpiSwitch(checked = true, onCheckedChange = {})
            RipDpiSwitch(checked = true, onCheckedChange = {}, enabled = false)
        }
    }
}
