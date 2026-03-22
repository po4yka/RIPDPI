package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.HostPackApplyModeMerge
import com.poyka.ripdpi.data.HostPackApplyModeReplace
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.HostPackTargetBlacklist
import com.poyka.ripdpi.data.HostPackTargetWhitelist
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

internal fun LazyListScope.hostsSection(
    uiState: SettingsUiState,
    hostPackCatalog: HostPackCatalogUiState,
    visualEditorEnabled: Boolean,
    hostPackApplyControlsEnabled: Boolean,
    hostsOptions: List<RipDpiDropdownOption<String>>,
    pendingHostPack: HostPackPreset?,
    onPresetSelected: (HostPackPreset) -> Unit,
    onRefreshHostPackCatalog: () -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_hosts") {
        val colors = RipDpiThemeTokens.colors
        val spacing = RipDpiThemeTokens.spacing

        AdvancedSettingsSection(
            title = stringResource(R.string.ripdpi_hosts_mode_setting),
            testTag = RipDpiTestTags.advancedSection("hosts"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                HostPackCatalogStatusCard(
                    hostPackCatalog = hostPackCatalog,
                    onRefreshCatalog = onRefreshHostPackCatalog,
                )
                RipDpiCard {
                    if (hostPackCatalog.presets.isNotEmpty()) {
                        HostPackPresetSelector(
                            presets = hostPackCatalog.presets,
                            enabled = hostPackApplyControlsEnabled,
                            selectedPresetId = pendingHostPack?.id,
                            onPresetSelected = onPresetSelected,
                        )
                        if (!hostPackApplyControlsEnabled) {
                            Text(
                                text = stringResource(R.string.advanced_settings_visual_controls_disabled),
                                style = RipDpiThemeTokens.type.caption,
                                color = colors.mutedForeground,
                            )
                        }
                        Text(
                            text = stringResource(R.string.host_pack_semantics_note),
                            style = RipDpiThemeTokens.type.caption,
                            color = colors.mutedForeground,
                        )
                        HorizontalDivider(color = colors.divider)
                    }
                    AdvancedDropdownSetting(
                        title = stringResource(R.string.ripdpi_hosts_mode_setting),
                        value = uiState.hostsMode,
                        enabled = visualEditorEnabled,
                        options = hostsOptions,
                        setting = AdvancedOptionSetting.HostsMode,
                        onSelected = onOptionSelected,
                        showDivider = uiState.hostsMode != "disable",
                    )
                    when (uiState.hostsMode) {
                        "blacklist" -> {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_hosts_blacklist_setting),
                                value = uiState.hostsBlacklist,
                                enabled = visualEditorEnabled,
                                multiline = true,
                                disabledMessage =
                                    stringResource(
                                        R.string.advanced_settings_visual_controls_disabled,
                                    ),
                                setting = AdvancedTextSetting.HostsBlacklist,
                                onConfirm = onTextConfirmed,
                            )
                        }

                        "whitelist" -> {
                            AdvancedTextSetting(
                                title = stringResource(R.string.ripdpi_hosts_whitelist_setting),
                                value = uiState.hostsWhitelist,
                                enabled = visualEditorEnabled,
                                multiline = true,
                                disabledMessage =
                                    stringResource(
                                        R.string.advanced_settings_visual_controls_disabled,
                                    ),
                                setting = AdvancedTextSetting.HostsWhitelist,
                                onConfirm = onTextConfirmed,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class HostPackCatalogStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun HostPackCatalogStatusCard(
    hostPackCatalog: HostPackCatalogUiState,
    onRefreshCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHostPackCatalogStatus(hostPackCatalog)
    val generatedAt =
        remember(hostPackCatalog.snapshot.catalog.generatedAt) {
            formatHostPackGeneratedAt(hostPackCatalog.snapshot.catalog.generatedAt)
        }
    val lastFetchedAt =
        remember(hostPackCatalog.snapshot.lastFetchedAtEpochMillis) {
            hostPackCatalog.snapshot.lastFetchedAtEpochMillis?.let(::formatHostPackFetchedAt)
        }
    val downloadedBadge = stringResource(R.string.host_pack_badge_downloaded)
    val bundledBadge = stringResource(R.string.host_pack_badge_bundled)
    val packCountBadge = stringResource(R.string.host_pack_packs_badge, hostPackCatalog.snapshot.packs.size)
    val verifiedBadge = stringResource(R.string.host_pack_badge_checksum_verified)
    val offlineBadge = stringResource(R.string.host_pack_badge_offline_snapshot)
    val badges =
        remember(
            hostPackCatalog.snapshot.source,
            hostPackCatalog.snapshot.packs.size,
            hostPackCatalog.snapshot.lastFetchedAtEpochMillis,
            downloadedBadge,
            bundledBadge,
            packCountBadge,
            verifiedBadge,
            offlineBadge,
        ) {
            buildList {
                add(
                    if (hostPackCatalog.snapshot.source == HostPackCatalogSourceDownloaded) {
                        downloadedBadge to SummaryCapsuleTone.Active
                    } else {
                        bundledBadge to SummaryCapsuleTone.Info
                    },
                )
                add(packCountBadge to SummaryCapsuleTone.Neutral)
                add(
                    if (hostPackCatalog.snapshot.lastFetchedAtEpochMillis != null) {
                        verifiedBadge to SummaryCapsuleTone.Active
                    } else {
                        offlineBadge to SummaryCapsuleTone.Neutral
                    },
                )
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        generatedAt?.let {
            ProfileSummaryLine(
                label = stringResource(R.string.host_pack_snapshot_built_label),
                value = it,
            )
        }
        ProfileSummaryLine(
            label = stringResource(R.string.host_pack_last_fetch_label),
            value = lastFetchedAt ?: stringResource(R.string.host_pack_last_fetch_never),
        )
        Text(
            text = stringResource(R.string.host_pack_refresh_source_hint),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            RipDpiButton(
                text =
                    if (hostPackCatalog.isRefreshing) {
                        stringResource(R.string.host_pack_refresh_in_progress)
                    } else {
                        stringResource(R.string.host_pack_refresh_action)
                    },
                onClick = onRefreshCatalog,
                enabled = hostPackRefreshEnabled(hostPackCatalog),
                loading = hostPackCatalog.isRefreshing,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
private fun rememberHostPackCatalogStatus(hostPackCatalog: HostPackCatalogUiState): HostPackCatalogStatusContent =
    when {
        hostPackCatalog.isRefreshing -> {
            HostPackCatalogStatusContent(
                label = stringResource(R.string.host_pack_refresh_status_title),
                body = stringResource(R.string.host_pack_refresh_status_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        hostPackCatalog.snapshot.source == HostPackCatalogSourceDownloaded -> {
            HostPackCatalogStatusContent(
                label = stringResource(R.string.host_pack_downloaded_status_title),
                body = stringResource(R.string.host_pack_downloaded_status_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            HostPackCatalogStatusContent(
                label = stringResource(R.string.host_pack_bundled_status_title),
                body = stringResource(R.string.host_pack_bundled_status_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }

@Composable
private fun HostPackPresetSelector(
    presets: List<HostPackPreset>,
    enabled: Boolean,
    selectedPresetId: String?,
    onPresetSelected: (HostPackPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.host_pack_presets_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.host_pack_presets_body),
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        presets.forEach { preset ->
            HostPackPresetCard(
                preset = preset,
                enabled = enabled,
                selected = selectedPresetId == preset.id,
                onClick = { onPresetSelected(preset) },
            )
        }
    }
}

@Composable
private fun HostPackPresetCard(
    preset: HostPackPreset,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceSummary = hostPackSourceSummary(preset)
    val description =
        if (sourceSummary.isBlank()) {
            preset.description
        } else {
            stringResource(R.string.host_pack_preset_body, preset.description, sourceSummary)
        }

    PresetCard(
        title = preset.title,
        description = description,
        modifier = modifier,
        badgeText =
            stringResource(
                R.string.host_pack_hosts_badge,
                preset.hostCount.takeIf { it > 0 } ?: preset.hosts.size,
            ),
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun HostPackApplyDialog(
    preset: HostPackPreset,
    targetMode: String,
    applyMode: String,
    onTargetModeChanged: (String) -> Unit,
    onApplyModeChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.host_pack_apply_dialog_title, preset.title),
        dialogTestTag = RipDpiTestTags.HostPackApplyDialog,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.host_pack_apply_dismiss),
                onClick = onDismiss,
                testTag = RipDpiTestTags.HostPackApplyDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.host_pack_apply_confirm),
                onClick = onApply,
                testTag = RipDpiTestTags.HostPackApplyConfirm,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.host_pack_apply_dialog_message),
            ),
    ) {
        HostPackApplyDialogContent(
            preset = preset,
            targetMode = targetMode,
            applyMode = applyMode,
            onTargetModeChanged = onTargetModeChanged,
            onApplyModeChanged = onApplyModeChanged,
        )
    }
}

@Composable
private fun HostPackApplyDialogContent(
    preset: HostPackPreset,
    targetMode: String,
    applyMode: String,
    onTargetModeChanged: (String) -> Unit,
    onApplyModeChanged: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
    ) {
        Text(
            text = hostPackApplySummary(preset),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        HostPackDialogDropdown(
            title = stringResource(R.string.host_pack_target_title),
            value = targetMode,
            options = rememberHostPackTargetOptions(),
            onSelected = onTargetModeChanged,
            testTag = RipDpiTestTags.HostPackTargetDropdown,
            optionTagForValue = RipDpiTestTags.hostPackTargetOption,
        )
        HostPackDialogDropdown(
            title = stringResource(R.string.host_pack_action_title),
            value = applyMode,
            options = rememberHostPackApplyModeOptions(),
            onSelected = onApplyModeChanged,
            testTag = RipDpiTestTags.HostPackApplyModeDropdown,
            optionTagForValue = RipDpiTestTags.hostPackApplyModeOption,
        )
    }
}

@Composable
private fun rememberHostPackTargetOptions(): List<RipDpiDropdownOption<String>> =
    listOf(
        RipDpiDropdownOption(
            value = HostPackTargetBlacklist,
            label = stringResource(R.string.host_pack_target_blacklist),
        ),
        RipDpiDropdownOption(
            value = HostPackTargetWhitelist,
            label = stringResource(R.string.host_pack_target_whitelist),
        ),
    )

@Composable
private fun rememberHostPackApplyModeOptions(): List<RipDpiDropdownOption<String>> =
    listOf(
        RipDpiDropdownOption(
            value = HostPackApplyModeMerge,
            label = stringResource(R.string.host_pack_apply_merge),
        ),
        RipDpiDropdownOption(
            value = HostPackApplyModeReplace,
            label = stringResource(R.string.host_pack_apply_replace),
        ),
    )

@Composable
private fun hostPackApplySummary(preset: HostPackPreset): String {
    val sourceSummary = hostPackSourceSummary(preset)
    val hostCount = preset.hostCount.takeIf { it > 0 } ?: preset.hosts.size
    return if (sourceSummary.isBlank()) {
        stringResource(R.string.host_pack_apply_summary_hosts_only, hostCount)
    } else {
        stringResource(R.string.host_pack_apply_summary, hostCount, sourceSummary)
    }
}

@Composable
private fun HostPackDialogDropdown(
    title: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    optionTagForValue: ((String) -> String)? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = onSelected,
            testTag = testTag,
            optionTagForValue = optionTagForValue,
        )
    }
}
