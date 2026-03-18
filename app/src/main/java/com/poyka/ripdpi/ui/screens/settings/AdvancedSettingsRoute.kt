package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeCustom
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.AdaptiveSplitPresetCustom
import com.poyka.ripdpi.activities.AdaptiveSplitPresetManual
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
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
import kotlinx.coroutines.flow.collect

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
    uiState.splitMarker.takeUnless(::isAdaptiveOffsetExpression) ?: DefaultSplitMarker

private fun updateTlsPreludeProfile(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    key: String,
    value: String,
    mode: String = uiState.tlsPrelude.tlsPreludeMode,
    marker: String = uiState.tlsPrelude.tlsrecMarker,
    fragmentCount: Int = uiState.tlsPrelude.tlsRandRecFragmentCount,
    minFragmentSize: Int = uiState.tlsPrelude.tlsRandRecMinFragmentSize,
    maxFragmentSize: Int = uiState.tlsPrelude.tlsRandRecMaxFragmentSize,
) {
    viewModel.updateSetting(
        key = key,
        value = value,
    ) {
        setStrategyChains(
            tcpSteps =
                uiState.rewriteTlsPreludeChainForEditor(
                    mode = mode,
                    marker = marker,
                    fragmentCount = fragmentCount,
                    minFragmentSize = minFragmentSize,
                    maxFragmentSize = maxFragmentSize,
                ),
            udpSteps = uiState.udpChainSteps,
        )
    }
}

private fun updateGroupActivationFilter(
    viewModel: SettingsViewModel,
    key: String,
    value: String,
    filter: com.poyka.ripdpi.data.ActivationFilterModel,
) {
    val normalized = normalizeActivationFilter(filter)
    viewModel.updateSetting(
        key = key,
        value = value,
    ) {
        setGroupActivationFilterCompat(normalized)
    }
}

private fun updatePrimarySplitMarker(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    key: String,
    marker: String,
) {
    val normalized = normalizeOffsetExpression(marker, DefaultSplitMarker)
    val explicitChains = uiState.settings.tcpChainStepsCount > 0
    val primaryStep = primaryTcpChainStep(uiState.tcpChainSteps)
    if (explicitChains && primaryStep != null) {
        if (!primaryStep.kind.supportsAdaptiveMarker) {
            return
        }
        viewModel.updateSetting(
            key = key,
            value = normalized,
        ) {
            setStrategyChains(
                tcpSteps = rewritePrimaryTcpMarker(uiState.tcpChainSteps, normalized),
                udpSteps = uiState.udpChainSteps,
            )
        }
        return
    }
    viewModel.updateSetting(
        key = key,
        value = normalized,
    ) {
        setSplitMarker(normalized)
    }
}

private fun updateRoundRangeBoundary(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    key: String,
    value: String,
    updateStart: Boolean,
) {
    val current = uiState.groupActivationFilter.round
    val updated =
        normalizeRoundRange(
            if (updateStart) {
                current.copy(start = parseOptionalRangeValue(value))
            } else {
                current.copy(end = parseOptionalRangeValue(value))
            },
        )
    updateGroupActivationFilter(
        viewModel = viewModel,
        key = key,
        value = value,
        filter = uiState.groupActivationFilter.copy(round = updated),
    )
}

private fun updatePayloadSizeRangeBoundary(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    key: String,
    value: String,
    updateStart: Boolean,
) {
    val current = uiState.groupActivationFilter.payloadSize
    val updated =
        normalizePayloadSizeRange(
            if (updateStart) {
                current.copy(start = parseOptionalRangeValue(value))
            } else {
                current.copy(end = parseOptionalRangeValue(value))
            },
        )
    updateGroupActivationFilter(
        viewModel = viewModel,
        key = key,
        value = value,
        filter = uiState.groupActivationFilter.copy(payloadSize = updated),
    )
}

private fun updateStreamBytesRangeBoundary(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    key: String,
    value: String,
    updateStart: Boolean,
) {
    val current = uiState.groupActivationFilter.streamBytes
    val updated =
        normalizeStreamBytesRange(
            if (updateStart) {
                current.copy(start = parseOptionalRangeValue(value))
            } else {
                current.copy(end = parseOptionalRangeValue(value))
            },
        )
    updateGroupActivationFilter(
        viewModel = viewModel,
        key = key,
        value = value,
        filter = uiState.groupActivationFilter.copy(streamBytes = updated),
    )
}

@Composable
fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hostPackCatalog by viewModel.hostPackCatalog.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<AdvancedNotice?>(null) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            if (effect is SettingsEffect.Notice) {
                notice = mapNoticeEffect(effect)
            }
        }
    }

    AdvancedSettingsScreen(
        uiState = uiState,
        hostPackCatalog = hostPackCatalog,
        notice = notice,
        onBack = onBack,
        onToggleChanged = { setting, enabled ->
            when (setting) {
                AdvancedToggleSetting.UseCommandLine -> {
                    viewModel.updateSetting(
                        key = "enableCmdSettings",
                        value = enabled.toString(),
                    ) {
                        setEnableCmdSettings(enabled)
                    }
                }

                AdvancedToggleSetting.DiagnosticsMonitorEnabled -> {
                    viewModel.updateSetting(
                        key = "diagnosticsMonitorEnabled",
                        value = enabled.toString(),
                    ) {
                        setDiagnosticsMonitorEnabled(enabled)
                    }
                }

                AdvancedToggleSetting.DiagnosticsExportIncludeHistory -> {
                    viewModel.updateSetting(
                        key = "diagnosticsExportIncludeHistory",
                        value = enabled.toString(),
                    ) {
                        setDiagnosticsExportIncludeHistory(enabled)
                    }
                }

                AdvancedToggleSetting.NoDomain -> {
                    viewModel.updateSetting(
                        key = "noDomain",
                        value = enabled.toString(),
                    ) {
                        setNoDomain(enabled)
                    }
                }

                AdvancedToggleSetting.TcpFastOpen -> {
                    viewModel.updateSetting(
                        key = "tcpFastOpen",
                        value = enabled.toString(),
                    ) {
                        setTcpFastOpen(enabled)
                    }
                }

                AdvancedToggleSetting.DropSack -> {
                    viewModel.updateSetting(
                        key = "dropSack",
                        value = enabled.toString(),
                    ) {
                        setDropSack(enabled)
                    }
                }

                AdvancedToggleSetting.FakeTlsRandomize -> {
                    viewModel.updateSetting(
                        key = "fakeTlsRandomize",
                        value = enabled.toString(),
                    ) {
                        setFakeTlsRandomize(enabled)
                    }
                }

                AdvancedToggleSetting.FakeTlsDupSessionId -> {
                    viewModel.updateSetting(
                        key = "fakeTlsDupSessionId",
                        value = enabled.toString(),
                    ) {
                        setFakeTlsDupSessionId(enabled)
                    }
                }

                AdvancedToggleSetting.FakeTlsPadEncap -> {
                    viewModel.updateSetting(
                        key = "fakeTlsPadEncap",
                        value = enabled.toString(),
                    ) {
                        setFakeTlsPadEncap(enabled)
                    }
                }

                AdvancedToggleSetting.DesyncHttp -> {
                    viewModel.updateSetting(
                        key = "desyncHttp",
                        value = enabled.toString(),
                    ) {
                        setDesyncHttp(enabled)
                    }
                }

                AdvancedToggleSetting.DesyncHttps -> {
                    viewModel.updateSetting(
                        key = "desyncHttps",
                        value = enabled.toString(),
                    ) {
                        setDesyncHttps(enabled)
                    }
                }

                AdvancedToggleSetting.DesyncUdp -> {
                    viewModel.updateSetting(
                        key = "desyncUdp",
                        value = enabled.toString(),
                    ) {
                        setDesyncUdp(enabled)
                    }
                }

                AdvancedToggleSetting.HostMixedCase -> {
                    viewModel.updateSetting(
                        key = "hostMixedCase",
                        value = enabled.toString(),
                    ) {
                        setHostMixedCase(enabled)
                    }
                }

                AdvancedToggleSetting.DomainMixedCase -> {
                    viewModel.updateSetting(
                        key = "domainMixedCase",
                        value = enabled.toString(),
                    ) {
                        setDomainMixedCase(enabled)
                    }
                }

                AdvancedToggleSetting.HostRemoveSpaces -> {
                    viewModel.updateSetting(
                        key = "hostRemoveSpaces",
                        value = enabled.toString(),
                    ) {
                        setHostRemoveSpaces(enabled)
                    }
                }

                AdvancedToggleSetting.HttpMethodEol -> {
                    viewModel.updateSetting(
                        key = "httpMethodEol",
                        value = enabled.toString(),
                    ) {
                        setHttpMethodEol(enabled)
                    }
                }

                AdvancedToggleSetting.HttpUnixEol -> {
                    viewModel.updateSetting(
                        key = "httpUnixEol",
                        value = enabled.toString(),
                    ) {
                        setHttpUnixEol(enabled)
                    }
                }

                AdvancedToggleSetting.TlsrecEnabled -> {
                    viewModel.updateSetting(
                        key = "tlsrecEnabled",
                        value = enabled.toString(),
                    ) {
                        setTlsrecEnabled(enabled)
                    }
                }

                AdvancedToggleSetting.QuicSupportV1 -> {
                    viewModel.updateSetting(
                        key = "quicSupportV1",
                        value = enabled.toString(),
                    ) {
                        setQuicSupportV1(enabled)
                    }
                }

                AdvancedToggleSetting.QuicSupportV2 -> {
                    viewModel.updateSetting(
                        key = "quicSupportV2",
                        value = enabled.toString(),
                    ) {
                        setQuicSupportV2(enabled)
                    }
                }

                AdvancedToggleSetting.HostAutolearnEnabled -> {
                    viewModel.updateSetting(
                        key = "hostAutolearnEnabled",
                        value = enabled.toString(),
                    ) {
                        setHostAutolearnEnabled(enabled)
                    }
                }

                AdvancedToggleSetting.NetworkStrategyMemoryEnabled -> {
                    viewModel.updateSetting(
                        key = "networkStrategyMemoryEnabled",
                        value = enabled.toString(),
                    ) {
                        setNetworkStrategyMemoryEnabled(enabled)
                    }
                }
            }
        },
        onTextConfirmed = { setting, value ->
            when (setting) {
                AdvancedTextSetting.DiagnosticsSampleIntervalSeconds -> {
                    value.toIntOrNull()?.let { intervalSeconds ->
                        viewModel.updateSetting(
                            key = "diagnosticsSampleIntervalSeconds",
                            value = intervalSeconds.toString(),
                        ) {
                            setDiagnosticsSampleIntervalSeconds(intervalSeconds)
                        }
                    }
                }

                AdvancedTextSetting.DiagnosticsHistoryRetentionDays -> {
                    value.toIntOrNull()?.let { retentionDays ->
                        viewModel.updateSetting(
                            key = "diagnosticsHistoryRetentionDays",
                            value = retentionDays.toString(),
                        ) {
                            setDiagnosticsHistoryRetentionDays(retentionDays)
                        }
                    }
                }

                AdvancedTextSetting.CommandLineArgs -> {
                    viewModel.updateSetting(
                        key = "cmdArgs",
                        value = value,
                    ) {
                        setCmdArgs(value)
                    }
                }

                AdvancedTextSetting.ProxyIp -> {
                    viewModel.updateSetting(
                        key = "proxyIp",
                        value = value,
                    ) {
                        setProxyIp(value)
                    }
                }

                AdvancedTextSetting.ProxyPort -> {
                    value.toIntOrNull()?.let { port ->
                        viewModel.updateSetting(
                            key = "proxyPort",
                            value = value,
                        ) {
                            setProxyPort(port)
                        }
                    }
                }

                AdvancedTextSetting.MaxConnections -> {
                    value.toIntOrNull()?.let { maxConnections ->
                        viewModel.updateSetting(
                            key = "maxConnections",
                            value = value,
                        ) {
                            setMaxConnections(maxConnections)
                        }
                    }
                }

                AdvancedTextSetting.BufferSize -> {
                    value.toIntOrNull()?.let { bufferSize ->
                        viewModel.updateSetting(
                            key = "bufferSize",
                            value = value,
                        ) {
                            setBufferSize(bufferSize)
                        }
                    }
                }

                AdvancedTextSetting.DefaultTtl -> {
                    if (value.isBlank()) {
                        viewModel.updateSetting(
                            key = "defaultTtl",
                            value = "0",
                        ) {
                            setCustomTtl(false)
                            setDefaultTtl(0)
                        }
                    } else {
                        value.toIntOrNull()?.let { ttl ->
                            viewModel.updateSetting(
                                key = "defaultTtl",
                                value = value,
                            ) {
                                setCustomTtl(true)
                                setDefaultTtl(ttl)
                            }
                        }
                    }
                }

                AdvancedTextSetting.ChainDsl -> {
                    val parsed = parseStrategyChainDsl(value).getOrNull() ?: return@AdvancedSettingsScreen
                    viewModel.updateSetting(
                        key = "chainDsl",
                        value = value,
                    ) {
                        setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
                    }
                }

                AdvancedTextSetting.ActivationRoundFrom -> {
                    updateRoundRangeBoundary(
                        viewModel,
                        uiState,
                        "groupActivationFilter.round.start",
                        value,
                        updateStart = true,
                    )
                }

                AdvancedTextSetting.ActivationRoundTo -> {
                    updateRoundRangeBoundary(
                        viewModel,
                        uiState,
                        "groupActivationFilter.round.end",
                        value,
                        updateStart = false,
                    )
                }

                AdvancedTextSetting.ActivationPayloadSizeFrom -> {
                    updatePayloadSizeRangeBoundary(
                        viewModel,
                        uiState,
                        "groupActivationFilter.payloadSize.start",
                        value,
                        updateStart = true,
                    )
                }

                AdvancedTextSetting.ActivationPayloadSizeTo -> {
                    updatePayloadSizeRangeBoundary(
                        viewModel,
                        uiState,
                        "groupActivationFilter.payloadSize.end",
                        value,
                        updateStart = false,
                    )
                }

                AdvancedTextSetting.ActivationStreamBytesFrom -> {
                    updateStreamBytesRangeBoundary(
                        viewModel,
                        uiState,
                        "groupActivationFilter.streamBytes.start",
                        value,
                        updateStart = true,
                    )
                }

                AdvancedTextSetting.ActivationStreamBytesTo -> {
                    updateStreamBytesRangeBoundary(
                        viewModel,
                        uiState,
                        "groupActivationFilter.streamBytes.end",
                        value,
                        updateStart = false,
                    )
                }

                AdvancedTextSetting.SplitMarker -> {
                    val marker = normalizeOffsetExpression(value, DefaultSplitMarker)
                    updatePrimarySplitMarker(
                        viewModel = viewModel,
                        uiState = uiState,
                        key = "splitMarker",
                        marker = marker,
                    )
                }

                AdvancedTextSetting.FakeTtl -> {
                    value.toIntOrNull()?.let { fakeTtl ->
                        viewModel.updateSetting(
                            key = "fakeTtl",
                            value = value,
                        ) {
                            setFakeTtl(fakeTtl)
                        }
                    }
                }

                AdvancedTextSetting.AdaptiveFakeTtlMin -> {
                    value.toIntOrNull()?.let { minTtl ->
                        val normalized = minTtl.coerceIn(1, 255)
                        val maxTtl = uiState.fake.adaptiveFakeTtlMax.coerceAtLeast(normalized)
                        viewModel.updateSetting(
                            key = "adaptiveFakeTtlMin",
                            value = normalized.toString(),
                        ) {
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
                        viewModel.updateSetting(
                            key = "adaptiveFakeTtlMax",
                            value = normalized.toString(),
                        ) {
                            setAdaptiveFakeTtlEnabled(true)
                            setAdaptiveFakeTtlMax(normalized)
                        }
                    }
                }

                AdvancedTextSetting.AdaptiveFakeTtlFallback -> {
                    value.toIntOrNull()?.let { fallbackTtl ->
                        val normalized = fallbackTtl.coerceIn(1, 255)
                        viewModel.updateSetting(
                            key = "adaptiveFakeTtlFallback",
                            value = normalized.toString(),
                        ) {
                            setAdaptiveFakeTtlEnabled(true)
                            setAdaptiveFakeTtlFallback(normalized)
                        }
                    }
                }

                AdvancedTextSetting.FakeSni -> {
                    viewModel.updateSetting(
                        key = "fakeSni",
                        value = value,
                    ) {
                        setFakeSni(value)
                    }
                }

                AdvancedTextSetting.FakeOffsetMarker -> {
                    val marker = normalizeOffsetExpression(value, DefaultFakeOffsetMarker)
                    viewModel.updateSetting(
                        key = "fakeOffsetMarker",
                        value = marker,
                    ) {
                        setFakeOffsetMarker(marker)
                    }
                }

                AdvancedTextSetting.FakeTlsSize -> {
                    value.toIntOrNull()?.let { fakeTlsSize ->
                        viewModel.updateSetting(
                            key = "fakeTlsSize",
                            value = fakeTlsSize.toString(),
                        ) {
                            setFakeTlsSize(fakeTlsSize)
                        }
                    }
                }

                AdvancedTextSetting.QuicFakeHost -> {
                    val normalized = normalizeQuicFakeHost(value)
                    viewModel.updateSetting(
                        key = "quicFakeHost",
                        value = normalized,
                    ) {
                        setQuicFakeHost(normalized)
                    }
                }

                AdvancedTextSetting.OobData -> {
                    if (value.length <= 1) {
                        viewModel.updateSetting(
                            key = "oobData",
                            value = value,
                        ) {
                            setOobData(value)
                        }
                    }
                }

                AdvancedTextSetting.TlsrecMarker -> {
                    val marker = normalizeOffsetExpression(value, DefaultTlsRecordMarker)
                    updateTlsPreludeProfile(
                        viewModel = viewModel,
                        uiState = uiState,
                        key = "tlsrecMarker",
                        value = marker,
                        marker = marker,
                    )
                }

                AdvancedTextSetting.TlsRandRecFragmentCount -> {
                    value.toIntOrNull()?.let { fragmentCount ->
                        updateTlsPreludeProfile(
                            viewModel = viewModel,
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
                            viewModel = viewModel,
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
                            viewModel = viewModel,
                            uiState = uiState,
                            key = "tlsRandRecMaxFragmentSize",
                            value = maxSize.toString(),
                            maxFragmentSize = maxSize,
                        )
                    }
                }

                AdvancedTextSetting.UdpFakeCount -> {
                    value.toIntOrNull()?.let { udpFakeCount ->
                        viewModel.updateSetting(
                            key = "udpFakeCount",
                            value = value,
                        ) {
                            setUdpFakeCount(udpFakeCount)
                        }
                    }
                }

                AdvancedTextSetting.HostAutolearnPenaltyTtlHours -> {
                    value.toIntOrNull()?.let { ttl ->
                        val normalized = normalizeHostAutolearnPenaltyTtlHours(ttl)
                        viewModel.updateSetting(
                            key = "hostAutolearnPenaltyTtlHours",
                            value = normalized.toString(),
                        ) {
                            setHostAutolearnPenaltyTtlHours(normalized)
                        }
                    }
                }

                AdvancedTextSetting.HostAutolearnMaxHosts -> {
                    value.toIntOrNull()?.let { maxHosts ->
                        val normalized = normalizeHostAutolearnMaxHosts(maxHosts)
                        viewModel.updateSetting(
                            key = "hostAutolearnMaxHosts",
                            value = normalized.toString(),
                        ) {
                            setHostAutolearnMaxHosts(normalized)
                        }
                    }
                }

                AdvancedTextSetting.HostsBlacklist -> {
                    viewModel.updateSetting(
                        key = "hostsBlacklist",
                        value = value,
                    ) {
                        setHostsBlacklist(value)
                    }
                }

                AdvancedTextSetting.HostsWhitelist -> {
                    viewModel.updateSetting(
                        key = "hostsWhitelist",
                        value = value,
                    ) {
                        setHostsWhitelist(value)
                    }
                }
            }
        },
        onOptionSelected = { setting, value ->
            when (setting) {
                AdvancedOptionSetting.DesyncMethod -> {
                    viewModel.updateSetting(
                        key = "desyncMethod",
                        value = value,
                    ) {
                        setDesyncMethod(value)
                    }
                }

                AdvancedOptionSetting.AdaptiveSplitPreset -> {
                    when (value) {
                        AdaptiveSplitPresetCustom -> {
                            Unit
                        }

                        AdaptiveSplitPresetManual -> {
                            updatePrimarySplitMarker(
                                viewModel = viewModel,
                                uiState = uiState,
                                key = "splitMarker",
                                marker = manualSplitMarkerFallback(uiState),
                            )
                        }

                        else -> {
                            updatePrimarySplitMarker(
                                viewModel = viewModel,
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
                            viewModel.updateSetting(
                                key = "adaptiveFakeTtlEnabled",
                                value = "false",
                            ) {
                                setAdaptiveFakeTtlEnabled(false)
                            }
                        }

                        AdaptiveFakeTtlModeAdaptive -> {
                            viewModel.updateSetting(
                                key = "adaptiveFakeTtlEnabled",
                                value = "true",
                            ) {
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
                        viewModel = viewModel,
                        uiState = uiState,
                        key = "tlsPreludeMode",
                        value = value,
                        mode = value,
                    )
                }

                AdvancedOptionSetting.HttpFakeProfile -> {
                    viewModel.updateSetting(
                        key = "httpFakeProfile",
                        value = value,
                    ) {
                        setHttpFakeProfile(value)
                    }
                }

                AdvancedOptionSetting.FakeTlsBase -> {
                    val useOriginal = value == "original"
                    viewModel.updateSetting(
                        key = "fakeTlsUseOriginal",
                        value = useOriginal.toString(),
                    ) {
                        setFakeTlsUseOriginal(useOriginal)
                    }
                }

                AdvancedOptionSetting.FakeTlsSniMode -> {
                    viewModel.updateSetting(
                        key = "fakeTlsSniMode",
                        value = value,
                    ) {
                        setFakeTlsSniMode(value)
                    }
                }

                AdvancedOptionSetting.TlsFakeProfile -> {
                    viewModel.updateSetting(
                        key = "tlsFakeProfile",
                        value = value,
                    ) {
                        setTlsFakeProfile(value)
                    }
                }

                AdvancedOptionSetting.HostsMode -> {
                    viewModel.updateSetting(
                        key = "hostsMode",
                        value = value,
                    ) {
                        setHostsMode(value)
                    }
                }

                AdvancedOptionSetting.QuicInitialMode -> {
                    viewModel.updateSetting(
                        key = "quicInitialMode",
                        value = value,
                    ) {
                        setQuicInitialMode(value)
                    }
                }

                AdvancedOptionSetting.UdpFakeProfile -> {
                    viewModel.updateSetting(
                        key = "udpFakeProfile",
                        value = value,
                    ) {
                        setUdpFakeProfile(value)
                    }
                }

                AdvancedOptionSetting.QuicFakeProfile -> {
                    viewModel.updateSetting(
                        key = "quicFakeProfile",
                        value = value,
                    ) {
                        setQuicFakeProfile(value)
                    }
                }
            }
        },
        onApplyHostPackPreset = viewModel::applyHostPackPreset,
        onRefreshHostPackCatalog = viewModel::refreshHostPackCatalog,
        onForgetLearnedHosts = viewModel::forgetLearnedHosts,
        onClearRememberedNetworks = viewModel::clearRememberedNetworks,
        onRotateTelemetrySalt = viewModel::rotateTelemetrySalt,
        onSaveActivationRange = { dimension, start, end ->
            when (dimension) {
                ActivationWindowDimension.Round -> {
                    updateGroupActivationFilter(
                        viewModel = viewModel,
                        key = "groupActivationFilter.round",
                        value = listOfNotNull(start, end).joinToString("-"),
                        filter =
                            uiState.groupActivationFilter.copy(
                                round = normalizeRoundRange(start, end),
                            ),
                    )
                }

                ActivationWindowDimension.PayloadSize -> {
                    updateGroupActivationFilter(
                        viewModel = viewModel,
                        key = "groupActivationFilter.payloadSize",
                        value = listOfNotNull(start, end).joinToString("-"),
                        filter =
                            uiState.groupActivationFilter.copy(
                                payloadSize = normalizePayloadSizeRange(start, end),
                            ),
                    )
                }

                ActivationWindowDimension.StreamBytes -> {
                    updateGroupActivationFilter(
                        viewModel = viewModel,
                        key = "groupActivationFilter.streamBytes",
                        value = listOfNotNull(start, end).joinToString("-"),
                        filter =
                            uiState.groupActivationFilter.copy(
                                streamBytes = normalizeStreamBytesRange(start, end),
                            ),
                    )
                }
            }
        },
        onResetAdaptiveSplit = {
            updatePrimarySplitMarker(
                viewModel = viewModel,
                uiState = uiState,
                key = "splitMarker",
                marker = manualSplitMarkerFallback(uiState),
            )
        },
        onResetAdaptiveFakeTtlProfile = viewModel::resetAdaptiveFakeTtlProfile,
        onResetActivationWindow = viewModel::resetActivationWindow,
        onResetHttpParserEvasions = viewModel::resetHttpParserEvasions,
        onResetFakePayloadLibrary = viewModel::resetFakePayloadLibrary,
        onResetFakeTlsProfile = viewModel::resetFakeTlsProfile,
        modifier = modifier,
    )
}
