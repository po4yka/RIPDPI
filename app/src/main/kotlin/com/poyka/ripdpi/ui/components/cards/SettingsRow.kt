package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class SettingsRowVariant {
    Default,
    Tonal,
    Selected,
}

/** Row on the Material 3 [Surface]: Material owns the state layer, ripple and selection semantics. */
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
    val switchStateDescription =
        checked?.let {
            stringResource(if (it) R.string.semantic_state_on else R.string.semantic_state_off)
        }
    val accessibilityDescription = settingsRowAccessibilityDescription(title, subtitle, value)
    val state = settingsRowColors(variant)
    val minHeight =
        if (subtitle == null) components.rows.settingsRowMinHeight else components.rows.settingsRowMinHeightWithSubtitle
    val pillPadding = if (variant == SettingsRowVariant.Default) 0.dp else components.rows.compactPillHorizontalPadding
    val surfaceModifier =
        Modifier
            .ripDpiTestTag(testTag)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityDescription
                switchStateDescription?.let { stateDescription = it }
                if (variant == SettingsRowVariant.Selected) selected = true
            }.fillMaxWidth()
    val border = if (variant == SettingsRowVariant.Default) null else BorderStroke(RipDpiStroke.Thin, state.border)
    val rowContent: @Composable () -> Unit = {
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
            modifier =
                Modifier
                    .heightIn(min = minHeight)
                    .padding(
                        horizontal = pillPadding,
                        vertical = components.rows.settingsRowVerticalPadding,
                    ),
        )
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsRowSurface(
            checked = checked,
            onCheckedChange = onCheckedChange,
            onClick = onClick,
            enabled = enabled,
            modifier = surfaceModifier,
            container = state.container,
            border = border,
            content = rowContent,
        )
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
private fun SettingsRowSurface(
    checked: Boolean?,
    onCheckedChange: ((Boolean) -> Unit)?,
    onClick: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier,
    container: Color,
    border: BorderStroke?,
    content: @Composable () -> Unit,
) {
    val performHaptic = rememberRipDpiHapticPerformer()
    val shape = RipDpiThemeTokens.shapes.lg
    when {
        checked != null && onCheckedChange != null -> {
            Surface(
                checked = checked,
                onCheckedChange = {
                    performHaptic(RipDpiHapticFeedback.Toggle)
                    onCheckedChange(it)
                },
                modifier = modifier.semantics { role = Role.Switch },
                enabled = enabled,
                shape = shape,
                color = container,
                border = border,
                content = content,
            )
        }

        onClick != null -> {
            Surface(
                onClick = {
                    performHaptic(RipDpiHapticFeedback.Action)
                    onClick()
                },
                modifier = modifier.semantics { role = Role.Button },
                enabled = enabled,
                shape = shape,
                color = container,
                border = border,
                content = content,
            )
        }

        else -> {
            Surface(modifier = modifier, shape = shape, color = container, border = border, content = content)
        }
    }
}

private class SettingsRowColors(
    val container: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val value: Color,
    val badgeContainer: Color,
    val badgeIcon: Color,
)

@Composable
private fun settingsRowColors(variant: SettingsRowVariant): SettingsRowColors {
    val colors = RipDpiThemeTokens.colors
    return when (variant) {
        SettingsRowVariant.Default -> {
            SettingsRowColors(
                container = Color.Transparent,
                border = Color.Transparent,
                title = colors.foreground,
                subtitle = colors.mutedForeground,
                value = colors.mutedForeground,
                badgeContainer = colors.accent,
                badgeIcon = colors.foreground,
            )
        }

        SettingsRowVariant.Tonal -> {
            SettingsRowColors(
                container = colors.inputBackground,
                border = colors.border,
                title = colors.foreground,
                subtitle = colors.mutedForeground,
                value = colors.mutedForeground,
                badgeContainer = colors.accent,
                badgeIcon = colors.foreground,
            )
        }

        SettingsRowVariant.Selected -> {
            SettingsRowColors(
                container = colors.accent,
                border = colors.foreground,
                title = colors.foreground,
                subtitle = colors.foreground,
                value = colors.foreground,
                badgeContainer = colors.foreground,
                badgeIcon = colors.background,
            )
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
    state: SettingsRowColors,
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
            showChevron = showChevron,
            state = state,
        )
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
    state: SettingsRowColors,
) {
    if (leadingIcon == null) return
    val components = RipDpiThemeTokens.components
    Box(
        modifier =
            Modifier
                .size(components.rows.settingsRowLeadingBadgeSize)
                .background(state.badgeContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = state.badgeIcon,
            modifier = Modifier.size(components.rows.settingsRowLeadingIconSize),
        )
    }
}

@Composable
private fun RowScope.SettingsRowText(
    title: String,
    subtitle: String?,
    value: String?,
    monospaceValue: Boolean,
    state: SettingsRowColors,
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
            val valueStyle = if (monospaceValue) type.monoValue else type.secondaryBody
            val valueTextDirection = endpointValueTextDirection()
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                style =
                    valueTextDirection?.let { direction -> valueStyle.copy(textDirection = direction) } ?: valueStyle,
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
    state: SettingsRowColors,
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
                modifier = Modifier.size(RipDpiIconSizes.Default),
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
