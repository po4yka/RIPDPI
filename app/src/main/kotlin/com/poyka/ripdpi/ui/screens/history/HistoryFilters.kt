package com.poyka.ripdpi.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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
    val hasOptions =
        primaryFilter.options.isNotEmpty() || secondaryFilter.options.isNotEmpty() ||
            (autoScroll != null && onAutoScroll != null)
    val focusManager = LocalFocusManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val activeCount =
        listOf(searchValue.takeIf { it.isNotBlank() }, primaryFilter.selected, secondaryFilter.selected)
            .count { it != null }
    val disclosureState =
        stringResource(if (expanded) R.string.semantic_state_expanded else R.string.semantic_state_collapsed)

    RipDpiCard(modifier = modifier) {
        Text(
            text = title,
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        RipDpiTextField(
            value = searchValue,
            onValueChange = onSearch,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.diagnostics_search_label),
                    placeholder = searchPlaceholder,
                    testTag = searchTestTag,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                ),
        )
        if (hasOptions) {
            RipDpiButton(
                text = stringResource(R.string.history_filter_disclosure, activeCount),
                onClick = { expanded = !expanded },
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Advanced,
                trailingIcon = if (expanded) RipDpiIcons.KeyboardArrowUp else RipDpiIcons.KeyboardArrowDown,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            stateDescription = disclosureState
                            selected = expanded
                        }.ripDpiTestTag(RipDpiTestTags.historyFilterDisclosure(searchTestTag)),
            )
        }
        if (activeCount > 0) {
            ActiveHistoryFilters(searchValue, onSearch, searchTestTag, primaryFilter, secondaryFilter, onClearFilters)
        }
        if (expanded && hasOptions) {
            Column(
                modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.historyFilterOptions(searchTestTag)),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
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
                HistoryChips(config = primaryFilter)
                HistoryChips(config = secondaryFilter)
            }
        }
    }
}

@Composable
private fun ActiveHistoryFilters(
    searchValue: String,
    onSearch: (String) -> Unit,
    searchTestTag: String,
    primaryFilter: HistoryFilterChipsConfig,
    secondaryFilter: HistoryFilterChipsConfig,
    onClearFilters: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (searchValue.isNotBlank()) {
            ActiveHistoryFilter(searchValue, searchTestTag, "query") { onSearch("") }
        }
        primaryFilter.selected?.let { selected ->
            ActiveHistoryFilter(historyFilterLabel(selected), searchTestTag, "primary") {
                primaryFilter.onSelect(selected)
            }
        }
        secondaryFilter.selected?.let { selected ->
            ActiveHistoryFilter(historyFilterLabel(selected), searchTestTag, "secondary") {
                secondaryFilter.onSelect(selected)
            }
        }
    }
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

@Composable
private fun ActiveHistoryFilter(
    label: String,
    searchTag: String,
    key: String,
    onRemove: () -> Unit,
) {
    val removeDescription = stringResource(R.string.history_filter_remove, label)
    RipDpiChip(
        text = label,
        selected = true,
        leadingIcon = RipDpiIcons.Close,
        role = Role.Button,
        wrapLabel = true,
        onClick = onRemove,
        modifier =
            Modifier
                .semantics { contentDescription = removeDescription }
                .ripDpiTestTag(RipDpiTestTags.historyFilterRemove(searchTag, key)),
    )
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
                text = historyFilterLabel(option),
                selected = config.selected == option,
                wrapLabel = true,
                onClick = { config.onSelect(option) },
                modifier = Modifier.ripDpiTestTag(config.tagForOption(option)),
            )
        }
    }
}

@Composable
private fun historyFilterLabel(option: String): String =
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
            stringResource(
                R.string.diagnostics_scope_direct_label,
            )
        }

        com.poyka.ripdpi.diagnostics.ScanPathMode.IN_PATH.name -> {
            stringResource(
                R.string.diagnostics_scope_active_label,
            )
        }

        else -> {
            option.replaceFirstChar { it.uppercase() }
        }
    }
