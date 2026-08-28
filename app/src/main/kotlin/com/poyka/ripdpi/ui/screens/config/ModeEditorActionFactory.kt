package com.poyka.ripdpi.ui.screens.config

import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.relayChainHopAdded
import com.poyka.ripdpi.activities.relayChainHopMoved
import com.poyka.ripdpi.activities.relayChainHopProfileIdChanged
import com.poyka.ripdpi.activities.relayChainHopRemoved

internal data class ModeEditorExternalActions(
    val onRelayMasqueCloudflareGeohashEnabledChanged: (Boolean) -> Unit,
    val onRelayMasqueImportCertificateChainClicked: () -> Unit,
    val onRelayMasqueImportPrivateKeyClicked: () -> Unit,
    val onRelayMasqueImportPkcs12Clicked: () -> Unit,
)

internal fun createModeEditorActions(
    viewModel: ConfigViewModel,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onOpenXrayImport: () -> Unit,
    externalActions: ModeEditorExternalActions,
): ModeEditorActions =
    createBaseActions(viewModel, onBack, onCancel, onOpenXrayImport)
        .withRelayEndpointActions(viewModel)
        .withRelayTransportActions(viewModel)
        .withRelayChainAndMasqueActions(viewModel, externalActions)
        .withRelayProtocolActions(viewModel)
        .withFinalmaskActions(viewModel)

private fun createBaseActions(
    viewModel: ConfigViewModel,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onOpenXrayImport: () -> Unit,
): ModeEditorActions =
    NoOpModeEditorActions.copy(
        onBack = onBack,
        onCancel = onCancel,
        onOpenXrayImport = onOpenXrayImport,
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
        onSave = viewModel::saveDraft,
    )

private fun ModeEditorActions.withRelayEndpointActions(viewModel: ConfigViewModel): ModeEditorActions =
    copy(
        onRelayEnabledChanged = { viewModel.updateDraft { copy(relayEnabled = it) } },
        onRelayKindChanged = { updateRelayKind(viewModel, it) },
        onRelayProfileIdChanged = { viewModel.updateDraft { copy(relayProfileId = it) } },
        onRelayPresetSelected = viewModel::applyRelayPreset,
        onRelayServerChanged = { viewModel.updateDraft { copy(relayServer = it) } },
        onRelayServerPortChanged = { viewModel.updateDraft { copy(relayServerPort = it) } },
        onRelayServerNameChanged = { viewModel.updateDraft { copy(relayServerName = it) } },
        onRelayUdpEnabledChanged = { viewModel.updateDraft { copy(relayUdpEnabled = it) } },
        onRelayLocalSocksPortChanged = { viewModel.updateDraft { copy(relayLocalSocksPort = it) } },
    )

private fun ModeEditorActions.withRelayTransportActions(viewModel: ConfigViewModel): ModeEditorActions =
    copy(
        onRelayRealityPublicKeyChanged = { viewModel.updateDraft { copy(relayRealityPublicKey = it) } },
        onRelayRealityShortIdChanged = { viewModel.updateDraft { copy(relayRealityShortId = it) } },
        onRelayVlessTransportChanged = { viewModel.updateDraft { copy(relayVlessTransport = it) } },
        onRelayXhttpPathChanged = { viewModel.updateDraft { copy(relayXhttpPath = it) } },
        onRelayXhttpHostChanged = { viewModel.updateDraft { copy(relayXhttpHost = it) } },
        onRelayXhttpModeChanged = { viewModel.updateDraft { copy(relayXhttpMode = it) } },
        onRelayCloudflareTunnelModeChanged = { viewModel.updateDraft { copy(relayCloudflareTunnelMode = it) } },
        onRelayCloudflarePublishLocalOriginUrlChanged = {
            viewModel.updateDraft { copy(relayCloudflarePublishLocalOriginUrl = it) }
        },
        onRelayCloudflareCredentialsRefChanged = {
            viewModel.updateDraft { copy(relayCloudflareCredentialsRef = it) }
        },
        onRelayCloudflareTunnelTokenChanged = {
            viewModel.updateDraft { copy(relayCloudflareTunnelToken = it) }
        },
        onRelayCloudflareTunnelCredentialsJsonChanged = {
            viewModel.updateDraft { copy(relayCloudflareTunnelCredentialsJson = it) }
        },
    )

private fun ModeEditorActions.withRelayChainAndMasqueActions(
    viewModel: ConfigViewModel,
    externalActions: ModeEditorExternalActions,
): ModeEditorActions =
    copy(
        onRelayChainHopProfileIdChanged = { index, profileId ->
            viewModel.updateDraft { relayChainHopProfileIdChanged(index, profileId) }
        },
        onRelayChainHopAdded = { viewModel.updateDraft { relayChainHopAdded() } },
        onRelayChainHopRemoved = { viewModel.updateDraft { relayChainHopRemoved(it) } },
        onRelayChainHopMoved = { from, to -> viewModel.updateDraft { relayChainHopMoved(from, to) } },
        onRelayMasqueUrlChanged = { viewModel.updateDraft { copy(relayMasqueUrl = it) } },
        onRelayMasqueAuthModeChanged = { viewModel.updateDraft { copy(relayMasqueAuthMode = it) } },
        onRelayMasqueAuthTokenChanged = { viewModel.updateDraft { copy(relayMasqueAuthToken = it) } },
        onRelayMasqueClientCertificateChainPemChanged = viewModel::updateRelayMasqueCertificateChain,
        onRelayMasqueClientPrivateKeyPemChanged = viewModel::updateRelayMasquePrivateKey,
        onRelayMasqueUseHttp2FallbackChanged = {
            viewModel.updateDraft { copy(relayMasqueUseHttp2Fallback = it) }
        },
        onRelayMasqueCloudflareGeohashEnabledChanged =
            externalActions.onRelayMasqueCloudflareGeohashEnabledChanged,
        onRelayMasqueImportCertificateChainClicked = externalActions.onRelayMasqueImportCertificateChainClicked,
        onRelayMasqueImportPrivateKeyClicked = externalActions.onRelayMasqueImportPrivateKeyClicked,
        onRelayMasqueImportPkcs12Clicked = externalActions.onRelayMasqueImportPkcs12Clicked,
    )

private fun ModeEditorActions.withRelayProtocolActions(viewModel: ConfigViewModel): ModeEditorActions =
    copy(
        onRelayVlessUuidChanged = { viewModel.updateDraft { copy(relayVlessUuid = it) } },
        onRelayAnyTlsPasswordChanged = { viewModel.updateDraft { copy(relayAnyTlsPassword = it) } },
        onRelayHysteriaPasswordChanged = { viewModel.updateDraft { copy(relayHysteriaPassword = it) } },
        onRelayHysteriaSalamanderKeyChanged = { viewModel.updateDraft { copy(relayHysteriaSalamanderKey = it) } },
        onRelayTuicUuidChanged = { viewModel.updateDraft { copy(relayTuicUuid = it) } },
        onRelayTuicPasswordChanged = { viewModel.updateDraft { copy(relayTuicPassword = it) } },
        onRelayTuicZeroRttChanged = { viewModel.updateDraft { copy(relayTuicZeroRtt = it) } },
        onRelayTuicCongestionControlChanged = { viewModel.updateDraft { copy(relayTuicCongestionControl = it) } },
        onRelayShadowTlsPasswordChanged = { viewModel.updateDraft { copy(relayShadowTlsPassword = it) } },
        onRelayShadowTlsInnerProfileIdChanged = {
            viewModel.updateDraft { copy(relayShadowTlsInnerProfileId = it) }
        },
        onRelayNaiveUsernameChanged = { viewModel.updateDraft { copy(relayNaiveUsername = it) } },
        onRelayNaivePasswordChanged = { viewModel.updateDraft { copy(relayNaivePassword = it) } },
        onRelayNaivePathChanged = { viewModel.updateDraft { copy(relayNaivePath = it) } },
        onRelayPtBridgeLineChanged = { viewModel.updateDraft { copy(relayPtBridgeLine = it) } },
        onRelayWebTunnelUrlChanged = { viewModel.updateDraft { copy(relayWebTunnelUrl = it) } },
        onRelaySnowflakeBrokerUrlChanged = { viewModel.updateDraft { copy(relaySnowflakeBrokerUrl = it) } },
        onRelaySnowflakeFrontDomainChanged = { viewModel.updateDraft { copy(relaySnowflakeFrontDomain = it) } },
    )

private fun ModeEditorActions.withFinalmaskActions(viewModel: ConfigViewModel): ModeEditorActions =
    copy(
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
    )
