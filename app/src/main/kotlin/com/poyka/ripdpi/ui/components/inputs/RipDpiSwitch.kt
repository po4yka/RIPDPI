package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiSurfaceStyle

private const val luminanceMidpoint = 0.5f

private const val switchTrackDarkBlendFraction = 0.25f
private const val switchTrackLightBlendFraction = 0.16f
private const val switchThumbDarkBlendFraction = 0.5f

private const val PressedCheckedTrackBlend = 0.18f
private const val PressedDarkTrackBlend = 0.32f
private const val PressedLightTrackBlend = 0.22f

@Composable
fun RipDpiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    label: String? = null,
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
                enabled = enabled,
                readOnly = readOnly,
                interactionSource = interactionSource,
                testTag = testTag,
                modifier =
                    Modifier.semantics {
                        label?.let { contentDescription = it }
                        stateDescription = if (checked) "On" else "Off"
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
    enabled: Boolean,
    readOnly: Boolean,
    interactionSource: MutableInteractionSource?,
    testTag: String?,
) {
    val components = RipDpiThemeTokens.components
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by resolvedInteractionSource.collectIsPressedAsState()
    val visualState = rememberSwitchVisualState(checked = checked, enabled = enabled, isPressed = isPressed)
    val interactive = enabled && !readOnly && onCheckedChange != null
    val thumbTravel =
        components.switchWidth -
            components.switchThumbSize -
            (components.switchThumbPadding * 2)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) thumbTravel else 0.dp,
        label = "switchOffset",
    )
    val alpha by animateFloatAsState(targetValue = visualState.alpha, label = "switchAlpha")

    SwitchLayout(
        modifier = modifier,
        testTag = testTag,
        checked = checked,
        interactive = interactive,
        resolvedInteractionSource = resolvedInteractionSource,
        onCheckedChange = onCheckedChange,
        dimensions =
            SwitchDimensions(
                width = components.switchWidth,
                height = components.switchHeight,
                trackHeight = components.switchTrackHeight,
                thumbPadding = components.switchThumbPadding,
                thumbSize = components.switchThumbSize,
            ),
        trackColor = visualState.trackColor,
        thumbColor = visualState.thumbColor,
        thumbOffset = thumbOffset,
        alpha = alpha,
    )
}

@Composable
private fun rememberSwitchVisualState(
    checked: Boolean,
    enabled: Boolean,
    isPressed: Boolean,
): SwitchVisualState {
    val colors = RipDpiThemeTokens.colors
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < luminanceMidpoint
    val trackColor by animateColorAsState(
        targetValue =
            switchTrackColor(
                backgroundColor = colors.background,
                foregroundColor = colors.foreground,
                onSurfaceVariant = scheme.onSurfaceVariant,
                checked = checked,
                isPressed = isPressed,
                isDark = isDark,
            ),
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue =
            switchThumbColor(
                backgroundColor = colors.background,
                foregroundColor = colors.foreground,
                checked = checked,
                isDark = isDark,
            ),
        label = "switchThumb",
    )
    return SwitchVisualState(
        trackColor = trackColor,
        thumbColor = thumbColor,
        alpha = if (enabled) 1f else 0.38f,
    )
}

@Composable
private fun SwitchLayout(
    modifier: Modifier,
    testTag: String?,
    checked: Boolean,
    interactive: Boolean,
    resolvedInteractionSource: MutableInteractionSource,
    onCheckedChange: ((Boolean) -> Unit)?,
    dimensions: SwitchDimensions,
    trackColor: Color,
    thumbColor: Color,
    thumbOffset: androidx.compose.ui.unit.Dp,
    alpha: Float,
) {
    Box(
        modifier =
            modifier
                .ripDpiTestTag(testTag)
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
                    .background(trackColor.copy(alpha = alpha), CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier =
                    Modifier
                        .offset(x = dimensions.thumbPadding + thumbOffset)
                        .size(dimensions.thumbSize)
                        .shadow(
                            elevation = ripDpiSurfaceStyle(RipDpiSurfaceRole.SwitchThumb).shadowElevation,
                            shape = CircleShape,
                            clip = false,
                        ).background(thumbColor.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

private data class SwitchVisualState(
    val trackColor: Color,
    val thumbColor: Color,
    val alpha: Float,
)

private data class SwitchDimensions(
    val width: androidx.compose.ui.unit.Dp,
    val height: androidx.compose.ui.unit.Dp,
    val trackHeight: androidx.compose.ui.unit.Dp,
    val thumbPadding: androidx.compose.ui.unit.Dp,
    val thumbSize: androidx.compose.ui.unit.Dp,
)

private fun switchTrackColor(
    backgroundColor: Color,
    foregroundColor: Color,
    onSurfaceVariant: Color,
    checked: Boolean,
    isPressed: Boolean,
    isDark: Boolean,
): Color =
    when {
        isPressed && checked -> {
            lerp(foregroundColor, onSurfaceVariant, PressedCheckedTrackBlend)
        }

        isPressed -> {
            lerp(
                backgroundColor,
                foregroundColor,
                if (isDark) PressedDarkTrackBlend else PressedLightTrackBlend,
            )
        }

        checked -> {
            foregroundColor
        }

        isDark -> {
            lerp(backgroundColor, foregroundColor, switchTrackDarkBlendFraction)
        }

        else -> {
            lerp(backgroundColor, foregroundColor, switchTrackLightBlendFraction)
        }
    }

private fun switchThumbColor(
    backgroundColor: Color,
    foregroundColor: Color,
    checked: Boolean,
    isDark: Boolean,
): Color =
    when {
        checked && isDark -> backgroundColor
        checked -> backgroundColor
        isDark -> lerp(backgroundColor, foregroundColor, switchThumbDarkBlendFraction)
        else -> backgroundColor
    }

@Suppress("UnusedPrivateMember")
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

@Suppress("UnusedPrivateMember")
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
