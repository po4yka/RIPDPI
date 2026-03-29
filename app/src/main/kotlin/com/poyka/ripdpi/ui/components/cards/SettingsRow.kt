package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class SettingsRowVariant {
    Default,
    Tonal,
    Selected,
}

private data class SettingsRowColors(
    val container: Color,
    val border: Color,
)

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = value != null && onClick != null,
    showDivider: Boolean = false,
    monospaceValue: Boolean = false,
    variant: SettingsRowVariant = SettingsRowVariant.Default,
    testTag: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val rowColors = settingsRowColors(variant)
    Column(modifier = modifier.fillMaxWidth()) {
        val rowInteractionSource = remember { MutableInteractionSource() }
        Row(
            modifier =
                Modifier.settingsRowModifier(
                    testTag = testTag,
                    variant = variant,
                    subtitle = subtitle,
                    enabled = enabled,
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    onClick = onClick,
                    rowColors = rowColors,
                    interactionSource = rowInteractionSource,
                ),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowLeadingIcon(leadingIcon = leadingIcon)
            SettingsRowText(title = title, subtitle = subtitle)
            SettingsRowTrailing(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                value = value,
                monospaceValue = monospaceValue,
                showChevron = showChevron,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
private fun settingsRowColors(variant: SettingsRowVariant): SettingsRowColors =
    with(RipDpiThemeTokens.colors) {
        when (variant) {
            SettingsRowVariant.Default -> SettingsRowColors(Color.Transparent, Color.Transparent)
            SettingsRowVariant.Tonal -> SettingsRowColors(inputBackground, border)
            SettingsRowVariant.Selected -> SettingsRowColors(accent, foreground)
        }
    }

@Composable
private fun Modifier.settingsRowModifier(
    testTag: String?,
    variant: SettingsRowVariant,
    subtitle: String?,
    enabled: Boolean,
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    onClick: (() -> Unit)?,
    rowColors: SettingsRowColors,
    interactionSource: MutableInteractionSource,
): Modifier {
    val components = RipDpiThemeTokens.components
    val minHeight =
        if (subtitle == null) {
            components.settingsRowMinHeight
        } else {
            components.settingsRowMinHeightWithSubtitle
        }
    val baseModifier =
        this
            .ripDpiTestTag(testTag)
            .semantics(mergeDescendants = true) {}
            .fillMaxWidth()
            .background(rowColors.container, RipDpiThemeTokens.shapes.lg)
            .border(
                width = if (variant == SettingsRowVariant.Default) 0.dp else 1.dp,
                color = rowColors.border,
                shape = RipDpiThemeTokens.shapes.lg,
            ).then(
                if (variant == SettingsRowVariant.Default) {
                    Modifier
                } else {
                    Modifier.padding(horizontal = components.compactPillHorizontalPadding)
                },
            ).heightIn(min = minHeight)
            .padding(vertical = components.settingsRowVerticalPadding)
    return when {
        checked != null && onCheckedChange != null -> {
            baseModifier.ripDpiToggleable(
                enabled = enabled,
                value = checked,
                role = Role.Switch,
                interactionSource = interactionSource,
                onValueChange = onCheckedChange,
            )
        }

        onClick != null -> {
            baseModifier.ripDpiClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                onClick = onClick,
            )
        }

        else -> {
            baseModifier
        }
    }
}

@Composable
private fun SettingsRowLeadingIcon(leadingIcon: ImageVector?) {
    if (leadingIcon == null) return
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    Box(
        modifier =
            Modifier
                .size(components.decorativeBadgeSize)
                .background(colors.accent, RipDpiThemeTokens.shapes.full),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = colors.foreground,
            modifier = Modifier.size(RipDpiIconSizes.Small),
        )
    }
}

@Composable
private fun RowScope.SettingsRowText(
    title: String,
    subtitle: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val type = RipDpiThemeTokens.type
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(components.compactPillVerticalPadding),
    ) {
        Text(text = title, style = type.body, color = colors.foreground)
        subtitle?.let {
            Text(text = it, style = type.caption, color = colors.mutedForeground)
        }
    }
}

@Composable
private fun RowScope.SettingsRowTrailing(
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean,
    value: String?,
    monospaceValue: Boolean,
    showChevron: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    when {
        checked != null && onCheckedChange != null -> {
            RipDpiSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }

        value != null -> {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    style = if (monospaceValue) type.monoValue else type.secondaryBody,
                    color = colors.mutedForeground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showChevron) {
                    Icon(
                        imageVector = RipDpiIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.mutedForeground,
                        modifier = Modifier.size(RipDpiIconSizes.Small),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsRowPreview() {
    RipDpiComponentPreview {
        RipDpiCard(
            paddingValues =
                PaddingValues(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = 0.dp),
        ) {
            SettingsRow(
                title = "DNS provider",
                subtitle = "Cloudflare DoH",
                value = "Edit",
                showChevron = true,
                onClick = {},
                showDivider = true,
            )
            SettingsRow(
                title = "Block DNS over plain UDP",
                checked = true,
                onCheckedChange = {},
                showDivider = true,
            )
            SettingsRow(
                title = "Use system DNS as fallback",
                checked = false,
                onCheckedChange = {},
                leadingIcon = RipDpiIcons.Dns,
            )
        }
    }
}
