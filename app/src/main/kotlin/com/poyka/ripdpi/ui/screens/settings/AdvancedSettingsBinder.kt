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
import com.poyka.ripdpi.data.DefaultAppRoutingRussianPresetId
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.FakeOrderDefault
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.WarpAmneziaPresetCustom
import com.poyka.ripdpi.data.WarpAmneziaPresetOff
import com.poyka.ripdpi.data.WarpAmneziaSettings
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCachePrefixV4
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCacheTtlSeconds
import com.poyka.ripdpi.data.normalizeAppRoutingPolicyMode
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.data.normalizeFakeOrder
import com.poyka.ripdpi.data.normalizeFakeSeqMode
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeIpIdMode
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizePayloadSizeRange
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.normalizeRoundRange
import com.poyka.ripdpi.data.normalizeStreamBytesRange
import com.poyka.ripdpi.data.normalizeTcpFlagMask
import com.poyka.ripdpi.data.normalizeWarpAmneziaPreset
import com.poyka.ripdpi.data.normalizeWarpEndpointSelectionMode
import com.poyka.ripdpi.data.normalizeWarpRouteMode
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.resolveWarpAmneziaProfile
import com.poyka.ripdpi.data.rewritePrimaryTcpMarker
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.supportsAdaptiveMarker
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone

private const val MinTtl = 1
private const val MaxTtl = 255
private const val AdaptiveTtlDeltaSentinel = -1
private const val MaxOobDataLength = 1
internal const val MaxWarpEndpointPort = 65535

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

    fun onRoutingPolicyModeSelected(value: String) {
        val normalized = normalizeAppRoutingPolicyMode(value)
        writer.updateValue("appRoutingPolicyMode", normalized) {
            setAppRoutingPolicyMode(normalized)
        }
    }

    fun onDhtMitigationModeSelected(value: String) {
        val normalized = normalizeDhtMitigationMode(value)
        writer.updateValue("dhtMitigationMode", normalized) {
            setDhtMitigationMode(normalized)
        }
    }

    fun onAntiCorrelationEnabledChanged(enabled: Boolean) {
        writer.updateBoolean("antiCorrelationEnabled", enabled) {
            setAntiCorrelationEnabled(enabled)
        }
    }

    fun onAppRoutingPresetEnabledChanged(
        presetId: String,
        enabled: Boolean,
        uiState: SettingsUiState,
    ) {
        val updatedPresetIds = uiState.routingProtection.enabledPresetIds.toMutableSet()
        if (enabled) {
            updatedPresetIds += presetId
        } else {
            updatedPresetIds -= presetId
        }
        writer.updateValue("appRoutingEnabledPresetIds", updatedPresetIds.joinToString(",")) {
            clearAppRoutingEnabledPresetIds()
            if (updatedPresetIds.isNotEmpty()) {
                addAllAppRoutingEnabledPresetIds(updatedPresetIds.sorted())
            }
            setExcludeRussianAppsEnabled(DefaultAppRoutingRussianPresetId in updatedPresetIds)
        }
    }
}

internal class AdvancedSettingsMutationWriter(
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

    fun updateChainDsl(
        value: String,
        uiState: SettingsUiState,
    ) {
        val parsed = parseStrategyChainDsl(value).getOrNull() ?: return
        runCatching {
            validateStrategyChainUsage(
                tcpSteps = parsed.tcpSteps,
                udpSteps = parsed.udpSteps,
                mode = uiState.selectedMode,
                useCommandLineSettings = uiState.enableCmdSettings,
            )
        }.getOrNull() ?: return
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
        val primaryStep = primaryTcpChainStep(uiState.desync.tcpChainSteps)
        if (primaryStep != null) {
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
            setStrategyChains(
                tcpSteps =
                    uiState.desync.tcpChainSteps +
                        TcpChainStepModel(
                            kind = TcpChainStepKind.Split,
                            marker = normalized,
                        ),
                udpSteps = uiState.desync.udpChainSteps,
            )
        }
    }

    fun updatePrimaryTcpFlags(
        uiState: SettingsUiState,
        key: String,
        value: String,
        transform: (TcpChainStepModel, String) -> TcpChainStepModel,
    ) {
        val primaryStep = primaryTcpChainStep(uiState.desync.tcpChainSteps) ?: return
        val index = uiState.desync.tcpChainSteps.indexOf(primaryStep)
        val canUpdate = uiState.desync.tcpFlagVisualEditorSupported && index >= 0
        if (!canUpdate) return
        val normalized = normalizeTcpFlagMask(value)
        updateValue(key, normalized) {
            val updated = uiState.desync.tcpChainSteps.toMutableList()
            updated[index] = transform(primaryStep, normalized)
            setStrategyChains(
                tcpSteps = updated,
                udpSteps = uiState.desync.udpChainSteps,
            )
        }
    }

    fun updatePrimaryFakeOrdering(
        uiState: SettingsUiState,
        key: String,
        value: String,
        normalize: (String) -> String,
        transform: (TcpChainStepModel, String) -> TcpChainStepModel,
    ) {
        val primaryStep = uiState.desync.primaryFakeOrderingStep ?: return
        val normalized = normalize(value)
        val index = uiState.desync.tcpChainSteps.indexOf(primaryStep)
        val canUpdate =
            uiState.desync.fakeOrderingVisualEditorSupported &&
                index >= 0 &&
                !isUnsupportedHostFakeOrder(key, primaryStep, normalized)
        if (!canUpdate) return
        updateValue(key, normalized) {
            val updated = uiState.desync.tcpChainSteps.toMutableList()
            updated[index] = transform(primaryStep, normalized)
            setStrategyChains(
                tcpSteps = updated,
                udpSteps = uiState.desync.udpChainSteps,
            )
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

internal fun updateNumericRangeBoundary(
    range: NumericRangeModel,
    value: String,
    updateStart: Boolean,
): NumericRangeModel =
    if (updateStart) {
        range.copy(start = parseOptionalRangeValue(value))
    } else {
        range.copy(end = parseOptionalRangeValue(value))
    }

internal fun isUnsupportedHostFakeOrder(
    key: String,
    primaryStep: TcpChainStepModel,
    normalized: String,
): Boolean =
    key == "fakeOrder" &&
        primaryStep.kind == TcpChainStepKind.HostFake &&
        primaryStep.midhostMarker.isBlank() &&
        normalized != FakeOrderDefault

internal fun AdvancedSettingsMutationWriter.updateQuicFakeHost(value: String) {
    val normalized = normalizeQuicFakeHost(value)
    updateValue("quicFakeHost", normalized) {
        setQuicFakeHost(normalized)
    }
}

internal fun AdvancedSettingsMutationWriter.updateOobData(value: String) {
    if (value.length <= MaxOobDataLength) {
        updateValue("oobData", value) {
            setOobData(value)
        }
    }
}

internal fun AdvancedSettingsMutationWriter.updateHostAutolearnPenaltyTtlHours(value: String) {
    value.toIntOrNull()?.let { ttl ->
        val normalized = normalizeHostAutolearnPenaltyTtlHours(ttl)
        updateValue("hostAutolearnPenaltyTtlHours", normalized.toString()) {
            setHostAutolearnPenaltyTtlHours(normalized)
        }
    }
}

internal fun AdvancedSettingsMutationWriter.updateHostAutolearnMaxHosts(value: String) {
    value.toIntOrNull()?.let { maxHosts ->
        val normalized = normalizeHostAutolearnMaxHosts(maxHosts)
        updateValue("hostAutolearnMaxHosts", normalized.toString()) {
            setHostAutolearnMaxHosts(normalized)
        }
    }
}

internal fun AdvancedSettingsMutationWriter.updateAdaptiveFallbackCacheTtlSeconds(value: String) {
    value.toIntOrNull()?.let { ttl ->
        val normalized = normalizeAdaptiveFallbackCacheTtlSeconds(ttl)
        updateValue("adaptiveFallbackCacheTtlSeconds", normalized.toString()) {
            setAdaptiveFallbackCacheTtlSeconds(normalized)
        }
    }
}

internal fun AdvancedSettingsMutationWriter.updateAdaptiveFallbackCachePrefixV4(value: String) {
    value.toIntOrNull()?.let { prefix ->
        val normalized = normalizeAdaptiveFallbackCachePrefixV4(prefix)
        updateValue("adaptiveFallbackCachePrefixV4", normalized.toString()) {
            setAdaptiveFallbackCachePrefixV4(normalized)
        }
    }
}

internal fun AdvancedSettingsMutationWriter.updateWarpRouteMode(value: String) {
    val normalized = normalizeWarpRouteMode(value)
    updateValue("warpRouteMode", normalized) {
        setWarpRouteMode(normalized)
    }
}

internal fun AdvancedSettingsMutationWriter.updateWarpEndpointSelectionMode(value: String) {
    val normalized = normalizeWarpEndpointSelectionMode(value)
    updateValue("warpEndpointSelectionMode", normalized) {
        setWarpEndpointSelectionMode(normalized)
    }
}

internal fun AdvancedSettingsMutationWriter.updateWarpAmneziaPreset(
    value: String,
    uiState: SettingsUiState,
) {
    val normalized = normalizeWarpAmneziaPreset(value)
    val rawSettings =
        WarpAmneziaSettings(
            enabled = uiState.warp.amneziaEnabled,
            jc = uiState.warp.amneziaJc,
            jmin = uiState.warp.amneziaJmin,
            jmax = uiState.warp.amneziaJmax,
            h1 = uiState.warp.amneziaH1,
            h2 = uiState.warp.amneziaH2,
            h3 = uiState.warp.amneziaH3,
            h4 = uiState.warp.amneziaH4,
            s1 = uiState.warp.amneziaS1,
            s2 = uiState.warp.amneziaS2,
            s3 = uiState.warp.amneziaS3,
            s4 = uiState.warp.amneziaS4,
        )
    val resolved =
        resolveWarpAmneziaProfile(
            preset = normalized,
            rawSettings =
                if (normalized == WarpAmneziaPresetCustom) {
                    rawSettings.copy(enabled = true)
                } else {
                    rawSettings
                },
        ).settings

    updateValue("warpAmneziaPreset", normalized) {
        setWarpAmneziaPreset(normalized)
        setWarpAmneziaEnabled(normalized != WarpAmneziaPresetOff)
        setWarpAmneziaJc(resolved.jc)
        setWarpAmneziaJmin(resolved.jmin)
        setWarpAmneziaJmax(resolved.jmax)
        setWarpAmneziaH1(resolved.h1)
        setWarpAmneziaH2(resolved.h2)
        setWarpAmneziaH3(resolved.h3)
        setWarpAmneziaH4(resolved.h4)
        setWarpAmneziaS1(resolved.s1)
        setWarpAmneziaS2(resolved.s2)
        setWarpAmneziaS3(resolved.s3)
        setWarpAmneziaS4(resolved.s4)
    }
}

internal fun AdvancedSettingsMutationWriter.updateUdpBurstCount(
    value: String,
    uiState: SettingsUiState,
) {
    value.toIntOrNull()?.let { count ->
        val normalized = count.coerceAtLeast(0)
        val existing = uiState.desync.udpChainSteps.firstOrNull()
        val updatedUdpSteps =
            if (normalized == 0) {
                emptyList()
            } else {
                listOf(existing?.copy(count = normalized) ?: UdpChainStepModel(count = normalized))
            }
        updateValue("udpFakeCount", normalized.toString()) {
            setStrategyChains(
                tcpSteps = uiState.desync.tcpChainSteps,
                udpSteps = updatedUdpSteps,
            )
        }
    }
}

@Suppress("ReturnCount")
internal fun AdvancedSettingsMutationWriter.updatePrimaryDesyncMethod(
    value: String,
    uiState: SettingsUiState,
) {
    if (uiState.desync.tcpChainSteps.any { it.kind == TcpChainStepKind.MultiDisorder }) {
        return
    }

    val primaryIndex = uiState.desync.tcpChainSteps.indexOfFirst { !it.kind.isTlsPrelude }
    val replacementKind =
        when (value) {
            "none" -> {
                null
            }

            "split" -> {
                TcpChainStepKind.Split
            }

            TcpChainStepKind.SeqOverlap.wireName -> {
                uiState.desync.tcpChainSteps.getOrNull(primaryIndex)?.kind?.takeIf {
                    it == TcpChainStepKind.SeqOverlap
                } ?: return
            }

            "disorder" -> {
                TcpChainStepKind.Disorder
            }

            TcpChainStepKind.MultiDisorder.wireName -> {
                return
            }

            "fake" -> {
                TcpChainStepKind.Fake
            }

            "oob" -> {
                TcpChainStepKind.Oob
            }

            "disoob" -> {
                TcpChainStepKind.Disoob
            }

            else -> {
                return
            }
        }
    val updatedTcpSteps =
        when {
            primaryIndex >= 0 && replacementKind != null -> {
                val current = uiState.desync.tcpChainSteps[primaryIndex]
                uiState.desync.tcpChainSteps.toMutableList().apply {
                    this[primaryIndex] = current.copy(kind = replacementKind)
                }
            }

            primaryIndex >= 0 -> {
                uiState.desync.tcpChainSteps.filterIndexed { index, _ -> index != primaryIndex }
            }

            replacementKind != null -> {
                uiState.desync.tcpChainSteps +
                    TcpChainStepModel(
                        kind = replacementKind,
                        marker = normalizeOffsetExpression(uiState.desync.splitMarker, CanonicalDefaultSplitMarker),
                    )
            }

            else -> {
                uiState.desync.tcpChainSteps
            }
        }
    updateValue("desyncMethod", value) {
        setStrategyChains(
            tcpSteps = updatedTcpSteps,
            udpSteps = uiState.desync.udpChainSteps,
        )
    }
}
