package com.poyka.ripdpi.ui.screens.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
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
import com.poyka.ripdpi.activities.ConfigFieldRelayChainEntryPort
import com.poyka.ripdpi.activities.ConfigFieldRelayChainExitPort
import com.poyka.ripdpi.activities.ConfigFieldRelayCredentials
import com.poyka.ripdpi.activities.ConfigFieldRelayLocalSocksPort
import com.poyka.ripdpi.activities.ConfigFieldRelayServer
import com.poyka.ripdpi.activities.ConfigFieldRelayServerPort
import com.poyka.ripdpi.activities.ConfigFieldStrategyChain
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.collectLatest

@Suppress("LongMethod")
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

    val currentOnBack by rememberUpdatedState(onBack)
    val performHaptic = rememberRipDpiHapticPerformer()

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                ConfigEffect.SaveSuccess -> {
                    performHaptic(RipDpiHapticFeedback.Success)
                    currentOnBack()
                }

                ConfigEffect.ValidationFailed -> {
                    performHaptic(RipDpiHapticFeedback.Error)
                    snackbarHostState.showRipDpiSnackbar(
                        message = validationMessage,
                        tone = RipDpiSnackbarTone.Warning,
                        duration = SnackbarDuration.Short,
                        testTag = RipDpiTestTags.ModeEditorValidationSnackbar,
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
        onChainDslChanged = viewModel::updateChainDsl,
        onDefaultTtlChanged = { viewModel.updateDraft { copy(defaultTtl = it) } },
        onCommandLineEnabledChanged = { viewModel.updateDraft { copy(useCommandLineSettings = it) } },
        onCommandLineArgsChanged = { viewModel.updateDraft { copy(commandLineArgs = it) } },
        onRelayEnabledChanged = { viewModel.updateDraft { copy(relayEnabled = it) } },
        onRelayKindChanged = { viewModel.updateDraft { copy(relayKind = it) } },
        onRelayProfileIdChanged = { viewModel.updateDraft { copy(relayProfileId = it) } },
        onRelayServerChanged = { viewModel.updateDraft { copy(relayServer = it) } },
        onRelayServerPortChanged = { viewModel.updateDraft { copy(relayServerPort = it) } },
        onRelayServerNameChanged = { viewModel.updateDraft { copy(relayServerName = it) } },
        onRelayRealityPublicKeyChanged = { viewModel.updateDraft { copy(relayRealityPublicKey = it) } },
        onRelayRealityShortIdChanged = { viewModel.updateDraft { copy(relayRealityShortId = it) } },
        onRelayVlessUuidChanged = { viewModel.updateDraft { copy(relayVlessUuid = it) } },
        onRelayHysteriaPasswordChanged = { viewModel.updateDraft { copy(relayHysteriaPassword = it) } },
        onRelayHysteriaSalamanderKeyChanged = { viewModel.updateDraft { copy(relayHysteriaSalamanderKey = it) } },
        onRelayChainEntryServerChanged = { viewModel.updateDraft { copy(relayChainEntryServer = it) } },
        onRelayChainEntryPortChanged = { viewModel.updateDraft { copy(relayChainEntryPort = it) } },
        onRelayChainEntryServerNameChanged = { viewModel.updateDraft { copy(relayChainEntryServerName = it) } },
        onRelayChainEntryPublicKeyChanged = { viewModel.updateDraft { copy(relayChainEntryPublicKey = it) } },
        onRelayChainEntryShortIdChanged = { viewModel.updateDraft { copy(relayChainEntryShortId = it) } },
        onRelayChainEntryUuidChanged = { viewModel.updateDraft { copy(relayChainEntryUuid = it) } },
        onRelayChainExitServerChanged = { viewModel.updateDraft { copy(relayChainExitServer = it) } },
        onRelayChainExitPortChanged = { viewModel.updateDraft { copy(relayChainExitPort = it) } },
        onRelayChainExitServerNameChanged = { viewModel.updateDraft { copy(relayChainExitServerName = it) } },
        onRelayChainExitPublicKeyChanged = { viewModel.updateDraft { copy(relayChainExitPublicKey = it) } },
        onRelayChainExitShortIdChanged = { viewModel.updateDraft { copy(relayChainExitShortId = it) } },
        onRelayChainExitUuidChanged = { viewModel.updateDraft { copy(relayChainExitUuid = it) } },
        onRelayMasqueUrlChanged = { viewModel.updateDraft { copy(relayMasqueUrl = it) } },
        onRelayMasqueAuthTokenChanged = { viewModel.updateDraft { copy(relayMasqueAuthToken = it) } },
        onRelayMasqueUseHttp2FallbackChanged = { viewModel.updateDraft { copy(relayMasqueUseHttp2Fallback = it) } },
        onRelayMasqueCloudflareModeChanged = { viewModel.updateDraft { copy(relayMasqueCloudflareMode = it) } },
        onRelayLocalSocksPortChanged = { viewModel.updateDraft { copy(relayLocalSocksPort = it) } },
        onSave = viewModel::saveDraft,
    )
}

@Suppress("LongMethod", "LongParameterList", "UnusedParameter")
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
    onChainDslChanged: (String) -> Unit,
    onDefaultTtlChanged: (String) -> Unit,
    onCommandLineEnabledChanged: (Boolean) -> Unit,
    onCommandLineArgsChanged: (String) -> Unit,
    onRelayEnabledChanged: (Boolean) -> Unit,
    onRelayKindChanged: (String) -> Unit,
    onRelayProfileIdChanged: (String) -> Unit,
    onRelayServerChanged: (String) -> Unit,
    onRelayServerPortChanged: (String) -> Unit,
    onRelayServerNameChanged: (String) -> Unit,
    onRelayRealityPublicKeyChanged: (String) -> Unit,
    onRelayRealityShortIdChanged: (String) -> Unit,
    onRelayVlessUuidChanged: (String) -> Unit,
    onRelayHysteriaPasswordChanged: (String) -> Unit,
    onRelayHysteriaSalamanderKeyChanged: (String) -> Unit,
    onRelayChainEntryServerChanged: (String) -> Unit,
    onRelayChainEntryPortChanged: (String) -> Unit,
    onRelayChainEntryServerNameChanged: (String) -> Unit,
    onRelayChainEntryPublicKeyChanged: (String) -> Unit,
    onRelayChainEntryShortIdChanged: (String) -> Unit,
    onRelayChainEntryUuidChanged: (String) -> Unit,
    onRelayChainExitServerChanged: (String) -> Unit,
    onRelayChainExitPortChanged: (String) -> Unit,
    onRelayChainExitServerNameChanged: (String) -> Unit,
    onRelayChainExitPublicKeyChanged: (String) -> Unit,
    onRelayChainExitShortIdChanged: (String) -> Unit,
    onRelayChainExitUuidChanged: (String) -> Unit,
    onRelayMasqueUrlChanged: (String) -> Unit,
    onRelayMasqueAuthTokenChanged: (String) -> Unit,
    onRelayMasqueUseHttp2FallbackChanged: (Boolean) -> Unit,
    onRelayMasqueCloudflareModeChanged: (Boolean) -> Unit,
    onRelayLocalSocksPortChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val draft = uiState.editingPreset?.draft ?: uiState.draft

    RipDpiScreenScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.ModeEditor))
                .fillMaxSize(),
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_mode_editor),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
            )
        },
        snackbarHost = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                RipDpiSnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = layout.formMaxWidth)
                            .padding(horizontal = layout.horizontalPadding),
                )
            }
        },
        bottomBar = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                        .navigationBarsPadding()
                        .imePadding(),
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
                        onClick = onBack,
                        modifier =
                            Modifier
                                .weight(1f)
                                .ripDpiTestTag(RipDpiTestTags.ModeEditorCancel),
                        variant = RipDpiButtonVariant.Outline,
                    )
                    RipDpiButton(
                        text = stringResource(R.string.config_save),
                        onClick = onSave,
                        modifier =
                            Modifier
                                .weight(1f)
                                .ripDpiTestTag(RipDpiTestTags.ModeEditorSave),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(innerPadding),
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
                            value = draft.proxyIp,
                            onValueChange = onProxyIpChanged,
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
                            value = draft.proxyPort,
                            onValueChange = onProxyPortChanged,
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

                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    SettingsCategoryHeader(title = stringResource(R.string.config_relay_section))
                    RipDpiCard {
                        Text(
                            text = stringResource(R.string.config_relay_title),
                            style = RipDpiThemeTokens.type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text = stringResource(R.string.config_relay_body),
                            style = RipDpiThemeTokens.type.body,
                            color = colors.mutedForeground,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.config_relay_enable),
                                style = RipDpiThemeTokens.type.body,
                                color = colors.foreground,
                                modifier = Modifier.weight(1f),
                            )
                            RipDpiSwitch(
                                checked = draft.relayEnabled,
                                onCheckedChange = onRelayEnabledChanged,
                            )
                        }
                        if (draft.relayEnabled) {
                            RipDpiTextField(
                                value = draft.relayProfileId,
                                onValueChange = onRelayProfileIdChanged,
                                decoration =
                                    RipDpiTextFieldDecoration(
                                        label = stringResource(R.string.config_relay_profile_id),
                                        helperText = stringResource(R.string.config_relay_profile_id_helper),
                                    ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindVlessReality,
                                    R.string.config_relay_kind_vless,
                                    onRelayKindChanged,
                                )
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindHysteria2,
                                    R.string.config_relay_kind_hysteria2,
                                    onRelayKindChanged,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindChainRelay,
                                    R.string.config_relay_kind_chain,
                                    onRelayKindChanged,
                                )
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindMasque,
                                    R.string.config_relay_kind_masque,
                                    onRelayKindChanged,
                                )
                            }
                            if (uiState.validationErrors[ConfigFieldRelayCredentials] != null) {
                                WarningBanner(
                                    title = stringResource(R.string.config_relay_credentials_title),
                                    message = stringResource(R.string.config_relay_credentials_body),
                                    tone = WarningBannerTone.Warning,
                                )
                            }
                            when (draft.relayKind) {
                                RelayKindVlessReality,
                                RelayKindHysteria2,
                                -> {
                                    RipDpiTextField(
                                        value = draft.relayServer,
                                        onValueChange = onRelayServerChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_server),
                                                errorText =
                                                    validationMessage(
                                                        uiState.validationErrors[ConfigFieldRelayServer],
                                                    ),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayServerPort,
                                        onValueChange = onRelayServerPortChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_server_port),
                                                errorText =
                                                    validationMessage(
                                                        uiState.validationErrors[ConfigFieldRelayServerPort],
                                                    ),
                                            ),
                                        behavior =
                                            RipDpiTextFieldBehavior(
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayServerName,
                                        onValueChange = onRelayServerNameChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_server_name),
                                            ),
                                    )
                                }

                                else -> {
                                    Unit
                                }
                            }
                            when (draft.relayKind) {
                                RelayKindVlessReality -> {
                                    RipDpiTextField(
                                        value = draft.relayRealityPublicKey,
                                        onValueChange = onRelayRealityPublicKeyChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_reality_public_key),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayRealityShortId,
                                        onValueChange = onRelayRealityShortIdChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_reality_short_id),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayVlessUuid,
                                        onValueChange = onRelayVlessUuidChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_vless_uuid),
                                            ),
                                    )
                                }

                                RelayKindHysteria2 -> {
                                    RipDpiTextField(
                                        value = draft.relayHysteriaPassword,
                                        onValueChange = onRelayHysteriaPasswordChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_hysteria_password),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayHysteriaSalamanderKey,
                                        onValueChange = onRelayHysteriaSalamanderKeyChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_hysteria_salamander),
                                            ),
                                    )
                                }

                                RelayKindChainRelay -> {
                                    RipDpiTextField(
                                        value = draft.relayChainEntryServer,
                                        onValueChange = onRelayChainEntryServerChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_entry_server),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainEntryPort,
                                        onValueChange = onRelayChainEntryPortChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_entry_port),
                                                errorText =
                                                    validationMessage(
                                                        uiState.validationErrors[ConfigFieldRelayChainEntryPort],
                                                    ),
                                            ),
                                        behavior =
                                            RipDpiTextFieldBehavior(
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainEntryServerName,
                                        onValueChange = onRelayChainEntryServerNameChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_entry_sni),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainEntryPublicKey,
                                        onValueChange = onRelayChainEntryPublicKeyChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_entry_public_key),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainEntryShortId,
                                        onValueChange = onRelayChainEntryShortIdChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_entry_short_id),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainEntryUuid,
                                        onValueChange = onRelayChainEntryUuidChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_entry_uuid),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainExitServer,
                                        onValueChange = onRelayChainExitServerChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_exit_server),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainExitPort,
                                        onValueChange = onRelayChainExitPortChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_exit_port),
                                                errorText =
                                                    validationMessage(
                                                        uiState.validationErrors[ConfigFieldRelayChainExitPort],
                                                    ),
                                            ),
                                        behavior =
                                            RipDpiTextFieldBehavior(
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainExitServerName,
                                        onValueChange = onRelayChainExitServerNameChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_exit_sni),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainExitPublicKey,
                                        onValueChange = onRelayChainExitPublicKeyChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_exit_public_key),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainExitShortId,
                                        onValueChange = onRelayChainExitShortIdChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_exit_short_id),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayChainExitUuid,
                                        onValueChange = onRelayChainExitUuidChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_chain_exit_uuid),
                                            ),
                                    )
                                }

                                RelayKindMasque -> {
                                    RipDpiTextField(
                                        value = draft.relayMasqueUrl,
                                        onValueChange = onRelayMasqueUrlChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_masque_url),
                                            ),
                                    )
                                    RipDpiTextField(
                                        value = draft.relayMasqueAuthToken,
                                        onValueChange = onRelayMasqueAuthTokenChanged,
                                        decoration =
                                            RipDpiTextFieldDecoration(
                                                label = stringResource(R.string.config_relay_masque_token),
                                            ),
                                    )
                                    RipDpiSwitch(
                                        checked = draft.relayMasqueUseHttp2Fallback,
                                        onCheckedChange = onRelayMasqueUseHttp2FallbackChanged,
                                        label = stringResource(R.string.config_relay_masque_http2),
                                    )
                                    RipDpiSwitch(
                                        checked = draft.relayMasqueCloudflareMode,
                                        onCheckedChange = onRelayMasqueCloudflareModeChanged,
                                        label = stringResource(R.string.config_relay_masque_cloudflare),
                                    )
                                }
                            }
                            RipDpiTextField(
                                value = draft.relayLocalSocksPort,
                                onValueChange = onRelayLocalSocksPortChanged,
                                decoration =
                                    RipDpiTextFieldDecoration(
                                        label = stringResource(R.string.config_relay_local_port),
                                        helperText = stringResource(R.string.config_relay_local_port_helper),
                                        errorText =
                                            validationMessage(
                                                uiState.validationErrors[ConfigFieldRelayLocalSocksPort],
                                            ),
                                    ),
                                behavior =
                                    RipDpiTextFieldBehavior(
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    ),
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    SettingsCategoryHeader(title = stringResource(R.string.config_engine_section))
                    RipDpiCard {
                        RipDpiTextField(
                            value = draft.maxConnections,
                            onValueChange = onMaxConnectionsChanged,
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
                            value = draft.bufferSize,
                            onValueChange = onBufferSizeChanged,
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
                        Text(
                            text = stringResource(R.string.config_chain_summary_label, draft.chainSummary),
                            style = RipDpiThemeTokens.type.caption,
                            color = colors.mutedForeground,
                        )
                        RipDpiConfigTextField(
                            value = draft.chainDsl,
                            onValueChange = onChainDslChanged,
                            decoration =
                                RipDpiTextFieldDecoration(
                                    label = stringResource(R.string.config_chain_editor_label),
                                    placeholder = stringResource(R.string.config_placeholder_chain_dsl),
                                    helperText = stringResource(R.string.config_chain_editor_helper),
                                    errorText = validationMessage(uiState.validationErrors[ConfigFieldStrategyChain]),
                                    testTag = RipDpiTestTags.ModeEditorChainDsl,
                                ),
                            multiline = true,
                            behavior =
                                RipDpiTextFieldBehavior(
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                ),
                        )
                        RipDpiTextField(
                            value = draft.defaultTtl,
                            onValueChange = onDefaultTtlChanged,
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
                                    onCheckedChange = onCommandLineEnabledChanged,
                                    testTag = RipDpiTestTags.ModeEditorCommandLineToggle,
                                )
                            }
                        }
                        RipDpiConfigTextField(
                            value = draft.commandLineArgs,
                            onValueChange = onCommandLineArgsChanged,
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
            }
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
        "invalid_chain" -> stringResource(R.string.config_error_invalid_chain)
        "required" -> stringResource(R.string.config_error_required)
        "unsupported" -> stringResource(R.string.config_error_unsupported)
        else -> null
    }

@Composable
private fun RowScope.RelayKindChip(
    selectedKind: String,
    kind: String,
    labelRes: Int,
    onRelayKindChanged: (String) -> Unit,
) {
    RipDpiChip(
        text = stringResource(labelRes),
        selected = selectedKind == kind,
        onClick = { onRelayKindChanged(kind) },
        modifier = Modifier.weight(1f),
    )
}

private fun editorPresetKind(uiState: ConfigUiState): ConfigPresetKind =
    uiState.editingPreset?.kind ?: ConfigPresetKind.Custom

@Suppress("UnusedPrivateMember")
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
            onChainDslChanged = {},
            onDefaultTtlChanged = {},
            onCommandLineEnabledChanged = {},
            onCommandLineArgsChanged = {},
            onRelayEnabledChanged = {},
            onRelayKindChanged = {},
            onRelayProfileIdChanged = {},
            onRelayServerChanged = {},
            onRelayServerPortChanged = {},
            onRelayServerNameChanged = {},
            onRelayRealityPublicKeyChanged = {},
            onRelayRealityShortIdChanged = {},
            onRelayVlessUuidChanged = {},
            onRelayHysteriaPasswordChanged = {},
            onRelayHysteriaSalamanderKeyChanged = {},
            onRelayChainEntryServerChanged = {},
            onRelayChainEntryPortChanged = {},
            onRelayChainEntryServerNameChanged = {},
            onRelayChainEntryPublicKeyChanged = {},
            onRelayChainEntryShortIdChanged = {},
            onRelayChainEntryUuidChanged = {},
            onRelayChainExitServerChanged = {},
            onRelayChainExitPortChanged = {},
            onRelayChainExitServerNameChanged = {},
            onRelayChainExitPublicKeyChanged = {},
            onRelayChainExitShortIdChanged = {},
            onRelayChainExitUuidChanged = {},
            onRelayMasqueUrlChanged = {},
            onRelayMasqueAuthTokenChanged = {},
            onRelayMasqueUseHttp2FallbackChanged = {},
            onRelayMasqueCloudflareModeChanged = {},
            onRelayLocalSocksPortChanged = {},
            onSave = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
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
            chainDsl = "[tcp]\nfake host+1\nsplit midsld\n\n[udp]\nfake_burst 2",
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
                        persistentMapOf(
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
            onChainDslChanged = {},
            onDefaultTtlChanged = {},
            onCommandLineEnabledChanged = {},
            onCommandLineArgsChanged = {},
            onRelayEnabledChanged = {},
            onRelayKindChanged = {},
            onRelayProfileIdChanged = {},
            onRelayServerChanged = {},
            onRelayServerPortChanged = {},
            onRelayServerNameChanged = {},
            onRelayRealityPublicKeyChanged = {},
            onRelayRealityShortIdChanged = {},
            onRelayVlessUuidChanged = {},
            onRelayHysteriaPasswordChanged = {},
            onRelayHysteriaSalamanderKeyChanged = {},
            onRelayChainEntryServerChanged = {},
            onRelayChainEntryPortChanged = {},
            onRelayChainEntryServerNameChanged = {},
            onRelayChainEntryPublicKeyChanged = {},
            onRelayChainEntryShortIdChanged = {},
            onRelayChainEntryUuidChanged = {},
            onRelayChainExitServerChanged = {},
            onRelayChainExitPortChanged = {},
            onRelayChainExitServerNameChanged = {},
            onRelayChainExitPublicKeyChanged = {},
            onRelayChainExitShortIdChanged = {},
            onRelayChainExitUuidChanged = {},
            onRelayMasqueUrlChanged = {},
            onRelayMasqueAuthTokenChanged = {},
            onRelayMasqueUseHttp2FallbackChanged = {},
            onRelayMasqueCloudflareModeChanged = {},
            onRelayLocalSocksPortChanged = {},
            onSave = {},
        )
    }
}
