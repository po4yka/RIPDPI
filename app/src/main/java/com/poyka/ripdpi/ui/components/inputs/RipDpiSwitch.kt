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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val PressedCheckedTrackBlend = 0.18f
private const val PressedDarkTrackBlend = 0.32f
private const val PressedLightTrackBlend = 0.22f

@Composable
fun RipDpiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDark = scheme.background.luminance() < 0.5f
    val trackColor by animateColorAsState(
        targetValue =
            when {
                isPressed && checked -> {
                    lerp(colors.foreground, scheme.onSurfaceVariant, PressedCheckedTrackBlend)
                }

                isPressed -> {
                    lerp(
                        colors.background,
                        colors.foreground,
                        if (isDark) PressedDarkTrackBlend else PressedLightTrackBlend,
                    )
                }

                checked && isDark -> {
                    colors.foreground
                }

                checked -> {
                    colors.foreground
                }

                isDark -> {
                    lerp(colors.background, colors.foreground, 0.25f)
                }

                else -> {
                    lerp(colors.background, colors.foreground, 0.16f)
                }
            },
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue =
            when {
                checked && isDark -> colors.background
                checked -> Color.White
                isDark -> lerp(colors.background, colors.foreground, 0.5f)
                else -> Color.White
            },
        label = "switchThumb",
    )
    val thumbTravel =
        components.switchWidth -
            components.switchThumbSize -
            (components.switchThumbPadding * 2)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) thumbTravel else 0.dp,
        label = "switchOffset",
    )
    val alpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.38f, label = "switchAlpha")
    val interactive = enabled && onCheckedChange != null

    Box(
        modifier =
            modifier
                .size(width = components.switchWidth, height = components.switchHeight)
                .then(
                    if (interactive) {
                        Modifier.ripDpiToggleable(
                            value = checked,
                            enabled = true,
                            role = Role.Switch,
                            interactionSource = interactionSource,
                            onValueChange = { value -> onCheckedChange(value) },
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
                    .size(width = components.switchWidth, height = components.switchTrackHeight)
                    .background(trackColor.copy(alpha = alpha), CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier =
                    Modifier
                        .offset(x = components.switchThumbPadding + thumbOffset)
                        .size(components.switchThumbSize)
                        .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                        .background(thumbColor.copy(alpha = alpha), CircleShape),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiSwitchPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
