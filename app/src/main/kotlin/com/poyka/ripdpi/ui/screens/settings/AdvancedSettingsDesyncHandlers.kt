package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.entropyModeToProto
import com.poyka.ripdpi.data.normalizeFakeOrder
import com.poyka.ripdpi.data.normalizeFakeSeqMode
import com.poyka.ripdpi.data.normalizeIpIdMode
import com.poyka.ripdpi.data.normalizeOffsetExpression

internal val desyncToggleHandlers: Map<AdvancedToggleSetting, DesyncToggleHandler> =
    mapOf(
        AdvancedToggleSetting.DropSack to
            { enabled -> updateBoolean("dropSack", enabled) { setDropSack(enabled) } },
        AdvancedToggleSetting.Md5Sig to
            { enabled -> updateBoolean("fakeMd5Sig", enabled) { setFakeMd5Sig(enabled) } },
        AdvancedToggleSetting.TlsMinor to
            { enabled -> updateBoolean("tlsMinorEnabled", enabled) { setTlsMinorEnabled(enabled) } },
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
            { _ -> },
        AdvancedToggleSetting.StrategyEvolution to
            { enabled -> updateBoolean("strategyEvolution", enabled) { setStrategyEvolution(enabled) } },
    )

internal val desyncTextHandlers: Map<AdvancedTextSetting, DesyncTextHandler> =
    mapOf(
        AdvancedTextSetting.DefaultTtl to { value, _ -> updateDefaultTtl(value) },
        AdvancedTextSetting.ChainDsl to { value, uiState -> updateChainDsl(value, uiState) },
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
        AdvancedTextSetting.TlsMinorValue to
            {
                value,
                _,
                ->
                updateIntValue("tlsMinorValue", value) { tlsMinorValue -> { setTlsMinorValue(tlsMinorValue) } }
            },
        AdvancedTextSetting.OobData to { value, _ -> updateOobData(value) },
        AdvancedTextSetting.TlsrecMarker to
            { value, uiState ->
                val marker = normalizeOffsetExpression(value, DefaultTlsRecordMarker)
                updateTlsPreludeProfile(uiState = uiState, key = "tlsrecMarker", value = marker, marker = marker)
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
    )

internal val desyncOptionHandlers: Map<AdvancedOptionSetting, DesyncOptionHandler> =
    mapOf(
        AdvancedOptionSetting.DesyncMethod to
            { value, uiState -> updatePrimaryDesyncMethod(value, uiState) },
        AdvancedOptionSetting.AdaptiveSplitPreset to
            { value, uiState -> updateAdaptiveSplitPreset(value, uiState) },
        AdvancedOptionSetting.AdaptiveFakeTtlMode to
            { value, uiState -> updateAdaptiveFakeTtlMode(value, uiState) },
        AdvancedOptionSetting.TlsPreludeMode to
            { value, uiState ->
                updateTlsPreludeProfile(uiState = uiState, key = "tlsPreludeMode", value = value, mode = value)
            },
        AdvancedOptionSetting.FakeOrder to
            { value, uiState ->
                updatePrimaryFakeOrdering(uiState, "fakeOrder", value, ::normalizeFakeOrder) { step, normalized ->
                    step.copy(fakeOrder = normalized)
                }
            },
        AdvancedOptionSetting.FakeSeqMode to
            { value, uiState ->
                updatePrimaryFakeOrdering(uiState, "fakeSeqMode", value, ::normalizeFakeSeqMode) { step, normalized ->
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
        AdvancedOptionSetting.TlsFingerprintProfile to
            { value, _ -> updateValue("tlsFingerprintProfile", value) { setTlsFingerprintProfile(value) } },
        AdvancedOptionSetting.EntropyMode to
            { value, _ ->
                updateValue("entropyMode", value) {
                    setEntropyMode(entropyModeToProto(value))
                }
            },
        AdvancedOptionSetting.UdpFakeProfile to
            { value, _ -> updateValue("udpFakeProfile", value) { setUdpFakeProfile(value) } },
    )
