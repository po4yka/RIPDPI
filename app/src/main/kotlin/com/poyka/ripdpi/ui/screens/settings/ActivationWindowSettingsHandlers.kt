package com.poyka.ripdpi.ui.screens.settings

internal val activationWindowTextHandlers: Map<AdvancedTextSetting, ActivationWindowTextHandler> =
    mapOf(
        AdvancedTextSetting.ActivationRoundFrom to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState,
                    "groupActivationFilter.round.start",
                    value,
                    ActivationWindowDimension.Round,
                    true,
                )
            },
        AdvancedTextSetting.ActivationRoundTo to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState,
                    "groupActivationFilter.round.end",
                    value,
                    ActivationWindowDimension.Round,
                    false,
                )
            },
        AdvancedTextSetting.ActivationPayloadSizeFrom to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState,
                    "groupActivationFilter.payloadSize.start",
                    value,
                    ActivationWindowDimension.PayloadSize,
                    true,
                )
            },
        AdvancedTextSetting.ActivationPayloadSizeTo to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState,
                    "groupActivationFilter.payloadSize.end",
                    value,
                    ActivationWindowDimension.PayloadSize,
                    false,
                )
            },
        AdvancedTextSetting.ActivationStreamBytesFrom to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState,
                    "groupActivationFilter.streamBytes.start",
                    value,
                    ActivationWindowDimension.StreamBytes,
                    true,
                )
            },
        AdvancedTextSetting.ActivationStreamBytesTo to
            { value, uiState ->
                updateActivationRangeBoundary(
                    uiState,
                    "groupActivationFilter.streamBytes.end",
                    value,
                    ActivationWindowDimension.StreamBytes,
                    false,
                )
            },
    )
