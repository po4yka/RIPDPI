@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleResolver
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiSpringStyle
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleAppearance
import com.poyka.ripdpi.ui.theme.ripDpiStyleDrawing
import com.poyka.ripdpi.ui.theme.ripDpiStylePadding

private const val chipContentSpacingDp = 6

@Composable
@NonRestartableComposable
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
    RipDpiChip(
        text = text,
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        enabled = enabled,
        density = density,
        leadingIcon = leadingIcon,
        role = role,
        hapticFeedback = hapticFeedback,
        wrapLabel = wrapLabel,
        visualStyle = CommonStyle,
    )
}

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
    role: Role = Role.Checkbox,
    hapticFeedback: RipDpiHapticFeedback = RipDpiHapticFeedback.Selection,
    wrapLabel: Boolean = false,
    visualStyle: CommonStyle = CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val motion = RipDpiThemeTokens.motion
    val interactionSource = remember { MutableInteractionSource() }
    val styleState =
        rememberUpdatedStyleState(interactionSource) {
            it.isEnabled = enabled
            it.isSelected = selected
            it.isChecked = selected
            it[RipDpiStyleStateKeys.Compact] = density == RipDpiControlDensity.Compact
        }
    val state =
        RipDpiThemeTokens.state.chip.resolve(
            selected = selected,
            enabled = enabled,
            isPressed = styleState.isPressed,
        )
    val defaults =
        rememberRipDpiSpringStyle(
            defaults = RipDpiThemeTokens.styles.chip,
            scale = state.scale,
            cornerRadius = state.cornerRadius,
            expressiveScale = selected,
        )
    val styleResolver = rememberRipDpiStyleResolver(defaults, visualStyle, styleState)
    val animatedContentColor by animateColorAsState(
        targetValue = state.content,
        animationSpec = motion.stateTween(),
        label = "chipContent",
    )
    val alpha by animateFloatAsState(
        targetValue = state.alpha,
        animationSpec = motion.quickTween(),
        label = "chipAlpha",
    )

    Row(
        modifier =
            modifier
                .styleResolver(styleResolver)
                .ripDpiStyleDrawing(styleResolver)
                .ripDpiStyleAppearance(styleResolver)
                .semantics { contentDescription = text }
                .then(
                    if (role == Role.Checkbox) {
                        Modifier.ripDpiToggleable(
                            value = selected,
                            enabled = enabled,
                            role = role,
                            interactionSource = interactionSource,
                            hapticFeedback = hapticFeedback,
                            onValueChange = { onClick() },
                        )
                    } else {
                        Modifier.ripDpiSelectable(
                            selected = selected,
                            enabled = enabled,
                            role = role,
                            interactionSource = interactionSource,
                            hapticFeedback = hapticFeedback,
                            onClick = onClick,
                        )
                    },
                ).ripDpiStylePadding(styleResolver)
                .alpha(alpha),
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
                    modifier = Modifier.size(components.inputs.chipIconSize),
                )
            }
        }
        Text(
            text = text,
            modifier = (if (wrapLabel) Modifier.weight(1f, fill = false) else Modifier).width(IntrinsicSize.Max),
            style = RipDpiThemeTokens.type.bodyEmphasis,
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
