package com.poyka.ripdpi.ui.screens.customization

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.activities.LauncherIconOption
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiFeatureSurface

private const val iconGridColumns = 3

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
        val rows = remember(options) { options.chunked(iconGridColumns) }
        rows.forEach { row ->
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
                repeat(iconGridColumns - row.size) {
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
                .ripDpiTestTag(RipDpiTestTags.customizationIcon(option.key))
                .ripDpiSelectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Every tile has the same neutral surface; only the ring marks the selection, so a dark icon
        // (like Cracked) no longer reads as selected.
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .ripDpiFeatureSurface(
                        container = colors.inputBackground,
                        shape = shapes.xl,
                        border = if (selected) colors.foreground else colors.cardBorder,
                        borderWidth = if (selected) 2.dp else 1.dp,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .ripDpiTestTag(RipDpiTestTags.customizationIconPreview(option.key))
                        .ripDpiFeatureSurface(
                            container = colorResource(option.previewBackgroundRes),
                            shape = shapes.xl,
                            border = colors.cardBorder,
                            borderWidth = 1.dp,
                            clip = true,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = option.previewRes),
                    contentDescription = stringResource(option.labelRes),
                    modifier = Modifier.fillMaxSize().scale(option.previewForegroundScale),
                )
            }

            if (selected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                            .ripDpiFeatureSurface(
                                container = colors.foreground,
                                shape = RipDpiThemeTokens.shapes.full,
                                clip = true,
                            ),
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
            selectedKey = LauncherIconManager.CrackedIconKey,
            onOptionSelected = {},
        )
    }
}
