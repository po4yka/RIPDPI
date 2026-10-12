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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiSettingsRowStateStyle
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
    testTag: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val switchStateDescription =
        checked?.let {
            stringResource(
                if (it) {
                    R.string.semantic_state_on
                } else {
                    R.string.semantic_state_off
                },
            )
        }
    val state =
        RipDpiThemeTokens.state.settingsRow.resolve(
            RipDpiThemeTokens.stateRoles.settingsRow.fromVariant(variant),
        )
    Column(modifier = modifier.fillMaxWidth()) {
        val rowInteractionSource = remember { MutableInteractionSource() }
        val contentModifier =
            Modifier.settingsRowModifier(
                testTag = testTag,
                accessibilityDescription =
                    settingsRowAccessibilityDescription(
                        title = title,
                        subtitle = subtitle,
                        value = value,
                    ),
                stateDescription = switchStateDescription,
                variant = variant,
                subtitle = subtitle,
                enabled = enabled,
                checked = checked,
                onCheckedChange = onCheckedChange,
                onClick = onClick,
                state = state,
                interactionSource = rowInteractionSource,
            )
        SettingsRowContent(
            title = title,
            subtitle = subtitle,
            value = value,
            checked = checked,
            onCheckedChange = onCheckedChange,
            leadingIcon = leadingIcon,
            enabled = enabled,
            showChevron = showChevron,
            monospaceValue = monospaceValue,
            state = state,
            modifier = contentModifier,
        )
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
private fun SettingsRowContent(
    title: String,
    subtitle: String?,
    value: String?,
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    leadingIcon: ImageVector?,
    enabled: Boolean,
    showChevron: Boolean,
    monospaceValue: Boolean,
    state: RipDpiSettingsRowStateStyle,
    modifier: Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowLeadingIcon(leadingIcon = leadingIcon, state = state)
        SettingsRowText(
            title = title,
            subtitle = subtitle,
            value = value,
            monospaceValue = monospaceValue,
            state = state,
        )
        SettingsRowTrailing(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            showChevron = value != null && showChevron,
            state = state,
        )
    }
}

@Composable
private fun Modifier.settingsRowModifier(
    testTag: String?,
    accessibilityDescription: String,
    stateDescription: String?,
    variant: SettingsRowVariant,
    subtitle: String?,
    enabled: Boolean,
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    onClick: (() -> Unit)?,
    state: RipDpiSettingsRowStateStyle,
    interactionSource: MutableInteractionSource,
): Modifier {
    val components = RipDpiThemeTokens.components
    val minHeight =
        if (subtitle == null) {
            components.rows.settingsRowMinHeight
        } else {
            components.rows.settingsRowMinHeightWithSubtitle
        }
    val baseModifier =
        this
            .ripDpiTestTag(testTag)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDescription
                stateDescription?.let { this.stateDescription = it }
            }.fillMaxWidth()
            .background(state.container, RipDpiThemeTokens.shapes.lg)
            .border(
                width = if (variant == SettingsRowVariant.Default) 0.dp else 1.dp,
                color = state.border,
                shape = RipDpiThemeTokens.shapes.lg,
            ).then(
                if (variant == SettingsRowVariant.Default) {
                    Modifier
                } else {
                    Modifier.padding(horizontal = components.rows.compactPillHorizontalPadding)
                },
            ).heightIn(min = minHeight)
            .padding(vertical = components.rows.settingsRowVerticalPadding)
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

private fun settingsRowAccessibilityDescription(
    title: String,
    subtitle: String?,
    value: String?,
): String =
    listOfNotNull(
        title,
        subtitle?.takeIf { it.isNotBlank() },
        value?.takeIf { it.isNotBlank() },
    ).joinToString(separator = ", ")

@Composable
private fun SettingsRowLeadingIcon(
    leadingIcon: ImageVector?,
    state: RipDpiSettingsRowStateStyle,
) {
    if (leadingIcon == null) return
    val components = RipDpiThemeTokens.components
    Box(
        modifier =
            Modifier
                .size(components.feedback.decorativeBadgeSize)
                .background(state.leadingBadgeContainer, RipDpiThemeTokens.shapes.full),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = state.leadingBadgeIcon,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
    }
}

@Composable
private fun RowScope.SettingsRowText(
    title: String,
    subtitle: String?,
    value: String?,
    monospaceValue: Boolean,
    state: RipDpiSettingsRowStateStyle,
) {
    val components = RipDpiThemeTokens.components
    val type = RipDpiThemeTokens.type
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(components.rows.compactPillVerticalPadding),
    ) {
        Text(text = title, modifier = Modifier.fillMaxWidth(), style = type.body, color = state.title)
        subtitle?.let {
            Text(text = it, modifier = Modifier.fillMaxWidth(), style = type.secondaryBody, color = state.subtitle)
        }
        value?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                style = if (monospaceValue) type.monoValue else type.secondaryBody,
                color = state.value,
            )
        }
    }
}

@Composable
private fun SettingsRowTrailing(
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean,
    showChevron: Boolean,
    state: RipDpiSettingsRowStateStyle,
) {
    when {
        checked != null && onCheckedChange != null -> {
            RipDpiSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }

        showChevron -> {
            Icon(
                imageVector = RipDpiIcons.ChevronRight,
                contentDescription = null,
                tint = state.value,
                modifier = Modifier.size(RipDpiIconSizes.Small),
            )
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 390)
@Preview(name = "Large font", showBackground = true, widthDp = 390, fontScale = 2f)
@Composable
private fun SettingsRowPreview() {
    SettingsRowReadabilityPreviewScene()
}

@Preview(name = "Dark", showBackground = true, widthDp = 390)
@Composable
private fun SettingsRowDarkPreview() {
    SettingsRowReadabilityPreviewScene(themePreference = "dark")
}

@Composable
internal fun SettingsRowReadabilityPreviewScene(themePreference: String = "light") {
    RipDpiComponentPreview(themePreference = themePreference) {
        RipDpiCard(
            paddingValues =
                PaddingValues(horizontal = RipDpiThemeTokens.layout.cardPadding, vertical = 0.dp),
        ) {
            SettingsRow(
                title = "DNS provider",
                subtitle = "Encrypted DNS and fallback resolver",
                value = "Cloudflare over HTTPS with AdGuard as fallback",
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
