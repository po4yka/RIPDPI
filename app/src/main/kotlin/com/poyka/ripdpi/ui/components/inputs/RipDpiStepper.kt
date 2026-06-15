package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val StepperDefaultMin = 0
private const val StepperDefaultMax = 100
private const val StepperDefaultStep = 1
private const val StepperPreviewMin = 0
private const val StepperPreviewMax = 10
private const val StepperPreviewInitial = 3
private const val StepperPreviewDisabled = 5
private const val StepperDarkPreview = 4
private val StepperOuterPadding = 2.dp
private val StepperButtonSize = 32.dp
private val StepperButtonCornerRadius = 6.dp
private val StepperValueMinWidth = 48.dp
private val StepperIconSize = 16.dp

/**
 * Numeric stepper: minus / value / plus row, clamped to [valueRange],
 * with disabled button states at the ends. Buttons emit a haptic via
 * the existing ripDpiClickable. Matches `components-stepper.html`.
 */
@Composable
fun RipDpiStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: IntRange = StepperDefaultMin..StepperDefaultMax,
    step: Int = StepperDefaultStep,
    enabled: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val shape = RoundedCornerShape(RipDpiThemeTokens.spacing.sm)
    val canDecrement = enabled && value > valueRange.first
    val canIncrement = enabled && value < valueRange.last
    Row(
        modifier =
            modifier
                .background(colors.card, shape)
                .border(width = 1.dp, color = colors.border, shape = shape)
                .padding(StepperOuterPadding)
                .semantics { contentDescription = "$value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StepperOuterPadding),
    ) {
        StepperButton(
            icon = RipDpiIcons.Remove,
            enabled = canDecrement,
            description = stringResource(R.string.cd_decrement),
            onClick = { onValueChange((value - step).coerceAtLeast(valueRange.first)) },
        )
        Box(
            modifier =
                Modifier
                    .defaultMinSize(minWidth = StepperValueMinWidth)
                    .padding(horizontal = RipDpiThemeTokens.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value.toString(),
                style = RipDpiThemeTokens.type.monoValue.copy(color = colors.foreground),
            )
        }
        StepperButton(
            icon = Icons.Outlined.Add,
            enabled = canIncrement,
            description = stringResource(R.string.cd_increment),
            onClick = { onValueChange((value + step).coerceAtMost(valueRange.last)) },
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val container = if (enabled) colors.muted else colors.card
    val content = if (enabled) colors.foreground else colors.mutedForeground
    // Visual 32dp; ripDpiClickable wraps the layout in
    // minimumInteractiveComponentSize() internally, so the actual touch
    // target is Android's 48dp Material minimum without enlarging the
    // painted surface.
    Box(
        modifier =
            Modifier
                .size(StepperButtonSize)
                .background(container, RoundedCornerShape(StepperButtonCornerRadius))
                .ripDpiClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = description,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(StepperIconSize),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiStepper (light)")
@Composable
private fun RipDpiStepperLightPreview() {
    RipDpiComponentPreview {
        var n by remember { mutableIntStateOf(StepperPreviewInitial) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiStepper(
                value = n,
                onValueChange = { n = it },
                valueRange = StepperPreviewMin..StepperPreviewMax,
            )
            RipDpiStepper(
                value = StepperPreviewMin,
                onValueChange = {},
                valueRange = StepperPreviewMin..StepperPreviewMax,
            )
            RipDpiStepper(
                value = StepperPreviewMax,
                onValueChange = {},
                valueRange = StepperPreviewMin..StepperPreviewMax,
            )
            RipDpiStepper(value = StepperPreviewDisabled, onValueChange = {}, enabled = false)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiStepper (dark)")
@Composable
private fun RipDpiStepperDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiStepper(value = StepperDarkPreview, onValueChange = {})
    }
}
