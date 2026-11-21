package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.data.AppSettingsSerializer

internal enum class AdvancedTextSetting {
    DiagnosticsSampleIntervalSeconds,
    DiagnosticsHistoryRetentionDays,
    CommandLineArgs,
    ProxyIp,
    ProxyPort,
    MaxConnections,
    BufferSize,
    DefaultTtl,
    ChainDsl,
    ActivationRoundFrom,
    ActivationRoundTo,
    ActivationPayloadSizeFrom,
    ActivationPayloadSizeTo,
    ActivationStreamBytesFrom,
    ActivationStreamBytesTo,
    SplitMarker,
    FakeTtl,
    AdaptiveFakeTtlMin,
    AdaptiveFakeTtlMax,
    AdaptiveFakeTtlFallback,
    TlsMinorValue,
    FakeSni,
    FakeOffsetMarker,
    FakeTlsSize,
    QuicFakeHost,
    OobData,
    TlsrecMarker,
    TlsRandRecFragmentCount,
    TlsRandRecMinFragmentSize,
    TlsRandRecMaxFragmentSize,
    UdpFakeCount,
    HostAutolearnPenaltyTtlHours,
    HostAutolearnMaxHosts,
    AdaptiveFallbackCacheTtlSeconds,
    AdaptiveFallbackCachePrefixV4,
    EvolutionEpsilon,
    EvolutionExperimentTtlMs,
    EvolutionDecayHalfLifeMs,
    EvolutionCooldownAfterFailures,
    EvolutionCooldownMs,
    EntropyPaddingTargetPermil,
    EntropyPaddingMax,
    ShannonEntropyTargetPermil,
    HostsBlacklist,
    HostsWhitelist,
    WarpRouteHosts,
    WarpManualEndpointHost,
    WarpManualEndpointIpv4,
    WarpManualEndpointIpv6,
    WarpManualEndpointPort,
    WarpScannerParallelism,
    WarpScannerMaxRttMs,
    WarpAmneziaJc,
    WarpAmneziaJmin,
    WarpAmneziaJmax,
    WarpAmneziaH1,
    WarpAmneziaH2,
    WarpAmneziaH3,
    WarpAmneziaH4,
    WarpAmneziaS1,
    WarpAmneziaS2,
    WarpAmneziaS3,
    WarpAmneziaS4,
}

/**
 * The value a fresh install stores for [this], for the fields whose default is a real value. A field shows a reset
 * only when it has one here.
 */
internal fun AdvancedTextSetting.defaultValue(): String? {
    val defaults = AppSettingsSerializer.defaultValue
    return when (this) {
        AdvancedTextSetting.DiagnosticsSampleIntervalSeconds -> defaults.diagnosticsSampleIntervalSeconds
        AdvancedTextSetting.DiagnosticsHistoryRetentionDays -> defaults.diagnosticsHistoryRetentionDays
        AdvancedTextSetting.ProxyIp -> defaults.proxyIp
        AdvancedTextSetting.ProxyPort -> defaults.proxyPort
        AdvancedTextSetting.MaxConnections -> defaults.maxConnections
        AdvancedTextSetting.BufferSize -> defaults.bufferSize
        AdvancedTextSetting.FakeTtl -> defaults.fakeTtl
        AdvancedTextSetting.HostAutolearnPenaltyTtlHours -> defaults.hostAutolearnPenaltyTtlHours
        AdvancedTextSetting.HostAutolearnMaxHosts -> defaults.hostAutolearnMaxHosts
        AdvancedTextSetting.AdaptiveFallbackCacheTtlSeconds -> defaults.adaptiveFallbackCacheTtlSeconds
        AdvancedTextSetting.WarpScannerParallelism -> defaults.warpScannerParallelism
        AdvancedTextSetting.WarpScannerMaxRttMs -> defaults.warpScannerMaxRttMs
        else -> return null
    }.toString()
}
