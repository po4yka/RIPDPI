package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    leadingIcon: ImageVector? = if (selected) RipDpiIcons.Check else null,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val horizontalPadding =
        when (density) {
            RipDpiControlDensity.Default -> components.chipHorizontalPadding
            RipDpiControlDensity.Compact -> components.chipHorizontalPadding - 4.dp
        }
    val verticalPadding =
        when (density) {
            RipDpiControlDensity.Default -> components.chipVerticalPadding
            RipDpiControlDensity.Compact -> components.chipVerticalPadding - 2.dp
        }
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
    val animatedContainer by animateColorAsState(
        targetValue = container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "chipContainer",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "chipBorder",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "chipContent",
    )
    val scale by animateFloatAsState(
        targetValue =
            when {
                isPressed && enabled -> motion.pressScale
                selected -> motion.selectionScale
                else -> 1f
            },
        animationSpec = tween(
            durationMillis = motion.duration(motion.quickDurationMillis),
            easing = FastOutSlowInEasing,
        ),
        label = "chipScale",
    )

    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(animatedContainer, RipDpiThemeTokens.shapes.lg)
                .border(1.dp, animatedBorderColor, RipDpiThemeTokens.shapes.lg)
                .focusable(enabled = enabled, interactionSource = interactionSource)
                .ripDpiSelectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    interactionSource = interactionSource,
                    onClick = onClick,
                )
                .padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                )
                .alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = leadingIcon != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = animatedContentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = text,
            style = if (selected) RipDpiThemeTokens.type.bodyEmphasis else RipDpiThemeTokens.type.secondaryBody,
            color = animatedContentColor,
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
