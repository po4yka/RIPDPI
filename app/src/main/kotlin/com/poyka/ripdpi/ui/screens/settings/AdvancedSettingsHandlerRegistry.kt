package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.normalizeFakeOrder
import com.poyka.ripdpi.data.normalizeFakeSeqMode
import com.poyka.ripdpi.data.normalizeIpIdMode
import com.poyka.ripdpi.data.normalizeOffsetExpression

internal typealias ToggleHandler = AdvancedSettingsMutationWriter.(Boolean) -> Unit
internal typealias TextHandler = AdvancedSettingsMutationWriter.(String, SettingsUiState) -> Unit
internal typealias OptionHandler = AdvancedSettingsMutationWriter.(String, SettingsUiState) -> Unit

internal val toggleHandlers: Map<AdvancedToggleSetting, ToggleHandler> =
    mapOf(
        AdvancedToggleSetting.UseCommandLine to
            { enabled ->
                updateBoolean("enableCmdSettings", enabled) { setEnableCmdSettings(enabled) }
            },
        AdvancedToggleSetting.DiagnosticsMonitorEnabled to
            { enabled ->
                updateBoolean("diagnosticsMonitorEnabled", enabled) {
                    setDiagnosticsMonitorEnabled(enabled)
                }
            },
        AdvancedToggleSetting.DiagnosticsExportIncludeHistory to
            { enabled ->
                updateBoolean("diagnosticsExportIncludeHistory", enabled) {
                    setDiagnosticsExportIncludeHistory(enabled)
                }
            },
        AdvancedToggleSetting.StrategyPackAllowRollbackOverride to
            { enabled ->
                updateBoolean("strategyPackAllowRollbackOverride", enabled) {
                    setStrategyPackAllowRollbackOverride(enabled)
                }
            },
        AdvancedToggleSetting.NoDomain to
            { enabled -> updateBoolean("noDomain", enabled) { setNoDomain(enabled) } },
        AdvancedToggleSetting.TcpFastOpen to
            { enabled -> updateBoolean("tcpFastOpen", enabled) { setTcpFastOpen(enabled) } },
        AdvancedToggleSetting.DropSack to
            { enabled -> updateBoolean("dropSack", enabled) { setDropSack(enabled) } },
        AdvancedToggleSetting.FakeTlsRandomize to
            { enabled -> updateBoolean("fakeTlsRandomize", enabled) { setFakeTlsRandomize(enabled) } },
        AdvancedToggleSetting.FakeTlsDupSessionId to
            { enabled -> updateBoolean("fakeTlsDupSessionId", enabled) { setFakeTlsDupSessionId(enabled) } },
        AdvancedToggleSetting.FakeTlsPadEncap to
            { enabled -> updateBoolean("fakeTlsPadEncap", enabled) { setFakeTlsPadEncap(enabled) } },
        AdvancedToggleSetting.DesyncHttp to
            { enabled -> updateBoolean("desyncHttp", enabled) { setDesyncHttp(enabled) } },
        AdvancedToggleSetting.DesyncHttps to
            { enabled -> updateBoolean("desyncHttps", enabled) { setDesyncHttps(enabled) } },
        AdvancedToggleSetting.DesyncUdp to
            { enabled -> updateBoolean("desyncUdp", enabled) { setDesyncUdp(enabled) } },
        AdvancedToggleSetting.HostMixedCase to
            { enabled -> updateBoolean("hostMixedCase", enabled) { setHostMixedCase(enabled) } },
        AdvancedToggleSetting.DomainMixedCase to
            { enabled -> updateBoolean("domainMixedCase", enabled) { setDomainMixedCase(enabled) } },
        AdvancedToggleSetting.HostRemoveSpaces to
            { enabled -> updateBoolean("hostRemoveSpaces", enabled) { setHostRemoveSpaces(enabled) } },
        AdvancedToggleSetting.HttpHostPad to
            { enabled -> updateBoolean("httpHostPad", enabled) { setHttpHostPad(enabled) } },
        AdvancedToggleSetting.HttpMethodEol to
            { enabled -> updateBoolean("httpMethodEol", enabled) { setHttpMethodEol(enabled) } },
        AdvancedToggleSetting.HttpUnixEol to
            { enabled -> updateBoolean("httpUnixEol", enabled) { setHttpUnixEol(enabled) } },
        AdvancedToggleSetting.HttpMethodSpace to
            { enabled -> updateBoolean("httpMethodSpace", enabled) { setHttpMethodSpace(enabled) } },
        AdvancedToggleSetting.HttpHostExtraSpace to
            { enabled -> updateBoolean("httpHostExtraSpace", enabled) { setHttpHostExtraSpace(enabled) } },
        AdvancedToggleSetting.HttpHostTab to
            { enabled -> updateBoolean("httpHostTab", enabled) { setHttpHostTab(enabled) } },
        AdvancedToggleSetting.TlsrecEnabled to
            { _ -> Unit },
        AdvancedToggleSetting.QuicSupportV1 to
            { enabled -> updateBoolean("quicSupportV1", enabled) { setQuicSupportV1(enabled) } },
        AdvancedToggleSetting.QuicSupportV2 to
            { enabled -> updateBoolean("quicSupportV2", enabled) { setQuicSupportV2(enabled) } },
        AdvancedToggleSetting.QuicBindLowPort to
            { enabled -> updateBoolean("quicBindLowPort", enabled) { setQuicBindLowPort(enabled) } },
        AdvancedToggleSetting.QuicMigrateAfterHandshake to
            { enabled ->
                updateBoolean("quicMigrateAfterHandshake", enabled) {
                    setQuicMigrateAfterHandshake(enabled)
                }
            },
        AdvancedToggleSetting.StrategyEvolution to
            { enabled -> updateBoolean("strategyEvolution", enabled) { setStrategyEvolution(enabled) } },
        AdvancedToggleSetting.WarpEnabled to
            { enabled -> updateBoolean("warpEnabled", enabled) { setWarpEnabled(enabled) } },
        AdvancedToggleSetting.WarpBuiltInRulesEnabled to
            { enabled ->
                updateBoolean("warpBuiltinRulesEnabled", enabled) {
                    setWarpBuiltinRulesEnabled(enabled)
                }
            },
        AdvancedToggleSetting.WarpScannerEnabled to
            { enabled -> updateBoolean("warpScannerEnabled", enabled) { setWarpScannerEnabled(enabled) } },
        AdvancedToggleSetting.WarpAmneziaEnabled to
            { enabled -> updateBoolean("warpAmneziaEnabled", enabled) { setWarpAmneziaEnabled(enabled) } },
        AdvancedToggleSetting.HostAutolearnEnabled to
            { enabled -> updateBoolean("hostAutolearnEnabled", enabled) { setHostAutolearnEnabled(enabled) } },
        AdvancedToggleSetting.NetworkStrategyMemoryEnabled to
            { enabled ->
                updateBoolean("networkStrategyMemoryEnabled", enabled) {
                    setNetworkStrategyMemoryEnabled(enabled)
                }
            },
        AdvancedToggleSetting.AdaptiveFallbackEnabled to
            { enabled -> updateBoolean("adaptiveFallbackEnabled", enabled) { setAdaptiveFallbackEnabled(enabled) } },
        AdvancedToggleSetting.AdaptiveFallbackTorst to
            { enabled -> updateBoolean("adaptiveFallbackTorst", enabled) { setAdaptiveFallbackTorst(enabled) } },
        AdvancedToggleSetting.AdaptiveFallbackTlsErr to
            { enabled -> updateBoolean("adaptiveFallbackTlsErr", enabled) { setAdaptiveFallbackTlsErr(enabled) } },
        AdvancedToggleSetting.AdaptiveFallbackHttpRedirect to
            { enabled ->
                updateBoolean("adaptiveFallbackHttpRedirect", enabled) {
                    setAdaptiveFallbackHttpRedirect(enabled)
                }
            },
        AdvancedToggleSetting.AdaptiveFallbackConnectFailure to
            { enabled ->
                updateBoolean("adaptiveFallbackConnectFailure", enabled) {
                    setAdaptiveFallbackConnectFailure(enabled)
                }
            },
        AdvancedToggleSetting.AdaptiveFallbackAutoSort to
            { enabled -> updateBoolean("adaptiveFallbackAutoSort", enabled) { setAdaptiveFallbackAutoSort(enabled) } },
    )

internal val textHandlers: Map<AdvancedTextSetting, TextHandler> =
    mapOf(
        AdvancedTextSetting.DiagnosticsSampleIntervalSeconds to
            { value, _ ->
                updateIntValue("diagnosticsSampleIntervalSeconds", value) { intervalSeconds ->
                    { setDiagnosticsSampleIntervalSeconds(intervalSeconds) }
                }
            },
        AdvancedTextSetting.DiagnosticsHistoryRetentionDays to
            { value, _ ->
                updateIntValue("diagnosticsHistoryRetentionDays", value) { retentionDays ->
                    { setDiagnosticsHistoryRetentionDays(retentionDays) }
                }
            },
        AdvancedTextSetting.CommandLineArgs to { value, _ -> updateValue("cmdArgs", value) { setCmdArgs(value) } },
        AdvancedTextSetting.ProxyIp to { value, _ -> updateValue("proxyIp", value) { setProxyIp(value) } },
        AdvancedTextSetting.ProxyPort to
            { value, _ -> updateIntValue("proxyPort", value) { port -> { setProxyPort(port) } } },
        AdvancedTextSetting.MaxConnections to
            { value, _ ->
                updateIntValue("maxConnections", value) { maxConnections ->
                    { setMaxConnections(maxConnections) }
                }
            },
        AdvancedTextSetting.BufferSize to
            { value, _ -> updateIntValue("bufferSize", value) { bufferSize -> { setBufferSize(bufferSize) } } },
        AdvancedTextSetting.DefaultTtl to { value, _ -> updateDefaultTtl(value) },
        AdvancedTextSetting.ChainDsl to { value, uiState -> updateChainDsl(value, uiState) },
        AdvancedTextSetting.ActivationRoundFrom to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.round.start",
                    value = value,
                    dimension = ActivationWindowDimension.Round,
                    updateStart = true,
                )
            },
        AdvancedTextSetting.ActivationRoundTo to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.round.end",
                    value = value,
                    dimension = ActivationWindowDimension.Round,
                    updateStart = false,
                )
            },
        AdvancedTextSetting.ActivationPayloadSizeFrom to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.payloadSize.start",
                    value = value,
                    dimension = ActivationWindowDimension.PayloadSize,
                    updateStart = true,
                )
            },
        AdvancedTextSetting.ActivationPayloadSizeTo to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.payloadSize.end",
                    value = value,
                    dimension = ActivationWindowDimension.PayloadSize,
                    updateStart = false,
                )
            },
        AdvancedTextSetting.ActivationStreamBytesFrom to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.streamBytes.start",
                    value = value,
                    dimension = ActivationWindowDimension.StreamBytes,
                    updateStart = true,
                )
            },
        AdvancedTextSetting.ActivationStreamBytesTo to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.streamBytes.end",
                    value = value,
                    dimension = ActivationWindowDimension.StreamBytes,
                    updateStart = false,
                )
            },
        AdvancedTextSetting.SplitMarker to
            { value, uiState -> updatePrimarySplitMarker(uiState, "splitMarker", value) },
        AdvancedTextSetting.FakeTtl to
            { value, _ -> updateIntValue("fakeTtl", value) { fakeTtl -> { setFakeTtl(fakeTtl) } } },
        AdvancedTextSetting.AdaptiveFakeTtlMin to
            { value, uiState -> updateAdaptiveFakeTtlMin(value, uiState) },
        AdvancedTextSetting.AdaptiveFakeTtlMax to
            { value, uiState -> updateAdaptiveFakeTtlMax(value, uiState) },
        AdvancedTextSetting.AdaptiveFakeTtlFallback to
            { value, _ -> updateAdaptiveFakeTtlFallback(value) },
        AdvancedTextSetting.FakeSni to { value, _ -> updateValue("fakeSni", value) { setFakeSni(value) } },
        AdvancedTextSetting.FakeOffsetMarker to
            { value, _ ->
                updateNormalizedOffset("fakeOffsetMarker", value, DefaultFakeOffsetMarker) {
                    setFakeOffsetMarker(normalizeOffsetExpression(value, DefaultFakeOffsetMarker))
                }
            },
        AdvancedTextSetting.FakeTlsSize to
            { value, _ -> updateIntValue("fakeTlsSize", value) { fakeTlsSize -> { setFakeTlsSize(fakeTlsSize) } } },
        AdvancedTextSetting.QuicFakeHost to { value, _ -> updateQuicFakeHost(value) },
        AdvancedTextSetting.OobData to { value, _ -> updateOobData(value) },
        AdvancedTextSetting.TlsrecMarker to
            { value, uiState ->
                val marker = normalizeOffsetExpression(value, DefaultTlsRecordMarker)
                updateTlsPreludeProfile(
                    uiState = uiState,
                    key = "tlsrecMarker",
                    value = marker,
                    marker = marker,
                )
            },
        AdvancedTextSetting.TlsRandRecFragmentCount to
            { value, uiState ->
                value.toIntOrNull()?.let { fragmentCount ->
                    updateTlsPreludeProfile(
                        uiState = uiState,
                        key = "tlsRandRecFragmentCount",
                        value = fragmentCount.toString(),
                        fragmentCount = fragmentCount,
                    )
                }
            },
        AdvancedTextSetting.TlsRandRecMinFragmentSize to
            { value, uiState ->
                value.toIntOrNull()?.let { minSize ->
                    updateTlsPreludeProfile(
                        uiState = uiState,
                        key = "tlsRandRecMinFragmentSize",
                        value = minSize.toString(),
                        minFragmentSize = minSize,
                    )
                }
            },
        AdvancedTextSetting.TlsRandRecMaxFragmentSize to
            { value, uiState ->
                value.toIntOrNull()?.let { maxSize ->
                    updateTlsPreludeProfile(
                        uiState = uiState,
                        key = "tlsRandRecMaxFragmentSize",
                        value = maxSize.toString(),
                        maxFragmentSize = maxSize,
                    )
                }
            },
        AdvancedTextSetting.UdpFakeCount to
            { value, uiState -> updateUdpBurstCount(value, uiState) },
        AdvancedTextSetting.HostAutolearnPenaltyTtlHours to
            { value, _ -> updateHostAutolearnPenaltyTtlHours(value) },
        AdvancedTextSetting.HostAutolearnMaxHosts to
            { value, _ -> updateHostAutolearnMaxHosts(value) },
        AdvancedTextSetting.AdaptiveFallbackCacheTtlSeconds to
            { value, _ -> updateAdaptiveFallbackCacheTtlSeconds(value) },
        AdvancedTextSetting.AdaptiveFallbackCachePrefixV4 to
            { value, _ -> updateAdaptiveFallbackCachePrefixV4(value) },
        AdvancedTextSetting.EvolutionEpsilon to
            { value, _ ->
                value.toDoubleOrNull()?.let { parsed ->
                    val normalized = parsed.coerceIn(0.0, 1.0)
                    updateValue("evolutionEpsilon", normalized.toString()) { setEvolutionEpsilon(normalized) }
                }
            },
        AdvancedTextSetting.EvolutionExperimentTtlMs to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    val v = parsed.coerceAtLeast(1)
                    updateValue("evolutionExperimentTtlMs", v.toString()) { setEvolutionExperimentTtlMs(v) }
                }
            },
        AdvancedTextSetting.EvolutionDecayHalfLifeMs to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    val v = parsed.coerceAtLeast(1)
                    updateValue("evolutionDecayHalfLifeMs", v.toString()) { setEvolutionDecayHalfLifeMs(v) }
                }
            },
        AdvancedTextSetting.EvolutionCooldownAfterFailures to
            { value, _ ->
                updateIntValue("evolutionCooldownAfterFailures", value) { n ->
                    { setEvolutionCooldownAfterFailures(n.coerceAtLeast(1)) }
                }
            },
        AdvancedTextSetting.EvolutionCooldownMs to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    val v = parsed.coerceAtLeast(1)
                    updateValue("evolutionCooldownMs", v.toString()) { setEvolutionCooldownMs(v) }
                }
            },
        AdvancedTextSetting.EntropyPaddingTargetPermil to
            { value, _ ->
                updateIntValue("entropyPaddingTargetPermil", value) { parsed ->
                    { setEntropyPaddingTargetPermil(parsed.coerceAtLeast(0)) }
                }
            },
        AdvancedTextSetting.EntropyPaddingMax to
            { value, _ ->
                updateIntValue("entropyPaddingMax", value) { parsed ->
                    { setEntropyPaddingMax(parsed.coerceAtLeast(0)) }
                }
            },
        AdvancedTextSetting.ShannonEntropyTargetPermil to
            { value, _ ->
                updateIntValue("shannonEntropyTargetPermil", value) { parsed ->
                    { setShannonEntropyTargetPermil(parsed.coerceAtLeast(0)) }
                }
            },
        AdvancedTextSetting.HostsBlacklist to
            { value, _ -> updateValue("hostsBlacklist", value) { setHostsBlacklist(value) } },
        AdvancedTextSetting.HostsWhitelist to
            { value, _ -> updateValue("hostsWhitelist", value) { setHostsWhitelist(value) } },
        AdvancedTextSetting.WarpRouteHosts to
            { value, _ -> updateValue("warpRouteHosts", value) { setWarpRouteHosts(value) } },
        AdvancedTextSetting.WarpManualEndpointHost to
            { value, _ -> updateValue("warpManualEndpointHost", value) { setWarpManualEndpointHost(value) } },
        AdvancedTextSetting.WarpManualEndpointIpv4 to
            { value, _ -> updateValue("warpManualEndpointV4", value) { setWarpManualEndpointV4(value) } },
        AdvancedTextSetting.WarpManualEndpointIpv6 to
            { value, _ -> updateValue("warpManualEndpointV6", value) { setWarpManualEndpointV6(value) } },
        AdvancedTextSetting.WarpManualEndpointPort to
            { value, _ ->
                updateIntValue("warpManualEndpointPort", value) { port ->
                    { setWarpManualEndpointPort(port.coerceIn(1, MaxWarpEndpointPort)) }
                }
            },
        AdvancedTextSetting.WarpScannerParallelism to
            { value, _ ->
                updateIntValue("warpScannerParallelism", value) { parallelism ->
                    { setWarpScannerParallelism(parallelism.coerceAtLeast(1)) }
                }
            },
        AdvancedTextSetting.WarpScannerMaxRttMs to
            { value, _ ->
                updateIntValue("warpScannerMaxRttMs", value) { maxRttMs ->
                    { setWarpScannerMaxRttMs(maxRttMs.coerceAtLeast(1)) }
                }
            },
        AdvancedTextSetting.WarpAmneziaJc to
            { value, _ -> updateIntValue("warpAmneziaJc", value) { jc -> { setWarpAmneziaJc(jc) } } },
        AdvancedTextSetting.WarpAmneziaJmin to
            { value, _ -> updateIntValue("warpAmneziaJmin", value) { jmin -> { setWarpAmneziaJmin(jmin) } } },
        AdvancedTextSetting.WarpAmneziaJmax to
            { value, _ -> updateIntValue("warpAmneziaJmax", value) { jmax -> { setWarpAmneziaJmax(jmax) } } },
        AdvancedTextSetting.WarpAmneziaH1 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH1", parsed.toString()) { setWarpAmneziaH1(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH2 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH2", parsed.toString()) { setWarpAmneziaH2(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH3 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH3", parsed.toString()) { setWarpAmneziaH3(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaH4 to
            { value, _ ->
                value.toLongOrNull()?.let { parsed ->
                    updateValue("warpAmneziaH4", parsed.toString()) { setWarpAmneziaH4(parsed) }
                }
            },
        AdvancedTextSetting.WarpAmneziaS1 to
            { value, _ -> updateIntValue("warpAmneziaS1", value) { s1 -> { setWarpAmneziaS1(s1) } } },
        AdvancedTextSetting.WarpAmneziaS2 to
            { value, _ -> updateIntValue("warpAmneziaS2", value) { s2 -> { setWarpAmneziaS2(s2) } } },
        AdvancedTextSetting.WarpAmneziaS3 to
            { value, _ -> updateIntValue("warpAmneziaS3", value) { s3 -> { setWarpAmneziaS3(s3) } } },
        AdvancedTextSetting.WarpAmneziaS4 to
            { value, _ -> updateIntValue("warpAmneziaS4", value) { s4 -> { setWarpAmneziaS4(s4) } } },
    )

internal val optionHandlers: Map<AdvancedOptionSetting, OptionHandler> =
    mapOf(
        AdvancedOptionSetting.DesyncMethod to
            { value, uiState -> updatePrimaryDesyncMethod(value, uiState) },
        AdvancedOptionSetting.AdaptiveSplitPreset to
            { value, uiState -> updateAdaptiveSplitPreset(value, uiState) },
        AdvancedOptionSetting.AdaptiveFakeTtlMode to
            { value, uiState -> updateAdaptiveFakeTtlMode(value, uiState) },
        AdvancedOptionSetting.TlsPreludeMode to
            { value, uiState ->
                updateTlsPreludeProfile(
                    uiState = uiState,
                    key = "tlsPreludeMode",
                    value = value,
                    mode = value,
                )
            },
        AdvancedOptionSetting.FakeOrder to
            { value, uiState ->
                updatePrimaryFakeOrdering(
                    uiState = uiState,
                    key = "fakeOrder",
                    value = value,
                    normalize = ::normalizeFakeOrder,
                ) { step, normalized ->
                    step.copy(fakeOrder = normalized)
                }
            },
        AdvancedOptionSetting.FakeSeqMode to
            { value, uiState ->
                updatePrimaryFakeOrdering(
                    uiState = uiState,
                    key = "fakeSeqMode",
                    value = value,
                    normalize = ::normalizeFakeSeqMode,
                ) { step, normalized ->
                    step.copy(fakeSeqMode = normalized)
                }
            },
        AdvancedOptionSetting.TcpFlagsSet to
            { value, uiState ->
                updatePrimaryTcpFlags(uiState, "tcpFlagsSet", value) { step, normalized ->
                    step.copy(tcpFlagsSet = normalized)
                }
            },
        AdvancedOptionSetting.TcpFlagsUnset to
            { value, uiState ->
                updatePrimaryTcpFlags(uiState, "tcpFlagsUnset", value) { step, normalized ->
                    step.copy(tcpFlagsUnset = normalized)
                }
            },
        AdvancedOptionSetting.TcpFlagsOrigSet to
            { value, uiState ->
                updatePrimaryTcpFlags(uiState, "tcpFlagsOrigSet", value) { step, normalized ->
                    step.copy(tcpFlagsOrigSet = normalized)
                }
            },
        AdvancedOptionSetting.TcpFlagsOrigUnset to
            { value, uiState ->
                updatePrimaryTcpFlags(uiState, "tcpFlagsOrigUnset", value) { step, normalized ->
                    step.copy(tcpFlagsOrigUnset = normalized)
                }
            },
        AdvancedOptionSetting.IpIdMode to
            { value, _ ->
                val normalized = normalizeIpIdMode(value)
                updateValue("ipIdMode", normalized) { setIpIdMode(normalized) }
            },
        AdvancedOptionSetting.HttpFakeProfile to
            { value, _ -> updateValue("httpFakeProfile", value) { setHttpFakeProfile(value) } },
        AdvancedOptionSetting.FakeTlsBase to
            { value, _ ->
                val useOriginal = value == "original"
                updateValue("fakeTlsUseOriginal", useOriginal.toString()) {
                    setFakeTlsUseOriginal(useOriginal)
                }
            },
        AdvancedOptionSetting.FakeTlsSniMode to
            { value, _ -> updateValue("fakeTlsSniMode", value) { setFakeTlsSniMode(value) } },
        AdvancedOptionSetting.TlsFakeProfile to
            { value, _ -> updateValue("tlsFakeProfile", value) { setTlsFakeProfile(value) } },
        AdvancedOptionSetting.HostsMode to
            { value, _ -> updateValue("hostsMode", value) { setHostsMode(value) } },
        AdvancedOptionSetting.WarpRouteMode to
            { value, _ -> updateWarpRouteMode(value) },
        AdvancedOptionSetting.WarpEndpointSelectionMode to
            { value, _ -> updateWarpEndpointSelectionMode(value) },
        AdvancedOptionSetting.WarpAmneziaPreset to
            { value, uiState -> updateWarpAmneziaPreset(value, uiState) },
        AdvancedOptionSetting.QuicInitialMode to
            { value, _ -> updateValue("quicInitialMode", value) { setQuicInitialMode(value) } },
        AdvancedOptionSetting.TlsFingerprintProfile to
            { value, _ -> updateValue("tlsFingerprintProfile", value) { setTlsFingerprintProfile(value) } },
        AdvancedOptionSetting.EntropyMode to
            { value, _ ->
                updateValue("entropyMode", value) {
                    setEntropyMode(
                        com.poyka.ripdpi.data
                            .entropyModeToProto(value),
                    )
                }
            },
        AdvancedOptionSetting.UdpFakeProfile to
            { value, _ -> updateValue("udpFakeProfile", value) { setUdpFakeProfile(value) } },
        AdvancedOptionSetting.QuicFakeProfile to
            { value, _ -> updateValue("quicFakeProfile", value) { setQuicFakeProfile(value) } },
    )
