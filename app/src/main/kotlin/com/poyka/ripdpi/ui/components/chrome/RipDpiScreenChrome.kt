package com.poyka.ripdpi.ui.components.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Immutable
data class RipDpiTelemetryEntry(
    val label: String,
    val value: String,
    val supporting: String? = null,
    val monospaceValue: Boolean = false,
)

@Composable
fun RipDpiScreenSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    showDivider: Boolean = false,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = title.uppercase(),
                style = RipDpiThemeTokens.type.sectionTitle,
                color = colors.mutedForeground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            trailingContent?.invoke(this)
        }
        supporting?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

@Composable
fun RipDpiPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            trailingContent?.invoke(this)
        }
        supporting?.let {
            Text(
                text = it,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
fun RipDpiEmptyStateCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    variant: RipDpiCardVariant = RipDpiCardVariant.Outlined,
    bodyMaxLines: Int = Int.MAX_VALUE,
    bodyOverflow: TextOverflow = TextOverflow.Clip,
) {
    val colors = RipDpiThemeTokens.colors

    RipDpiCard(
        modifier = modifier,
        variant = variant,
    ) {
        RipDpiPanelHeader(title = title)
        Text(
            text = body,
            style = RipDpiThemeTokens.type.body,
            color = colors.mutedForeground,
            maxLines = bodyMaxLines,
            overflow = bodyOverflow,
        )
    }
}

@Composable
fun RipDpiTelemetryRows(
    entries: List<RipDpiTelemetryEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, entry ->
            SettingsRow(
                title = entry.label,
                subtitle = entry.supporting,
                value = entry.value,
                monospaceValue = entry.monospaceValue,
                showDivider = index != entries.lastIndex,
            )
        }
    }
}

@Composable
fun RipDpiRemediationCard(
    title: String,
    summary: String,
    steps: List<String>,
    tone: StatusIndicatorTone,
    modifier: Modifier = Modifier,
    cardVariant: RipDpiCardVariant = RipDpiCardVariant.Elevated,
    cardTestTag: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionVariant: RipDpiButtonVariant = RipDpiButtonVariant.Secondary,
    actionTestTag: String? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors

    RipDpiCard(
        modifier =
            modifier.then(
                if (cardTestTag != null) {
                    Modifier.ripDpiTestTag(cardTestTag)
                } else {
                    Modifier
                },
            ),
        variant = cardVariant,
    ) {
        StatusIndicator(
            label = title,
            tone = tone,
        )
        Text(
            text = summary,
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        if (steps.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                steps.forEach { step ->
                    Text(
                        text = "\u2022 $step",
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
        if (actionLabel != null && onAction != null) {
            RipDpiButton(
                text = actionLabel,
                onClick = onAction,
                variant = actionVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.xs)
                        .then(
                            if (actionTestTag != null) {
                                Modifier.ripDpiTestTag(actionTestTag)
                            } else {
                                Modifier
                            },
                        ),
            )
        }
    }
}
