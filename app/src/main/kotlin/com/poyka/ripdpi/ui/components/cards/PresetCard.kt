package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiSurfaceRole
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val disabledAlpha = 0.38f

@Suppress("LongMethod")
@Composable
fun PresetCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val surfaceStyle =
        RipDpiThemeTokens.surfaces.resolve(
            if (selected) {
                RipDpiSurfaceRole.SelectedCard
            } else {
                RipDpiSurfaceRole.Card
            },
        )
    val badgePalette = presetCardBadgePalette(selected = selected)
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = components.rows.settingsRowMinHeightWithSubtitle)
                .shadow(surfaceStyle.shadowElevation, shape, clip = false)
                .clip(shape)
                .background(surfaceStyle.container, shape)
                .border(
                    width = if (surfaceStyle.border == Color.Transparent) 0.dp else 1.dp,
                    color = surfaceStyle.border,
                    shape = shape,
                ).alpha(if (enabled) 1f else disabledAlpha)
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
                            .background(
                                color = badgePalette.container,
                                shape = RipDpiThemeTokens.shapes.xxl,
                            ).padding(
                                horizontal = components.rows.compactPillHorizontalPadding,
                                vertical = components.rows.compactPillVerticalPadding,
                            ),
                ) {
                    Text(
                        text = it,
                        style = type.smallLabel,
                        color = badgePalette.content,
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
    }
}

private val RadioIndicatorSize = 18.dp
private val RadioIndicatorDotSize = 8.dp
private val RadioIndicatorBorderWidth = 2.dp

@Composable
private fun RadioIndicator(selected: Boolean) {
    val colors = RipDpiThemeTokens.colors
    val outerColor = if (selected) colors.foreground else colors.mutedForeground

    Box(
        modifier =
            Modifier
                .size(RadioIndicatorSize)
                .border(
                    width = RadioIndicatorBorderWidth,
                    color = outerColor,
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(RadioIndicatorDotSize)
                        .background(color = outerColor, shape = CircleShape),
            )
        }
    }
}

@Immutable
private data class PresetCardBadgePalette(
    val container: Color,
    val content: Color,
)

@Composable
private fun presetCardBadgePalette(selected: Boolean): PresetCardBadgePalette {
    val colors = RipDpiThemeTokens.colors
    val selectedCardSurface = RipDpiThemeTokens.surfaces.resolve(RipDpiSurfaceRole.SelectedCard)
    val tonalCardSurface = RipDpiThemeTokens.surfaces.resolve(RipDpiSurfaceRole.TonalCard)

    return if (selected) {
        PresetCardBadgePalette(
            container = selectedCardSurface.content,
            content = colors.background,
        )
    } else {
        PresetCardBadgePalette(
            container = tonalCardSurface.container,
            content = colors.mutedForeground,
        )
    }
}

@Suppress("UnusedPrivateMember")
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
