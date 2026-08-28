package com.poyka.ripdpi.ui.screens.config

internal data class RelayKindFieldActions(
    val endpoint: RelayEndpointActions = RelayEndpointActions(),
    val vless: RelayVlessActions = RelayVlessActions(),
    val hysteria: RelayHysteriaActions = RelayHysteriaActions(),
    val onAnyTlsPasswordChanged: (String) -> Unit = {},
    val chain: RelayChainActions = RelayChainActions(),
    val masque: RelayMasqueActions = RelayMasqueActions(),
    val tuic: RelayTuicActions = RelayTuicActions(),
    val misc: RelayMiscKindActions = RelayMiscKindActions(),
    val finalmask: RelayFinalmaskActions = RelayFinalmaskActions(),
)

internal data class RelayEndpointActions(
    val onRelayServerChanged: (String) -> Unit = {},
    val onRelayServerPortChanged: (String) -> Unit = {},
    val onRelayServerNameChanged: (String) -> Unit = {},
)

internal data class RelayVlessActions(
    val onRelayRealityPublicKeyChanged: (String) -> Unit = {},
    val onRelayRealityShortIdChanged: (String) -> Unit = {},
    val onRelayVlessTransportChanged: (String) -> Unit = {},
    val onRelayXhttpPathChanged: (String) -> Unit = {},
    val onRelayXhttpHostChanged: (String) -> Unit = {},
    val onRelayXhttpModeChanged: (String) -> Unit = {},
    val onRelayCloudflareTunnelModeChanged: (String) -> Unit = {},
    val onRelayCloudflarePublishLocalOriginUrlChanged: (String) -> Unit = {},
    val onRelayCloudflareCredentialsRefChanged: (String) -> Unit = {},
    val onRelayCloudflareTunnelTokenChanged: (String) -> Unit = {},
    val onRelayCloudflareTunnelCredentialsJsonChanged: (String) -> Unit = {},
    val onRelayVlessUuidChanged: (String) -> Unit = {},
)

internal data class RelayHysteriaActions(
    val onRelayHysteriaPasswordChanged: (String) -> Unit = {},
    val onRelayHysteriaSalamanderKeyChanged: (String) -> Unit = {},
)

internal data class RelayChainActions(
    val onRelayChainHopProfileIdChanged: (Int, String) -> Unit = { _, _ -> },
    val onRelayChainHopAdded: () -> Unit = {},
    val onRelayChainHopRemoved: (Int) -> Unit = {},
    val onRelayChainHopMoved: (Int, Int) -> Unit = { _, _ -> },
)

internal data class RelayMasqueActions(
    val onRelayMasqueUrlChanged: (String) -> Unit = {},
    val onRelayMasqueAuthModeChanged: (String) -> Unit = {},
    val onRelayMasqueAuthTokenChanged: (String) -> Unit = {},
    val onRelayMasqueClientCertificateChainPemChanged: (String) -> Unit = {},
    val onRelayMasqueClientPrivateKeyPemChanged: (String) -> Unit = {},
    val onRelayMasqueUseHttp2FallbackChanged: (Boolean) -> Unit = {},
    val onRelayMasqueCloudflareGeohashEnabledChanged: (Boolean) -> Unit = {},
    val onRelayMasqueImportCertificateChainClicked: () -> Unit = {},
    val onRelayMasqueImportPrivateKeyClicked: () -> Unit = {},
    val onRelayMasqueImportPkcs12Clicked: () -> Unit = {},
)

internal data class RelayTuicActions(
    val onRelayTuicUuidChanged: (String) -> Unit = {},
    val onRelayTuicPasswordChanged: (String) -> Unit = {},
    val onRelayTuicZeroRttChanged: (Boolean) -> Unit = {},
    val onRelayTuicCongestionControlChanged: (String) -> Unit = {},
)

internal data class RelayMiscKindActions(
    val onRelayShadowTlsPasswordChanged: (String) -> Unit = {},
    val onRelayShadowTlsInnerProfileIdChanged: (String) -> Unit = {},
    val onRelayNaiveUsernameChanged: (String) -> Unit = {},
    val onRelayNaivePasswordChanged: (String) -> Unit = {},
    val onRelayNaivePathChanged: (String) -> Unit = {},
    val onRelayPtBridgeLineChanged: (String) -> Unit = {},
    val onRelayWebTunnelUrlChanged: (String) -> Unit = {},
    val onRelaySnowflakeBrokerUrlChanged: (String) -> Unit = {},
    val onRelaySnowflakeFrontDomainChanged: (String) -> Unit = {},
)

internal data class RelayFinalmaskActions(
    val onRelayFinalmaskTypeChanged: (String) -> Unit = {},
    val onRelayFinalmaskHeaderHexChanged: (String) -> Unit = {},
    val onRelayFinalmaskTrailerHexChanged: (String) -> Unit = {},
    val onRelayFinalmaskRandRangeChanged: (String) -> Unit = {},
    val onRelayFinalmaskSudokuSeedChanged: (String) -> Unit = {},
    val onRelayFinalmaskFragmentPacketsChanged: (String) -> Unit = {},
    val onRelayFinalmaskFragmentMinBytesChanged: (String) -> Unit = {},
    val onRelayFinalmaskFragmentMaxBytesChanged: (String) -> Unit = {},
)
