package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldBufferSize
import com.poyka.ripdpi.activities.ConfigFieldDefaultTtl
import com.poyka.ripdpi.activities.ConfigFieldMaxConnections
import com.poyka.ripdpi.activities.ConfigFieldProxyIp
import com.poyka.ripdpi.activities.ConfigFieldProxyPort
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.AdvancedSection
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldReplacementScope
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun ModeEditorBottomBar(
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = layout.formMaxWidth)
                    .padding(horizontal = layout.horizontalPadding, vertical = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.config_cancel),
                onClick = actions.onCancel,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.ModeEditorCancel),
                variant = RipDpiButtonVariant.Outline,
                enabled = !uiState.isEditorSaving,
            )
            RipDpiButton(
                text = stringResource(R.string.config_save),
                onClick = actions.onSave,
                loading = uiState.isEditorSaving,
                enabled = !uiState.isEditorSaving && !uiState.isEditorImporting,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.ModeEditorSave),
            )
        }
    }
}

@Composable
internal fun ModeEditorBody(
    uiState: ConfigUiState,
    actions: ModeEditorActions,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val draft = uiState.editingPreset?.draft ?: uiState.draft

    RipDpiTextFieldReplacementScope(replacementKey = uiState.textFieldReplacementRevision) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(colors.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = layout.formMaxWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = layout.horizontalPadding,
                            top = spacing.sm,
                            end = layout.horizontalPadding,
                            bottom = spacing.sm,
                        ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
            ) {
                ModeEditorIntroCard(uiState = uiState)
                ModeEditorRecoveryPersistenceBanner(uiState = uiState)
                ModeEditorValidationBanner(uiState = uiState)
                ModeEditorModeSection(draft = draft, actions = actions)
                ModeEditorNetworkSection(draft = draft, uiState = uiState, actions = actions)
                ModeEditorRelaySection(draft = draft, uiState = uiState, actions = actions)
                ModeEditorAdvancedSection(draft = draft, uiState = uiState, actions = actions)
            }
        }
    }
}

@Composable
private fun ModeEditorRecoveryPersistenceBanner(uiState: ConfigUiState) {
    if (uiState.hasEditorRecoveryPersistenceError) {
        WarningBanner(
            title = stringResource(R.string.strategy_config_draft_save_failed_title),
            message = stringResource(R.string.strategy_config_draft_save_failed_body),
            tone = WarningBannerTone.Warning,
        )
    }
}

@Composable
private fun ModeEditorIntroCard(uiState: ConfigUiState) {
    val colors = RipDpiThemeTokens.colors

    RipDpiCard {
        Text(
            text = stringResource(R.string.config_editor_title),
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        Text(
            text =
                stringResource(
                    R.string.config_editor_body,
                    stringResource(titleResForPreset(editorPresetKind(uiState))),
                ),
            style = RipDpiThemeTokens.type.body,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun ModeEditorValidationBanner(uiState: ConfigUiState) {
    if (uiState.validationErrors.isNotEmpty()) {
        WarningBanner(
            title = stringResource(R.string.config_validation_banner_title),
            message = stringResource(R.string.config_validation_banner_body),
            tone = WarningBannerTone.Warning,
        )
    }
}

@Composable
private fun ModeEditorModeSection(
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.config_mode_section))
        RipDpiCard {
            ConfigModeChips(
                selectedMode = draft.mode,
                onModeSelected = actions.onModeSelected,
            )
        }
    }
}

@Composable
private fun ModeEditorNetworkSection(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.config_network_section))
        RipDpiCard {
            Text(
                text = stringResource(R.string.title_dns_settings),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = draft.dnsSummary,
                style = RipDpiThemeTokens.type.monoValue,
                color = colors.foreground,
            )
            Text(
                text =
                    stringResource(
                        if (draft.mode == Mode.VPN) {
                            R.string.config_dns_summary_enabled
                        } else {
                            R.string.config_dns_summary_disabled
                        },
                    ),
                style = RipDpiThemeTokens.type.body,
                color = colors.mutedForeground,
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = draft.proxyIp,
                        onValueChange = actions.onProxyIpChanged,
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.bye_dpi_proxy_ip_setting),
                        placeholder = stringResource(R.string.config_placeholder_proxy_ip),
                        helperText = stringResource(R.string.config_proxy_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldProxyIp]),
                        testTag = RipDpiTestTags.ModeEditorProxyIp,
                    ),
            )
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = draft.proxyPort,
                        onValueChange = actions.onProxyPortChanged,
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.ripdpi_proxy_port_setting),
                        placeholder = stringResource(R.string.config_placeholder_proxy_port),
                        helperText = stringResource(R.string.config_port_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldProxyPort]),
                        testTag = RipDpiTestTags.ModeEditorProxyPort,
                    ),
                behavior =
                    RipDpiTextFieldBehavior(
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    ),
            )
        }
    }
}

@Composable
private fun ModeEditorAdvancedSection(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val spacing = RipDpiThemeTokens.spacing

    AdvancedSection(testTag = RipDpiTestTags.ModeEditorAdvanced) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            ModeEditorEngineSection(draft = draft, uiState = uiState, actions = actions)
            ModeEditorOverridesSection(draft = draft, actions = actions)
        }
    }
}

@Composable
private fun ModeEditorEngineSection(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.config_engine_section))
        ModeEditorEngineFields(draft = draft, uiState = uiState, actions = actions)
    }
}

@Composable
private fun ModeEditorEngineFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        RipDpiTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = draft.maxConnections,
                    onValueChange = actions.onMaxConnectionsChanged,
                ),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.ripdpi_max_connections_setting),
                    helperText = stringResource(R.string.config_max_connections_helper),
                    errorText = validationMessage(uiState.validationErrors[ConfigFieldMaxConnections]),
                    testTag = RipDpiTestTags.ModeEditorMaxConnections,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                ),
        )
        RipDpiTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = draft.bufferSize,
                    onValueChange = actions.onBufferSizeChanged,
                ),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.ripdpi_buffer_size_setting),
                    helperText = stringResource(R.string.config_buffer_helper),
                    errorText = validationMessage(uiState.validationErrors[ConfigFieldBufferSize]),
                    testTag = RipDpiTestTags.ModeEditorBufferSize,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                ),
        )
        ModeEditorChainFields(draft = draft, uiState = uiState, actions = actions)
        RipDpiTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = draft.defaultTtl,
                    onValueChange = actions.onDefaultTtlChanged,
                ),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.ripdpi_default_ttl_setting),
                    placeholder = stringResource(R.string.config_placeholder_default_ttl),
                    helperText = stringResource(R.string.config_default_ttl_helper),
                    errorText = validationMessage(uiState.validationErrors[ConfigFieldDefaultTtl]),
                    testTag = RipDpiTestTags.ModeEditorDefaultTtl,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                ),
        )
    }
}

@Composable
private fun ModeEditorChainFields(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    ModeEditorChainBlockEditor(draft = draft, uiState = uiState, actions = actions)
}

@Composable
private fun ModeEditorOverridesSection(
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.config_overrides_section))
        ModeEditorOverrideFields(draft = draft, actions = actions)
    }
}

@Composable
private fun ModeEditorOverrideFields(
    draft: ConfigDraft,
    actions: ModeEditorActions,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (draft.useCommandLineSettings) {
            WarningBanner(
                title = stringResource(R.string.config_cli_banner_title),
                message = stringResource(R.string.config_cli_banner_body),
                tone = WarningBannerTone.Restricted,
            )
        }
        Text(
            text = stringResource(R.string.use_command_line_settings),
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.config_command_line_caption),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
                RipDpiSwitch(
                    checked = draft.useCommandLineSettings,
                    onCheckedChange = actions.onCommandLineEnabledChanged,
                    accessibilityLabel = stringResource(R.string.use_command_line_settings),
                    testTag = RipDpiTestTags.ModeEditorCommandLineToggle,
                )
            }
        }
        RipDpiConfigTextField(
            state =
                rememberRipDpiTextFieldState(
                    value = draft.commandLineArgs,
                    onValueChange = actions.onCommandLineArgsChanged,
                ),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.command_line_arguments),
                    placeholder = stringResource(R.string.config_placeholder_command_line),
                    helperText = stringResource(R.string.config_command_line_helper),
                    testTag = RipDpiTestTags.ModeEditorCommandLineArgs,
                ),
        )
    }
}
