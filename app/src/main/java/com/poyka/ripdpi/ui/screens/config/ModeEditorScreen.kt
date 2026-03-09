package com.poyka.ripdpi.ui.screens.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigEffect
import com.poyka.ripdpi.activities.ConfigFieldBufferSize
import com.poyka.ripdpi.activities.ConfigFieldDefaultTtl
import com.poyka.ripdpi.activities.ConfigFieldDnsIp
import com.poyka.ripdpi.activities.ConfigFieldMaxConnections
import com.poyka.ripdpi.activities.ConfigFieldProxyIp
import com.poyka.ripdpi.activities.ConfigFieldProxyPort
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ModeEditorRoute(
    onBack: () -> Unit,
    viewModel: ConfigViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val validationMessage = stringResource(R.string.config_validation_fix)
    val handleBack = {
        viewModel.cancelEditing()
        onBack()
    }

    BackHandler(onBack = handleBack)

    LaunchedEffect(Unit) {
        if (uiState.editingPreset == null) {
            viewModel.startEditingPreset()
        }
    }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                ConfigEffect.SaveSuccess -> {
                    onBack()
                }

                ConfigEffect.ValidationFailed -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = validationMessage,
                        tone = RipDpiSnackbarTone.Warning,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    ModeEditorScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        onBack = handleBack,
        onModeSelected = { viewModel.updateDraft { copy(mode = it) } },
        onDnsIpChanged = { viewModel.updateDraft { copy(dnsIp = it) } },
        onProxyIpChanged = { viewModel.updateDraft { copy(proxyIp = it) } },
        onProxyPortChanged = { viewModel.updateDraft { copy(proxyPort = it) } },
        onMaxConnectionsChanged = { viewModel.updateDraft { copy(maxConnections = it) } },
        onBufferSizeChanged = { viewModel.updateDraft { copy(bufferSize = it) } },
        onDesyncMethodChanged = { viewModel.updateDraft { copy(desyncMethod = it) } },
        onDefaultTtlChanged = { viewModel.updateDraft { copy(defaultTtl = it) } },
        onCommandLineEnabledChanged = { viewModel.updateDraft { copy(useCommandLineSettings = it) } },
        onCommandLineArgsChanged = { viewModel.updateDraft { copy(commandLineArgs = it) } },
        onSave = viewModel::saveDraft,
    )
}

@Composable
fun ModeEditorScreen(
    uiState: ConfigUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onModeSelected: (Mode) -> Unit,
    onDnsIpChanged: (String) -> Unit,
    onProxyIpChanged: (String) -> Unit,
    onProxyPortChanged: (String) -> Unit,
    onMaxConnectionsChanged: (String) -> Unit,
    onBufferSizeChanged: (String) -> Unit,
    onDesyncMethodChanged: (String) -> Unit,
    onDefaultTtlChanged: (String) -> Unit,
    onCommandLineEnabledChanged: (Boolean) -> Unit,
    onCommandLineArgsChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val draft = uiState.editingPreset?.draft ?: uiState.draft
    val desyncOptions =
        listOf(
            RipDpiDropdownOption(value = "none", label = stringResource(R.string.config_desync_none)),
            RipDpiDropdownOption(value = "disorder", label = stringResource(R.string.config_desync_disorder)),
            RipDpiDropdownOption(value = "fake", label = stringResource(R.string.config_desync_fake)),
            RipDpiDropdownOption(value = "split", label = stringResource(R.string.config_desync_split)),
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_mode_editor),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
            )
        },
        snackbarHost = {
            RipDpiSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = layout.horizontalPadding),
            )
        },
        bottomBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                        .navigationBarsPadding()
                        .padding(horizontal = layout.horizontalPadding, vertical = spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                RipDpiButton(
                    text = stringResource(R.string.config_cancel),
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    variant = RipDpiButtonVariant.Outline,
                )
                RipDpiButton(
                    text = stringResource(R.string.config_save),
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = layout.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
        ) {
            Spacer(modifier = Modifier.height(spacing.sm))

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

            if (uiState.validationErrors.isNotEmpty()) {
                WarningBanner(
                    title = stringResource(R.string.config_validation_banner_title),
                    message = stringResource(R.string.config_validation_banner_body),
                    tone = WarningBannerTone.Warning,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.config_mode_section))
                RipDpiCard {
                    ConfigModeChips(
                        selectedMode = draft.mode,
                        onModeSelected = onModeSelected,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.config_network_section))
                RipDpiCard {
                    RipDpiTextField(
                        value = draft.dnsIp,
                        onValueChange = onDnsIpChanged,
                        label = stringResource(R.string.dbs_ip_setting),
                        placeholder = stringResource(R.string.config_placeholder_dns),
                        helperText =
                            stringResource(
                                if (draft.mode == Mode.VPN) {
                                    R.string.config_dns_helper
                                } else {
                                    R.string.config_dns_disabled_helper
                                },
                            ),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldDnsIp]),
                        enabled = draft.mode == Mode.VPN,
                    )
                    RipDpiTextField(
                        value = draft.proxyIp,
                        onValueChange = onProxyIpChanged,
                        label = stringResource(R.string.bye_dpi_proxy_ip_setting),
                        placeholder = stringResource(R.string.config_placeholder_proxy_ip),
                        helperText = stringResource(R.string.config_proxy_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldProxyIp]),
                    )
                    RipDpiTextField(
                        value = draft.proxyPort,
                        onValueChange = onProxyPortChanged,
                        label = stringResource(R.string.ripdpi_proxy_port_setting),
                        placeholder = stringResource(R.string.config_placeholder_proxy_port),
                        helperText = stringResource(R.string.config_port_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldProxyPort]),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.config_engine_section))
                RipDpiCard {
                    RipDpiTextField(
                        value = draft.maxConnections,
                        onValueChange = onMaxConnectionsChanged,
                        label = stringResource(R.string.ripdpi_max_connections_setting),
                        helperText = stringResource(R.string.config_max_connections_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldMaxConnections]),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    RipDpiTextField(
                        value = draft.bufferSize,
                        onValueChange = onBufferSizeChanged,
                        label = stringResource(R.string.ripdpi_buffer_size_setting),
                        helperText = stringResource(R.string.config_buffer_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldBufferSize]),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    RipDpiDropdown(
                        options = desyncOptions,
                        selectedValue = draft.desyncMethod,
                        onValueSelected = onDesyncMethodChanged,
                        label = stringResource(R.string.ripdpi_desync_method_setting),
                        helperText = stringResource(R.string.config_desync_helper),
                    )
                    RipDpiTextField(
                        value = draft.defaultTtl,
                        onValueChange = onDefaultTtlChanged,
                        label = stringResource(R.string.ripdpi_default_ttl_setting),
                        placeholder = stringResource(R.string.config_placeholder_default_ttl),
                        helperText = stringResource(R.string.config_default_ttl_helper),
                        errorText = validationMessage(uiState.validationErrors[ConfigFieldDefaultTtl]),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(title = stringResource(R.string.config_overrides_section))
                if (draft.useCommandLineSettings) {
                    WarningBanner(
                        title = stringResource(R.string.config_cli_banner_title),
                        message = stringResource(R.string.config_cli_banner_body),
                        tone = WarningBannerTone.Restricted,
                    )
                }
                RipDpiCard {
                    Text(
                        text = stringResource(R.string.use_command_line_settings),
                        style = RipDpiThemeTokens.type.body,
                        color = colors.foreground,
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.config_command_line_caption),
                                style = RipDpiThemeTokens.type.caption,
                                color = colors.mutedForeground,
                                modifier = Modifier.weight(1f),
                            )
                            RipDpiSwitch(
                                checked = draft.useCommandLineSettings,
                                onCheckedChange = onCommandLineEnabledChanged,
                            )
                        }
                    }
                    RipDpiConfigTextField(
                        value = draft.commandLineArgs,
                        onValueChange = onCommandLineArgsChanged,
                        label = stringResource(R.string.command_line_arguments),
                        placeholder = stringResource(R.string.config_placeholder_command_line),
                        helperText = stringResource(R.string.config_command_line_helper),
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))
        }
    }
}

@Composable
private fun validationMessage(errorKey: String?): String? =
    when (errorKey) {
        "invalid_dns_ip" -> stringResource(R.string.config_error_invalid_dns)
        "invalid_proxy_ip" -> stringResource(R.string.config_error_invalid_proxy_ip)
        "invalid_port" -> stringResource(R.string.config_error_invalid_port)
        "out_of_range" -> stringResource(R.string.config_error_out_of_range)
        else -> null
    }

private fun editorPresetKind(uiState: ConfigUiState): ConfigPresetKind =
    uiState.editingPreset?.kind ?: ConfigPresetKind.Custom

@Preview(showBackground = true)
@Composable
private fun ModeEditorScreenPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.VPN,
            dnsIp = "1.1.1.1",
            proxyIp = "127.0.0.1",
            proxyPort = "1080",
            maxConnections = "512",
            bufferSize = "16384",
            desyncMethod = "disorder",
        )
    RipDpiTheme {
        ModeEditorScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    editingPreset =
                        ConfigPreset(
                            id = "custom",
                            kind = ConfigPresetKind.Custom,
                            draft = draft,
                        ),
                    draft = draft,
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onModeSelected = {},
            onDnsIpChanged = {},
            onProxyIpChanged = {},
            onProxyPortChanged = {},
            onMaxConnectionsChanged = {},
            onBufferSizeChanged = {},
            onDesyncMethodChanged = {},
            onDefaultTtlChanged = {},
            onCommandLineEnabledChanged = {},
            onCommandLineArgsChanged = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ModeEditorScreenDarkPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.Proxy,
            dnsIp = "1.1.1.1",
            proxyIp = "10.0.0.14",
            proxyPort = "1085",
            maxConnections = "1024",
            bufferSize = "32768",
            desyncMethod = "fake",
            defaultTtl = "12",
            useCommandLineSettings = true,
            commandLineArgs = "--fake --ttl 12 --split 2",
        )
    RipDpiTheme(themePreference = "dark") {
        ModeEditorScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    editingPreset =
                        ConfigPreset(
                            id = "recommended",
                            kind = ConfigPresetKind.Recommended,
                            draft = draft,
                        ),
                    draft = draft,
                    validationErrors =
                        mapOf(
                            ConfigFieldDnsIp to "invalid_dns_ip",
                            ConfigFieldDefaultTtl to "out_of_range",
                        ),
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onModeSelected = {},
            onDnsIpChanged = {},
            onProxyIpChanged = {},
            onProxyPortChanged = {},
            onMaxConnectionsChanged = {},
            onBufferSizeChanged = {},
            onDesyncMethodChanged = {},
            onDefaultTtlChanged = {},
            onCommandLineEnabledChanged = {},
            onCommandLineArgsChanged = {},
            onSave = {},
        )
    }
}
