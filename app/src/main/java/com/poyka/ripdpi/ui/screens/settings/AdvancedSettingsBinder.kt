@file:Suppress("CyclomaticComplexMethod", "LargeClass", "LongMethod", "MagicNumber", "MaxLineLength")

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
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
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
    uiState.desync.splitMarker.takeUnless(::isAdaptiveOffsetExpression) ?: DefaultSplitMarker

internal class AdvancedSettingsBinder(
    private val updateSetting: (String, String, SettingsMutation) -> Unit,
) {
    fun onToggleChanged(
        setting: AdvancedToggleSetting,
        enabled: Boolean,
    ) {
        when (setting) {
            AdvancedToggleSetting.UseCommandLine -> {
                updateBoolean("enableCmdSettings", enabled) { setEnableCmdSettings(enabled) }
            }

            AdvancedToggleSetting.DiagnosticsMonitorEnabled -> {
                updateBoolean("diagnosticsMonitorEnabled", enabled) { setDiagnosticsMonitorEnabled(enabled) }
            }

            AdvancedToggleSetting.DiagnosticsExportIncludeHistory -> {
                updateBoolean(
                    "diagnosticsExportIncludeHistory",
                    enabled,
                ) { setDiagnosticsExportIncludeHistory(enabled) }
            }

            AdvancedToggleSetting.NoDomain -> {
                updateBoolean("noDomain", enabled) { setNoDomain(enabled) }
            }

            AdvancedToggleSetting.TcpFastOpen -> {
                updateBoolean("tcpFastOpen", enabled) { setTcpFastOpen(enabled) }
            }

            AdvancedToggleSetting.DropSack -> {
                updateBoolean("dropSack", enabled) { setDropSack(enabled) }
            }

            AdvancedToggleSetting.FakeTlsRandomize -> {
                updateBoolean("fakeTlsRandomize", enabled) { setFakeTlsRandomize(enabled) }
            }

            AdvancedToggleSetting.FakeTlsDupSessionId -> {
                updateBoolean("fakeTlsDupSessionId", enabled) { setFakeTlsDupSessionId(enabled) }
            }

            AdvancedToggleSetting.FakeTlsPadEncap -> {
                updateBoolean("fakeTlsPadEncap", enabled) { setFakeTlsPadEncap(enabled) }
            }

            AdvancedToggleSetting.DesyncHttp -> {
                updateBoolean("desyncHttp", enabled) { setDesyncHttp(enabled) }
            }

            AdvancedToggleSetting.DesyncHttps -> {
                updateBoolean("desyncHttps", enabled) { setDesyncHttps(enabled) }
            }

            AdvancedToggleSetting.DesyncUdp -> {
                updateBoolean("desyncUdp", enabled) { setDesyncUdp(enabled) }
            }

            AdvancedToggleSetting.HostMixedCase -> {
                updateBoolean("hostMixedCase", enabled) { setHostMixedCase(enabled) }
            }

            AdvancedToggleSetting.DomainMixedCase -> {
                updateBoolean("domainMixedCase", enabled) { setDomainMixedCase(enabled) }
            }

            AdvancedToggleSetting.HostRemoveSpaces -> {
                updateBoolean("hostRemoveSpaces", enabled) { setHostRemoveSpaces(enabled) }
            }

            AdvancedToggleSetting.HttpMethodEol -> {
                updateBoolean("httpMethodEol", enabled) { setHttpMethodEol(enabled) }
            }

            AdvancedToggleSetting.HttpUnixEol -> {
                updateBoolean("httpUnixEol", enabled) { setHttpUnixEol(enabled) }
            }

            AdvancedToggleSetting.TlsrecEnabled -> {
                updateBoolean("tlsrecEnabled", enabled) { setTlsrecEnabled(enabled) }
            }

            AdvancedToggleSetting.QuicSupportV1 -> {
                updateBoolean("quicSupportV1", enabled) { setQuicSupportV1(enabled) }
            }

            AdvancedToggleSetting.QuicSupportV2 -> {
                updateBoolean("quicSupportV2", enabled) { setQuicSupportV2(enabled) }
            }

            AdvancedToggleSetting.HostAutolearnEnabled -> {
                updateBoolean("hostAutolearnEnabled", enabled) { setHostAutolearnEnabled(enabled) }
            }

            AdvancedToggleSetting.NetworkStrategyMemoryEnabled -> {
                updateBoolean("networkStrategyMemoryEnabled", enabled) { setNetworkStrategyMemoryEnabled(enabled) }
            }
        }
    }

    fun onTextConfirmed(
        setting: AdvancedTextSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        when (setting) {
            AdvancedTextSetting.DiagnosticsSampleIntervalSeconds -> {
                value.toIntOrNull()?.let { intervalSeconds ->
                    updateSetting("diagnosticsSampleIntervalSeconds", intervalSeconds.toString()) {
                        setDiagnosticsSampleIntervalSeconds(intervalSeconds)
                    }
                }
            }

            AdvancedTextSetting.DiagnosticsHistoryRetentionDays -> {
                value.toIntOrNull()?.let { retentionDays ->
                    updateSetting("diagnosticsHistoryRetentionDays", retentionDays.toString()) {
                        setDiagnosticsHistoryRetentionDays(retentionDays)
                    }
                }
            }

            AdvancedTextSetting.CommandLineArgs -> {
                updateSetting("cmdArgs", value) { setCmdArgs(value) }
            }

            AdvancedTextSetting.ProxyIp -> {
                updateSetting("proxyIp", value) { setProxyIp(value) }
            }

            AdvancedTextSetting.ProxyPort -> {
                value.toIntOrNull()?.let { port ->
                    updateSetting("proxyPort", value) {
                        setProxyPort(port)
                    }
                }
            }

            AdvancedTextSetting.MaxConnections -> {
                value.toIntOrNull()?.let { maxConnections ->
                    updateSetting("maxConnections", value) {
                        setMaxConnections(maxConnections)
                    }
                }
            }

            AdvancedTextSetting.BufferSize -> {
                value.toIntOrNull()?.let { bufferSize ->
                    updateSetting("bufferSize", value) {
                        setBufferSize(bufferSize)
                    }
                }
            }

            AdvancedTextSetting.DefaultTtl -> {
                if (value.isBlank()) {
                    updateSetting("defaultTtl", "0") {
                        setCustomTtl(false)
                        setDefaultTtl(0)
                    }
                } else {
                    value.toIntOrNull()?.let { ttl ->
                        updateSetting("defaultTtl", value) {
                            setCustomTtl(true)
                            setDefaultTtl(ttl)
                        }
                    }
                }
            }

            AdvancedTextSetting.ChainDsl -> {
                val parsed = parseStrategyChainDsl(value).getOrNull() ?: return
                updateSetting("chainDsl", value) {
                    setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
                }
            }

            AdvancedTextSetting.ActivationRoundFrom -> {
                updateRoundRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.round.start",
                    value = value,
                    updateStart = true,
                )
            }

            AdvancedTextSetting.ActivationRoundTo -> {
                updateRoundRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.round.end",
                    value = value,
                    updateStart = false,
                )
            }

            AdvancedTextSetting.ActivationPayloadSizeFrom -> {
                updatePayloadSizeRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.payloadSize.start",
                    value = value,
                    updateStart = true,
                )
            }

            AdvancedTextSetting.ActivationPayloadSizeTo -> {
                updatePayloadSizeRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.payloadSize.end",
                    value = value,
                    updateStart = false,
                )
            }

            AdvancedTextSetting.ActivationStreamBytesFrom -> {
                updateStreamBytesRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.streamBytes.start",
                    value = value,
                    updateStart = true,
                )
            }

            AdvancedTextSetting.ActivationStreamBytesTo -> {
                updateStreamBytesRangeBoundary(
                    uiState = uiState,
                    key = "groupActivationFilter.streamBytes.end",
                    value = value,
                    updateStart = false,
                )
            }

            AdvancedTextSetting.SplitMarker -> {
                val marker = normalizeOffsetExpression(value, DefaultSplitMarker)
                updatePrimarySplitMarker(
                    uiState = uiState,
                    key = "splitMarker",
                    marker = marker,
                )
            }

            AdvancedTextSetting.FakeTtl -> {
                value.toIntOrNull()?.let { fakeTtl ->
                    updateSetting("fakeTtl", value) {
                        setFakeTtl(fakeTtl)
                    }
                }
            }

            AdvancedTextSetting.AdaptiveFakeTtlMin -> {
                value.toIntOrNull()?.let { minTtl ->
                    val normalized = minTtl.coerceIn(1, 255)
                    val maxTtl = uiState.fake.adaptiveFakeTtlMax.coerceAtLeast(normalized)
                    updateSetting("adaptiveFakeTtlMin", normalized.toString()) {
                        setAdaptiveFakeTtlEnabled(true)
                        setAdaptiveFakeTtlMin(normalized)
                        setAdaptiveFakeTtlMax(maxTtl)
                    }
                }
            }

            AdvancedTextSetting.AdaptiveFakeTtlMax -> {
                value.toIntOrNull()?.let { maxTtl ->
                    val minTtl = uiState.fake.adaptiveFakeTtlMin.coerceIn(1, 255)
                    val normalized = maxTtl.coerceIn(minTtl, 255)
                    updateSetting("adaptiveFakeTtlMax", normalized.toString()) {
                        setAdaptiveFakeTtlEnabled(true)
                        setAdaptiveFakeTtlMax(normalized)
                    }
                }
            }

            AdvancedTextSetting.AdaptiveFakeTtlFallback -> {
                value.toIntOrNull()?.let { fallbackTtl ->
                    val normalized = fallbackTtl.coerceIn(1, 255)
                    updateSetting("adaptiveFakeTtlFallback", normalized.toString()) {
                        setAdaptiveFakeTtlEnabled(true)
                        setAdaptiveFakeTtlFallback(normalized)
                    }
                }
            }

            AdvancedTextSetting.FakeSni -> {
                updateSetting("fakeSni", value) { setFakeSni(value) }
            }

            AdvancedTextSetting.FakeOffsetMarker -> {
                val marker = normalizeOffsetExpression(value, DefaultFakeOffsetMarker)
                updateSetting("fakeOffsetMarker", marker) {
                    setFakeOffsetMarker(marker)
                }
            }

            AdvancedTextSetting.FakeTlsSize -> {
                value.toIntOrNull()?.let { fakeTlsSize ->
                    updateSetting("fakeTlsSize", fakeTlsSize.toString()) {
                        setFakeTlsSize(fakeTlsSize)
                    }
                }
            }

            AdvancedTextSetting.QuicFakeHost -> {
                val normalized = normalizeQuicFakeHost(value)
                updateSetting("quicFakeHost", normalized) {
                    setQuicFakeHost(normalized)
                }
            }

            AdvancedTextSetting.OobData -> {
                if (value.length <= 1) {
                    updateSetting("oobData", value) {
                        setOobData(value)
                    }
                }
            }

            AdvancedTextSetting.TlsrecMarker -> {
                val marker = normalizeOffsetExpression(value, DefaultTlsRecordMarker)
                updateTlsPreludeProfile(
                    uiState = uiState,
                    key = "tlsrecMarker",
                    value = marker,
                    marker = marker,
                )
            }

            AdvancedTextSetting.TlsRandRecFragmentCount -> {
                value.toIntOrNull()?.let { fragmentCount ->
                    updateTlsPreludeProfile(
                        uiState = uiState,
                        key = "tlsRandRecFragmentCount",
                        value = fragmentCount.toString(),
                        fragmentCount = fragmentCount,
                    )
                }
            }

            AdvancedTextSetting.TlsRandRecMinFragmentSize -> {
                value.toIntOrNull()?.let { minSize ->
                    updateTlsPreludeProfile(
                        uiState = uiState,
                        key = "tlsRandRecMinFragmentSize",
                        value = minSize.toString(),
                        minFragmentSize = minSize,
                    )
                }
            }

            AdvancedTextSetting.TlsRandRecMaxFragmentSize -> {
                value.toIntOrNull()?.let { maxSize ->
                    updateTlsPreludeProfile(
                        uiState = uiState,
                        key = "tlsRandRecMaxFragmentSize",
                        value = maxSize.toString(),
                        maxFragmentSize = maxSize,
                    )
                }
            }

            AdvancedTextSetting.UdpFakeCount -> {
                value.toIntOrNull()?.let { udpFakeCount ->
                    updateSetting("udpFakeCount", value) {
                        setUdpFakeCount(udpFakeCount)
                    }
                }
            }

            AdvancedTextSetting.HostAutolearnPenaltyTtlHours -> {
                value.toIntOrNull()?.let { ttl ->
                    val normalized = normalizeHostAutolearnPenaltyTtlHours(ttl)
                    updateSetting("hostAutolearnPenaltyTtlHours", normalized.toString()) {
                        setHostAutolearnPenaltyTtlHours(normalized)
                    }
                }
            }

            AdvancedTextSetting.HostAutolearnMaxHosts -> {
                value.toIntOrNull()?.let { maxHosts ->
                    val normalized = normalizeHostAutolearnMaxHosts(maxHosts)
                    updateSetting("hostAutolearnMaxHosts", normalized.toString()) {
                        setHostAutolearnMaxHosts(normalized)
                    }
                }
            }

            AdvancedTextSetting.HostsBlacklist -> {
                updateSetting("hostsBlacklist", value) { setHostsBlacklist(value) }
            }

            AdvancedTextSetting.HostsWhitelist -> {
                updateSetting("hostsWhitelist", value) { setHostsWhitelist(value) }
            }
        }
    }

    fun onOptionSelected(
        setting: AdvancedOptionSetting,
        value: String,
        uiState: SettingsUiState,
    ) {
        when (setting) {
            AdvancedOptionSetting.DesyncMethod -> {
                updateSetting("desyncMethod", value) { setDesyncMethod(value) }
            }

            AdvancedOptionSetting.AdaptiveSplitPreset -> {
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

            AdvancedOptionSetting.AdaptiveFakeTtlMode -> {
                when (value) {
                    AdaptiveFakeTtlModeCustom -> {
                        Unit
                    }

                    AdaptiveFakeTtlModeFixed -> {
                        updateSetting("adaptiveFakeTtlEnabled", "false") {
                            setAdaptiveFakeTtlEnabled(false)
                        }
                    }

                    AdaptiveFakeTtlModeAdaptive -> {
                        updateSetting("adaptiveFakeTtlEnabled", "true") {
                            setAdaptiveFakeTtlEnabled(true)
                            setAdaptiveFakeTtlDelta(-1)
                            setAdaptiveFakeTtlMin(uiState.fake.adaptiveFakeTtlMin.coerceIn(1, 255))
                            setAdaptiveFakeTtlMax(
                                uiState.fake.adaptiveFakeTtlMax.coerceIn(
                                    uiState.fake.adaptiveFakeTtlMin.coerceIn(1, 255),
                                    255,
                                ),
                            )
                            setAdaptiveFakeTtlFallback(
                                uiState.fake.fakeTtl.takeIf { it in 1..255 } ?: DefaultAdaptiveFakeTtlFallback,
                            )
                        }
                    }
                }
            }

            AdvancedOptionSetting.TlsPreludeMode -> {
                updateTlsPreludeProfile(
                    uiState = uiState,
                    key = "tlsPreludeMode",
                    value = value,
                    mode = value,
                )
            }

            AdvancedOptionSetting.HttpFakeProfile -> {
                updateSetting("httpFakeProfile", value) { setHttpFakeProfile(value) }
            }

            AdvancedOptionSetting.FakeTlsBase -> {
                val useOriginal = value == "original"
                updateSetting("fakeTlsUseOriginal", useOriginal.toString()) {
                    setFakeTlsUseOriginal(useOriginal)
                }
            }

            AdvancedOptionSetting.FakeTlsSniMode -> {
                updateSetting("fakeTlsSniMode", value) { setFakeTlsSniMode(value) }
            }

            AdvancedOptionSetting.TlsFakeProfile -> {
                updateSetting("tlsFakeProfile", value) { setTlsFakeProfile(value) }
            }

            AdvancedOptionSetting.HostsMode -> {
                updateSetting("hostsMode", value) { setHostsMode(value) }
            }

            AdvancedOptionSetting.QuicInitialMode -> {
                updateSetting("quicInitialMode", value) { setQuicInitialMode(value) }
            }

            AdvancedOptionSetting.UdpFakeProfile -> {
                updateSetting("udpFakeProfile", value) { setUdpFakeProfile(value) }
            }

            AdvancedOptionSetting.QuicFakeProfile -> {
                updateSetting("quicFakeProfile", value) { setQuicFakeProfile(value) }
            }
        }
    }

    fun onSaveActivationRange(
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

    fun onResetAdaptiveSplit(uiState: SettingsUiState) {
        updatePrimarySplitMarker(
            uiState = uiState,
            key = "splitMarker",
            marker = manualSplitMarkerFallback(uiState),
        )
    }

    private fun updateBoolean(
        key: String,
        enabled: Boolean,
        transform: SettingsMutation,
    ) {
        updateSetting(key, enabled.toString(), transform)
    }

    private fun updateSetting(
        key: String,
        value: String,
        transform: SettingsMutation,
    ) {
        updateSetting.invoke(key, value, transform)
    }

    private fun updateTlsPreludeProfile(
        uiState: SettingsUiState,
        key: String,
        value: String,
        mode: String = uiState.tlsPrelude.tlsPreludeMode,
        marker: String = uiState.tlsPrelude.tlsrecMarker,
        fragmentCount: Int = uiState.tlsPrelude.tlsRandRecFragmentCount,
        minFragmentSize: Int = uiState.tlsPrelude.tlsRandRecMinFragmentSize,
        maxFragmentSize: Int = uiState.tlsPrelude.tlsRandRecMaxFragmentSize,
    ) {
        updateSetting(key, value) {
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

    private fun updateGroupActivationFilter(
        key: String,
        value: String,
        filter: com.poyka.ripdpi.data.ActivationFilterModel,
    ) {
        val normalized = normalizeActivationFilter(filter)
        updateSetting(key, value) {
            setGroupActivationFilterCompat(normalized)
        }
    }

    private fun updatePrimarySplitMarker(
        uiState: SettingsUiState,
        key: String,
        marker: String,
    ) {
        val normalized = normalizeOffsetExpression(marker, DefaultSplitMarker)
        val explicitChains = uiState.settings.tcpChainStepsCount > 0
        val primaryStep = primaryTcpChainStep(uiState.desync.tcpChainSteps)
        if (explicitChains && primaryStep != null) {
            if (!primaryStep.kind.supportsAdaptiveMarker) {
                return
            }
            updateSetting(key, normalized) {
                setStrategyChains(
                    tcpSteps = rewritePrimaryTcpMarker(uiState.desync.tcpChainSteps, normalized),
                    udpSteps = uiState.desync.udpChainSteps,
                )
            }
            return
        }
        updateSetting(key, normalized) {
            setSplitMarker(normalized)
        }
    }

    private fun updateRoundRangeBoundary(
        uiState: SettingsUiState,
        key: String,
        value: String,
        updateStart: Boolean,
    ) {
        val current = uiState.desync.groupActivationFilter.round
        val updated =
            normalizeRoundRange(
                if (updateStart) {
                    current.copy(start = parseOptionalRangeValue(value))
                } else {
                    current.copy(end = parseOptionalRangeValue(value))
                },
            )
        updateGroupActivationFilter(
            key = key,
            value = value,
            filter = uiState.desync.groupActivationFilter.copy(round = updated),
        )
    }

    private fun updatePayloadSizeRangeBoundary(
        uiState: SettingsUiState,
        key: String,
        value: String,
        updateStart: Boolean,
    ) {
        val current = uiState.desync.groupActivationFilter.payloadSize
        val updated =
            normalizePayloadSizeRange(
                if (updateStart) {
                    current.copy(start = parseOptionalRangeValue(value))
                } else {
                    current.copy(end = parseOptionalRangeValue(value))
                },
            )
        updateGroupActivationFilter(
            key = key,
            value = value,
            filter = uiState.desync.groupActivationFilter.copy(payloadSize = updated),
        )
    }

    private fun updateStreamBytesRangeBoundary(
        uiState: SettingsUiState,
        key: String,
        value: String,
        updateStart: Boolean,
    ) {
        val current = uiState.desync.groupActivationFilter.streamBytes
        val updated =
            normalizeStreamBytesRange(
                if (updateStart) {
                    current.copy(start = parseOptionalRangeValue(value))
                } else {
                    current.copy(end = parseOptionalRangeValue(value))
                },
            )
        updateGroupActivationFilter(
            key = key,
            value = value,
            filter = uiState.desync.groupActivationFilter.copy(streamBytes = updated),
        )
    }
}
