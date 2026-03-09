package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiIconButtonStyle {
    Ghost,
    Tonal,
    Filled,
    Outline,
}

@Composable
fun RipDpiIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RipDpiIconButtonStyle = RipDpiIconButtonStyle.Ghost,
    enabled: Boolean = true,
    selected: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val scheme = MaterialTheme.colorScheme
    val shape = RipDpiThemeTokens.shapes.xl
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by resolvedInteractionSource.collectIsPressedAsState()
    val baseContainer =
        when (style) {
            RipDpiIconButtonStyle.Ghost -> Color.Transparent
            RipDpiIconButtonStyle.Tonal -> if (selected) scheme.surfaceVariant else colors.accent
            RipDpiIconButtonStyle.Filled -> colors.foreground
            RipDpiIconButtonStyle.Outline -> Color.Transparent
        }
    val background =
        when {
            !enabled && style == RipDpiIconButtonStyle.Ghost -> Color.Transparent
            !enabled -> colors.border
            isPressed -> lerp(baseContainer, scheme.onSurfaceVariant, 0.25f)
            else -> baseContainer
        }
    val iconTint =
        when {
            !enabled -> colors.mutedForeground
            style == RipDpiIconButtonStyle.Filled -> colors.background
            else -> colors.foreground
        }
    val borderColor =
        when {
            style == RipDpiIconButtonStyle.Outline && enabled -> colors.border
            style == RipDpiIconButtonStyle.Outline -> colors.border
            else -> Color.Transparent
        }

    Row(
        modifier =
            modifier
                .size(components.iconButtonSize)
                .clip(shape)
                .background(background, shape)
                .border(if (style == RipDpiIconButtonStyle.Outline) 1.dp else 0.dp, borderColor, shape)
                .focusable(enabled = enabled, interactionSource = resolvedInteractionSource)
                .ripDpiClickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = resolvedInteractionSource,
                    onClick = onClick,
                ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiIconButtonPreview() {
    RipDpiComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiIconButton(
                icon = RipDpiIcons.Search,
                contentDescription = "Search",
                onClick = {},
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Overflow,
                contentDescription = "More",
                onClick = {},
                style = RipDpiIconButtonStyle.Tonal,
                selected = true,
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Settings,
                contentDescription = "Settings",
                onClick = {},
                style = RipDpiIconButtonStyle.Outline,
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Close,
                contentDescription = "Close",
                onClick = {},
                style = RipDpiIconButtonStyle.Filled,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiIconButtonDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiIconButton(
                icon = RipDpiIcons.Back,
                contentDescription = "Back",
                onClick = {},
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Warning,
                contentDescription = "Warning",
                onClick = {},
                style = RipDpiIconButtonStyle.Tonal,
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Settings,
                contentDescription = "Settings",
                onClick = {},
                style = RipDpiIconButtonStyle.Outline,
                enabled = false,
            )
        }
    }
}
