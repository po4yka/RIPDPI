package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
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
    val hasActiveFilters =
        searchValue.isNotBlank() || primaryFilter.selected != null || secondaryFilter.selected != null

    RipDpiCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
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
                            .width(IntrinsicSize.Max)
                            .heightIn(min = 48.dp)
                            .ripDpiClickable(role = Role.Button, onClick = onClearFilters)
                            .padding(horizontal = spacing.sm, vertical = spacing.xs)
                            .ripDpiTestTag(RipDpiTestTags.HistoryFilterClearAll),
                )
            }
        }
        RipDpiTextField(
            value = searchValue,
            onValueChange = onSearch,
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
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        config.options.forEach { option ->
            RipDpiChip(
                text =
                    when (option) {
                        "completed" -> {
                            stringResource(R.string.diagnostics_scan_completion_normal)
                        }

                        "running" -> {
                            stringResource(R.string.diagnostics_status_running)
                        }

                        "failed" -> {
                            stringResource(R.string.diagnostics_stages_failed)
                        }

                        "cancelled" -> {
                            stringResource(R.string.vpn_replay_history_verdict_cancelled)
                        }

                        com.poyka.ripdpi.diagnostics.ScanPathMode.RAW_PATH.name -> {
                            stringResource(R.string.diagnostics_scope_direct_label)
                        }

                        com.poyka.ripdpi.diagnostics.ScanPathMode.IN_PATH.name -> {
                            stringResource(R.string.diagnostics_scope_active_label)
                        }

                        else -> {
                            option.replaceFirstChar { it.uppercase() }
                        }
                    },
                selected = config.selected == option,
                wrapLabel = true,
                onClick = { config.onSelect(option) },
                modifier = Modifier.ripDpiTestTag(config.tagForOption(option)),
            )
        }
    }
}
