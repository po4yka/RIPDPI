package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.FakeOrderDefault
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.ui.state.SettingsUiState

internal fun manualSplitMarkerFallback(uiState: SettingsUiState): String =
    uiState.desync.splitMarker.takeUnless(::isAdaptiveOffsetExpression) ?: CanonicalDefaultSplitMarker

internal fun parseOptionalRangeValue(value: String): Long? = value.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

internal open class AdvancedSettingsMutationWriter(
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
}

internal class AdvancedSettingsMutationWriters(
    update: (String, String, SettingsMutation) -> Unit,
) {
    val core = CoreSettingsMutationWriter(update)
    val desync = DesyncSettingsMutationWriter(update)
    val activationWindow = ActivationWindowSettingsMutationWriter(update)
    val quic = QuicSettingsMutationWriter(update)
    val warp = WarpSettingsMutationWriter(update)
    val autolearn = HostAutolearnSettingsMutationWriter(update)
    val adaptiveFallback = AdaptiveFallbackSettingsMutationWriter(update)
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
