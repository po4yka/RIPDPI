package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizePayloadSizeRange
import com.poyka.ripdpi.data.normalizeRoundRange
import com.poyka.ripdpi.data.normalizeStreamBytesRange
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.ui.state.SettingsUiState

internal class ActivationWindowSettingsMutationWriter(
    update: (String, String, SettingsMutation) -> Unit,
) : AdvancedSettingsMutationWriter(update) {
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
