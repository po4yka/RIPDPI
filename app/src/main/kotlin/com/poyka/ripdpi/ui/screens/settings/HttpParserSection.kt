package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Suppress("LongMethod")
internal fun LazyListScope.httpParserSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onResetHttpParserEvasions: () -> Unit,
) {
    item(key = "advanced_http") {
        val spacing = RipDpiThemeTokens.spacing

        AdvancedSettingsSection(
            title = stringResource(R.string.desync_http_category),
            testTag = RipDpiTestTags.advancedSection("http_parser"),
        ) {
            HttpParserEvasionsProfileCard(
                uiState = uiState,
                onResetHttpParserEvasions = onResetHttpParserEvasions,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            HttpParserToggleGroupCard(
                title = stringResource(R.string.ripdpi_http_parser_safe_group_title),
                description = stringResource(R.string.ripdpi_http_parser_safe_group_body),
                summary = formatHttpParserSafeSummary(uiState),
                statusLabel =
                    stringResource(
                        if (uiState.httpParser.hasSafeHttpParserTweaks) {
                            R.string.ripdpi_http_parser_group_status_active
                        } else {
                            R.string.ripdpi_http_parser_group_status_off
                        },
                    ),
                statusTone =
                    if (uiState.httpParser.hasSafeHttpParserTweaks) {
                        StatusIndicatorTone.Active
                    } else {
                        StatusIndicatorTone.Idle
                    },
                badges =
                    buildList {
                        add(stringResource(R.string.ripdpi_http_parser_badge_http_only) to SummaryCapsuleTone.Info)
                        if (uiState.httpParser.httpParserSafeCount > 0) {
                            add(
                                stringResource(
                                    R.string.ripdpi_http_parser_badge_safe_count,
                                    uiState.httpParser.httpParserSafeCount,
                                ) to SummaryCapsuleTone.Active,
                            )
                        }
                    },
            ) {
                SettingsRow(
                    title = stringResource(R.string.ripdpi_host_mixed_case_setting),
                    checked = uiState.httpParser.hostMixedCase,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HostMixedCase, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HostMixedCase),
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_domain_mixed_case_setting),
                    checked = uiState.httpParser.domainMixedCase,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DomainMixedCase, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DomainMixedCase),
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_host_remove_spaces_setting),
                    checked = uiState.httpParser.hostRemoveSpaces,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HostRemoveSpaces, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HostRemoveSpaces),
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_http_host_pad_setting),
                    checked = uiState.httpParser.httpHostPad,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HttpHostPad, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HttpHostPad),
                )
            }
            HttpParserToggleGroupCard(
                modifier = Modifier.padding(top = spacing.sm),
                title = stringResource(R.string.ripdpi_http_parser_aggressive_group_title),
                description = stringResource(R.string.ripdpi_http_parser_aggressive_group_body),
                summary = formatHttpParserAggressiveSummary(uiState),
                statusLabel =
                    stringResource(
                        if (uiState.httpParser.hasAggressiveHttpParserEvasions) {
                            R.string.ripdpi_http_parser_group_status_warning
                        } else {
                            R.string.ripdpi_http_parser_group_status_off
                        },
                    ),
                statusTone =
                    if (uiState.httpParser.hasAggressiveHttpParserEvasions) {
                        StatusIndicatorTone.Warning
                    } else {
                        StatusIndicatorTone.Idle
                    },
                badges =
                    buildList {
                        add(stringResource(R.string.ripdpi_http_parser_badge_http_only) to SummaryCapsuleTone.Info)
                        add(
                            stringResource(
                                R.string.ripdpi_http_parser_badge_nginx_biased,
                            ) to SummaryCapsuleTone.Warning,
                        )
                        if (uiState.httpParser.httpParserAggressiveCount > 0) {
                            add(
                                stringResource(
                                    R.string.ripdpi_http_parser_badge_aggressive_count,
                                    uiState.httpParser.httpParserAggressiveCount,
                                ) to SummaryCapsuleTone.Warning,
                            )
                        }
                    },
            ) {
                SettingsRow(
                    title = stringResource(R.string.ripdpi_http_method_eol_setting),
                    checked = uiState.httpParser.httpMethodEol,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HttpMethodEol, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HttpMethodEol),
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_http_unix_eol_setting),
                    checked = uiState.httpParser.httpUnixEol,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HttpUnixEol, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HttpUnixEol),
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_http_method_space_setting),
                    checked = uiState.httpParser.httpMethodSpace,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.HttpMethodSpace, it) },
                    enabled = visualEditorEnabled && uiState.desyncHttpEnabled,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.HttpMethodSpace),
                )
            }
        }
    }
}

private data class HttpParserEvasionStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Suppress("LongMethod")
@Composable
private fun HttpParserEvasionsProfileCard(
    uiState: SettingsUiState,
    onResetHttpParserEvasions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHttpParserEvasionStatus(uiState)
    val profileSummary =
        when {
            uiState.httpParser.hasSafeHttpParserTweaks && uiState.httpParser.hasAggressiveHttpParserEvasions -> {
                stringResource(R.string.ripdpi_http_parser_profile_safe_and_aggressive)
            }

            uiState.httpParser.hasAggressiveHttpParserEvasions -> {
                stringResource(R.string.ripdpi_http_parser_profile_aggressive_only)
            }

            uiState.httpParser.hasSafeHttpParserTweaks -> {
                stringResource(R.string.ripdpi_http_parser_profile_safe)
            }

            else -> {
                stringResource(R.string.ripdpi_http_parser_profile_default)
            }
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> {
                stringResource(R.string.ripdpi_http_parser_scope_cli)
            }

            !uiState.httpParserControlsRelevant -> {
                stringResource(R.string.ripdpi_http_parser_scope_http_off)
            }

            uiState.isServiceRunning && uiState.httpParser.hasCustomHttpParserEvasions -> {
                stringResource(R.string.ripdpi_http_parser_scope_restart)
            }

            else -> {
                stringResource(R.string.ripdpi_http_parser_scope_active)
            }
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.httpParser.hasCustomHttpParserEvasions) {
                        stringResource(R.string.ripdpi_http_parser_badge_custom)
                    } else {
                        stringResource(R.string.ripdpi_http_parser_badge_default)
                    }
                ) to
                    if (uiState.httpParser.hasCustomHttpParserEvasions) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.ripdpi_http_parser_badge_http_only) to SummaryCapsuleTone.Info)
            if (uiState.httpParser.httpParserSafeCount > 0) {
                add(
                    stringResource(
                        R.string.ripdpi_http_parser_badge_safe_count,
                        uiState.httpParser.httpParserSafeCount,
                    ) to SummaryCapsuleTone.Active,
                )
            }
            if (uiState.httpParser.httpParserAggressiveCount > 0) {
                add(
                    stringResource(
                        R.string.ripdpi_http_parser_badge_aggressive_count,
                        uiState.httpParser.httpParserAggressiveCount,
                    ) to SummaryCapsuleTone.Warning,
                )
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
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
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_safe),
                value = formatHttpParserSafeSummary(uiState),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_aggressive),
                value = formatHttpParserAggressiveSummary(uiState),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_http_parser_summary_label_probing),
                value = stringResource(R.string.ripdpi_http_parser_probing_summary),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_http_parser_section_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetHttpParserEvasions) {
            var showResetDialog by remember { mutableStateOf(false) }

            if (showResetDialog) {
                RipDpiDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = stringResource(R.string.confirm_reset_http_parser_evasions_title),
                    dismissAction =
                        RipDpiDialogAction(
                            label = stringResource(R.string.confirm_reset_http_parser_evasions_dismiss),
                            onClick = { showResetDialog = false },
                        ),
                    confirmAction =
                        RipDpiDialogAction(
                            label = stringResource(R.string.confirm_reset_http_parser_evasions_confirm),
                            onClick = {
                                showResetDialog = false
                                onResetHttpParserEvasions()
                            },
                        ),
                    visuals =
                        RipDpiDialogVisuals(
                            message = stringResource(R.string.confirm_reset_http_parser_evasions_body),
                            tone = RipDpiDialogTone.Destructive,
                        ),
                )
            }

            RipDpiButton(
                text = stringResource(R.string.ripdpi_http_parser_reset_action),
                onClick = { showResetDialog = true },
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberHttpParserEvasionStatus(uiState: SettingsUiState): HttpParserEvasionStatusContent =
    when {
        uiState.enableCmdSettings -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_cli_title),
                body = stringResource(R.string.ripdpi_http_parser_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.httpParserControlsRelevant && uiState.httpParser.hasCustomHttpParserEvasions -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_saved_title),
                body = stringResource(R.string.ripdpi_http_parser_saved_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.httpParserControlsRelevant -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_http_off_title),
                body = stringResource(R.string.ripdpi_http_parser_http_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.isServiceRunning && uiState.httpParser.hasCustomHttpParserEvasions -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_restart_title),
                body = stringResource(R.string.ripdpi_http_parser_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.httpParser.hasAggressiveHttpParserEvasions -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_aggressive_title),
                body = stringResource(R.string.ripdpi_http_parser_aggressive_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.httpParser.hasSafeHttpParserTweaks -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_safe_title),
                body = stringResource(R.string.ripdpi_http_parser_safe_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            HttpParserEvasionStatusContent(
                label = stringResource(R.string.ripdpi_http_parser_default_title),
                body = stringResource(R.string.ripdpi_http_parser_default_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }

@Composable
private fun HttpParserToggleGroupCard(
    title: String,
    description: String,
    summary: String,
    statusLabel: String,
    statusTone: StatusIndicatorTone,
    badges: List<Pair<String, SummaryCapsuleTone>>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = statusTone,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        SummaryCapsuleFlow(items = badges)
        ProfileSummaryLine(
            label = stringResource(R.string.ripdpi_http_parser_group_summary_label_current),
            value = summary,
        )
        HorizontalDivider(color = colors.divider)
        content()
    }
}

@Composable
private fun formatHttpParserSafeSummary(uiState: SettingsUiState): String =
    buildList {
        if (uiState.httpParser.hostMixedCase) {
            add(stringResource(R.string.ripdpi_host_mixed_case_setting))
        }
        if (uiState.httpParser.domainMixedCase) {
            add(stringResource(R.string.ripdpi_domain_mixed_case_setting))
        }
        if (uiState.httpParser.hostRemoveSpaces) {
            add(stringResource(R.string.ripdpi_host_remove_spaces_setting))
        }
        if (uiState.httpParser.httpHostPad) {
            add(stringResource(R.string.ripdpi_http_host_pad_setting))
        }
    }.joinToString(separator = " · ")
        .ifBlank { stringResource(R.string.ripdpi_http_parser_safe_none) }

@Composable
private fun formatHttpParserAggressiveSummary(uiState: SettingsUiState): String =
    buildList {
        if (uiState.httpParser.httpMethodEol) {
            add(stringResource(R.string.ripdpi_http_method_eol_setting))
        }
        if (uiState.httpParser.httpUnixEol) {
            add(stringResource(R.string.ripdpi_http_unix_eol_setting))
        }
        if (uiState.httpParser.httpMethodSpace) {
            add(stringResource(R.string.ripdpi_http_method_space_setting))
        }
    }.joinToString(separator = " · ")
        .ifBlank { stringResource(R.string.ripdpi_http_parser_aggressive_none) }
