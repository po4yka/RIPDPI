package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun HistoryNoResultsCard(onClearFilters: () -> Unit) {
    HistoryEmptyStateCard(
        title = stringResource(R.string.history_no_results_title),
        body = stringResource(R.string.history_no_results_body),
        actionLabel = stringResource(R.string.history_clear_filters_action),
        actionTestTag = RipDpiTestTags.HistoryEmptyClearFilters,
        onAction = onClearFilters,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.HistoryNoResults),
    )
}

@Composable
internal fun HistoryEmptyStateCard(
    title: String,
    body: String,
    actionLabel: String,
    actionTestTag: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(colors.muted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = RipDpiIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = colors.mutedForeground,
                )
            }
            Text(text = title, style = type.bodyEmphasisBold, color = colors.foreground, textAlign = TextAlign.Center)
            Text(text = body, style = type.body, color = colors.mutedForeground, textAlign = TextAlign.Center)
            RipDpiButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(actionTestTag),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}
