package com.poyka.ripdpi.ui.components.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiButtonVariant {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

@Composable
fun RipDpiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RipDpiButtonVariant = RipDpiButtonVariant.Primary,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val base = buttonPalette(variant = variant, enabled = enabled, isPressed = isPressed)
    val borderWidth =
        when {
            !enabled && variant == RipDpiButtonVariant.Ghost -> 0.dp
            isFocused -> 2.dp
            variant == RipDpiButtonVariant.Outline -> 1.dp
            else -> 0.dp
        }
    val borderColor =
        when {
            !enabled && variant == RipDpiButtonVariant.Outline -> colors.border
            isFocused -> MaterialTheme.colorScheme.outline
            variant == RipDpiButtonVariant.Outline -> colors.border
            else -> Color.Transparent
        }

    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = components.buttonMinHeight)
                .clip(shape)
                .background(base.container, shape)
                .border(width = borderWidth, color = borderColor, shape = shape)
                .focusable(enabled = enabled, interactionSource = interactionSource)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    onClick = onClick,
                ).padding(
                    horizontal = components.buttonHorizontalPadding,
                    vertical = components.buttonVerticalPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            RipDpiButtonIcon(icon = it, tint = base.content)
        }
        Text(
            text = text,
            color = base.content,
            style = type.button,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailingIcon?.let {
            RipDpiButtonIcon(icon = it, tint = base.content)
        }
    }
}

@Composable
private fun RipDpiButtonIcon(
    icon: ImageVector,
    tint: Color,
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(RipDpiIconSizes.Default),
    )
}

private data class RipDpiButtonPalette(
    val container: Color,
    val content: Color,
)

@Composable
private fun buttonPalette(
    variant: RipDpiButtonVariant,
    enabled: Boolean,
    isPressed: Boolean,
): RipDpiButtonPalette {
    val colors = RipDpiThemeTokens.colors
    val scheme = MaterialTheme.colorScheme

    if (!enabled) {
        return when (variant) {
            RipDpiButtonVariant.Primary,
            RipDpiButtonVariant.Secondary,
            RipDpiButtonVariant.Destructive,
            -> {
                RipDpiButtonPalette(
                    container = colors.border,
                    content = colors.mutedForeground,
                )
            }

            RipDpiButtonVariant.Outline,
            RipDpiButtonVariant.Ghost,
            -> {
                RipDpiButtonPalette(
                    container = Color.Transparent,
                    content = colors.mutedForeground,
                )
            }
        }
    }

    val pressedOverlay = scheme.onSurfaceVariant
    return when (variant) {
        RipDpiButtonVariant.Primary -> {
            val base = colors.foreground
            RipDpiButtonPalette(
                container = if (isPressed) lerp(base, pressedOverlay, 0.35f) else base,
                content = colors.background,
            )
        }

        RipDpiButtonVariant.Secondary -> {
            val base = scheme.secondary
            RipDpiButtonPalette(
                container = if (isPressed) lerp(base, scheme.surfaceVariant, 0.5f) else base,
                content = scheme.onSecondary,
            )
        }

        RipDpiButtonVariant.Outline -> {
            RipDpiButtonPalette(
                container = if (isPressed) scheme.surfaceVariant else Color.Transparent,
                content = colors.foreground,
            )
        }

        RipDpiButtonVariant.Ghost -> {
            RipDpiButtonPalette(
                container = if (isPressed) scheme.surfaceVariant else Color.Transparent,
                content = colors.foreground,
            )
        }

        RipDpiButtonVariant.Destructive -> {
            RipDpiButtonPalette(
                container = if (isPressed) lerp(colors.destructive, pressedOverlay, 0.3f) else colors.destructive,
                content = colors.destructiveForeground,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiButtonLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiButton(text = "Connect", onClick = {})
            RipDpiButton(text = "Connect", onClick = {}, enabled = false)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RipDpiButton(text = "Secondary", onClick = {}, variant = RipDpiButtonVariant.Secondary)
                RipDpiButton(text = "Outline", onClick = {}, variant = RipDpiButtonVariant.Outline)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RipDpiButton(text = "Ghost", onClick = {}, variant = RipDpiButtonVariant.Ghost)
                RipDpiButton(text = "Reset", onClick = {}, variant = RipDpiButtonVariant.Destructive)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiButtonDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiButton(text = "Connect", onClick = {})
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RipDpiButton(text = "Cancel", onClick = {}, variant = RipDpiButtonVariant.Outline)
                RipDpiButton(text = "Reset", onClick = {}, variant = RipDpiButtonVariant.Destructive)
            }
        }
    }
}
