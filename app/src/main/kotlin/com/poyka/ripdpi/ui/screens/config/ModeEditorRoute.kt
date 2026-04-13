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
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
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

internal enum class MasqueImportAction {
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
        RipDpiDialog(
            onDismissRequest = {
                pendingPkcs12Uri = null
                pkcs12Password = ""
            },
            title = stringResource(R.string.config_relay_masque_pkcs12_dialog_title),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.config_relay_import),
                    onClick = {
                        viewModel.importRelayMasquePkcs12(uri, pkcs12Password)
                        pendingPkcs12Uri = null
                        pkcs12Password = ""
                    },
                ),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.config_cancel),
                    onClick = {
                        pendingPkcs12Uri = null
                        pkcs12Password = ""
                    },
                ),
        ) {
            RipDpiTextField(
                value = pkcs12Password,
                onValueChange = { pkcs12Password = it },
                decoration =
                    RipDpiTextFieldDecoration(
                        label = stringResource(R.string.config_relay_masque_pkcs12_password),
                        helperText = stringResource(R.string.config_relay_masque_pkcs12_password_helper),
                    ),
            )
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
        onRelayCloudflareTunnelModeChanged = { viewModel.updateDraft { copy(relayCloudflareTunnelMode = it) } },
        onRelayCloudflarePublishLocalOriginUrlChanged = {
            viewModel.updateDraft { copy(relayCloudflarePublishLocalOriginUrl = it) }
        },
        onRelayCloudflareCredentialsRefChanged = { viewModel.updateDraft { copy(relayCloudflareCredentialsRef = it) } },
        onRelayCloudflareTunnelTokenChanged = { viewModel.updateDraft { copy(relayCloudflareTunnelToken = it) } },
        onRelayCloudflareTunnelCredentialsJsonChanged = {
            viewModel.updateDraft { copy(relayCloudflareTunnelCredentialsJson = it) }
        },
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
        onRelayFinalmaskTypeChanged = { viewModel.updateDraft { copy(relayFinalmaskType = it) } },
        onRelayFinalmaskHeaderHexChanged = { viewModel.updateDraft { copy(relayFinalmaskHeaderHex = it) } },
        onRelayFinalmaskTrailerHexChanged = { viewModel.updateDraft { copy(relayFinalmaskTrailerHex = it) } },
        onRelayFinalmaskRandRangeChanged = { viewModel.updateDraft { copy(relayFinalmaskRandRange = it) } },
        onRelayFinalmaskSudokuSeedChanged = { viewModel.updateDraft { copy(relayFinalmaskSudokuSeed = it) } },
        onRelayFinalmaskFragmentPacketsChanged = {
            viewModel.updateDraft { copy(relayFinalmaskFragmentPackets = it) }
        },
        onRelayFinalmaskFragmentMinBytesChanged = {
            viewModel.updateDraft { copy(relayFinalmaskFragmentMinBytes = it) }
        },
        onRelayFinalmaskFragmentMaxBytesChanged = {
            viewModel.updateDraft { copy(relayFinalmaskFragmentMaxBytes = it) }
        },
        onRelayUdpEnabledChanged = { viewModel.updateDraft { copy(relayUdpEnabled = it) } },
        onRelayLocalSocksPortChanged = { viewModel.updateDraft { copy(relayLocalSocksPort = it) } },
        onSave = viewModel::saveDraft,
    )
}
