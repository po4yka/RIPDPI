@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.StyleResolver
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

@Composable
@NonRestartableComposable
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
    RipDpiSwitch(
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
        visualStyle = CommonStyle,
    )
}

/** [visualStyle] customizes the track; the thumb keeps its own RDS fill and shadow. */
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
    visualStyle: CommonStyle = CommonStyle,
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
            visualStyle = visualStyle,
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
        visualStyle = visualStyle,
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
    visualStyle: CommonStyle,
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
                visualStyle = visualStyle,
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
    visualStyle: CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val styleState =
        rememberUpdatedStyleState(resolvedInteractionSource) {
            it.isEnabled = enabled
            it.isChecked = checked
        }
    val trackResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.switchTrack, visualStyle, styleState)
    val thumbResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.switchThumb, CommonStyle, styleState)
    val interactive = enabled && !readOnly && onCheckedChange != null
    val thumbTravel =
        components.inputs.switchWidth -
            components.inputs.switchThumbSize -
            (components.inputs.switchThumbPadding * 2)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) thumbTravel else 0.dp,
        animationSpec = motion.stateTween(),
        label = "switchOffset",
    )
    SwitchLayout(
        modifier = modifier,
        accessibilityLabel = accessibilityLabel,
        testTag = testTag,
        checked = checked,
        interactive = interactive,
        resolvedInteractionSource = resolvedInteractionSource,
        onCheckedChange = onCheckedChange,
        dimensions =
            SwitchDimensions(
                width = components.inputs.switchWidth,
                height = components.inputs.switchHeight,
                trackHeight = components.inputs.switchTrackHeight,
                thumbPadding = components.inputs.switchThumbPadding,
                thumbSize = components.inputs.switchThumbSize,
            ),
        trackResolver = trackResolver,
        thumbResolver = thumbResolver,
        thumbOffset = thumbOffset,
    )
}

@Composable
private fun SwitchLayout(
    modifier: Modifier,
    accessibilityLabel: String?,
    testTag: String?,
    checked: Boolean,
    interactive: Boolean,
    resolvedInteractionSource: MutableInteractionSource,
    onCheckedChange: ((Boolean) -> Unit)?,
    dimensions: SwitchDimensions,
    trackResolver: StyleResolver,
    thumbResolver: StyleResolver,
    thumbOffset: androidx.compose.ui.unit.Dp,
) {
    val thumbShadowElevation =
        RipDpiThemeTokens.surfaces
            .resolve(RipDpiThemeTokens.surfaceRoles.inputs.switchThumb)
            .shadowElevation

    Box(
        modifier =
            modifier
                .semantics {
                    accessibilityLabel?.let { contentDescription = it }
                }.ripDpiTestTag(testTag)
                .size(width = dimensions.width, height = dimensions.height)
                .then(
                    if (interactive) {
                        Modifier.ripDpiToggleable(
                            value = checked,
                            enabled = true,
                            role = Role.Switch,
                            interactionSource = resolvedInteractionSource,
                            onValueChange = { value -> onCheckedChange?.invoke(value) },
                        )
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = dimensions.width, height = dimensions.trackHeight)
                    .ripDpiStyleSurface(trackResolver, shape = CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier =
                    Modifier
                        .offset(x = dimensions.thumbPadding + thumbOffset)
                        .size(dimensions.thumbSize)
                        .shadow(
                            elevation = thumbShadowElevation,
                            shape = CircleShape,
                            clip = false,
                        ).ripDpiStyleSurface(thumbResolver, shape = CircleShape),
            )
        }
    }
}

private data class SwitchDimensions(
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp,
    val trackHeight: androidx.compose.ui.unit.Dp,
    val thumbPadding: androidx.compose.ui.unit.Dp,
    val thumbSize: androidx.compose.ui.unit.Dp,
)

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
