package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val chipContentSpacingDp = 6
private const val DisabledAlpha = 0.38f

/**
 * Chip on the Material 3 selectable [Surface]: Material owns the state layer, ripple and selection semantics, and
 * the RDS layout (padding, icon, wrapping) stays because the filter chip insets are fixed. Selected chips use the
 * RDS ink fill. The default [Role.Checkbox] chip is toggleable; any other [role] reports selection only.
 */
@Composable
fun RipDpiChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    leadingIcon: ImageVector? = if (selected) RipDpiIcons.Check else null,
    role: Role = Role.Checkbox,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Selection,
    wrapLabel: Boolean = false,
) {
    val components = RipDpiThemeTokens.components
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val performHaptic = rememberRipDpiHapticPerformer()
    val compact = density == RipDpiControlDensity.Compact
    val inputs = components.inputs
    val horizontal = inputs.chipHorizontalPadding - if (compact) inputs.chipFocusedHorizontalPaddingOffset else 0.dp
    val vertical = inputs.chipVerticalPadding - if (compact) inputs.chipFocusedVerticalPaddingOffset else 0.dp
    val container = if (selected) colors.foreground else Color.Transparent
    val content =
        when {
            selected -> colors.background
            enabled -> colors.foreground
            else -> colors.mutedForeground
        }
    val borderColor =
        when {
            selected -> colors.foreground
            enabled -> colors.outlineVariant
            else -> colors.border
        }
    val animatedContainer by animateColorAsState(container, motion.stateTween(), label = "chipContainer")
    val animatedContent by animateColorAsState(content, motion.stateTween(), label = "chipContent")
    val surfaceModifier =
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .semantics {
                contentDescription = text
                this.role = role
            }
    val shape = RoundedCornerShape(components.shapes.largeCornerRadius)
    val border = BorderStroke(RipDpiStroke.Thin, borderColor)
    val chipContent: @Composable () -> Unit = {
        RipDpiChipContent(text, leadingIcon, Modifier.padding(horizontal = horizontal, vertical = vertical), wrapLabel)
    }
    if (role == Role.Checkbox) {
        Surface(
            checked = selected,
            onCheckedChange = {
                performHaptic(hapticFeedback)
                onClick()
            },
            modifier = surfaceModifier,
            enabled = enabled,
            shape = shape,
            color = animatedContainer,
            contentColor = animatedContent,
            border = border,
            content = chipContent,
        )
    } else {
        Surface(
            selected = selected,
            onClick = {
                performHaptic(hapticFeedback)
                onClick()
            },
            modifier = surfaceModifier,
            enabled = enabled,
            shape = shape,
            color = animatedContainer,
            contentColor = animatedContent,
            border = border,
            content = chipContent,
        )
    }
}

@Composable
private fun RipDpiChipContent(
    text: String,
    leadingIcon: ImageVector?,
    modifier: Modifier,
    wrapLabel: Boolean,
) {
    val motion = RipDpiThemeTokens.motion
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(chipContentSpacingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = leadingIcon != null,
            enter = motion.quickContentTransform(initialScale = 0.8f, targetScale = 0.8f).targetContentEnter,
            exit = motion.quickContentTransform(initialScale = 0.8f, targetScale = 0.8f).initialContentExit,
        ) {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(RipDpiThemeTokens.components.inputs.chipIconSize),
                )
            }
        }
        Text(
            text = text,
            modifier = (if (wrapLabel) Modifier.weight(1f, fill = false) else Modifier).width(IntrinsicSize.Max),
            style = RipDpiThemeTokens.type.bodyEmphasis,
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
