package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val trackColor by animateColorAsState(
        targetValue = when {
            checked && isDark -> colors.foreground
            checked -> colors.foreground
            isDark -> lerp(colors.background, colors.foreground, 0.25f)
            else -> lerp(colors.background, colors.foreground, 0.16f)
        },
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            checked && isDark -> colors.background
            checked -> Color.White
            isDark -> lerp(colors.background, colors.foreground, 0.5f)
            else -> Color.White
        },
        label = "switchThumb",
    )
    val thumbOffset by animateDpAsState(targetValue = if (checked) 20.dp else 0.dp, label = "switchOffset")
    val alpha by animateFloatAsState(targetValue = if (enabled) 1f else 0.38f, label = "switchAlpha")

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .background(trackColor.copy(alpha = alpha), CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .offset(x = thumbOffset)
                .size(20.dp)
                .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                .background(thumbColor.copy(alpha = alpha), CircleShape),
        )
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
