package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    isPressed: Boolean = false,
    leadingIcon: ImageVector? = if (selected) RipDpiIcons.Check else null,
) {
    val colors = RipDpiThemeTokens.colors
    val scheme = MaterialTheme.colorScheme
    val container =
        when {
            selected -> colors.foreground
            isPressed -> scheme.surfaceVariant
            else -> Color.Transparent
        }
    val borderColor =
        when {
            selected -> colors.foreground
            enabled -> MaterialTheme.colorScheme.outlineVariant
            else -> colors.border
        }
    val contentColor =
        when {
            selected -> colors.background
            enabled -> colors.foreground
            else -> colors.mutedForeground
        }

    Row(
        modifier =
            modifier
                .background(container, RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(
                    enabled = enabled,
                    role = Role.Checkbox,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 17.dp, vertical = 6.dp)
                .alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = if (selected) RipDpiThemeTokens.type.bodyEmphasis else RipDpiThemeTokens.type.secondaryBody,
            color = contentColor,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiChipPreview() {
    RipDpiComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiChip(text = "Filter", onClick = {})
            RipDpiChip(text = "Filter", onClick = {}, selected = true)
            RipDpiChip(text = "Filter", onClick = {}, isPressed = true)
            RipDpiChip(text = "Filter", onClick = {}, enabled = false)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiChipDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiChip(text = "Filter", onClick = {})
            RipDpiChip(text = "Filter", onClick = {}, selected = true)
        }
    }
}
