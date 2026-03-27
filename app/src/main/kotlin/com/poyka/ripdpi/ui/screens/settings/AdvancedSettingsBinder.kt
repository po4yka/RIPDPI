package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeCustom
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.AdaptiveSplitPresetCustom
import com.poyka.ripdpi.activities.AdaptiveSplitPresetManual
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizePayloadSizeRange
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.normalizeRoundRange
import com.poyka.ripdpi.data.normalizeStreamBytesRange
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.rewritePrimaryTcpMarker
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.supportsAdaptiveMarker
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone

private const val MinTtl = 1
private const val MaxTtl = 255
private const val AdaptiveTtlDeltaSentinel = -1
private const val MaxOobDataLength = 1

private typealias ToggleHandler = AdvancedSettingsMutationWriter.(Boolean) -> Unit
private typealias TextHandler = AdvancedSettingsMutationWriter.(String, SettingsUiState) -> Unit
private typealias OptionHandler = AdvancedSettingsMutationWriter.(String, SettingsUiState) -> Unit

internal fun mapNoticeEffect(effect: SettingsEffect.Notice): AdvancedNotice =
    AdvancedNotice(
        title = effect.title,
        message = effect.message,
        tone =
            when (effect.tone) {
                SettingsNoticeTone.Info -> WarningBannerTone.Info
                SettingsNoticeTone.Warning -> WarningBannerTone.Warning
                SettingsNoticeTone.Error -> WarningBannerTone.Error
            },
    )

internal fun parseOptionalRangeValue(value: String): Long? = value.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

internal fun manualSplitMarkerFallback(uiState: SettingsUiState): String =
    uiState.desync.splitMarker.takeUnless(::isAdaptiveOffsetExpression) ?: CanonicalDefaultSplitMarker

internal class AdvancedSettingsBinder(
    private val updateSetting: (String, String, SettingsMutation) -> Unit,
) {
    private val writer = AdvancedSettingsMutationWriter(updateSetting)

    fun onToggleChanged(
        setting: AdvancedToggleSetting,
        enabled: Boolean,
    ) {
        toggleHandlers.getValue(setting).invoke(writer, enabled)
    }

    fun onTextConfirmed(
        setting: AdvancedTextSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        textHandlers.getValue(setting).invoke(writer, value, uiState)
    }

    fun onOptionSelected(
        setting: AdvancedOptionSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        optionHandlers.getValue(setting).invoke(writer, value, uiState)
    }

    fun onSaveActivationRange(
        dimension: ActivationWindowDimension,
        start: Long?,
        end: Long?,
        uiState: SettingsUiState,
    ) {
        writer.updateActivationRange(dimension, start, end, uiState)
    }

    fun onWsTunnelModeChanged(mode: String) {
        writer.updateValue("wsTunnelMode", mode) {
            setWsTunnelMode(mode)
                .setWsTunnelEnabled(mode != "off")
        }
    }

    fun onResetAdaptiveSplit(uiState: SettingsUiState) {
        writer.updatePrimarySplitMarker(
            uiState = uiState,
            key = "splitMarker",
            marker = manualSplitMarkerFallback(uiState),
        )
    }
}

private class AdvancedSettingsMutationWriter(
    private val update: (String, String, SettingsMutation) -> Unit,
) {
    fun updateBoolean(
        key: String,
        enabled: Boolean,
        transform: SettingsMutation,
    ) {
        updateValue(key, enabled.toString(), transform)
    }

    fun updateValue(
        key: String,
        value: String,
        transform: SettingsMutation,
    ) {
        update(key, value, transform)
    }

    fun updateIntValue(
        key: String,
        value: String,
        transform: (Int) -> SettingsMutation,
    ) {
        value.toIntOrNull()?.let { parsed ->
            updateValue(key, value) {
                transform(parsed).invoke(this)
            }
        }
    }

    fun updateDefaultTtl(value: String) {
        if (value.isBlank()) {
            updateValue("defaultTtl", "0") {
                setCustomTtl(false)
                setDefaultTtl(0)
            }
            return
        }

        value.toIntOrNull()?.let { ttl ->
            updateValue("defaultTtl", value) {
                setCustomTtl(true)
                setDefaultTtl(ttl)
            }
        }
    }

    fun updateChainDsl(value: String) {
        val parsed = parseStrategyChainDsl(value).getOrNull() ?: return
        updateValue("chainDsl", value) {
            setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
        }
    }

    fun updateActivationRangeBoundary(
        uiState: SettingsUiState,
        key: String,
        value: String,
        dimension: ActivationWindowDimension,
        updateStart: Boolean,
    ) {
        val filter = uiState.desync.groupActivationFilter
        val updatedFilter =
            when (dimension) {
                ActivationWindowDimension.Round -> {
                    filter.copy(
                        round =
                            normalizeRoundRange(
                                updateNumericRangeBoundary(filter.round, value, updateStart),
                            ),
                    )
                }

                ActivationWindowDimension.PayloadSize -> {
                    filter.copy(
                        payloadSize =
                            normalizePayloadSizeRange(
                                updateNumericRangeBoundary(filter.payloadSize, value, updateStart),
                            ),
                    )
                }

                ActivationWindowDimension.StreamBytes -> {
                    filter.copy(
                        streamBytes =
                            normalizeStreamBytesRange(
                                updateNumericRangeBoundary(filter.streamBytes, value, updateStart),
                            ),
                    )
                }
            }
        updateGroupActivationFilter(key, value, updatedFilter)
    }

    fun updatePrimarySplitMarker(
        uiState: SettingsUiState,
        key: String,
        marker: String,
    ) {
        val normalized = normalizeOffsetExpression(marker, CanonicalDefaultSplitMarker)
        val explicitChains = uiState.settings.tcpChainStepsCount > 0
        val primaryStep = primaryTcpChainStep(uiState.desync.tcpChainSteps)
        if (explicitChains && primaryStep != null) {
            if (!primaryStep.kind.supportsAdaptiveMarker) {
                return
            }
            updateValue(key, normalized) {
                setStrategyChains(
                    tcpSteps = rewritePrimaryTcpMarker(uiState.desync.tcpChainSteps, normalized),
                    udpSteps = uiState.desync.udpChainSteps,
                )
            }
            return
        }
        updateValue(key, normalized) {
            setSplitMarker(normalized)
        }
    }

    fun updateAdaptiveFakeTtlMin(
        value: String,
        uiState: SettingsUiState,
    ) {
        value.toIntOrNull()?.let { minTtl ->
            val normalized = minTtl.coerceIn(MinTtl, MaxTtl)
            val maxTtl = uiState.fake.adaptiveFakeTtlMax.coerceAtLeast(normalized)
            updateValue("adaptiveFakeTtlMin", normalized.toString()) {
                setAdaptiveFakeTtlEnabled(true)
                setAdaptiveFakeTtlMin(normalized)
                setAdaptiveFakeTtlMax(maxTtl)
            }
        }
    }

    fun updateAdaptiveFakeTtlMax(
        value: String,
        uiState: SettingsUiState,
    ) {
        value.toIntOrNull()?.let { maxTtl ->
            val minTtl = uiState.fake.adaptiveFakeTtlMin.coerceIn(MinTtl, MaxTtl)
            val normalized = maxTtl.coerceIn(minTtl, MaxTtl)
            updateValue("adaptiveFakeTtlMax", normalized.toString()) {
                setAdaptiveFakeTtlEnabled(true)
                setAdaptiveFakeTtlMax(normalized)
            }
        }
    }

    fun updateAdaptiveFakeTtlFallback(value: String) {
        value.toIntOrNull()?.let { fallbackTtl ->
            val normalized = fallbackTtl.coerceIn(MinTtl, MaxTtl)
            updateValue("adaptiveFakeTtlFallback", normalized.toString()) {
                setAdaptiveFakeTtlEnabled(true)
                setAdaptiveFakeTtlFallback(normalized)
            }
        }
    }

    fun updateNormalizedOffset(
        key: String,
        value: String,
        fallback: String,
        transform: SettingsMutation,
    ) {
        val normalized = normalizeOffsetExpression(value, fallback)
        updateValue(key, normalized, transform)
    }

    fun updateQuicFakeHost(value: String) {
        val normalized = normalizeQuicFakeHost(value)
        updateValue("quicFakeHost", normalized) {
            setQuicFakeHost(normalized)
        }
    }

    fun updateOobData(value: String) {
        if (value.length <= MaxOobDataLength) {
            updateValue("oobData", value) {
                setOobData(value)
            }
        }
    }

    fun updateTlsPreludeProfile(
        uiState: SettingsUiState,
        key: String,
        value: String,
        mode: String = uiState.tlsPrelude.tlsPreludeMode,
        marker: String = uiState.tlsPrelude.tlsrecMarker,
        fragmentCount: Int = uiState.tlsPrelude.tlsRandRecFragmentCount,
        minFragmentSize: Int = uiState.tlsPrelude.tlsRandRecMinFragmentSize,
        maxFragmentSize: Int = uiState.tlsPrelude.tlsRandRecMaxFragmentSize,
    ) {
        updateValue(key, value) {
            setStrategyChains(
                tcpSteps =
                    uiState.rewriteTlsPreludeChainForEditor(
                        mode = mode,
                        marker = marker,
                        fragmentCount = fragmentCount,
                        minFragmentSize = minFragmentSize,
                        maxFragmentSize = maxFragmentSize,
                    ),
                udpSteps = uiState.desync.udpChainSteps,
            )
        }
    }

    fun updateAdaptiveSplitPreset(
        value: String,
        uiState: SettingsUiState,
    ) {
        when (value) {
            AdaptiveSplitPresetCustom -> {
                Unit
            }

            AdaptiveSplitPresetManual -> {
                updatePrimarySplitMarker(
                    uiState = uiState,
                    key = "splitMarker",
                    marker = manualSplitMarkerFallback(uiState),
                )
            }

            else -> {
                updatePrimarySplitMarker(
                    uiState = uiState,
                    key = "splitMarker",
                    marker = value,
                )
            }
        }
    }

    fun updateAdaptiveFakeTtlMode(
        value: String,
        uiState: SettingsUiState,
    ) {
        when (value) {
            AdaptiveFakeTtlModeCustom -> {
                Unit
            }

            AdaptiveFakeTtlModeFixed -> {
                updateValue("adaptiveFakeTtlEnabled", "false") {
                    setAdaptiveFakeTtlEnabled(false)
                }
            }

            AdaptiveFakeTtlModeAdaptive -> {
                val minTtl = uiState.fake.adaptiveFakeTtlMin.coerceIn(MinTtl, MaxTtl)
                val maxTtl = uiState.fake.adaptiveFakeTtlMax.coerceIn(minTtl, MaxTtl)
                val fallbackTtl =
                    uiState.fake.fakeTtl.takeIf { it in MinTtl..MaxTtl } ?: DefaultAdaptiveFakeTtlFallback
                updateValue("adaptiveFakeTtlEnabled", "true") {
                    setAdaptiveFakeTtlEnabled(true)
                    setAdaptiveFakeTtlDelta(AdaptiveTtlDeltaSentinel)
                    setAdaptiveFakeTtlMin(minTtl)
                    setAdaptiveFakeTtlMax(maxTtl)
                    setAdaptiveFakeTtlFallback(fallbackTtl)
                }
            }
        }
    }

    fun updateActivationRange(
        dimension: ActivationWindowDimension,
        start: Long?,
        end: Long?,
        uiState: SettingsUiState,
    ) {
        when (dimension) {
            ActivationWindowDimension.Round -> {
                updateGroupActivationFilter(
                    key = "groupActivationFilter.round",
                    value = listOfNotNull(start, end).joinToString("-"),
                    filter =
                        uiState.desync.groupActivationFilter.copy(
                            round = normalizeRoundRange(start, end),
                        ),
                )
            }

            ActivationWindowDimension.PayloadSize -> {
                updateGroupActivationFilter(
                    key = "groupActivationFilter.payloadSize",
                    value = listOfNotNull(start, end).joinToString("-"),
                    filter =
                        uiState.desync.groupActivationFilter.copy(
                            payloadSize = normalizePayloadSizeRange(start, end),
                        ),
                )
            }

            ActivationWindowDimension.StreamBytes -> {
                updateGroupActivationFilter(
                    key = "groupActivationFilter.streamBytes",
                    value = listOfNotNull(start, end).joinToString("-"),
                    filter =
                        uiState.desync.groupActivationFilter.copy(
                            streamBytes = normalizeStreamBytesRange(start, end),
                        ),
                )
            }
        }
    }

    private fun updateGroupActivationFilter(
        key: String,
        value: String,
        filter: ActivationFilterModel,
    ) {
        val normalized = normalizeActivationFilter(filter)
        updateValue(key, value) {
            setGroupActivationFilterCompat(normalized)
        }
    }
}

private fun updateNumericRangeBoundary(
    range: NumericRangeModel,
    value: String,
    updateStart: Boolean,
): NumericRangeModel =
    if (updateStart) {
        range.copy(start = parseOptionalRangeValue(value))
    } else {
        range.copy(end = parseOptionalRangeValue(value))
    }

private fun AdvancedSettingsMutationWriter.updateHostAutolearnPenaltyTtlHours(value: String) {
    value.toIntOrNull()?.let { ttl ->
        val normalized = normalizeHostAutolearnPenaltyTtlHours(ttl)
        updateValue("hostAutolearnPenaltyTtlHours", normalized.toString()) {
            setHostAutolearnPenaltyTtlHours(normalized)
        }
    }
}

private fun AdvancedSettingsMutationWriter.updateHostAutolearnMaxHosts(value: String) {
    value.toIntOrNull()?.let { maxHosts ->
        val normalized = normalizeHostAutolearnMaxHosts(maxHosts)
        updateValue("hostAutolearnMaxHosts", normalized.toString()) {
            setHostAutolearnMaxHosts(normalized)
        }
    }
}

private val toggleHandlers: Map<AdvancedToggleSetting, ToggleHandler> =
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
        AdvancedToggleSetting.HttpMethodEol to
            { enabled -> updateBoolean("httpMethodEol", enabled) { setHttpMethodEol(enabled) } },
        AdvancedToggleSetting.HttpUnixEol to
            { enabled -> updateBoolean("httpUnixEol", enabled) { setHttpUnixEol(enabled) } },
        AdvancedToggleSetting.TlsrecEnabled to
            { enabled -> updateBoolean("tlsrecEnabled", enabled) { setTlsrecEnabled(enabled) } },
        AdvancedToggleSetting.QuicSupportV1 to
            { enabled -> updateBoolean("quicSupportV1", enabled) { setQuicSupportV1(enabled) } },
        AdvancedToggleSetting.QuicSupportV2 to
            { enabled -> updateBoolean("quicSupportV2", enabled) { setQuicSupportV2(enabled) } },
        AdvancedToggleSetting.HostAutolearnEnabled to
            { enabled -> updateBoolean("hostAutolearnEnabled", enabled) { setHostAutolearnEnabled(enabled) } },
        AdvancedToggleSetting.NetworkStrategyMemoryEnabled to
            { enabled ->
                updateBoolean("networkStrategyMemoryEnabled", enabled) {
                    setNetworkStrategyMemoryEnabled(enabled)
                }
            },
    )

private val textHandlers: Map<AdvancedTextSetting, TextHandler> =
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
        AdvancedTextSetting.ChainDsl to { value, _ -> updateChainDsl(value) },
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
            { value, _ -> updateIntValue("udpFakeCount", value) { count -> { setUdpFakeCount(count) } } },
        AdvancedTextSetting.HostAutolearnPenaltyTtlHours to
            { value, _ -> updateHostAutolearnPenaltyTtlHours(value) },
        AdvancedTextSetting.HostAutolearnMaxHosts to
            { value, _ -> updateHostAutolearnMaxHosts(value) },
        AdvancedTextSetting.HostsBlacklist to
            { value, _ -> updateValue("hostsBlacklist", value) { setHostsBlacklist(value) } },
        AdvancedTextSetting.HostsWhitelist to
            { value, _ -> updateValue("hostsWhitelist", value) { setHostsWhitelist(value) } },
    )

private val optionHandlers: Map<AdvancedOptionSetting, OptionHandler> =
    mapOf(
        AdvancedOptionSetting.DesyncMethod to
            { value, _ -> updateValue("desyncMethod", value) { setDesyncMethod(value) } },
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
        AdvancedOptionSetting.QuicInitialMode to
            { value, _ -> updateValue("quicInitialMode", value) { setQuicInitialMode(value) } },
        AdvancedOptionSetting.UdpFakeProfile to
            { value, _ -> updateValue("udpFakeProfile", value) { setUdpFakeProfile(value) } },
        AdvancedOptionSetting.QuicFakeProfile to
            { value, _ -> updateValue("quicFakeProfile", value) { setQuicFakeProfile(value) } },
    )
