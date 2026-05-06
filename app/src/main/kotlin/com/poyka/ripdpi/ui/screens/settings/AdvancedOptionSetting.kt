package com.poyka.ripdpi.ui.screens.settings

internal enum class AdvancedOptionSetting {
    DesyncMethod,
    AdaptiveSplitPreset,
    AdaptiveFakeTtlMode,
    TlsPreludeMode,
    FakeOrder,
    FakeSeqMode,
    TcpFlagsSet,
    TcpFlagsUnset,
    TcpFlagsOrigSet,
    TcpFlagsOrigUnset,
    IpIdMode,
    HttpFakeProfile,
    FakeTlsBase,
    FakeTlsSniMode,
    TlsFakeProfile,
    HostsMode,
    WarpRouteMode,
    WarpEndpointSelectionMode,
    WarpAmneziaPreset,
    QuicInitialMode,
    TlsFingerprintProfile,
    EntropyMode,
    UdpFakeProfile,
    QuicFakeProfile,
    AppRoutingPolicyMode,
    DhtMitigationMode,
}

internal enum class ActivationWindowDimension {
    Round,
    PayloadSize,
    StreamBytes,
}
