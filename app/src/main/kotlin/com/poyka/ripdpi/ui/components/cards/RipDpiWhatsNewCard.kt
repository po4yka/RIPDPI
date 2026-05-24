package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class RipDpiWhatsNewTag {
    New,
    Fix,
    Breaking,
}

data class RipDpiWhatsNewChange(
    val tag: RipDpiWhatsNewTag,
    val title: String,
    val body: String,
)

private const val NewTagAlpha = 0.18f
private const val PillDotAlpha = 1f

/**
 * Release-notes pop-up card: header, version hero band, list of grouped
 * changes (NEW / FIX / BREAKING), dismiss + acknowledge actions.
 *
 * Matches `docs/design/rds/preview/whats-new-card.html`.
 */
@Composable
fun RipDpiWhatsNewCard(
    version: String,
    releasedOn: String,
    heroLabel: String,
    changes: ImmutableList<RipDpiWhatsNewChange>,
    onDismiss: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Outlined) {
        WhatsNewHeader()
        HorizontalDivider(thickness = RipDpiStroke.Hairline, color = colors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = version,
                    style = type.monoValue,
                    color = colors.foreground,
                )
                Text(
                    text = releasedOn,
                    style = type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
            WhatsNewBadge()
        }
        WhatsNewHero(heroLabel = heroLabel)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            changes.forEach { change ->
                WhatsNewChangeRow(change = change)
            }
        }
        HorizontalDivider(thickness = RipDpiStroke.Hairline, color = colors.divider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RipDpiButton(
                text = stringResource(R.string.whats_new_later),
                onClick = onDismiss,
                variant = RipDpiButtonVariant.Outline,
            )
            Box(modifier = Modifier.size(spacing.sm))
            RipDpiButton(
                text = stringResource(R.string.whats_new_acknowledge),
                onClick = onAcknowledge,
                variant = RipDpiButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun WhatsNewHeader() {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.whats_new_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.whats_new_subtitle),
            style = type.monoSmall,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun WhatsNewBadge() {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    Surface(
        shape = shapes.full,
        color = colors.infoContainer,
        contentColor = colors.info,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(spacing.xs)
                        .clip(CircleShape)
                        .background(colors.info.copy(alpha = PillDotAlpha)),
            )
            Text(
                text = stringResource(R.string.whats_new_badge),
                style = type.smallLabel,
                color = colors.info,
            )
        }
    }
}

@Composable
private fun WhatsNewHero(heroLabel: String) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(spacing.section + spacing.section),
        shape = shapes.lg,
        color = colors.accent,
        contentColor = colors.accentForeground,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = heroLabel,
                style = type.brandStatus,
                color = colors.foreground,
            )
        }
    }
}

@Composable
private fun WhatsNewChangeRow(change: RipDpiWhatsNewChange) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        WhatsNewTagChip(tag = change.tag)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = change.title,
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = change.body,
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun WhatsNewTagChip(tag: RipDpiWhatsNewTag) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    val (container, content) = tagPalette(tag = tag)
    Surface(
        shape = shapes.sm,
        color = container,
        contentColor = content,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            text = tag.name.uppercase(),
            style = type.smallLabel,
            color = content,
        )
    }
}

@Composable
private fun tagPalette(tag: RipDpiWhatsNewTag): Pair<Color, Color> {
    val colors = RipDpiThemeTokens.colors
    return when (tag) {
        RipDpiWhatsNewTag.New -> colors.success.copy(alpha = NewTagAlpha) to colors.success
        RipDpiWhatsNewTag.Fix -> colors.infoContainer to colors.info
        RipDpiWhatsNewTag.Breaking -> colors.destructiveContainer to colors.destructive
    }
}

private val SampleChanges =
    persistentListOf(
        RipDpiWhatsNewChange(
            tag = RipDpiWhatsNewTag.New,
            title = "DPI signature confidence meter",
            body =
                "Each detected signature now ships with a 0-1 confidence " +
                    "score and inline evidence excerpts. Tap to expand.",
        ),
        RipDpiWhatsNewChange(
            tag = RipDpiWhatsNewTag.New,
            title = "Path-MTU binary search",
            body = "Probes converge on link MTU in 1.4 s on average; adds FRAG fallback row.",
        ),
        RipDpiWhatsNewChange(
            tag = RipDpiWhatsNewTag.Fix,
            title = "Reconnect backoff respects user cancel",
            body =
                "Cancelling during a backoff window now stops the chain " +
                    "immediately instead of waiting the remaining seconds.",
        ),
        RipDpiWhatsNewChange(
            tag = RipDpiWhatsNewTag.Breaking,
            title = "Strategy schema: retry field renamed to retries",
            body =
                "Imported strategies are auto-migrated; manually-edited " +
                    ".toml files need a one-character change.",
        ),
    )

@Preview(showBackground = true, name = "RipDpiWhatsNewCard (light)")
@Composable
private fun RipDpiWhatsNewCardLightPreview() {
    RipDpiComponentPreview {
        RipDpiWhatsNewCard(
            version = "v3.2.0",
            releasedOn = "Released 2026-05-15",
            heroLabel = "v3.2",
            changes = SampleChanges,
            onDismiss = {},
            onAcknowledge = {},
        )
    }
}

@Preview(showBackground = true, name = "RipDpiWhatsNewCard (dark)")
@Composable
private fun RipDpiWhatsNewCardDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiWhatsNewCard(
            version = "v3.2.0",
            releasedOn = "Released 2026-05-15",
            heroLabel = "v3.2",
            changes = SampleChanges,
            onDismiss = {},
            onAcknowledge = {},
        )
    }
}
