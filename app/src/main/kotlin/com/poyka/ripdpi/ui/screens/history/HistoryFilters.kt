package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal data class HistoryFilterChipsConfig(
    val options: List<String>,
    val selected: String?,
    val onSelect: (String?) -> Unit,
    val tagForOption: (String) -> String,
)

@Composable
internal fun FilterCard(
    modifier: Modifier = Modifier,
    title: String,
    searchValue: String,
    searchPlaceholder: String,
    onSearch: (String) -> Unit,
    searchTestTag: String,
    primaryFilter: HistoryFilterChipsConfig,
    secondaryFilter: HistoryFilterChipsConfig,
    onClearFilters: () -> Unit = {},
    autoScroll: Boolean? = null,
    onAutoScroll: ((Boolean) -> Unit)? = null,
    autoScrollTestTag: String? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    var searchReplacementRevision by remember { mutableLongStateOf(0L) }
    val hasActiveFilters =
        searchValue.isNotBlank() || primaryFilter.selected != null || secondaryFilter.selected != null

    RipDpiCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = RipDpiThemeTokens.type.sectionTitle,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            if (hasActiveFilters) {
                Text(
                    text = stringResource(R.string.history_filter_clear_all),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.info,
                    modifier =
                        Modifier
                            .ripDpiClickable(
                                role = Role.Button,
                                onClick = {
                                    searchReplacementRevision++
                                    onClearFilters()
                                },
                            ).padding(horizontal = spacing.sm, vertical = spacing.xs)
                            .ripDpiTestTag(RipDpiTestTags.HistoryFilterClearAll),
                )
            }
        }
        RipDpiTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = searchValue,
                    onValueChange = onSearch,
                    replacementKey = searchReplacementRevision,
                ),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_search_label),
                    placeholder = searchPlaceholder,
                    testTag = searchTestTag,
                ),
        )
        if (autoScroll != null && onAutoScroll != null) {
            SettingsRow(
                title = stringResource(R.string.logs_auto_scroll_title),
                subtitle = stringResource(R.string.logs_auto_scroll_body),
                checked = autoScroll,
                onCheckedChange = onAutoScroll,
                showDivider = true,
                testTag = autoScrollTestTag,
            )
        }
        HistoryChips(
            config = primaryFilter,
        )
        if (secondaryFilter.options.isNotEmpty()) {
            HistoryChips(
                config = secondaryFilter,
            )
        }
    }
}

@Composable
private fun HistoryChips(config: HistoryFilterChipsConfig) {
    val spacing = RipDpiThemeTokens.spacing
    if (config.options.isEmpty()) {
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        config.options.forEach { option ->
            RipDpiChip(
                text = option.replaceFirstChar { it.uppercase() },
                selected = config.selected == option,
                onClick = { config.onSelect(option) },
                modifier = Modifier.ripDpiTestTag(config.tagForOption(option)),
            )
        }
    }
}
