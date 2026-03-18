package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class SettingsRowVariant {
    Default,
    Tonal,
    Selected,
}

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
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val containerColor =
        when (variant) {
            SettingsRowVariant.Default -> Color.Transparent
            SettingsRowVariant.Tonal -> colors.inputBackground
            SettingsRowVariant.Selected -> colors.accent
        }
    val borderColor =
        when (variant) {
            SettingsRowVariant.Default -> Color.Transparent
            SettingsRowVariant.Tonal -> colors.border
            SettingsRowVariant.Selected -> colors.foreground
        }
    val rowContent: @Composable () -> Unit = {
        val rowInteractionSource = remember { MutableInteractionSource() }
        Row(
            modifier =
                Modifier
                    .semantics(mergeDescendants = true) {}
                    .fillMaxWidth()
                    .background(containerColor, RipDpiThemeTokens.shapes.lg)
                    .border(
                        width = if (variant == SettingsRowVariant.Default) 0.dp else 1.dp,
                        color = borderColor,
                        shape = RipDpiThemeTokens.shapes.lg,
                    ).then(
                        if (variant == SettingsRowVariant.Default) {
                            Modifier
                        } else {
                            Modifier.padding(horizontal = components.compactPillHorizontalPadding)
                        },
                    ).heightIn(
                        min =
                            if (subtitle == null) {
                                components.settingsRowMinHeight
                            } else {
                                components.settingsRowMinHeightWithSubtitle
                            },
                    ).then(
                        if (checked != null && onCheckedChange != null) {
                            Modifier.ripDpiToggleable(
                                enabled = enabled,
                                value = checked,
                                role = Role.Switch,
                                interactionSource = rowInteractionSource,
                                onValueChange = onCheckedChange,
                            )
                        } else if (onClick != null) {
                            Modifier.ripDpiClickable(
                                enabled = enabled,
                                role = Role.Button,
                                interactionSource = rowInteractionSource,
                                onClick = onClick,
                            )
                        } else {
                            Modifier
                        },
                    ).padding(vertical = components.settingsRowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Box(
                    modifier =
                        Modifier
                            .size(components.decorativeBadgeSize)
                            .background(colors.accent, RipDpiThemeTokens.shapes.full),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = colors.foreground,
                        modifier = Modifier.size(RipDpiIconSizes.Small),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(components.compactPillVerticalPadding),
            ) {
                Text(text = title, style = type.body, color = colors.foreground)
                subtitle?.let {
                    Text(text = it, style = type.caption, color = colors.mutedForeground)
                }
            }
            when {
                checked != null && onCheckedChange != null -> {
                    RipDpiSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
                }

                value != null -> {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = value,
                            style = if (monospaceValue) type.monoValue else type.caption,
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
    }

    Column(modifier = modifier.fillMaxWidth()) {
        rowContent()
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
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
