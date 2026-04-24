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

@Suppress("LongMethod")
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

internal fun editorPresetKind(uiState: ConfigUiState): ConfigPresetKind =
    uiState.editingPreset?.kind ?: ConfigPresetKind.Custom

@Composable
internal fun ModeEditorScreenWithNoOpCallbacks(
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
            onRelayCloudflareTunnelModeChanged = {},
            onRelayCloudflarePublishLocalOriginUrlChanged = {},
            onRelayCloudflareCredentialsRefChanged = {},
            onRelayCloudflareTunnelTokenChanged = {},
            onRelayCloudflareTunnelCredentialsJsonChanged = {},
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
            onRelayFinalmaskTypeChanged = {},
            onRelayFinalmaskHeaderHexChanged = {},
            onRelayFinalmaskTrailerHexChanged = {},
            onRelayFinalmaskRandRangeChanged = {},
            onRelayFinalmaskSudokuSeedChanged = {},
            onRelayFinalmaskFragmentPacketsChanged = {},
            onRelayFinalmaskFragmentMinBytesChanged = {},
            onRelayFinalmaskFragmentMaxBytesChanged = {},
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
