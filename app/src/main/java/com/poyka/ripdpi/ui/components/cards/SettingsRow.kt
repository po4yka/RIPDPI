package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (subtitle == null) 52.dp else 67.dp)
                    .then(
                        if (onClick != null || (checked != null && onCheckedChange != null)) {
                            Modifier.clickable(
                                enabled = enabled,
                                role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    when {
                                        checked != null && onCheckedChange != null -> onCheckedChange(!checked)
                                        onClick != null -> onClick()
                                    }
                                },
                            )
                        } else {
                            Modifier
                        },
                    ).padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .background(colors.accent, RipDpiThemeTokens.shapes.full),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = colors.foreground,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = title, style = type.body, color = colors.foreground)
                subtitle?.let {
                    Text(text = it, style = type.caption, color = colors.mutedForeground)
                }
            }
            when {
                checked != null && onCheckedChange != null -> {
                    RipDpiSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
                }

                value != null -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = value,
                            style = if (monospaceValue) type.monoValue else type.caption,
                            color = colors.mutedForeground,
                        )
                        if (showChevron) {
                            Icon(
                                imageVector = RipDpiIcons.ChevronRight,
                                contentDescription = null,
                                tint = colors.mutedForeground,
                                modifier = Modifier.size(16.dp),
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
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = 16.dp, vertical = 0.dp),
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
