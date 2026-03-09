package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

data class RipDpiDropdownOption<T>(
    val value: T,
    val label: String,
)

@Composable
fun <T> RipDpiDropdown(
    options: List<RipDpiDropdownOption<T>>,
    selectedValue: T?,
    onValueSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    helperText: String? = null,
    enabled: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val type = RipDpiThemeTokens.type
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    val borderWidth = if (expanded && enabled) 2.dp else 1.dp
    val borderColor = if (expanded && enabled) colors.foreground else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        label?.let {
            Text(text = it, style = type.smallLabel, color = colors.mutedForeground)
        }
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(components.controlHeight)
                        .background(colors.inputBackground, RipDpiThemeTokens.shapes.xl)
                        .border(borderWidth, borderColor, RipDpiThemeTokens.shapes.xl)
                        .clickable(enabled = enabled) { setExpanded(true) }
                        .padding(
                            horizontal =
                                if (borderWidth > 1.dp) {
                                    components.fieldFocusedHorizontalPadding
                                } else {
                                    components.fieldHorizontalPadding
                                },
                        )
                        .alpha(if (enabled) 1f else 0.38f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    style = RipDpiThemeTokens.type.monoValue,
                    color = if (selectedLabel.isEmpty()) colors.mutedForeground else colors.foreground,
                )
                Icon(
                    imageVector = RipDpiIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.mutedForeground,
                    modifier = Modifier.graphicsLayer { rotationZ = 90f },
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { setExpanded(false) },
                modifier =
                    Modifier
                        .fillMaxWidth(0.9f)
                        .background(MaterialTheme.colorScheme.surface, RipDpiThemeTokens.shapes.xl),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                style = RipDpiThemeTokens.type.body,
                                color = colors.foreground,
                            )
                        },
                        onClick = {
                            onValueSelected(option.value)
                            setExpanded(false)
                        },
                    )
                }
            }
        }
        helperText?.let {
            Text(
                text = it,
                style = type.caption,
                color = colors.mutedForeground,
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDropdownPreview() {
    val options =
        listOf(
            RipDpiDropdownOption("auto", "Auto"),
            RipDpiDropdownOption("fake", "desync (fake)"),
            RipDpiDropdownOption("proxy", "SOCKS5 proxy"),
        )
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiDropdown(
                options = options,
                selectedValue = "auto",
                onValueSelected = {},
                label = "Mode",
                helperText = "Select how traffic should be handled",
            )
            RipDpiDropdown(
                options = options,
                selectedValue = "fake",
                onValueSelected = {},
                label = "Selected",
            )
            RipDpiDropdown(
                options = options,
                selectedValue = "proxy",
                onValueSelected = {},
                label = "Disabled",
                enabled = false,
            )
        }
    }
}
