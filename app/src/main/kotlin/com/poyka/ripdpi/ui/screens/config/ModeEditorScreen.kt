package com.poyka.ripdpi.ui.screens.config

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigEffect
import com.poyka.ripdpi.activities.ConfigFieldBufferSize
import com.poyka.ripdpi.activities.ConfigFieldDefaultTtl
import com.poyka.ripdpi.activities.ConfigFieldDnsIp
import com.poyka.ripdpi.activities.ConfigFieldMaxConnections
import com.poyka.ripdpi.activities.ConfigFieldProxyIp
import com.poyka.ripdpi.activities.ConfigFieldProxyPort
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
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayMasqueAuthModeBearer
import com.poyka.ripdpi.data.RelayMasqueAuthModePreshared
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
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

private enum class MasqueImportAction {
    CertificateChain,
    PrivateKey,
    Pkcs12,
}

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
    val context = LocalContext.current
    var pendingMasqueImportAction by remember { mutableStateOf<MasqueImportAction?>(null) }
    var pendingPkcs12Uri by remember { mutableStateOf<Uri?>(null) }
    var pkcs12Password by rememberSaveable { mutableStateOf("") }
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
    val documentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val action = pendingMasqueImportAction
            pendingMasqueImportAction = null
            when {
                uri == null || action == null -> Unit
                action == MasqueImportAction.CertificateChain -> viewModel.importRelayMasqueCertificateChain(uri)
                action == MasqueImportAction.PrivateKey -> viewModel.importRelayMasquePrivateKey(uri)
                action == MasqueImportAction.Pkcs12 -> pendingPkcs12Uri = uri
            }
        }
    val coarseLocationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.updateDraft { copy(relayMasqueCloudflareGeohashEnabled = true) }
            }
        }

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

                is ConfigEffect.Message -> {
                    snackbarHostState.showRipDpiSnackbar(
                        message = effect.text,
                        tone = RipDpiSnackbarTone.Warning,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    pendingPkcs12Uri?.let { uri ->
        AlertDialog(
            onDismissRequest = {
                pendingPkcs12Uri = null
                pkcs12Password = ""
            },
            title = { Text(text = stringResource(R.string.config_relay_masque_pkcs12_dialog_title)) },
            text = {
                RipDpiTextField(
                    value = pkcs12Password,
                    onValueChange = { pkcs12Password = it },
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.config_relay_masque_pkcs12_password),
                            helperText = stringResource(R.string.config_relay_masque_pkcs12_password_helper),
                        ),
                )
            },
            confirmButton = {
                RipDpiButton(
                    text = stringResource(R.string.config_relay_import),
                    onClick = {
                        viewModel.importRelayMasquePkcs12(uri, pkcs12Password)
                        pendingPkcs12Uri = null
                        pkcs12Password = ""
                    },
                )
            },
            dismissButton = {
                RipDpiButton(
                    text = stringResource(R.string.config_cancel),
                    onClick = {
                        pendingPkcs12Uri = null
                        pkcs12Password = ""
                    },
                    variant = RipDpiButtonVariant.Outline,
                )
            },
        )
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
        onRelayKindChanged = {
            viewModel.updateDraft {
                when (it) {
                    RelayKindCloudflareTunnel -> {
                        copy(
                            relayKind = it,
                            relayVlessTransport = RelayVlessTransportXhttp,
                            relayUdpEnabled = false,
                        )
                    }

                    RelayKindShadowTlsV3,
                    RelayKindNaiveProxy,
                    -> {
                        copy(
                            relayKind = it,
                            relayUdpEnabled = false,
                        )
                    }

                    else -> {
                        copy(relayKind = it)
                    }
                }
            }
        },
        onRelayProfileIdChanged = { viewModel.updateDraft { copy(relayProfileId = it) } },
        onRelayPresetSelected = viewModel::applyRelayPreset,
        onRelayServerChanged = { viewModel.updateDraft { copy(relayServer = it) } },
        onRelayServerPortChanged = { viewModel.updateDraft { copy(relayServerPort = it) } },
        onRelayServerNameChanged = { viewModel.updateDraft { copy(relayServerName = it) } },
        onRelayRealityPublicKeyChanged = { viewModel.updateDraft { copy(relayRealityPublicKey = it) } },
        onRelayRealityShortIdChanged = { viewModel.updateDraft { copy(relayRealityShortId = it) } },
        onRelayVlessTransportChanged = { viewModel.updateDraft { copy(relayVlessTransport = it) } },
        onRelayXhttpPathChanged = { viewModel.updateDraft { copy(relayXhttpPath = it) } },
        onRelayXhttpHostChanged = { viewModel.updateDraft { copy(relayXhttpHost = it) } },
        onRelayVlessUuidChanged = { viewModel.updateDraft { copy(relayVlessUuid = it) } },
        onRelayHysteriaPasswordChanged = { viewModel.updateDraft { copy(relayHysteriaPassword = it) } },
        onRelayHysteriaSalamanderKeyChanged = { viewModel.updateDraft { copy(relayHysteriaSalamanderKey = it) } },
        onRelayChainEntryProfileIdChanged = { viewModel.updateDraft { copy(relayChainEntryProfileId = it) } },
        onRelayChainExitProfileIdChanged = { viewModel.updateDraft { copy(relayChainExitProfileId = it) } },
        onRelayMasqueUrlChanged = { viewModel.updateDraft { copy(relayMasqueUrl = it) } },
        onRelayMasqueAuthModeChanged = { viewModel.updateDraft { copy(relayMasqueAuthMode = it) } },
        onRelayMasqueAuthTokenChanged = { viewModel.updateDraft { copy(relayMasqueAuthToken = it) } },
        onRelayMasqueClientCertificateChainPemChanged = {
            viewModel.updateDraft { copy(relayMasqueClientCertificateChainPem = it) }
        },
        onRelayMasqueClientPrivateKeyPemChanged = {
            viewModel.updateDraft { copy(relayMasqueClientPrivateKeyPem = it) }
        },
        onRelayMasqueUseHttp2FallbackChanged = { viewModel.updateDraft { copy(relayMasqueUseHttp2Fallback = it) } },
        onRelayMasqueCloudflareGeohashEnabledChanged = { enabled ->
            if (!enabled) {
                viewModel.updateDraft { copy(relayMasqueCloudflareGeohashEnabled = false) }
            } else {
                val permissionState =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (permissionState == PackageManager.PERMISSION_GRANTED) {
                    viewModel.updateDraft { copy(relayMasqueCloudflareGeohashEnabled = true) }
                } else {
                    coarseLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        },
        onRelayMasqueImportCertificateChainClicked = {
            pendingMasqueImportAction = MasqueImportAction.CertificateChain
            documentLauncher.launch(arrayOf("*/*"))
        },
        onRelayMasqueImportPrivateKeyClicked = {
            pendingMasqueImportAction = MasqueImportAction.PrivateKey
            documentLauncher.launch(arrayOf("*/*"))
        },
        onRelayMasqueImportPkcs12Clicked = {
            pendingMasqueImportAction = MasqueImportAction.Pkcs12
            documentLauncher.launch(arrayOf("*/*"))
        },
        onRelayTuicUuidChanged = { viewModel.updateDraft { copy(relayTuicUuid = it) } },
        onRelayTuicPasswordChanged = { viewModel.updateDraft { copy(relayTuicPassword = it) } },
        onRelayTuicZeroRttChanged = { viewModel.updateDraft { copy(relayTuicZeroRtt = it) } },
        onRelayTuicCongestionControlChanged = { viewModel.updateDraft { copy(relayTuicCongestionControl = it) } },
        onRelayShadowTlsPasswordChanged = { viewModel.updateDraft { copy(relayShadowTlsPassword = it) } },
        onRelayShadowTlsInnerProfileIdChanged = { viewModel.updateDraft { copy(relayShadowTlsInnerProfileId = it) } },
        onRelayNaiveUsernameChanged = { viewModel.updateDraft { copy(relayNaiveUsername = it) } },
        onRelayNaivePasswordChanged = { viewModel.updateDraft { copy(relayNaivePassword = it) } },
        onRelayNaivePathChanged = { viewModel.updateDraft { copy(relayNaivePath = it) } },
        onRelayPtBridgeLineChanged = { viewModel.updateDraft { copy(relayPtBridgeLine = it) } },
        onRelayWebTunnelUrlChanged = { viewModel.updateDraft { copy(relayWebTunnelUrl = it) } },
        onRelaySnowflakeBrokerUrlChanged = { viewModel.updateDraft { copy(relaySnowflakeBrokerUrl = it) } },
        onRelaySnowflakeFrontDomainChanged = { viewModel.updateDraft { copy(relaySnowflakeFrontDomain = it) } },
        onRelayUdpEnabledChanged = { viewModel.updateDraft { copy(relayUdpEnabled = it) } },
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
    onRelayPresetSelected: (String) -> Unit,
    onRelayServerChanged: (String) -> Unit,
    onRelayServerPortChanged: (String) -> Unit,
    onRelayServerNameChanged: (String) -> Unit,
    onRelayRealityPublicKeyChanged: (String) -> Unit,
    onRelayRealityShortIdChanged: (String) -> Unit,
    onRelayVlessTransportChanged: (String) -> Unit,
    onRelayXhttpPathChanged: (String) -> Unit,
    onRelayXhttpHostChanged: (String) -> Unit,
    onRelayVlessUuidChanged: (String) -> Unit,
    onRelayHysteriaPasswordChanged: (String) -> Unit,
    onRelayHysteriaSalamanderKeyChanged: (String) -> Unit,
    onRelayChainEntryProfileIdChanged: (String) -> Unit,
    onRelayChainExitProfileIdChanged: (String) -> Unit,
    onRelayMasqueUrlChanged: (String) -> Unit,
    onRelayMasqueAuthModeChanged: (String) -> Unit,
    onRelayMasqueAuthTokenChanged: (String) -> Unit,
    onRelayMasqueClientCertificateChainPemChanged: (String) -> Unit,
    onRelayMasqueClientPrivateKeyPemChanged: (String) -> Unit,
    onRelayMasqueUseHttp2FallbackChanged: (Boolean) -> Unit,
    onRelayMasqueCloudflareGeohashEnabledChanged: (Boolean) -> Unit,
    onRelayMasqueImportCertificateChainClicked: () -> Unit,
    onRelayMasqueImportPrivateKeyClicked: () -> Unit,
    onRelayMasqueImportPkcs12Clicked: () -> Unit,
    onRelayTuicUuidChanged: (String) -> Unit,
    onRelayTuicPasswordChanged: (String) -> Unit,
    onRelayTuicZeroRttChanged: (Boolean) -> Unit,
    onRelayTuicCongestionControlChanged: (String) -> Unit,
    onRelayShadowTlsPasswordChanged: (String) -> Unit,
    onRelayShadowTlsInnerProfileIdChanged: (String) -> Unit,
    onRelayNaiveUsernameChanged: (String) -> Unit,
    onRelayNaivePasswordChanged: (String) -> Unit,
    onRelayNaivePathChanged: (String) -> Unit,
    onRelayPtBridgeLineChanged: (String) -> Unit,
    onRelayWebTunnelUrlChanged: (String) -> Unit,
    onRelaySnowflakeBrokerUrlChanged: (String) -> Unit,
    onRelaySnowflakeFrontDomainChanged: (String) -> Unit,
    onRelayUdpEnabledChanged: (Boolean) -> Unit,
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
                            uiState.relayPresetSuggestion?.let { suggestion ->
                                WarningBanner(
                                    title = suggestion.title,
                                    message = suggestion.reason,
                                    tone = WarningBannerTone.Info,
                                )
                            }
                            if (uiState.relayPresets.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.config_relay_presets_title),
                                    style = RipDpiThemeTokens.type.caption,
                                    color = colors.mutedForeground,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                ) {
                                    uiState.relayPresets.forEach { preset ->
                                        RelayKindChip(
                                            selectedKind = draft.relayPresetId,
                                            kind = preset.id,
                                            label = preset.title,
                                            onRelayKindChanged = onRelayPresetSelected,
                                        )
                                    }
                                }
                            }
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
                                    RelayKindCloudflareTunnel,
                                    R.string.config_relay_kind_cloudflare_tunnel,
                                    onRelayKindChanged,
                                )
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindNaiveProxy,
                                    "NaiveProxy",
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
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindTuicV5,
                                    "TUIC v5",
                                    onRelayKindChanged,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindShadowTlsV3,
                                    "ShadowTLS v3",
                                    onRelayKindChanged,
                                )
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindSnowflake,
                                    "Snowflake",
                                    onRelayKindChanged,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindWebTunnel,
                                    "WebTunnel",
                                    onRelayKindChanged,
                                )
                                RelayKindChip(
                                    draft.relayKind,
                                    RelayKindObfs4,
                                    "obfs4",
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
                            RelayKindFields(
                                draft = draft,
                                uiState = uiState,
                                onRelayServerChanged = onRelayServerChanged,
                                onRelayServerPortChanged = onRelayServerPortChanged,
                                onRelayServerNameChanged = onRelayServerNameChanged,
                                onRelayRealityPublicKeyChanged = onRelayRealityPublicKeyChanged,
                                onRelayRealityShortIdChanged = onRelayRealityShortIdChanged,
                                onRelayVlessTransportChanged = onRelayVlessTransportChanged,
                                onRelayXhttpPathChanged = onRelayXhttpPathChanged,
                                onRelayXhttpHostChanged = onRelayXhttpHostChanged,
                                onRelayVlessUuidChanged = onRelayVlessUuidChanged,
                                onRelayHysteriaPasswordChanged = onRelayHysteriaPasswordChanged,
                                onRelayHysteriaSalamanderKeyChanged = onRelayHysteriaSalamanderKeyChanged,
                                onRelayChainEntryProfileIdChanged = onRelayChainEntryProfileIdChanged,
                                onRelayChainExitProfileIdChanged = onRelayChainExitProfileIdChanged,
                                onRelayMasqueUrlChanged = onRelayMasqueUrlChanged,
                                onRelayMasqueAuthModeChanged = onRelayMasqueAuthModeChanged,
                                onRelayMasqueAuthTokenChanged = onRelayMasqueAuthTokenChanged,
                                onRelayMasqueClientCertificateChainPemChanged =
                                onRelayMasqueClientCertificateChainPemChanged,
                                onRelayMasqueClientPrivateKeyPemChanged = onRelayMasqueClientPrivateKeyPemChanged,
                                onRelayMasqueUseHttp2FallbackChanged = onRelayMasqueUseHttp2FallbackChanged,
                                onRelayMasqueCloudflareGeohashEnabledChanged =
                                onRelayMasqueCloudflareGeohashEnabledChanged,
                                onRelayMasqueImportCertificateChainClicked = onRelayMasqueImportCertificateChainClicked,
                                onRelayMasqueImportPrivateKeyClicked = onRelayMasqueImportPrivateKeyClicked,
                                onRelayMasqueImportPkcs12Clicked = onRelayMasqueImportPkcs12Clicked,
                                onRelayTuicUuidChanged = onRelayTuicUuidChanged,
                                onRelayTuicPasswordChanged = onRelayTuicPasswordChanged,
                                onRelayTuicZeroRttChanged = onRelayTuicZeroRttChanged,
                                onRelayTuicCongestionControlChanged = onRelayTuicCongestionControlChanged,
                                onRelayShadowTlsPasswordChanged = onRelayShadowTlsPasswordChanged,
                                onRelayShadowTlsInnerProfileIdChanged = onRelayShadowTlsInnerProfileIdChanged,
                                onRelayNaiveUsernameChanged = onRelayNaiveUsernameChanged,
                                onRelayNaivePasswordChanged = onRelayNaivePasswordChanged,
                                onRelayNaivePathChanged = onRelayNaivePathChanged,
                                onRelayPtBridgeLineChanged = onRelayPtBridgeLineChanged,
                                onRelayWebTunnelUrlChanged = onRelayWebTunnelUrlChanged,
                                onRelaySnowflakeBrokerUrlChanged = onRelaySnowflakeBrokerUrlChanged,
                                onRelaySnowflakeFrontDomainChanged = onRelaySnowflakeFrontDomainChanged,
                            )
                            if (
                                draft.relayKind == RelayKindHysteria2 ||
                                draft.relayKind == RelayKindMasque ||
                                draft.relayKind == RelayKindTuicV5
                            ) {
                                RipDpiSwitch(
                                    checked = draft.relayUdpEnabled,
                                    onCheckedChange = onRelayUdpEnabledChanged,
                                    label = stringResource(R.string.config_relay_udp),
                                )
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
internal fun validationMessage(errorKey: String?): String? =
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

private fun editorPresetKind(uiState: ConfigUiState): ConfigPresetKind =
    uiState.editingPreset?.kind ?: ConfigPresetKind.Custom

@Composable
private fun ModeEditorScreenWithNoOpCallbacks(
    uiState: ConfigUiState,
    themePreference: String = "light",
) {
    RipDpiTheme(themePreference = themePreference) {
        ModeEditorScreen(
            uiState = uiState,
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
            onRelayPresetSelected = {},
            onRelayServerChanged = {},
            onRelayServerPortChanged = {},
            onRelayServerNameChanged = {},
            onRelayRealityPublicKeyChanged = {},
            onRelayRealityShortIdChanged = {},
            onRelayVlessTransportChanged = {},
            onRelayXhttpPathChanged = {},
            onRelayXhttpHostChanged = {},
            onRelayVlessUuidChanged = {},
            onRelayHysteriaPasswordChanged = {},
            onRelayHysteriaSalamanderKeyChanged = {},
            onRelayChainEntryProfileIdChanged = {},
            onRelayChainExitProfileIdChanged = {},
            onRelayMasqueUrlChanged = {},
            onRelayMasqueAuthModeChanged = {},
            onRelayMasqueAuthTokenChanged = {},
            onRelayMasqueClientCertificateChainPemChanged = {},
            onRelayMasqueClientPrivateKeyPemChanged = {},
            onRelayMasqueUseHttp2FallbackChanged = {},
            onRelayMasqueCloudflareGeohashEnabledChanged = {},
            onRelayMasqueImportCertificateChainClicked = {},
            onRelayMasqueImportPrivateKeyClicked = {},
            onRelayMasqueImportPkcs12Clicked = {},
            onRelayTuicUuidChanged = {},
            onRelayTuicPasswordChanged = {},
            onRelayTuicZeroRttChanged = {},
            onRelayTuicCongestionControlChanged = {},
            onRelayShadowTlsPasswordChanged = {},
            onRelayShadowTlsInnerProfileIdChanged = {},
            onRelayNaiveUsernameChanged = {},
            onRelayNaivePasswordChanged = {},
            onRelayNaivePathChanged = {},
            onRelayPtBridgeLineChanged = {},
            onRelayWebTunnelUrlChanged = {},
            onRelaySnowflakeBrokerUrlChanged = {},
            onRelaySnowflakeFrontDomainChanged = {},
            onRelayUdpEnabledChanged = {},
            onRelayLocalSocksPortChanged = {},
            onSave = {},
        )
    }
}

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
    ModeEditorScreenWithNoOpCallbacks(
        uiState =
            ConfigUiState(
                activeMode = draft.mode,
                presets = buildConfigPresets(draft),
                editingPreset = ConfigPreset(id = "custom", kind = ConfigPresetKind.Custom, draft = draft),
                draft = draft,
            ),
    )
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
    ModeEditorScreenWithNoOpCallbacks(
        uiState =
            ConfigUiState(
                activeMode = draft.mode,
                presets = buildConfigPresets(draft),
                editingPreset =
                    ConfigPreset(id = "recommended", kind = ConfigPresetKind.Recommended, draft = draft),
                draft = draft,
                validationErrors =
                    persistentMapOf(
                        ConfigFieldDnsIp to "invalid_dns_ip",
                        ConfigFieldDefaultTtl to "out_of_range",
                    ),
            ),
        themePreference = "dark",
    )
}
