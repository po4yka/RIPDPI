package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeCustom
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.AdaptiveSplitPresetCustom
import com.poyka.ripdpi.activities.AdaptiveSplitPresetManual
import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeTcpFlagMask
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.data.rewritePrimaryTcpMarker
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.supportsAdaptiveMarker
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.ui.state.SettingsUiState

private const val MinTtl = 1
private const val MaxTtl = 255
private const val AdaptiveTtlDeltaSentinel = -1
private const val MaxOobDataLength = 1

private sealed interface PrimaryDesyncMethodChoice {
    data class Selected(
        val kind: TcpChainStepKind?,
    ) : PrimaryDesyncMethodChoice

    data object Unsupported : PrimaryDesyncMethodChoice
}

internal class DesyncSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
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

    fun updateOobData(value: String) {
        if (value.length <= MaxOobDataLength) {
            updateValue("oobData", value) {
                setOobData(value)
            }
        }
    }

    fun updateUdpBurstCount(
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

    fun updatePrimaryDesyncMethod(
        value: String,
        uiState: SettingsUiState,
    ) {
        if (uiState.desync.tcpChainSteps.any { it.kind == TcpChainStepKind.MultiDisorder }) {
            return
        }

        val primaryIndex = uiState.desync.tcpChainSteps.indexOfFirst { !it.kind.isTlsPrelude }
        val choice =
            resolvePrimaryDesyncMethod(
                value = value,
                currentKind =
                    uiState.desync.tcpChainSteps
                        .getOrNull(primaryIndex)
                        ?.kind,
            )
        if (choice is PrimaryDesyncMethodChoice.Unsupported) return

        val replacementKind = (choice as PrimaryDesyncMethodChoice.Selected).kind
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
}

private fun resolvePrimaryDesyncMethod(
    value: String,
    currentKind: TcpChainStepKind?,
): PrimaryDesyncMethodChoice =
    when (value) {
        "none" -> {
            PrimaryDesyncMethodChoice.Selected(null)
        }

        "split" -> {
            PrimaryDesyncMethodChoice.Selected(TcpChainStepKind.Split)
        }

        TcpChainStepKind.SeqOverlap.wireName -> {
            if (currentKind == TcpChainStepKind.SeqOverlap) {
                PrimaryDesyncMethodChoice.Selected(TcpChainStepKind.SeqOverlap)
            } else {
                PrimaryDesyncMethodChoice.Unsupported
            }
        }

        "disorder" -> {
            PrimaryDesyncMethodChoice.Selected(TcpChainStepKind.Disorder)
        }

        "fake" -> {
            PrimaryDesyncMethodChoice.Selected(TcpChainStepKind.Fake)
        }

        "oob" -> {
            PrimaryDesyncMethodChoice.Selected(TcpChainStepKind.Oob)
        }

        "disoob" -> {
            PrimaryDesyncMethodChoice.Selected(TcpChainStepKind.Disoob)
        }

        else -> {
            PrimaryDesyncMethodChoice.Unsupported
        }
    }
