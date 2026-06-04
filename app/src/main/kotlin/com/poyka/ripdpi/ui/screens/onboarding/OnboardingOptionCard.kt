package com.poyka.ripdpi.ui.screens.onboarding

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Onboarding-scoped selectable option card used for both the traffic-mode and DNS choices.
 *
 * Intentionally lighter than the shared [com.poyka.ripdpi.ui.components.cards.PresetCard]: no drop
 * shadow, a subtle neutral fill on selection, and a clean 2 dp border (1 dp when unselected) so the
 * selected state reads as "outlined + faintly filled" rather than a heavy elevated tile. The whole
 * card is a single [Role.RadioButton] selectable target with Android ripple; the badge is a quiet
 * tonal pill that stays secondary to the title.
 */
@Composable
internal fun OnboardingOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metadata: String? = null,
    badgeText: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val components = RipDpiThemeTokens.components
    val shape = RipDpiThemeTokens.shapes.xl
    val interactionSource = remember { MutableInteractionSource() }

    // Selection reads as a subtle filled container + the radio dot — NOT a harsh black outline.
    // Borders stay on the quiet outline ramp so the card looks like a real Material surface, with
    // the selected state carrying a slightly stronger (but still soft) outline for a non-color cue.
    val container = if (selected) colors.muted else colors.card
    val borderColor = if (selected) colors.outline else colors.outlineVariant
    // Token-derived widths (no literal dp): 2 dp selected / 1 dp unselected.
    val borderWidth = if (selected) spacing.xs / 2 else spacing.xs / 4

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = components.rows.settingsRowMinHeightWithSubtitle)
                .clip(shape)
                .background(container, shape)
                .border(width = borderWidth, color = borderColor, shape = shape)
                .ripDpiSelectable(
                    selected = selected,
                    enabled = true,
                    role = Role.RadioButton,
                    interactionSource = interactionSource,
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
            OnboardingRadioIndicator(selected = selected)
            Text(
                text = title,
                style = type.bodyEmphasis,
                color = colors.foreground,
                modifier = Modifier.weight(1f),
            )
            badgeText?.let { OnboardingOptionBadge(text = it) }
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

/** Quiet tonal pill — deliberately secondary to the card title (no solid/inverted fill). */
@Composable
private fun OnboardingOptionBadge(text: String) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val components = RipDpiThemeTokens.components
    Box(
        modifier =
            Modifier
                .background(color = colors.muted, shape = RipDpiThemeTokens.shapes.xxl)
                .padding(
                    horizontal = components.rows.compactPillHorizontalPadding,
                    vertical = components.rows.compactPillVerticalPadding,
                ),
    ) {
        Text(text = text, style = type.smallLabel, color = colors.mutedForeground)
    }
}

@Composable
private fun OnboardingRadioIndicator(selected: Boolean) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val outerColor = if (selected) colors.foreground else colors.mutedForeground
    // Outer ~20 dp, dot ~8 dp, ring ~2 dp — all token-derived (no literal dp).
    Box(
        modifier =
            Modifier
                .size(spacing.lg + spacing.xs)
                .border(width = spacing.xs / 2, color = outerColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(spacing.sm)
                        .background(color = outerColor, shape = CircleShape),
            )
        }
    }
}
