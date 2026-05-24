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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    valueRange: IntRange = 0..100,
    step: Int = 1,
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
                .padding(2.dp)
                .semantics { contentDescription = "$value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StepperButton(
            icon = RipDpiIcons.Remove,
            enabled = canDecrement,
            description = "Decrement",
            onClick = { onValueChange((value - step).coerceAtLeast(valueRange.first)) },
        )
        Box(
            modifier =
                Modifier
                    .defaultMinSize(minWidth = 48.dp)
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
            description = "Increment",
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
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .background(container, RoundedCornerShape(6.dp))
                .ripDpiClickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiStepper (light)")
@Composable
private fun RipDpiStepperPreviewLight() {
    RipDpiComponentPreview {
        var n by remember { mutableIntStateOf(3) }
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
            RipDpiStepper(value = n, onValueChange = { n = it }, valueRange = 0..10)
            RipDpiStepper(value = 0, onValueChange = {}, valueRange = 0..10)
            RipDpiStepper(value = 10, onValueChange = {}, valueRange = 0..10)
            RipDpiStepper(value = 5, onValueChange = {}, enabled = false)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiStepper (dark)")
@Composable
private fun RipDpiStepperPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiStepper(value = 4, onValueChange = {})
    }
}
