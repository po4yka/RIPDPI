package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val chipContentSpacingDp = 6

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun RipDpiChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    density: RipDpiControlDensity = RipDpiControlDensity.Default,
    leadingIcon: ImageVector? = if (selected) RipDpiIcons.Check else null,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Selection,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val state =
        RipDpiThemeTokens.state.chip.resolve(
            selected = selected,
            enabled = enabled,
            isPressed = isPressed,
        )
    val chipCornerRadius by animateDpAsState(
        targetValue = state.cornerRadius,
        animationSpec = motion.motionAwareSpring(),
        label = "chipCorner",
    )
    val chipShape = RoundedCornerShape(chipCornerRadius)
    val horizontalPadding =
        when (density) {
            RipDpiControlDensity.Default -> {
                components.chipHorizontalPadding
            }

            RipDpiControlDensity.Compact -> {
                components.chipHorizontalPadding -
                    components.chipFocusedHorizontalPaddingOffset
            }
        }
    val verticalPadding =
        when (density) {
            RipDpiControlDensity.Default -> components.chipVerticalPadding
            RipDpiControlDensity.Compact -> components.chipVerticalPadding - components.chipFocusedVerticalPaddingOffset
        }
    val animatedContainer by animateColorAsState(
        targetValue = state.container,
        animationSpec = motion.stateTween(),
        label = "chipContainer",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = state.border,
        animationSpec = motion.stateTween(),
        label = "chipBorder",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = state.content,
        animationSpec = motion.stateTween(),
        label = "chipContent",
    )
    val scale by animateFloatAsState(
        targetValue = state.scale,
        animationSpec = motion.motionAwareSpring(expressive = selected),
        label = "chipScale",
    )
    val alpha by animateFloatAsState(
        targetValue = state.alpha,
        animationSpec = motion.quickTween(),
        label = "chipAlpha",
    )

    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.background(animatedContainer, chipShape)
                .border(1.dp, animatedBorderColor, chipShape)
                .focusable(enabled = enabled, interactionSource = interactionSource)
                .ripDpiSelectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    interactionSource = interactionSource,
                    hapticFeedback = hapticFeedback,
                    onClick = onClick,
                ).padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                ).alpha(alpha),
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
                    tint = animatedContentColor,
                    modifier = Modifier.size(components.chipIconSize),
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

@Suppress("UnusedPrivateMember")
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

@Suppress("UnusedPrivateMember")
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
