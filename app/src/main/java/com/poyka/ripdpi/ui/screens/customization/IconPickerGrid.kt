package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.activities.LauncherIconOption
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun IconPickerGrid(
    options: List<LauncherIconOption>,
    selectedKey: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        options.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                row.forEach { option ->
                    IconOptionCell(
                        option = option,
                        selected = option.key == selectedKey,
                        onClick = { onOptionSelected(option.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IconOptionCell(
    option: LauncherIconOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type

    Column(
        modifier =
            modifier
                .clickable(
                    role = Role.RadioButton,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(shapes.xl)
                        .background(colors.inputBackground)
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.foreground else colors.cardBorder,
                            shape = shapes.xl,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = option.previewRes),
                    contentDescription = stringResource(option.labelRes),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (selected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(18.dp)
                            .clip(RipDpiThemeTokens.shapes.full)
                            .background(colors.foreground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = RipDpiIcons.Check,
                        contentDescription = null,
                        tint = colors.background,
                        modifier = Modifier.size(RipDpiIconSizes.Small),
                    )
                }
            }
        }

        Text(
            text = stringResource(option.labelRes),
            style = type.caption,
            color = if (selected) colors.foreground else colors.mutedForeground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun IconPickerGridPreview() {
    RipDpiComponentPreview {
        IconPickerGrid(
            options = LauncherIconManager.availableIcons,
            selectedKey = LauncherIconManager.DefaultIconKey,
            onOptionSelected = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun IconPickerGridDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        IconPickerGrid(
            options = LauncherIconManager.availableIcons,
            selectedKey = LauncherIconManager.RavenIconKey,
            onOptionSelected = {},
        )
    }
}
