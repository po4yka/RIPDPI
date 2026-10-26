package com.poyka.ripdpi.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.chrome.RipDpiEmptyStateCard
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag

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
    RipDpiEmptyStateCard(
        title = title,
        body = body,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
        actionTestTag = actionTestTag,
    )
}
