package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * Tonal accent applied to a [ProfileVariantCard] to visually distinguish
 * strategy profiles (Balanced / Aggressive / Stealth).
 */
enum class ProfileVariantTone {
    /** Neutral — info-tinted card for the balanced profile. */
    Balanced,

    /** Warning-tinted card for the aggressive profile. */
    Aggressive,

    /** Muted card for the stealth (low-fingerprint) profile. */
    Stealth,
}

/**
 * A single row inside the 3-row metric strip on a [ProfileVariantCard].
 *
 * @param labelRes   String resource for the metric label ("Latency", …).
 * @param value      Formatted metric value ("12 ms", "94 Mbps", "LOW").
 * @param isPositive Whether to render the value in the success colour.
 */
data class ProfileVariantMetric(
    val labelRes: Int,
    val value: String,
    val isPositive: Boolean,
)

/**
 * UI state for a single profile-variant card in [ProfileVariantsScreen].
 *
 * @param tone          Visual accent applied to the card header chip area.
 * @param chipLabelRes  String resource for the header chip label ("BALANCED", …).
 * @param nameRes       String resource for the profile name.
 * @param descriptionRes String resource for the 1–2 line description.
 * @param metrics       Exactly three metric rows (latency / throughput / detection).
 * @param isSelected    Whether this variant is the active selection.
 * @param onSelect      Callback when the "Select" CTA is tapped.
 */
data class ProfileVariantCardState(
    val tone: ProfileVariantTone,
    val chipLabelRes: Int,
    val nameRes: Int,
    val descriptionRes: Int,
    val metrics: List<ProfileVariantMetric>,
    val isSelected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * UI state for [ProfileVariantsScreen].
 *
 * @param variants  List of strategy profile variants; rendered as a vertical gallery.
 */
data class ProfileVariantsState(
    val variants: List<ProfileVariantCardState>,
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Presentation-only profile-variants screen matching
 * `docs/design/rds/preview/vpn-profile-switcher.html` (gallery variant).
 *
 * Renders a vertical gallery of strategy profile cards (Balanced / Aggressive /
 * Stealth), each with a tinted header chip, a 3-row metric strip (latency /
 * throughput / detection), a description, and a "Select" CTA. All tokens come
 * from [RipDpiThemeTokens]; no literal colours, dp values outside `ui/theme/`,
 * or animation-spec constants appear in this file.
 */
@Composable
fun ProfileVariantsScreen(
    state: ProfileVariantsState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.profile_variants_title),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.StaticProfileVariantsScreen),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(RipDpiThemeTokens.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            state.variants.forEach { variant ->
                ProfileVariantCard(card = variant)
            }

            Spacer(modifier = Modifier.height(RipDpiThemeTokens.spacing.md))
            Text(
                text = stringResource(R.string.profile_variants_caption),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Profile variant card
// ---------------------------------------------------------------------------

@Composable
private fun ProfileVariantCard(
    card: ProfileVariantCardState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing

    val borderColor =
        if (card.isSelected) colors.foreground else colors.cardBorder
    val borderWidth =
        if (card.isSelected) RipDpiStroke.Thick else RipDpiStroke.Thin

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.lg,
        color = colors.card,
        contentColor = colors.foreground,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ProfileVariantChipRow(card = card)

            Text(
                text = stringResource(card.nameRes),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )

            Text(
                text = stringResource(card.descriptionRes),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )

            HorizontalDivider(
                thickness = RipDpiStroke.Hairline,
                color = colors.divider,
            )

            ProfileVariantMetricStrip(metrics = card.metrics)

            RipDpiButton(
                text =
                    if (card.isSelected) {
                        stringResource(R.string.profile_variants_cta_selected)
                    } else {
                        stringResource(R.string.profile_variants_cta_select)
                    },
                onClick = card.onSelect,
                modifier = Modifier.fillMaxWidth(),
                variant =
                    if (card.isSelected) {
                        RipDpiButtonVariant.Secondary
                    } else {
                        RipDpiButtonVariant.Primary
                    },
                enabled = !card.isSelected,
            )
        }
    }
}

@Composable
private fun ProfileVariantChipRow(
    card: ProfileVariantCardState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    val (chipBg, chipFg, chipIcon) =
        when (card.tone) {
            ProfileVariantTone.Balanced -> {
                Triple(colors.infoContainer, colors.infoContainerForeground, RipDpiIcons.Shield)
            }

            ProfileVariantTone.Aggressive -> {
                Triple(colors.warningContainer, colors.warningContainerForeground, RipDpiIcons.NetworkCheck)
            }

            ProfileVariantTone.Stealth -> {
                Triple(colors.muted, colors.mutedForeground, RipDpiIcons.Visibility)
            }
        }

    Surface(
        modifier = modifier,
        shape = shapes.xxl,
        color = chipBg,
        contentColor = chipFg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = chipIcon,
                contentDescription = null,
                modifier = Modifier.size(RipDpiIconSizes.Small),
            )
            Text(
                text = stringResource(card.chipLabelRes),
                style = type.smallLabel,
                color = chipFg,
            )
        }
    }
}

@Composable
private fun ProfileVariantMetricStrip(
    metrics: List<ProfileVariantMetric>,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        metrics.forEachIndexed { index, metric ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(metric.labelRes),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
                Text(
                    text = metric.value,
                    style = type.monoValue,
                    color =
                        if (metric.isPositive) colors.success else colors.foreground,
                )
            }
            if (index < metrics.lastIndex) {
                HorizontalDivider(
                    thickness = RipDpiStroke.Hairline,
                    color = colors.divider,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
private fun ProfileVariantsScreenLightPreview() {
    RipDpiComponentPreview {
        ProfileVariantsScreen(
            state = sampleProfileVariantsState(),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileVariantsScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        ProfileVariantsScreen(
            state = sampleProfileVariantsState(),
            onBack = {},
        )
    }
}
