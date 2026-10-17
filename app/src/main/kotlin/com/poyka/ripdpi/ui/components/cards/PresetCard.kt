@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.presetBadge
import com.poyka.ripdpi.ui.theme.presetRadio
import com.poyka.ripdpi.ui.theme.presetRadioDot
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface

private const val disabledAlpha = 0.38f

@Composable
@NonRestartableComposable
fun PresetCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    metadata: String? = null,
    badgeText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    PresetCard(
        title = title,
        description = description,
        modifier = modifier,
        metadata = metadata,
        badgeText = badgeText,
        selected = selected,
        enabled = enabled,
        interactionSource = interactionSource,
        onClick = onClick,
        visualStyle = CommonStyle,
    )
}

@Suppress("LongMethod")
@Composable
fun PresetCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    metadata: String? = null,
    badgeText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    visualStyle: CommonStyle = CommonStyle,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val surfaceStyle =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.presetCard.card(selected),
        )
    val badgeContent = if (selected) colors.background else colors.mutedForeground
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val styleState =
        rememberUpdatedStyleState(resolvedInteractionSource) {
            it.isEnabled = enabled
            it.isSelected = selected
        }
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.outlinedSurface(RipDpiThemeTokens.surfaceRoles.presetCard.card(selected)),
            visualStyle,
            styleState,
        )
    val badgeResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.presetBadge(RipDpiThemeTokens.surfaceRoles.presetCard.badge(selected)),
            CommonStyle,
            styleState,
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = components.rows.settingsRowMinHeightWithSubtitle)
                .shadow(surfaceStyle.shadowElevation, shape, clip = false)
                .ripDpiStyleSurface(styleResolver, shape, clip = true)
                .alpha(if (enabled) 1f else disabledAlpha)
                .ripDpiSelectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    interactionSource = resolvedInteractionSource,
                    onClick = onClick,
                ).padding(
                    horizontal = components.inputs.fieldHorizontalPadding,
                    vertical = components.rows.settingsRowVerticalPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioIndicator(selected = selected)
            Text(
                text = title,
                style = type.bodyEmphasis,
                color = surfaceStyle.content,
                modifier = Modifier.weight(1f),
            )
            badgeText?.let {
                Box(
                    modifier =
                        Modifier
                            .ripDpiStyleSurface(badgeResolver, RipDpiThemeTokens.shapes.xxl)
                            .padding(
                                horizontal = components.rows.compactPillHorizontalPadding,
                                vertical = components.rows.compactPillVerticalPadding,
                            ),
                ) {
                    Text(
                        text = it,
                        style = type.smallLabel,
                        color = badgeContent,
                    )
                }
            }
        }
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        metadata?.let { meta ->
            Text(
                text = meta,
                style = type.caption,
                color = colors.mutedForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val RadioIndicatorSize = 18.dp
private val RadioIndicatorDotSize = 8.dp

@Composable
private fun RadioIndicator(selected: Boolean) {
    val state = rememberUpdatedStyleState(null) { it.isSelected = selected }
    val resolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.presetRadio, CommonStyle, state)
    val dotResolver = rememberRipDpiStyleResolver(RipDpiThemeTokens.styles.presetRadioDot, CommonStyle, state)

    Box(
        modifier =
            Modifier
                .size(RadioIndicatorSize)
                .ripDpiStyleSurface(resolver, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(RadioIndicatorDotSize)
                        .ripDpiStyleSurface(dotResolver, CircleShape),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PresetCardPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetCard(
                title = "Auto",
                description = "Detect and apply best optimization strategy",
                badgeText = "Active",
                selected = true,
                onClick = {},
            )
            PresetCard(
                title = "desync (fake)",
                description = "Send fake packets before real data",
                onClick = {},
            )
        }
    }
}
